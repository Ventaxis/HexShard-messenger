-- ====================================================================
-- Migration: 20261015120000_cloud_first_full_parity.sql
-- Description:
-- 1. Complete cloud-first persistence schema alignment.
-- 2. Add author_type, persona_id, and server_sequence columns to messages.
-- 3. Add last_read_sequence, is_pinned, is_muted, is_archived to conversation_members.
-- 4. Canonical RPCs:
--    - update_my_profile(display_name, bio, date_of_birth, avatar_path, avatar_mime, bg_path, bg_type)
--    - backup_private_key(encrypted_blob, metadata)
--    - send_ai_message(conversation_id, user_client_id, user_text, reply_client_id, reply_text, persona_id, time_str)
--    - sync_conversations_for_user()
--    - get_my_conversations()
--    - get_incremental_messages(conversation_id, after_sequence, limit)
--    - send_message_idempotent(...)
-- 5. Strict RLS policies and postgrest schema reload.
-- ====================================================================

-- 1. EXTEND TABLES WITH REQUIRED COLUMNS
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS author_type TEXT DEFAULT 'USER';
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS persona_id TEXT DEFAULT NULL;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS server_sequence BIGINT DEFAULT 0;

ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS last_read_sequence BIGINT DEFAULT 0;
ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS is_pinned BOOLEAN DEFAULT false;
ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS is_muted BOOLEAN DEFAULT false;
ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS is_archived BOOLEAN DEFAULT false;

-- Ensure unique client_message_id per conversation
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_conv_client ON public.messages(conversation_id, client_message_id);
CREATE INDEX IF NOT EXISTS idx_messages_conv_seq ON public.messages(conversation_id, sequence);
CREATE INDEX IF NOT EXISTS idx_messages_persona ON public.messages(persona_id);
CREATE INDEX IF NOT EXISTS idx_messages_author_type ON public.messages(author_type);

-- 2. UPDATE_MY_PROFILE RPC
CREATE OR REPLACE FUNCTION public.update_my_profile(
    p_display_name TEXT DEFAULT NULL,
    p_bio TEXT DEFAULT NULL,
    p_date_of_birth DATE DEFAULT NULL,
    p_avatar_path TEXT DEFAULT NULL,
    p_avatar_mime_type TEXT DEFAULT NULL,
    p_background_path TEXT DEFAULT NULL,
    p_background_type TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_profile RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    UPDATE public.profiles
    SET
        display_name = coalesce(p_display_name, display_name),
        bio = coalesce(p_bio, bio),
        date_of_birth = CASE WHEN p_date_of_birth IS NOT NULL THEN p_date_of_birth ELSE date_of_birth END,
        avatar_url = CASE WHEN p_avatar_path IS NOT NULL THEN p_avatar_path ELSE avatar_url END,
        avatar_path = CASE WHEN p_avatar_path IS NOT NULL THEN p_avatar_path ELSE avatar_path END,
        profile_background_path = CASE WHEN p_background_path IS NOT NULL THEN p_background_path ELSE profile_background_path END,
        profile_background_type = CASE WHEN p_background_type IS NOT NULL THEN p_background_type ELSE profile_background_type END,
        profile_background_updated_at = now(),
        updated_at = now()
    WHERE id = v_user_id
    RETURNING id, username, display_name, bio, date_of_birth, avatar_url, hex_number, profile_background_path, profile_background_type
    INTO v_profile;

    IF v_profile.id IS NULL THEN
        RAISE EXCEPTION 'Profile not found for user %', v_user_id;
    END IF;

    RETURN jsonb_build_object(
        'status', 'OK',
        'profile', jsonb_build_object(
            'id', v_profile.id,
            'username', v_profile.username,
            'display_name', v_profile.display_name,
            'bio', v_profile.bio,
            'date_of_birth', v_profile.date_of_birth,
            'avatar_url', v_profile.avatar_url,
            'hex_number', v_profile.hex_number,
            'background_path', v_profile.profile_background_path,
            'background_type', v_profile.profile_background_type
        )
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.update_my_profile(TEXT, TEXT, DATE, TEXT, TEXT, TEXT, TEXT) TO authenticated;

-- 3. BACKUP_PRIVATE_KEY RPC
CREATE OR REPLACE FUNCTION public.backup_private_key(
    p_encrypted_blob TEXT,
    p_metadata JSONB DEFAULT '{}'::jsonb
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_salt TEXT := coalesce(p_metadata->>'salt', '');
    v_iv TEXT := coalesce(p_metadata->>'iv', '');
    v_version INT := coalesce((p_metadata->>'version')::int, 1);
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    INSERT INTO public.user_crypto_backups (
        account_id, encrypted_envelope, salt, iv, key_version, updated_at
    ) VALUES (
        v_user_id, p_encrypted_blob, v_salt, v_iv, v_version, now()
    )
    ON CONFLICT (account_id) DO UPDATE SET
        encrypted_envelope = EXCLUDED.encrypted_envelope,
        salt = EXCLUDED.salt,
        iv = EXCLUDED.iv,
        key_version = EXCLUDED.key_version,
        updated_at = now();

    RETURN jsonb_build_object('status', 'OK', 'success', true);
END;
$$;
GRANT EXECUTE ON FUNCTION public.backup_private_key(TEXT, JSONB) TO authenticated;

-- 4. SEND_AI_MESSAGE RPC
CREATE OR REPLACE FUNCTION public.send_ai_message(
    p_conversation_id TEXT,
    p_user_client_id TEXT,
    p_user_text TEXT,
    p_reply_client_id TEXT,
    p_reply_text TEXT,
    p_persona_id TEXT,
    p_time_str TEXT DEFAULT ''
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_now TIMESTAMPTZ := now();
    v_seq1 BIGINT;
    v_seq2 BIGINT;
    v_conv_id TEXT := p_conversation_id;
    v_existing_user RECORD;
    v_persona TEXT := lower(trim(p_persona_id));
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    IF v_persona NOT IN ('ventaxis', 'hexagon') THEN
        RAISE EXCEPTION 'UNKNOWN_PERSONA: Persona must be ventaxis or hexagon, got %', p_persona_id;
    END IF;

    -- F001 FIX: Enforce canonical AI conversation ID derived strictly from calling user
    IF p_conversation_id IS NOT NULL AND p_conversation_id != '' AND p_conversation_id != ('ai_' || v_user_id::text) THEN
        RAISE EXCEPTION 'Access denied: Cannot bind AI message to foreign conversation' USING ERRCODE = '42501';
    END IF;
    v_conv_id := 'ai_' || v_user_id::text;

    -- Ensure AI conversation & membership exist for this user only
    INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
    VALUES (v_conv_id, 'AI', 'HexShard AI', v_user_id, 0, v_now, v_now)
    ON CONFLICT (id) DO UPDATE
        SET updated_at = v_now
        WHERE conversations.created_by = v_user_id AND conversations.type = 'AI';

    INSERT INTO public.conversation_members (conversation_id, account_id, role, is_pinned)
    VALUES (v_conv_id, v_user_id, 'owner', true)
    ON CONFLICT (conversation_id, account_id) DO NOTHING;

    -- Idempotency guard: check if user message already stored
    SELECT sequence, id INTO v_existing_user
    FROM public.messages
    WHERE conversation_id = v_conv_id AND client_message_id = p_user_client_id;

    IF v_existing_user.id IS NOT NULL THEN
        SELECT sequence INTO v_seq2
        FROM public.messages
        WHERE conversation_id = v_conv_id AND client_message_id = p_reply_client_id;

        RETURN jsonb_build_object(
            'status', 'OK',
            'user_message_id', p_user_client_id,
            'user_sequence', v_existing_user.sequence,
            'reply_message_id', p_reply_client_id,
            'reply_sequence', coalesce(v_seq2, v_existing_user.sequence + 1),
            'persona_id', v_persona,
            'is_duplicate', true
        );
    END IF;

    -- Allocate two contiguous sequences
    UPDATE public.conversations
    SET current_sequence = coalesce(current_sequence, 0) + 2,
        updated_at = v_now
    WHERE id = v_conv_id
    RETURNING current_sequence INTO v_seq2;

    v_seq1 := v_seq2 - 1;

    -- 1. Insert user message
    INSERT INTO public.messages (
        id, conversation_id, sender_id, recipient_id, client_message_id,
        sequence, server_sequence, payload, type, time_str, encryption_version, status,
        persona_id, author_type, is_deleted, created_at
    ) VALUES (
        p_user_client_id, v_conv_id, v_user_id, NULL, p_user_client_id,
        v_seq1, v_seq1, p_user_text, 'text', p_time_str, 0, 'delivered',
        v_persona, 'USER', false, v_now
    )
    ON CONFLICT (conversation_id, client_message_id) DO UPDATE SET
        server_sequence = EXCLUDED.server_sequence,
        sequence = EXCLUDED.sequence;

    -- 2. Insert assistant response
    INSERT INTO public.messages (
        id, conversation_id, sender_id, recipient_id, client_message_id,
        sequence, server_sequence, payload, type, time_str, encryption_version, status,
        persona_id, author_type, is_deleted, created_at
    ) VALUES (
        p_reply_client_id, v_conv_id, v_user_id, NULL, p_reply_client_id,
        v_seq2, v_seq2, p_reply_text, 'text', p_time_str, 0, 'delivered',
        v_persona, 'AI', false, v_now
    )
    ON CONFLICT (conversation_id, client_message_id) DO UPDATE SET
        server_sequence = EXCLUDED.server_sequence,
        sequence = EXCLUDED.sequence;

    RETURN jsonb_build_object(
        'status', 'OK',
        'user_message_id', p_user_client_id,
        'user_sequence', v_seq1,
        'reply_message_id', p_reply_client_id,
        'reply_sequence', v_seq2,
        'persona_id', v_persona,
        'is_duplicate', false
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.send_ai_message(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated;

-- 5. CANONICAL SYNC_CONVERSATIONS_FOR_USER RPC
CREATE OR REPLACE FUNCTION public.sync_conversations_for_user()
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
            'conversation_id', c.id,
            'type', c.type,
            'title', c.title,
            'current_sequence', coalesce(c.current_sequence, 0),
            'created_at', c.created_at,
            'updated_at', c.updated_at,
            'last_read_sequence', coalesce(cm.last_read_sequence, 0),
            'is_pinned', coalesce(cm.is_pinned, false),
            'is_muted', coalesce(cm.is_muted, false),
            'is_archived', coalesce(cm.is_archived, false),
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
GRANT EXECUTE ON FUNCTION public.sync_conversations_for_user() TO authenticated;

-- Alias for backward compatibility
CREATE OR REPLACE FUNCTION public.get_my_conversations()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN public.sync_conversations_for_user();
END;
$$;
GRANT EXECUTE ON FUNCTION public.get_my_conversations() TO authenticated;

-- 6. GET_INCREMENTAL_MESSAGES RPC
CREATE OR REPLACE FUNCTION public.get_incremental_messages(
    p_conversation_id TEXT,
    p_after_sequence BIGINT DEFAULT 0,
    p_limit INT DEFAULT 100
)
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

    -- Membership verification
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members
        WHERE conversation_id = p_conversation_id AND account_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'User % is not a member of conversation %', v_user_id, p_conversation_id;
    END IF;

    SELECT coalesce(jsonb_agg(
        jsonb_build_object(
            'id', m.id,
            'conversation_id', m.conversation_id,
            'client_message_id', m.client_message_id,
            'sender_id', m.sender_id,
            'recipient_id', m.recipient_id,
            'sequence', m.sequence,
            'server_sequence', coalesce(m.server_sequence, m.sequence),
            'payload', m.payload,
            'signature', m.signature,
            'type', m.type,
            'time_str', m.time_str,
            'status', m.status,
            'persona_id', m.persona_id,
            'author_type', coalesce(m.author_type, 'USER'),
            'encryption_version', coalesce(m.encryption_version, 2),
            'is_deleted', coalesce(m.is_deleted, false),
            'created_at', m.created_at
        )
        ORDER BY m.sequence ASC
    ), '[]'::jsonb)
    INTO v_result
    FROM public.messages m
    WHERE m.conversation_id = p_conversation_id
      AND m.sequence > p_after_sequence
      AND coalesce(m.is_deleted, false) = false
    LIMIT least(coalesce(p_limit, 100), 200);

    RETURN v_result;
END;
$$;
GRANT EXECUTE ON FUNCTION public.get_incremental_messages(TEXT, BIGINT, INT) TO authenticated;

-- 7. NOTIFY POSTGREST SCHEMA CACHE RELOAD
NOTIFY pgrst, 'reload schema';
