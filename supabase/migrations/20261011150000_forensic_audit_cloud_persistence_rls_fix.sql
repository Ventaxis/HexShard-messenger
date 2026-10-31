-- ====================================================================
-- Migration: 20261011150000_forensic_audit_cloud_persistence_rls_fix.sql
-- Description:
-- 1. Fix RLS Tautologies: Fully qualify outer table references in public.messages,
--    public.attachments, and public.message_reactions to prevent tautological
--    subquery correlation (cm.conversation_id = messages.conversation_id, etc.).
-- 2. Drop obsolete permissive Storage policies on attachments bucket:
--    Explicitly revoke legacy blanket policies ("Authenticated users can read attachments",
--    "Authenticated users can upload attachments", "Authenticated users can delete their attachments")
--    leaving only strict conversation-membership scoped policies.
-- 3. Align Profile Background Storage limit strictly to 512 KiB (524288 bytes)
--    and set strict MIME allowlist in storage.buckets.
-- 4. Fix send_ai_message: AI transcripts are server-stored plaintext (encryption_version = 0,
--    type = 'text', persona_id explicitly recorded), NOT masqueraded as E2EE ciphertext (encryption_version = 2).
--    Ensure idempotent handling so repeating the same user/reply client IDs returns existing sequence
--    without allocating new gaps or duplicating messages.
-- 5. Fix create_or_get_conversation:
--    - SAVED_MESSAGES: canonical ID 'self_' || v_user_id::text, guarantees membership
--    - AI: canonical ID 'ai_' || v_user_id::text, guarantees membership
--    - DIRECT: canonical ID 'direct_' || min_uuid || '_' || max_uuid, guarantees membership for caller & peer
--    Returns JSON object with id, type, title, recipient_id, current_sequence, created_at, updated_at.
-- 6. Canonical edit_message and delete_message RPCs with proper authentication and sender verification.
-- 7. PostgREST schema cache reload.
-- ====================================================================

-- 1. FIX RLS POLICIES (QUALIFIED EXTERNAL COLUMNS TO PREVENT TAUTOLOGIES)

-- 1.1 Messages RLS
DROP POLICY IF EXISTS "Members can view messages" ON public.messages;
CREATE POLICY "Members can view messages"
    ON public.messages FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = public.messages.conversation_id 
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Members can insert messages" ON public.messages;
CREATE POLICY "Members can insert messages"
    ON public.messages FOR INSERT
    TO authenticated
    WITH CHECK (
        sender_id = auth.uid() AND
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = public.messages.conversation_id 
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Senders can update own messages" ON public.messages;
CREATE POLICY "Senders can update own messages"
    ON public.messages FOR UPDATE
    TO authenticated
    USING (sender_id = auth.uid())
    WITH CHECK (sender_id = auth.uid());

DROP POLICY IF EXISTS "Senders can delete own messages" ON public.messages;
CREATE POLICY "Senders can delete own messages"
    ON public.messages FOR DELETE
    TO authenticated
    USING (sender_id = auth.uid());

-- 1.2 Attachments RLS
DROP POLICY IF EXISTS "Members can view attachments metadata" ON public.attachments;
CREATE POLICY "Members can view attachments metadata"
    ON public.attachments FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = public.attachments.conversation_id 
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Members can insert attachments metadata" ON public.attachments;
CREATE POLICY "Members can insert attachments metadata"
    ON public.attachments FOR INSERT
    TO authenticated
    WITH CHECK (
        uploader_id = auth.uid() AND
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = public.attachments.conversation_id 
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Uploaders can delete attachments metadata" ON public.attachments;
CREATE POLICY "Uploaders can delete attachments metadata"
    ON public.attachments FOR DELETE
    TO authenticated
    USING (uploader_id = auth.uid());

-- 1.3 Message Reactions RLS
DROP POLICY IF EXISTS "Members can view reactions" ON public.message_reactions;
CREATE POLICY "Members can view reactions"
    ON public.message_reactions FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = public.message_reactions.conversation_id 
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can insert own reactions" ON public.message_reactions;
CREATE POLICY "Users can insert own reactions"
    ON public.message_reactions FOR INSERT
    TO authenticated
    WITH CHECK (
        user_id = auth.uid() AND
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = public.message_reactions.conversation_id 
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can delete own reactions" ON public.message_reactions;
CREATE POLICY "Users can delete own reactions"
    ON public.message_reactions FOR DELETE
    TO authenticated
    USING (user_id = auth.uid());


-- 2. PURGE OVERLY BROAD STORAGE POLICIES
DROP POLICY IF EXISTS "Authenticated users can read attachments" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated users can upload attachments" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated users can delete their attachments" ON storage.objects;
DROP POLICY IF EXISTS "Users can upload their own profile background" ON storage.objects;
DROP POLICY IF EXISTS "Users can update their own profile background" ON storage.objects;
DROP POLICY IF EXISTS "Users can delete their own profile background" ON storage.objects;

-- Ensure strict conversation-scoped attachment policies on storage.objects
DROP POLICY IF EXISTS "Conversation members can read attachments" ON storage.objects;
CREATE POLICY "Conversation members can read attachments"
    ON storage.objects FOR SELECT
    TO authenticated
    USING (
        bucket_id = 'attachments' AND
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = (storage.foldername(name))[1]
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Conversation members can upload attachments" ON storage.objects;
CREATE POLICY "Conversation members can upload attachments"
    ON storage.objects FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'attachments' AND
        (storage.foldername(name))[2] = auth.uid()::text AND
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = (storage.foldername(name))[1]
              AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Uploaders can delete attachments" ON storage.objects;
CREATE POLICY "Uploaders can delete attachments"
    ON storage.objects FOR DELETE
    TO authenticated
    USING (
        bucket_id = 'attachments' AND
        (storage.foldername(name))[2] = auth.uid()::text
    );


-- 3. ALIGN PROFILE BACKGROUND LIMIT STRICTLY TO 512 KiB (524288 BYTES)
DO $$
BEGIN
    UPDATE storage.buckets
    SET file_size_limit = 524288,
        allowed_mime_types = ARRAY[
            'image/jpeg', 'image/png', 'image/webp', 'image/gif', 
            'image/bmp', 'image/heic', 'image/heif', 'video/webm', 'video/mp4'
        ]
    WHERE id = 'profile-backgrounds';
END $$;


-- 4. CANONICAL create_or_get_conversation RPC
CREATE OR REPLACE FUNCTION public.create_or_get_conversation(
    p_type TEXT,
    p_recipient_id TEXT,
    p_title TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_recipient_uuid UUID;
    v_conv_id TEXT;
    v_clean_type TEXT;
    v_current_seq BIGINT := 0;
    v_created_at TIMESTAMPTZ;
    v_updated_at TIMESTAMPTZ;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    v_clean_type := upper(trim(coalesce(p_type, 'DIRECT')));
    IF v_clean_type NOT IN ('DIRECT', 'SAVED_MESSAGES', 'AI', 'GROUP') THEN
        v_clean_type := 'DIRECT';
    END IF;

    -- 1. SAVED_MESSAGES
    IF v_clean_type = 'SAVED_MESSAGES' OR p_recipient_id = 'self' OR p_recipient_id = v_user_id::text THEN
        v_conv_id := 'self_' || v_user_id::text;

        INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
        VALUES (v_conv_id, 'SAVED_MESSAGES', coalesce(p_title, 'Saved Messages'), v_user_id, 0, now(), now())
        ON CONFLICT (id) DO UPDATE SET updated_at = now()
        RETURNING current_sequence, created_at, updated_at INTO v_current_seq, v_created_at, v_updated_at;

        INSERT INTO public.conversation_members (conversation_id, account_id, role, is_pinned)
        VALUES (v_conv_id, v_user_id, 'owner', true)
        ON CONFLICT (conversation_id, account_id) DO NOTHING;

        RETURN jsonb_build_object(
            'id', v_conv_id,
            'type', 'SAVED_MESSAGES',
            'title', coalesce(p_title, 'Saved Messages'),
            'recipient_id', 'self',
            'current_sequence', v_current_seq,
            'created_at', v_created_at,
            'updated_at', v_updated_at
        );
    END IF;

    -- 2. AI ASSISTANT
    IF v_clean_type = 'AI' OR p_recipient_id LIKE 'ai_%' OR p_recipient_id = 'hexagon' OR p_recipient_id = 'ventaxis' THEN
        v_conv_id := 'ai_' || v_user_id::text;

        INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
        VALUES (v_conv_id, 'AI', coalesce(p_title, 'HexShard AI'), v_user_id, 0, now(), now())
        ON CONFLICT (id) DO UPDATE SET updated_at = now()
        RETURNING current_sequence, created_at, updated_at INTO v_current_seq, v_created_at, v_updated_at;

        INSERT INTO public.conversation_members (conversation_id, account_id, role, is_pinned)
        VALUES (v_conv_id, v_user_id, 'owner', true)
        ON CONFLICT (conversation_id, account_id) DO NOTHING;

        RETURN jsonb_build_object(
            'id', v_conv_id,
            'type', 'AI',
            'title', coalesce(p_title, 'HexShard AI'),
            'recipient_id', 'ai_assistant',
            'current_sequence', v_current_seq,
            'created_at', v_created_at,
            'updated_at', v_updated_at
        );
    END IF;

    -- 3. DIRECT CONVERSATION
    BEGIN
        v_recipient_uuid := p_recipient_id::uuid;
    EXCEPTION WHEN OTHERS THEN
        SELECT id INTO v_recipient_uuid FROM public.profiles 
        WHERE lower(username) = lower(p_recipient_id) 
           OR hex_number = p_recipient_id 
           OR normalized_username = lower(p_recipient_id)
        LIMIT 1;
    END;

    IF v_recipient_uuid IS NULL THEN
        RAISE EXCEPTION 'Recipient user not found: %', p_recipient_id;
    END IF;

    IF v_user_id < v_recipient_uuid THEN
        v_conv_id := 'direct_' || v_user_id::text || '_' || v_recipient_uuid::text;
    ELSE
        v_conv_id := 'direct_' || v_recipient_uuid::text || '_' || v_user_id::text;
    END IF;

    INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
    VALUES (v_conv_id, 'DIRECT', coalesce(p_title, ''), v_user_id, 0, now(), now())
    ON CONFLICT (id) DO UPDATE SET updated_at = now()
    RETURNING current_sequence, created_at, updated_at INTO v_current_seq, v_created_at, v_updated_at;

    INSERT INTO public.conversation_members (conversation_id, account_id, role)
    VALUES (v_conv_id, v_user_id, 'member')
    ON CONFLICT (conversation_id, account_id) DO NOTHING;

    INSERT INTO public.conversation_members (conversation_id, account_id, role)
    VALUES (v_conv_id, v_recipient_uuid, 'member')
    ON CONFLICT (conversation_id, account_id) DO NOTHING;

    RETURN jsonb_build_object(
        'id', v_conv_id,
        'type', 'DIRECT',
        'title', coalesce(p_title, ''),
        'recipient_id', v_recipient_uuid::text,
        'current_sequence', v_current_seq,
        'created_at', v_created_at,
        'updated_at', v_updated_at
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.create_or_get_conversation(TEXT, TEXT, TEXT) TO authenticated;


-- 5. REVISED send_ai_message: REALISTIC STORAGE WITHOUT FAKE E2EE
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
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    -- F001 FIX: Reject foreign conversation IDs and strictly derive canonical AI conversation ID for calling user
    IF p_conversation_id IS NOT NULL AND p_conversation_id != '' AND p_conversation_id != ('ai_' || v_user_id::text) THEN
        RAISE EXCEPTION 'Access denied: Cannot bind AI message to foreign conversation' USING ERRCODE = '42501';
    END IF;
    v_conv_id := 'ai_' || v_user_id::text;

    -- Ensure conversation & membership exist atomically for this user only
    INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
    VALUES (v_conv_id, 'AI', 'HexShard AI', v_user_id, 0, v_now, v_now)
    ON CONFLICT (id) DO UPDATE
        SET updated_at = v_now
        WHERE conversations.created_by = v_user_id AND conversations.type = 'AI';

    INSERT INTO public.conversation_members (conversation_id, account_id, role, is_pinned)
    VALUES (v_conv_id, v_user_id, 'owner', true)
    ON CONFLICT (conversation_id, account_id) DO NOTHING;

    -- Check if this pair has already been stored (idempotency guard)
    SELECT sequence, id INTO v_existing_user
    FROM public.messages
    WHERE conversation_id = v_conv_id AND client_message_id = p_user_client_id;

    IF v_existing_user.id IS NOT NULL THEN
        -- Already stored! Return existing sequences without advancing conversation sequence
        SELECT sequence INTO v_seq2
        FROM public.messages
        WHERE conversation_id = v_conv_id AND client_message_id = p_reply_client_id;

        RETURN jsonb_build_object(
            'user_message_id', p_user_client_id,
            'user_sequence', v_existing_user.sequence,
            'reply_message_id', p_reply_client_id,
            'reply_sequence', coalesce(v_seq2, v_existing_user.sequence + 1),
            'persona_id', p_persona_id
        );
    END IF;

    -- Allocate two contiguous sequences atomically
    UPDATE public.conversations
    SET current_sequence = current_sequence + 2,
        updated_at = v_now
    WHERE id = v_conv_id
    RETURNING current_sequence INTO v_seq2;

    v_seq1 := v_seq2 - 1;

    -- 1. Insert user message (encryption_version = 0 for server AI text)
    INSERT INTO public.messages (
        id, conversation_id, sender_id, recipient_id, client_message_id,
        sequence, payload, type, time_str, encryption_version, status,
        persona_id, is_deleted, created_at
    ) VALUES (
        p_user_client_id, v_conv_id, v_user_id, 'ai_' || coalesce(p_persona_id, 'hexagon'), p_user_client_id,
        v_seq1, p_user_text, 'text', p_time_str, 0, 'delivered',
        p_persona_id, false, v_now
    )
    ON CONFLICT (conversation_id, client_message_id) DO NOTHING;

    -- 2. Insert AI reply message (encryption_version = 0 for server AI text)
    INSERT INTO public.messages (
        id, conversation_id, sender_id, recipient_id, client_message_id,
        sequence, payload, type, time_str, encryption_version, status,
        persona_id, is_deleted, created_at
    ) VALUES (
        p_reply_client_id, v_conv_id, v_user_id, v_user_id::text, p_reply_client_id,
        v_seq2, p_reply_text, 'text', p_time_str, 0, 'delivered',
        p_persona_id, false, v_now + interval '50 milliseconds'
    )
    ON CONFLICT (conversation_id, client_message_id) DO NOTHING;

    RETURN jsonb_build_object(
        'user_message_id', p_user_client_id,
        'user_sequence', v_seq1,
        'reply_message_id', p_reply_client_id,
        'reply_sequence', v_seq2,
        'persona_id', p_persona_id
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.send_ai_message(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated;


-- 6. CANONICAL edit_message and delete_message RPCs
CREATE OR REPLACE FUNCTION public.edit_message(
    p_message_id TEXT,
    p_payload TEXT,
    p_signature TEXT DEFAULT ''
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_msg RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    SELECT id, conversation_id, sender_id
    INTO v_msg
    FROM public.messages
    WHERE (id = p_message_id OR client_message_id = p_message_id);

    IF v_msg.id IS NULL THEN
        RAISE EXCEPTION 'Message not found: %', p_message_id;
    END IF;

    IF v_msg.sender_id != v_user_id THEN
        RAISE EXCEPTION 'Access denied: cannot edit message of another user';
    END IF;

    UPDATE public.messages
    SET payload = p_payload,
        signature = p_signature,
        created_at = now()
    WHERE id = v_msg.id;

    RETURN jsonb_build_object('success', true, 'message_id', v_msg.id);
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
    v_user_id UUID := auth.uid();
    v_msg RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    SELECT id, conversation_id, sender_id
    INTO v_msg
    FROM public.messages
    WHERE (id = p_message_id OR client_message_id = p_message_id);

    IF v_msg.id IS NULL THEN
        RAISE EXCEPTION 'Message not found: %', p_message_id;
    END IF;

    IF v_msg.sender_id != v_user_id THEN
        RAISE EXCEPTION 'Access denied: cannot delete message of another user';
    END IF;

    UPDATE public.messages
    SET is_deleted = true,
        payload = '',
        status = 'deleted'
    WHERE id = v_msg.id;

    RETURN jsonb_build_object('success', true, 'message_id', v_msg.id);
END;
$$;

GRANT EXECUTE ON FUNCTION public.delete_message(TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';
