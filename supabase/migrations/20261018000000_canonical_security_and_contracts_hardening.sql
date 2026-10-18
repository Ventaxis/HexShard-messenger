-- ====================================================================
-- CANONICAL SECURITY AND CONTRACTS HARDENING MIGRATION
-- Project: HexShard Messenger
-- ====================================================================

-- 1. NON-RECURSIVE MEMBERSHIP HELPER FUNCTION (F002)
CREATE OR REPLACE FUNCTION public.is_conversation_member(p_conv_id TEXT, p_user_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.conversation_members
        WHERE conversation_id = p_conv_id AND account_id = p_user_id
    );
$$;
GRANT EXECUTE ON FUNCTION public.is_conversation_member(TEXT, UUID) TO authenticated, service_role;

-- 2. NON-RECURSIVE RLS POLICIES ON conversation_members (F002, F003)
DROP POLICY IF EXISTS "Members can view their conversation memberships" ON public.conversation_members;
DROP POLICY IF EXISTS "Members can view membership" ON public.conversation_members;
DROP POLICY IF EXISTS "Users can insert membership when joining or creating" ON public.conversation_members;
DROP POLICY IF EXISTS "Users can update their own membership" ON public.conversation_members;
DROP POLICY IF EXISTS "Users can update own membership settings" ON public.conversation_members;

CREATE POLICY "conversation_members_select_policy"
    ON public.conversation_members FOR SELECT
    TO authenticated
    USING (
        account_id = auth.uid()
        OR public.is_conversation_member(conversation_id, auth.uid())
    );

CREATE POLICY "conversation_members_update_personal_settings"
    ON public.conversation_members FOR UPDATE
    TO authenticated
    USING (account_id = auth.uid())
    WITH CHECK (account_id = auth.uid());

-- Trigger to prevent regular users from elevating role or modifying conversation_id/account_id (F003)
CREATE OR REPLACE FUNCTION public.protect_membership_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF current_user != 'service_role' AND (OLD.role != NEW.role OR OLD.conversation_id != NEW.conversation_id OR OLD.account_id != NEW.account_id) THEN
        RAISE EXCEPTION 'Cannot modify identity or role directly. Use dedicated RPCs.' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_protect_membership ON public.conversation_members;
CREATE TRIGGER trg_protect_membership
    BEFORE UPDATE ON public.conversation_members
    FOR EACH ROW EXECUTE FUNCTION public.protect_membership_immutability();

-- 3. DROP OBSOLETE & PERMISSIVE MESSAGES POLICIES (F004)
DROP POLICY IF EXISTS "messages_insert_policy" ON public.messages;
DROP POLICY IF EXISTS "messages_select_policy" ON public.messages;
DROP POLICY IF EXISTS "Senders can insert messages into conversations they belong to" ON public.messages;
DROP POLICY IF EXISTS "Senders and recipients can update message status" ON public.messages;
DROP POLICY IF EXISTS "Members can view messages in their conversations" ON public.messages;
DROP POLICY IF EXISTS "Members can view messages" ON public.messages;
DROP POLICY IF EXISTS "Members can insert messages" ON public.messages;
DROP POLICY IF EXISTS "Senders can update own messages" ON public.messages;
DROP POLICY IF EXISTS "Senders can delete own messages" ON public.messages;

CREATE POLICY "messages_member_select"
    ON public.messages FOR SELECT
    TO authenticated
    USING (public.is_conversation_member(conversation_id, auth.uid()));

CREATE POLICY "messages_member_insert"
    ON public.messages FOR INSERT
    TO authenticated
    WITH CHECK (
        sender_id = auth.uid()
        AND public.is_conversation_member(conversation_id, auth.uid())
    );

CREATE POLICY "messages_member_update"
    ON public.messages FOR UPDATE
    TO authenticated
    USING (public.is_conversation_member(conversation_id, auth.uid()))
    WITH CHECK (public.is_conversation_member(conversation_id, auth.uid()));

-- 4. ATTACHMENTS SCHEMA & POLICIES (F015, F020, F061, D002)
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS uploader_id UUID REFERENCES auth.users(id) ON DELETE CASCADE;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS size_bytes BIGINT;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS encryption_version INT DEFAULT 2;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS checksum TEXT;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS file_name TEXT DEFAULT '';
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'completed';
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

-- Ensure owner_id is nullable if present from earlier migrations, and sync with uploader_id
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'attachments' AND column_name = 'owner_id') THEN
        ALTER TABLE public.attachments ALTER COLUMN owner_id DROP NOT NULL;
        UPDATE public.attachments SET uploader_id = owner_id WHERE uploader_id IS NULL;
        UPDATE public.attachments SET owner_id = uploader_id WHERE owner_id IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'attachments' AND column_name = 'size') THEN
        UPDATE public.attachments SET size_bytes = size WHERE (size_bytes IS NULL OR size_bytes = 0) AND size > 0;
        UPDATE public.attachments SET size = size_bytes WHERE (size IS NULL OR size = 0) AND size_bytes > 0;
    END IF;
END $$;

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

DROP POLICY IF EXISTS "Members can view attachments metadata" ON public.attachments;
DROP POLICY IF EXISTS "Members can insert attachments metadata" ON public.attachments;
DROP POLICY IF EXISTS "Uploaders can delete attachments metadata" ON public.attachments;
DROP POLICY IF EXISTS "Members can view attachments in their conversations" ON public.attachments;
DROP POLICY IF EXISTS "Owners can insert attachments" ON public.attachments;

CREATE POLICY "attachments_member_select"
    ON public.attachments FOR SELECT
    TO authenticated
    USING (public.is_conversation_member(conversation_id, auth.uid()));

CREATE POLICY "attachments_member_insert"
    ON public.attachments FOR INSERT
    TO authenticated
    WITH CHECK (
        coalesce(uploader_id, owner_id) = auth.uid()
        AND public.is_conversation_member(conversation_id, auth.uid())
    );

CREATE POLICY "attachments_uploader_delete"
    ON public.attachments FOR DELETE
    TO authenticated
    USING (coalesce(uploader_id, owner_id) = auth.uid());

-- Drop old 6-argument create_attachment overloads (D006, N002)
DROP FUNCTION IF EXISTS public.create_attachment(TEXT, TEXT, TEXT, BIGINT, TEXT, INT);
DROP FUNCTION IF EXISTS public.create_attachment(TEXT, TEXT, TEXT, BIGINT, TEXT, TEXT);

-- Canonical create_attachment RPC (F015, F020)
CREATE OR REPLACE FUNCTION public.create_attachment(
    p_id TEXT,
    p_conversation_id TEXT,
    p_storage_path TEXT,
    p_file_size BIGINT,
    p_mime_type TEXT,
    p_encryption_version INT DEFAULT 2,
    p_file_name TEXT DEFAULT ''
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_now TIMESTAMPTZ := now();
    v_existing RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required' USING ERRCODE = '42501';
    END IF;

    IF NOT public.is_conversation_member(p_conversation_id, v_user_id) THEN
        RAISE EXCEPTION 'Access denied: Not a member of this conversation' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_existing FROM public.attachments WHERE id = p_id;
    IF v_existing.id IS NOT NULL THEN
        IF v_existing.uploader_id != v_user_id OR v_existing.conversation_id != p_conversation_id THEN
            RAISE EXCEPTION 'Access denied: Cannot overwrite attachment of another user or conversation' USING ERRCODE = '42501';
        END IF;

        UPDATE public.attachments
        SET storage_path = p_storage_path,
            size_bytes = p_file_size,
            mime_type = p_mime_type,
            file_name = p_file_name,
            file_size = p_file_size,
            encryption_version = p_encryption_version,
            updated_at = v_now
        WHERE id = p_id;

        RETURN jsonb_build_object('id', p_id, 'status', 'updated');
    END IF;

    INSERT INTO public.attachments (
        id, conversation_id, uploader_id, storage_path, size_bytes,
        mime_type, file_name, file_size, encryption_version, status, created_at, updated_at
    )
    VALUES (
        p_id, p_conversation_id, v_user_id, p_storage_path, p_file_size,
        p_mime_type, p_file_name, p_file_size, p_encryption_version, 'completed', v_now, v_now
    );

    RETURN jsonb_build_object('id', p_id, 'status', 'created');
END;
$$;
GRANT EXECUTE ON FUNCTION public.create_attachment(TEXT, TEXT, TEXT, BIGINT, TEXT, INT, TEXT) TO authenticated;

-- Storage object policies alignment (F061)
DROP POLICY IF EXISTS "Uploaders can delete attachments" ON storage.objects;
CREATE POLICY "Uploaders can delete attachments"
    ON storage.objects FOR DELETE
    TO authenticated
    USING (
        bucket_id IN ('chat-attachments', 'attachments')
        AND (
            owner = auth.uid()
            OR (storage.foldername(name))[2] = auth.uid()::text
            OR (storage.foldername(name))[1] = auth.uid()::text
        )
    );

-- 5. CANONICAL edit_message RPC (F018, D006)
DROP FUNCTION IF EXISTS public.edit_message(TEXT, TEXT, TEXT);
DROP FUNCTION IF EXISTS public.edit_message(TEXT, TEXT, TEXT, TEXT);

CREATE OR REPLACE FUNCTION public.edit_message(
    p_message_id TEXT,
    p_payload TEXT,
    p_signature TEXT DEFAULT '',
    p_conversation_id TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_msg RECORD;
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    SELECT * INTO v_msg
    FROM public.messages
    WHERE (id::text = p_message_id OR client_message_id = p_message_id)
      AND (p_conversation_id IS NULL OR conversation_id = p_conversation_id);

    IF v_msg.id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Message not found');
    END IF;

    IF v_msg.sender_id != v_user_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'Only sender can edit message');
    END IF;

    IF coalesce(v_msg.is_deleted, false) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Cannot edit deleted message');
    END IF;

    UPDATE public.messages
    SET payload = p_payload,
        signature = coalesce(nullif(p_signature, ''), signature),
        edited_at = v_now
    WHERE id = v_msg.id;

    RETURN jsonb_build_object(
        'success', true,
        'id', v_msg.id,
        'message_id', v_msg.id,
        'client_message_id', v_msg.client_message_id,
        'edited_at', v_now
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.edit_message(TEXT, TEXT, TEXT, TEXT) TO authenticated;

-- 6. CANONICAL delete_message RPC (F019, D006)
DROP FUNCTION IF EXISTS public.delete_message(TEXT);
DROP FUNCTION IF EXISTS public.delete_message(TEXT, TEXT);

CREATE OR REPLACE FUNCTION public.delete_message(
    p_message_id TEXT,
    p_conversation_id TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_msg RECORD;
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    SELECT * INTO v_msg
    FROM public.messages
    WHERE (id::text = p_message_id OR client_message_id = p_message_id)
      AND (p_conversation_id IS NULL OR conversation_id = p_conversation_id);

    IF v_msg.id IS NULL THEN
        RETURN jsonb_build_object('success', true, 'id', p_message_id, 'message_id', p_message_id, 'deleted', true);
    END IF;

    IF v_msg.sender_id != v_user_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'Only sender can delete message');
    END IF;

    UPDATE public.messages
    SET is_deleted = true,
        deleted_at = v_now,
        payload = ''
    WHERE id = v_msg.id;

    RETURN jsonb_build_object(
        'success', true,
        'id', v_msg.id,
        'message_id', v_msg.id,
        'client_message_id', v_msg.client_message_id,
        'deleted', true
    );
END;
$$;
GRANT EXECUTE ON FUNCTION public.delete_message(TEXT, TEXT) TO authenticated;

-- 7. ATOMIC send_message_idempotent RPC (F021, D001, D006)
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
    SELECT id, sequence, status, created_at, client_message_id INTO v_existing_msg
    FROM public.messages
    WHERE conversation_id = p_conversation_id AND client_message_id = p_client_message_id;

    IF v_existing_msg.id IS NOT NULL THEN
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

-- 8. ATOMIC TELEGRAM VERIFY & LINK RPC (F007, F011, D003)
CREATE OR REPLACE FUNCTION public.verify_telegram_challenge_atomic(
    p_challenge_id TEXT,
    p_code_hash TEXT,
    p_account_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_challenge RECORD;
    v_link RECORD;
    v_now TIMESTAMPTZ := now();
BEGIN
    -- Strict validation against NULL or malformed credentials
    IF p_challenge_id IS NULL OR p_challenge_id = '' OR
       p_code_hash IS NULL OR length(p_code_hash) < 32 OR
       p_account_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'code', 'INVALID_ARGUMENTS', 'message', 'Challenge ID, valid code hash and account ID are required');
    END IF;

    SELECT * INTO v_challenge
    FROM public.telegram_challenges
    WHERE challenge_id = p_challenge_id AND account_id = p_account_id
    FOR UPDATE;

    IF v_challenge.challenge_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'code', 'NOT_FOUND', 'message', 'Verification challenge not found');
    END IF;

    IF v_challenge.is_used THEN
        RETURN jsonb_build_object('success', false, 'code', 'ALREADY_USED', 'message', 'This challenge has already been used');
    END IF;

    IF v_challenge.expires_at < v_now THEN
        RETURN jsonb_build_object('success', false, 'code', 'EXPIRED', 'message', 'This verification challenge has expired');
    END IF;

    IF v_challenge.attempts >= v_challenge.max_attempts THEN
        RETURN jsonb_build_object('success', false, 'code', 'MAX_ATTEMPTS', 'message', 'Maximum verification attempts exceeded');
    END IF;

    IF v_challenge.code_hash IS NULL OR v_challenge.telegram_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'code', 'NOT_STARTED', 'message', 'Please start the Telegram bot first to receive your code');
    END IF;

    -- Strict null-safe equality check (IS DISTINCT FROM)
    IF v_challenge.code_hash IS DISTINCT FROM p_code_hash THEN
        UPDATE public.telegram_challenges
        SET attempts = attempts + 1
        WHERE challenge_id = p_challenge_id;

        RETURN jsonb_build_object(
            'success', false,
            'code', 'INVALID_CODE',
            'message', 'Invalid verification code',
            'remaining_attempts', greatest(0, v_challenge.max_attempts - (v_challenge.attempts + 1))
        );
    END IF;

    SELECT account_id INTO v_link
    FROM public.telegram_links
    WHERE telegram_user_id = v_challenge.telegram_user_id;

    IF v_link.account_id IS NOT NULL AND v_link.account_id != p_account_id THEN
        RETURN jsonb_build_object('success', false, 'code', 'ALREADY_BOUND', 'message', 'This Telegram account is already linked to another user');
    END IF;

    UPDATE public.telegram_challenges
    SET is_used = true
    WHERE challenge_id = p_challenge_id;

    INSERT INTO public.telegram_links (account_id, telegram_user_id, telegram_username, verified, updated_at)
    VALUES (p_account_id, v_challenge.telegram_user_id, v_challenge.telegram_username, true, v_now)
    ON CONFLICT (account_id) DO UPDATE
    SET telegram_user_id = EXCLUDED.telegram_user_id,
        telegram_username = EXCLUDED.telegram_username,
        verified = true,
        updated_at = v_now;

    RETURN jsonb_build_object(
        'success', true,
        'telegram_user_id', v_challenge.telegram_user_id,
        'telegram_username', v_challenge.telegram_username
    );
END;
$$;
-- Revoke from public, anon, authenticated; only service_role (Edge Functions) can call atomic verification
REVOKE EXECUTE ON FUNCTION public.verify_telegram_challenge_atomic(TEXT, TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.verify_telegram_challenge_atomic(TEXT, TEXT, UUID) TO service_role;

-- 9. DEDUPLICATED TELEGRAM UPDATES TABLE (F008, D004)
CREATE TABLE IF NOT EXISTS public.telegram_processed_updates (
    update_id BIGINT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.telegram_processed_updates ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.telegram_processed_updates FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.telegram_processed_updates TO service_role;

-- 10. RECOVERY TICKETS TABLE (F012, D004)
CREATE TABLE IF NOT EXISTS public.account_recovery_tickets (
    ticket_id TEXT PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    telegram_user_id BIGINT NOT NULL,
    ticket_hash TEXT NOT NULL,
    is_used BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_recovery_account ON public.account_recovery_tickets(account_id);
ALTER TABLE public.account_recovery_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.account_recovery_tickets FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.account_recovery_tickets TO service_role;

-- 11. HARDEN CONVERSATIONS INSERTION & CANONICAL NAMESPACES (D007, N003)
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

NOTIFY pgrst, 'reload schema';
