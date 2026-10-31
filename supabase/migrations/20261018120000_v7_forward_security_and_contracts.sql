-- ====================================================================
-- FORWARD MIGRATION V7: CANONICAL CONTRACTS, SECURITY & ACL HARDENING
-- Target: HexShard Messenger V7 Forward Upgrade (N016)
-- Description:
-- 1. Drops legacy resolve_profile_by_share_id table signature and enforces
--    authenticated-only JSONB resolver (N001, N013).
-- 2. Drops stale 6-argument create_attachment overloads (N002).
-- 3. Drops legacy permissive conversations INSERT policies and secures namespace (N003).
-- 4. Reconciles attachments uploader_id and size consistency (N010).
-- 5. Tightens send_message_idempotent duplicate request validation (N012).
-- 6. Confirms service-role exclusivity on verify_telegram_challenge_atomic.
-- 7. Reloads PostgREST schema cache.
-- ====================================================================

-- 1. LEGACY PROFILE RESOLVER CLEANUP & HARDENING (N001, N013)
DROP FUNCTION IF EXISTS public.resolve_profile_by_share_id(TEXT);

CREATE OR REPLACE FUNCTION public.resolve_profile_by_share_id(
    p_share_id TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
    v_username TEXT;
    v_avatar_url TEXT;
    v_bg_path TEXT;
    v_bg_type TEXT;
    v_hex_number TEXT;
    v_public_key TEXT;
    v_key_version INT := 1;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    IF p_share_id IS NULL OR trim(p_share_id) = '' THEN
        RETURN jsonb_build_object('found', false);
    END IF;

    -- 1. Try finding by hex number
    SELECT owner_id INTO v_user_id
    FROM public.hex_numbers
    WHERE (number = trim(p_share_id) OR formatted = trim(p_share_id))
      AND status = 'active'
    LIMIT 1;

    -- 2. Try finding by username or UUID
    IF v_user_id IS NULL THEN
        SELECT id INTO v_user_id
        FROM public.profiles
        WHERE normalized_username = lower(trim(replace(p_share_id, '@', '')))
           OR id::text = trim(p_share_id)
        LIMIT 1;
    END IF;

    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('found', false);
    END IF;

    -- Fetch profile details
    SELECT 
        p.username, 
        COALESCE(p.avatar_url, ''), 
        COALESCE(p.profile_background_path, ''),
        COALESCE(p.profile_background_type, '')
    INTO v_username, v_avatar_url, v_bg_path, v_bg_type
    FROM public.profiles p
    WHERE p.id = v_user_id;

    -- Fetch active hex number
    SELECT COALESCE(formatted, number) INTO v_hex_number
    FROM public.hex_numbers
    WHERE owner_id = v_user_id AND status = 'active'
    LIMIT 1;

    -- Fetch active device public key
    SELECT public_key, key_version INTO v_public_key, v_key_version
    FROM public.active_device_keys
    WHERE account_id = v_user_id
    ORDER BY key_version DESC
    LIMIT 1;

    IF v_public_key IS NULL OR v_public_key = '' THEN
        SELECT public_key INTO v_public_key FROM public.profiles WHERE id = v_user_id;
    END IF;

    RETURN jsonb_build_object(
        'found', true,
        'user_id', v_user_id,
        'username', v_username,
        'avatar_url', COALESCE(v_avatar_url, ''),
        'profile_background_path', COALESCE(v_bg_path, ''),
        'profile_background_type', COALESCE(v_bg_type, ''),
        'hex_number', COALESCE(v_hex_number, ''),
        'public_key', COALESCE(v_public_key, ''),
        'key_version', COALESCE(v_key_version, 1)
    );
END;
$$;
REVOKE EXECUTE ON FUNCTION public.resolve_profile_by_share_id(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.resolve_profile_by_share_id(TEXT) TO authenticated;

-- 2. DROP OBSOLETE ATTACHMENT RPC OVERLOADS (N002)
DROP FUNCTION IF EXISTS public.create_attachment(TEXT, TEXT, TEXT, BIGINT, TEXT, INT);
DROP FUNCTION IF EXISTS public.create_attachment(TEXT, TEXT, TEXT, BIGINT, TEXT, TEXT);

-- 3. CONVERSATIONS NAMESPACE RLS PERMISSIVE POLICY CLEANUP (N003)
DROP POLICY IF EXISTS "Authenticated users can create conversations" ON public.conversations;
DROP POLICY IF EXISTS "conversations_insert_policy" ON public.conversations;
DROP POLICY IF EXISTS "conversations_authenticated_insert" ON public.conversations;

CREATE POLICY "conversations_authenticated_insert"
    ON public.conversations FOR INSERT
    TO authenticated
    WITH CHECK (
        created_by = auth.uid()
        AND (
            (id NOT LIKE 'ai\_%' ESCAPE '\' AND id NOT LIKE 'self\_%' ESCAPE '\' AND id NOT LIKE 'direct\_%' ESCAPE '\')
            OR (id = 'ai_' || auth.uid()::text AND type = 'AI')
            OR (id = 'self_' || auth.uid()::text AND type = 'SAVED_MESSAGES')
        )
    );

-- 4. ATTACHMENTS SCHEMA & TRIGGER RECONCILIATION (N010)
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS uploader_id UUID REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS size_bytes BIGINT;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS encryption_version INT DEFAULT 2;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS checksum TEXT;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS file_name TEXT DEFAULT '';
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'completed';
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

CREATE OR REPLACE FUNCTION public.sync_attachments_compat()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner_id IS NULL AND NEW.uploader_id IS NOT NULL THEN
        NEW.owner_id := NEW.uploader_id;
    END IF;
    IF NEW.uploader_id IS NULL AND NEW.owner_id IS NOT NULL THEN
        NEW.uploader_id := NEW.owner_id;
    END IF;
    IF NEW.owner_id IS NOT NULL AND NEW.uploader_id IS NOT NULL AND NEW.owner_id != NEW.uploader_id THEN
        RAISE EXCEPTION 'Attachment owner_id and uploader_id must match' USING ERRCODE = '23514';
    END IF;
    IF (NEW.size IS NULL OR NEW.size = 0) AND NEW.size_bytes IS NOT NULL THEN
        NEW.size := NEW.size_bytes;
    END IF;
    IF (NEW.size_bytes IS NULL OR NEW.size_bytes = 0) AND NEW.size IS NOT NULL THEN
        NEW.size_bytes := NEW.size;
    END IF;
    IF NEW.updated_at IS NULL THEN
        NEW.updated_at := now();
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_attachments_compat ON public.attachments;
CREATE TRIGGER trg_sync_attachments_compat
    BEFORE INSERT OR UPDATE ON public.attachments
    FOR EACH ROW EXECUTE FUNCTION public.sync_attachments_compat();

-- 5. IDEMPOTENT MESSAGE SENDING WITH CONFLICT DETECTION (N012)
CREATE OR REPLACE FUNCTION public.send_message_idempotent(
    p_conversation_id TEXT,
    p_client_message_id TEXT,
    p_payload TEXT,
    p_signature TEXT DEFAULT '',
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
        RAISE EXCEPTION 'Invalid parameters: conversation_id, client_message_id and payload are required';
    END IF;

    -- Verify membership
    IF NOT public.is_conversation_member(p_conversation_id, v_sender_id) THEN
        RAISE EXCEPTION 'Access denied: Not a member of this conversation' USING ERRCODE = '42501';
    END IF;

    -- Lock conversation row to serialize sequence allocation and avoid race conditions
    SELECT current_sequence INTO v_new_sequence
    FROM public.conversations
    WHERE id = p_conversation_id
    FOR UPDATE;

    IF v_new_sequence IS NULL THEN
        RAISE EXCEPTION 'Conversation % not found', p_conversation_id;
    END IF;

    -- Check if client_message_id has already been inserted under lock
    SELECT id, sequence, status, created_at, client_message_id, sender_id, payload INTO v_existing_msg
    FROM public.messages
    WHERE conversation_id = p_conversation_id AND client_message_id = p_client_message_id;

    IF v_existing_msg.id IS NOT NULL THEN
        -- Verify that the existing message belongs to the same sender
        IF v_existing_msg.sender_id != v_sender_id THEN
            RAISE EXCEPTION 'Idempotency conflict: client_message_id was submitted by another sender' USING ERRCODE = '23505';
        END IF;

        RETURN jsonb_build_object(
            'success', true,
            'id', v_existing_msg.id,
            'message_id', v_existing_msg.id,
            'client_message_id', v_existing_msg.client_message_id,
            'sequence', v_existing_msg.sequence,
            'server_sequence', coalesce(v_existing_msg.sequence, 0),
            'conversation_id', p_conversation_id,
            'status', v_existing_msg.status,
            'idempotent', true,
            'is_duplicate', true,
            'created_at', v_existing_msg.created_at
        );
    END IF;

    v_new_sequence := coalesce(v_new_sequence, 0) + 1;

    UPDATE public.conversations
    SET current_sequence = v_new_sequence,
        updated_at = v_now
    WHERE id = p_conversation_id;

    IF p_recipient_id IS NOT NULL AND p_recipient_id != '' AND p_recipient_id != 'self' AND NOT p_recipient_id LIKE 'ai_%' THEN
        BEGIN
            v_recipient_uuid := p_recipient_id::UUID;
        EXCEPTION WHEN OTHERS THEN
            v_recipient_uuid := NULL;
        END;
    END IF;

    INSERT INTO public.messages (
        id,
        conversation_id,
        client_message_id,
        sender_id,
        recipient_id,
        payload,
        signature,
        type,
        time_str,
        encryption_version,
        persona_id,
        sequence,
        status,
        created_at
    )
    VALUES (
        p_client_message_id,
        p_conversation_id,
        p_client_message_id,
        v_sender_id,
        coalesce(v_recipient_uuid::text, p_recipient_id),
        p_payload,
        coalesce(p_signature, ''),
        coalesce(p_type, 'text'),
        coalesce(p_time_str, ''),
        coalesce(p_encryption_version, 2),
        p_persona_id,
        v_new_sequence,
        'sent',
        v_now
    )
    RETURNING id, sequence, status, created_at, client_message_id INTO v_existing_msg;

    RETURN jsonb_build_object(
        'success', true,
        'id', v_existing_msg.id,
        'message_id', v_existing_msg.id,
        'client_message_id', p_client_message_id,
        'sequence', v_new_sequence,
        'server_sequence', v_new_sequence,
        'conversation_id', p_conversation_id,
        'status', 'sent',
        'idempotent', false,
        'is_duplicate', false,
        'created_at', v_now
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INT) TO authenticated;

-- 6. STRICT SECURITY GRANTS ON TELEGRAM VERIFICATION & SERVICE TABLES
REVOKE EXECUTE ON FUNCTION public.verify_telegram_challenge_atomic(TEXT, TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.verify_telegram_challenge_atomic(TEXT, TEXT, UUID) TO service_role;

ALTER TABLE IF EXISTS public.telegram_processed_updates ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.telegram_processed_updates FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.telegram_processed_updates TO service_role;

ALTER TABLE IF EXISTS public.account_recovery_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.account_recovery_tickets FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.account_recovery_tickets TO service_role;

NOTIFY pgrst, 'reload schema';
