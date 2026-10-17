-- ====================================================================
-- Migration: 20261015120000_cloud_first_storage_and_actions.sql
-- Description:
-- 1. Canonical cloud-first RPCs for conversations, attachments, actions:
--    - create_or_get_conversation
--    - create_attachment_upload & finalize_attachment
--    - mark_conversation_read & toggle_conversation_pin
--    - edit_message & delete_message
-- 2. Storage and RLS validation
-- ====================================================================

-- 1. Ensure columns exist on attachments table
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'completed';
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS encryption_version INT DEFAULT 2;

-- 2. CANONICAL create_or_get_conversation RPC
CREATE OR REPLACE FUNCTION public.create_or_get_conversation(
    p_peer_id TEXT,
    p_type TEXT DEFAULT 'DIRECT',
    p_title TEXT DEFAULT ''
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
    v_peer_uuid UUID;
    v_conv_id TEXT;
    v_conv_type TEXT;
    v_existing_id TEXT;
    v_now TIMESTAMPTZ := now();
    v_u1 TEXT;
    v_u2 TEXT;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    v_conv_type := upper(coalesce(p_type, 'DIRECT'));

    -- Handle SAVED_MESSAGES
    IF v_conv_type = 'SAVED_MESSAGES' OR p_peer_id = 'self' OR p_peer_id = 'me' OR p_peer_id = v_user_id::text THEN
        RETURN public.get_or_create_saved_messages_conversation();
    END IF;

    -- Handle AI Assistant
    IF v_conv_type = 'AI' OR p_peer_id LIKE 'ai_%' OR p_peer_id = 'ai_assistant' OR p_peer_id = 'hexagon' OR p_peer_id = 'ventaxis' THEN
        RETURN public.get_or_create_ai_conversation();
    END IF;

    -- DIRECT Conversation
    BEGIN
        v_peer_uuid := p_peer_id::UUID;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Invalid peer UUID: %', p_peer_id;
    END;

    IF v_peer_uuid = v_user_id THEN
        RETURN public.get_or_create_saved_messages_conversation();
    END IF;

    -- Deterministic conversation ID for 1-on-1 direct chat
    v_u1 := v_user_id::text;
    v_u2 := v_peer_uuid::text;
    IF v_u1 < v_u2 THEN
        v_conv_id := 'direct_' || v_u1 || '_' || v_u2;
    ELSE
        v_conv_id := 'direct_' || v_u2 || '_' || v_u1;
    END IF;

    -- Check if conversation exists
    SELECT id INTO v_existing_id FROM public.conversations WHERE id = v_conv_id;

    IF v_existing_id IS NULL THEN
        INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
        VALUES (v_conv_id, 'DIRECT', coalesce(p_title, ''), v_user_id, 0, v_now, v_now)
        ON CONFLICT (id) DO NOTHING;

        -- Add both participants
        INSERT INTO public.conversation_members (conversation_id, account_id, role, joined_at)
        VALUES 
            (v_conv_id, v_user_id, 'member', v_now),
            (v_conv_id, v_peer_uuid, 'member', v_now)
        ON CONFLICT (conversation_id, account_id) DO NOTHING;
    ELSE
        -- Ensure current user and peer are members
        INSERT INTO public.conversation_members (conversation_id, account_id, role, joined_at)
        VALUES 
            (v_conv_id, v_user_id, 'member', v_now),
            (v_conv_id, v_peer_uuid, 'member', v_now)
        ON CONFLICT (conversation_id, account_id) DO NOTHING;
    END IF;

    RETURN jsonb_build_object(
        'id', v_conv_id,
        'type', 'DIRECT',
        'title', coalesce(p_title, ''),
        'recipient_id', v_peer_uuid::text,
        'created_at', v_now
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.create_or_get_conversation(TEXT, TEXT, TEXT) TO authenticated;

-- 3. CANONICAL create_attachment_upload & finalize_attachment RPCs
CREATE OR REPLACE FUNCTION public.create_attachment_upload(
    p_conversation_id TEXT,
    p_file_name TEXT,
    p_file_size BIGINT,
    p_mime_type TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
    v_attach_id TEXT;
    v_storage_path TEXT;
    v_now TIMESTAMPTZ := now();
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Verify membership in conversation
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members
        WHERE conversation_id = p_conversation_id AND account_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'Not a member of conversation %', p_conversation_id;
    END IF;

    v_attach_id := gen_random_uuid()::text;
    v_storage_path := p_conversation_id || '/' || v_attach_id || '.bin';

    INSERT INTO public.attachments (
        id, conversation_id, uploader_id, file_name, file_size, mime_type, storage_path, status, created_at
    ) VALUES (
        v_attach_id, p_conversation_id, v_user_id, coalesce(p_file_name, ''), p_file_size, coalesce(p_mime_type, 'application/octet-stream'), v_storage_path, 'pending', v_now
    );

    RETURN jsonb_build_object(
        'attachment_id', v_attach_id,
        'storage_path', v_storage_path,
        'conversation_id', p_conversation_id
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.create_attachment_upload(TEXT, TEXT, BIGINT, TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.finalize_attachment(
    p_attachment_id TEXT,
    p_file_size BIGINT DEFAULT 0,
    p_storage_path TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE public.attachments
    SET status = 'completed',
        file_size = CASE WHEN p_file_size > 0 THEN p_file_size ELSE file_size END,
        storage_path = coalesce(p_storage_path, storage_path)
    WHERE id = p_attachment_id AND uploader_id = v_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Attachment % not found or not owned by user', p_attachment_id;
    END IF;

    RETURN jsonb_build_object('success', true, 'attachment_id', p_attachment_id);
END;
$$;
GRANT EXECUTE ON FUNCTION public.finalize_attachment(TEXT, BIGINT, TEXT) TO authenticated;

-- 4. CANONICAL mark_conversation_read & toggle_conversation_pin RPCs
CREATE OR REPLACE FUNCTION public.mark_conversation_read(
    p_conversation_id TEXT,
    p_sequence BIGINT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE public.conversation_members
    SET last_read_sequence = GREATEST(last_read_sequence, p_sequence)
    WHERE conversation_id = p_conversation_id AND account_id = v_user_id;

    RETURN jsonb_build_object('success', true, 'conversation_id', p_conversation_id, 'last_read_sequence', p_sequence);
END;
$$;
GRANT EXECUTE ON FUNCTION public.mark_conversation_read(TEXT, BIGINT) TO authenticated;

CREATE OR REPLACE FUNCTION public.toggle_conversation_pin(
    p_conversation_id TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
    v_new_pinned BOOLEAN;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE public.conversation_members
    SET is_pinned = NOT is_pinned
    WHERE conversation_id = p_conversation_id AND account_id = v_user_id
    RETURNING is_pinned INTO v_new_pinned;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Membership not found for conversation %', p_conversation_id;
    END IF;

    RETURN jsonb_build_object('success', true, 'conversation_id', p_conversation_id, 'is_pinned', v_new_pinned);
END;
$$;
GRANT EXECUTE ON FUNCTION public.toggle_conversation_pin(TEXT) TO authenticated;

-- 5. CANONICAL edit_message & delete_message RPCs
CREATE OR REPLACE FUNCTION public.edit_message(
    p_message_id TEXT,
    p_payload TEXT,
    p_signature TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE public.messages
    SET payload = p_payload,
        signature = coalesce(p_signature, signature)
    WHERE (id = p_message_id OR client_message_id = p_message_id) AND sender_id = v_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Message % not found or not owned by user', p_message_id;
    END IF;

    RETURN jsonb_build_object('success', true, 'message_id', p_message_id);
END;
$$;
GRANT EXECUTE ON FUNCTION public.edit_message(TEXT, TEXT, TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION public.delete_message(
    p_message_id TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE public.messages
    SET is_deleted = true,
        payload = '[Deleted message]'
    WHERE (id = p_message_id OR client_message_id = p_message_id) AND sender_id = v_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Message % not found or not owned by user', p_message_id;
    END IF;

    RETURN jsonb_build_object('success', true, 'message_id', p_message_id);
END;
$$;
GRANT EXECUTE ON FUNCTION public.delete_message(TEXT) TO authenticated;
