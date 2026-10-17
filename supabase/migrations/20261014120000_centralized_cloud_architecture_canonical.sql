-- ====================================================================
-- Migration: 20261014120000_centralized_cloud_architecture_canonical.sql
-- Description:
-- 1. Canonical schema unification: conversations, conversation_members,
--    messages, attachments, profiles.
-- 2. Clean up any obsolete function overloads.
-- 3. Canonical conversation RPCs:
--    - get_my_conversations()
--    - get_or_create_direct_conversation(p_peer_id)
--    - get_or_create_saved_messages_conversation()
--    - get_or_create_ai_conversation()
-- 4. Server-seeded welcome messages for Saved Messages & AI personas.
-- 5. Canonical send_message_idempotent and create_attachment RPCs.
-- 6. Strict Storage and RLS policies for chat-attachments, avatars, and profile_backgrounds.
-- ====================================================================

-- 1. PROFILES TABLE ALIGNMENT
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS date_of_birth DATE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS bio TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS profile_background_path TEXT DEFAULT NULL;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS profile_background_updated_at TIMESTAMPTZ DEFAULT now();

-- Ensure indexes
CREATE INDEX IF NOT EXISTS idx_profiles_username_lower ON public.profiles(lower(username));
CREATE INDEX IF NOT EXISTS idx_profiles_hex_number ON public.profiles(hex_number);

-- 2. DROP OBSOLETE FUNCTION OVERLOADS
DROP FUNCTION IF EXISTS public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INT);
DROP FUNCTION IF EXISTS public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, UUID, TEXT, TEXT, INT);
DROP FUNCTION IF EXISTS public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INT);

-- 3. CANONICAL RPC: send_message_idempotent
CREATE OR REPLACE FUNCTION public.send_message_idempotent(
    p_conversation_id TEXT,
    p_client_message_id TEXT,
    p_payload TEXT,
    p_signature TEXT DEFAULT NULL,
    p_type TEXT DEFAULT 'text',
    p_time_str TEXT DEFAULT '',
    p_recipient_id TEXT DEFAULT NULL,
    p_persona_id TEXT DEFAULT NULL,
    p_encryption_version INT DEFAULT 2
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_sender_id UUID := auth.uid();
    v_existing_msg RECORD;
    v_new_sequence BIGINT;
    v_recipient_uuid UUID := NULL;
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_sender_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    IF p_conversation_id IS NULL OR p_client_message_id IS NULL OR p_payload IS NULL THEN
        RAISE EXCEPTION 'Missing required message parameters';
    END IF;

    -- Verify sender is a conversation member
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members
        WHERE conversation_id = p_conversation_id AND account_id = v_sender_id
    ) THEN
        RAISE EXCEPTION 'User % is not a member of conversation %', v_sender_id, p_conversation_id;
    END IF;

    -- Idempotency check: if message with this (conversation_id, client_message_id) already exists, return existing
    SELECT id, sequence, status, created_at
    INTO v_existing_msg
    FROM public.messages
    WHERE conversation_id = p_conversation_id AND client_message_id = p_client_message_id;

    IF v_existing_msg.id IS NOT NULL THEN
        RETURN jsonb_build_object(
            'id', v_existing_msg.id,
            'conversation_id', p_conversation_id,
            'client_message_id', p_client_message_id,
            'sequence', v_existing_msg.sequence,
            'status', v_existing_msg.status,
            'created_at', v_existing_msg.created_at,
            'is_duplicate', true
        );
    END IF;

    -- Allocate per-conversation sequence
    UPDATE public.conversations
    SET current_sequence = coalesce(current_sequence, 0) + 1,
        updated_at = v_now
    WHERE id = p_conversation_id
    RETURNING current_sequence INTO v_new_sequence;

    IF v_new_sequence IS NULL THEN
        RAISE EXCEPTION 'Conversation not found: %', p_conversation_id;
    END IF;

    -- Resolve recipient UUID if provided
    IF p_recipient_id IS NOT NULL AND p_recipient_id != '' AND p_recipient_id != 'self' AND NOT p_recipient_id LIKE 'ai_%' THEN
        BEGIN
            v_recipient_uuid := p_recipient_id::uuid;
        EXCEPTION WHEN OTHERS THEN
            v_recipient_uuid := NULL;
        END;
    END IF;

    -- Insert message
    INSERT INTO public.messages (
        id,
        conversation_id,
        client_message_id,
        sender_id,
        recipient_id,
        sequence,
        payload,
        signature,
        type,
        time_str,
        status,
        persona_id,
        encryption_version,
        created_at
    ) VALUES (
        p_client_message_id,
        p_conversation_id,
        p_client_message_id,
        v_sender_id,
        v_recipient_uuid,
        v_new_sequence,
        p_payload,
        p_signature,
        coalesce(p_type, 'text'),
        coalesce(p_time_str, ''),
        'sent',
        p_persona_id,
        coalesce(p_encryption_version, 2),
        v_now
    );

    RETURN jsonb_build_object(
        'id', p_client_message_id,
        'conversation_id', p_conversation_id,
        'client_message_id', p_client_message_id,
        'sequence', v_new_sequence,
        'status', 'sent',
        'created_at', v_now,
        'is_duplicate', false
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INT) TO authenticated;

-- 4. CANONICAL RPC: get_or_create_saved_messages_conversation
CREATE OR REPLACE FUNCTION public.get_or_create_saved_messages_conversation()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_conv_id TEXT;
    v_seq BIGINT;
    v_msg_count INT;
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    v_conv_id := 'self_' || v_user_id::text;

    INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
    VALUES (v_conv_id, 'SAVED_MESSAGES', 'Saved Messages', v_user_id, 0, v_now, v_now)
    ON CONFLICT (id) DO UPDATE SET updated_at = now()
    RETURNING current_sequence INTO v_seq;

    INSERT INTO public.conversation_members (conversation_id, account_id, role, is_pinned)
    VALUES (v_conv_id, v_user_id, 'owner', true)
    ON CONFLICT (conversation_id, account_id) DO NOTHING;

    -- Seed welcome message on server idempotently if chat is empty
    SELECT count(*) INTO v_msg_count FROM public.messages WHERE conversation_id = v_conv_id;
    IF v_msg_count = 0 THEN
        UPDATE public.conversations SET current_sequence = current_sequence + 1 WHERE id = v_conv_id RETURNING current_sequence INTO v_seq;
        INSERT INTO public.messages (
            id, conversation_id, client_message_id, sender_id, recipient_id, sequence,
            payload, encryption_version, type, time_str, status, created_at
        ) VALUES (
            'init_saved_msg_' || v_user_id::text,
            v_conv_id,
            'init_saved_msg_' || v_user_id::text,
            v_user_id,
            v_user_id,
            v_seq,
            'Добро пожаловать в Избранное!',
            0,
            'text',
            'Только что',
            'sent',
            v_now
        )
        ON CONFLICT (conversation_id, client_message_id) DO NOTHING;
    END IF;

    RETURN jsonb_build_object(
        'id', v_conv_id,
        'type', 'SAVED_MESSAGES',
        'title', 'Saved Messages',
        'recipient_id', 'self',
        'current_sequence', v_seq,
        'created_at', v_now,
        'updated_at', v_now
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_or_create_saved_messages_conversation() TO authenticated;

-- 5. CANONICAL RPC: get_or_create_ai_conversation
CREATE OR REPLACE FUNCTION public.get_or_create_ai_conversation()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_conv_id TEXT;
    v_seq BIGINT;
    v_msg_count INT;
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    v_conv_id := 'ai_' || v_user_id::text;

    INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
    VALUES (v_conv_id, 'AI', 'HexShard AI', v_user_id, 0, v_now, v_now)
    ON CONFLICT (id) DO UPDATE SET updated_at = now()
    RETURNING current_sequence INTO v_seq;

    INSERT INTO public.conversation_members (conversation_id, account_id, role, is_pinned)
    VALUES (v_conv_id, v_user_id, 'owner', true)
    ON CONFLICT (conversation_id, account_id) DO NOTHING;

    -- Seed server-authoritative welcome messages for Hexagon and Ventaxis if empty
    SELECT count(*) INTO v_msg_count FROM public.messages WHERE conversation_id = v_conv_id;
    IF v_msg_count = 0 THEN
        UPDATE public.conversations SET current_sequence = current_sequence + 2 WHERE id = v_conv_id RETURNING current_sequence INTO v_seq;

        INSERT INTO public.messages (
            id, conversation_id, client_message_id, sender_id, recipient_id, sequence,
            payload, encryption_version, type, time_str, status, persona_id, created_at
        ) VALUES (
            'init_ai_welcome_hexagon_' || v_user_id::text,
            v_conv_id,
            'init_ai_welcome_hexagon_' || v_user_id::text,
            v_user_id,
            v_user_id,
            v_seq - 1,
            'Привет! Я HexShard AI. Задайте любой вопрос для быстрого ответа!',
            0,
            'text',
            'Только что',
            'sent',
            'hexagon',
            v_now
        ),
        (
            'init_ai_welcome_ventaxis_' || v_user_id::text,
            v_conv_id,
            'init_ai_welcome_ventaxis_' || v_user_id::text,
            v_user_id,
            v_user_id,
            v_seq,
            'Привет! Я Ventaxis AI. Чем могу помочь вам сегодня?',
            0,
            'text',
            'Только что',
            'sent',
            'ventaxis',
            v_now
        )
        ON CONFLICT (conversation_id, client_message_id) DO NOTHING;
    END IF;

    RETURN jsonb_build_object(
        'id', v_conv_id,
        'type', 'AI',
        'title', 'HexShard AI',
        'recipient_id', 'ai_assistant',
        'current_sequence', v_seq,
        'created_at', v_now,
        'updated_at', v_now
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_or_create_ai_conversation() TO authenticated;

-- 6. CANONICAL RPC: get_or_create_direct_conversation
CREATE OR REPLACE FUNCTION public.get_or_create_direct_conversation(
    p_peer_id TEXT,
    p_title TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_peer_uuid UUID;
    v_conv_id TEXT;
    v_seq BIGINT;
    v_peer_profile RECORD;
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    IF p_peer_id IS NULL OR trim(p_peer_id) = '' THEN
        RAISE EXCEPTION 'Peer ID is required';
    END IF;

    -- Resolve peer UUID
    BEGIN
        v_peer_uuid := p_peer_id::uuid;
    EXCEPTION WHEN OTHERS THEN
        SELECT id INTO v_peer_uuid FROM public.profiles
        WHERE lower(username) = lower(p_peer_id)
           OR hex_number = p_peer_id
           OR normalized_username = lower(p_peer_id)
        LIMIT 1;
    END;

    IF v_peer_uuid IS NULL THEN
        RAISE EXCEPTION 'Peer user not found for ID: %', p_peer_id;
    END IF;

    IF v_peer_uuid = v_user_id THEN
        RETURN public.get_or_create_saved_messages_conversation();
    END IF;

    -- Deterministic direct conversation ID: direct_<min>_<max>
    IF v_user_id < v_peer_uuid THEN
        v_conv_id := 'direct_' || v_user_id::text || '_' || v_peer_uuid::text;
    ELSE
        v_conv_id := 'direct_' || v_peer_uuid::text || '_' || v_user_id::text;
    END IF;

    INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
    VALUES (v_conv_id, 'DIRECT', coalesce(p_title, ''), v_user_id, 0, v_now, v_now)
    ON CONFLICT (id) DO UPDATE SET updated_at = now()
    RETURNING current_sequence INTO v_seq;

    INSERT INTO public.conversation_members (conversation_id, account_id, role)
    VALUES (v_conv_id, v_user_id, 'member')
    ON CONFLICT (conversation_id, account_id) DO NOTHING;

    INSERT INTO public.conversation_members (conversation_id, account_id, role)
    VALUES (v_conv_id, v_peer_uuid, 'member')
    ON CONFLICT (conversation_id, account_id) DO NOTHING;

    SELECT username, display_name, coalesce(avatar_url, avatar_path, '') as avatar_url
    INTO v_peer_profile
    FROM public.profiles
    WHERE id = v_peer_uuid;

    RETURN jsonb_build_object(
        'id', v_conv_id,
        'type', 'DIRECT',
        'title', coalesce(p_title, v_peer_profile.display_name, v_peer_profile.username, 'Chat'),
        'recipient_id', v_peer_uuid::text,
        'recipient_display_name', coalesce(v_peer_profile.display_name, v_peer_profile.username, 'User'),
        'recipient_avatar_url', coalesce(v_peer_profile.avatar_url, ''),
        'current_sequence', v_seq,
        'created_at', v_now,
        'updated_at', v_now
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_or_create_direct_conversation(TEXT, TEXT) TO authenticated;

-- 7. CANONICAL RPC: get_my_conversations
CREATE OR REPLACE FUNCTION public.get_my_conversations()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_result JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    SELECT coalesce(jsonb_agg(
        jsonb_build_object(
            'id', c.id,
            'type', c.type,
            'title', c.title,
            'current_sequence', c.current_sequence,
            'created_at', c.created_at,
            'updated_at', c.updated_at,
            'last_read_sequence', cm.last_read_sequence,
            'is_pinned', cm.is_pinned,
            'is_muted', cm.is_muted,
            'is_archived', cm.is_archived,
            'last_message', (
                SELECT jsonb_build_object(
                    'text', m.payload,
                    'created_at', m.created_at,
                    'sender_id', m.sender_id,
                    'type', m.type,
                    'encryption_version', m.encryption_version,
                    'time_str', m.time_str,
                    'sequence', m.sequence
                )
                FROM public.messages m
                WHERE m.conversation_id = c.id
                ORDER BY m.sequence DESC
                LIMIT 1
            ),
            'members', (
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'account_id', cm2.account_id,
                        'role', cm2.role,
                        'username', p.username,
                        'display_name', p.display_name,
                        'avatar_url', coalesce(p.avatar_url, p.avatar_path, '')
                    )
                )
                FROM public.conversation_members cm2
                LEFT JOIN public.profiles p ON p.id = cm2.account_id
                WHERE cm2.conversation_id = c.id
            )
        )
        ORDER BY cm.is_pinned DESC, c.updated_at DESC
    ), '[]'::jsonb)
    INTO v_result
    FROM public.conversation_members cm
    JOIN public.conversations c ON c.id = cm.conversation_id
    WHERE cm.account_id = v_user_id;

    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_my_conversations() TO authenticated;

-- 8. CANONICAL RPC: create_attachment
CREATE OR REPLACE FUNCTION public.create_attachment(
    p_attachment_id TEXT,
    p_conversation_id TEXT,
    p_file_name TEXT,
    p_file_size BIGINT,
    p_mime_type TEXT,
    p_storage_path TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    -- Verify caller is a member of the conversation
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members
        WHERE conversation_id = p_conversation_id AND account_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'User % is not a member of conversation %', v_user_id, p_conversation_id;
    END IF;

    INSERT INTO public.attachments (
        id,
        conversation_id,
        uploader_id,
        file_name,
        file_size,
        mime_type,
        storage_path,
        created_at
    ) VALUES (
        p_attachment_id,
        p_conversation_id,
        v_user_id,
        p_file_name,
        p_file_size,
        p_mime_type,
        p_storage_path,
        now()
    )
    ON CONFLICT (id) DO UPDATE SET
        storage_path = EXCLUDED.storage_path,
        file_size = EXCLUDED.file_size;

    RETURN jsonb_build_object(
        'id', p_attachment_id,
        'conversation_id', p_conversation_id,
        'storage_path', p_storage_path,
        'status', 'created'
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.create_attachment(TEXT, TEXT, TEXT, BIGINT, TEXT, TEXT) TO authenticated;

-- 9. STRICT STORAGE RLS POLICIES FOR ATTACHMENTS
-- Restrict chat-attachments bucket access to conversation members only
DROP POLICY IF EXISTS "Members can view chat attachments" ON storage.objects;
CREATE POLICY "Members can view chat attachments"
    ON storage.objects FOR SELECT
    TO authenticated
    USING (
        bucket_id IN ('chat-attachments', 'attachments') AND
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = (storage.foldername(name))[1]
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Members can upload chat attachments" ON storage.objects;
CREATE POLICY "Members can upload chat attachments"
    ON storage.objects FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id IN ('chat-attachments', 'attachments') AND
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = (storage.foldername(name))[1]
              AND cm.account_id = auth.uid()
        )
    );

-- 10. NOTIFY POSTGREST TO RELOAD SCHEMA CACHE
NOTIFY pgrst, 'reload schema';
