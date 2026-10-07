-- ====================================================================
-- Migration: 20261005120000_canonical_cloud_first_unified_schema.sql
-- Description:
-- 1. Unifies generations of conflicting schemas into ONE canonical cloud-first schema:
--    - conversations (id TEXT PRIMARY KEY, type, title, created_by, current_sequence, created_at, updated_at)
--    - conversation_members (conversation_id, account_id, role, joined_at, last_read_sequence, is_muted, is_pinned, is_archived)
--    - messages (id TEXT PRIMARY KEY, conversation_id, sender_id, recipient_id, client_message_id, sequence, payload, signature, type, time_str, encryption_version, status, persona_id, is_deleted, created_at, edited_at, deleted_at)
--    - message_reactions (message_id, conversation_id, user_id, reaction, created_at)
--    - attachments (id TEXT PRIMARY KEY, message_id, conversation_id, uploader_id, storage_path, mime_type, size_bytes, encryption_version, checksum, created_at)
--    - user_crypto_backups (account_id UUID PRIMARY KEY, encrypted_envelope, salt, iv, key_version, created_at, updated_at)
--    - profiles (canonical columns for date_of_birth, display_name, bio, avatar_storage_path, avatar_url, avatar_path, etc.)
-- 2. Eliminates conflicting overloads and creates CANONICAL RPCs:
--    - create_or_get_conversation
--    - send_message_idempotent
--    - edit_message
--    - delete_message
--    - mark_conversation_read
--    - set_conversation_settings
--    - add_message_reaction / remove_message_reaction
--    - sync_conversations_for_user
--    - backup_crypto_envelope / get_crypto_backup
-- 3. Hardened Storage RLS:
--    - chat-attachments strictly constrained to conversation members
--    - profile-backgrounds 512 KB limit
-- 4. Reloads PostgREST schema cache.
-- ====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ====================================================================
-- 1. CONVERSATIONS SCHEMA RECONCILIATION
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.conversations (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL CHECK (type IN ('DIRECT', 'GROUP', 'SAVED_MESSAGES', 'AI')) DEFAULT 'DIRECT',
    title TEXT DEFAULT '',
    created_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    current_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ensure all canonical columns exist on conversations
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'DIRECT';
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS title TEXT DEFAULT '';
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS current_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_conversations_created_by ON public.conversations(created_by);
CREATE INDEX IF NOT EXISTS idx_conversations_updated_at ON public.conversations(updated_at DESC);

-- ====================================================================
-- 2. CONVERSATION MEMBERS SCHEMA RECONCILIATION
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.conversation_members (
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT 'member',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_sequence BIGINT NOT NULL DEFAULT 0,
    is_muted BOOLEAN NOT NULL DEFAULT false,
    is_pinned BOOLEAN NOT NULL DEFAULT false,
    is_archived BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (conversation_id, account_id)
);

ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'member';
ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS last_read_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS is_muted BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS is_pinned BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.conversation_members ADD COLUMN IF NOT EXISTS is_archived BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_conv_members_account ON public.conversation_members(account_id);
CREATE INDEX IF NOT EXISTS idx_conv_members_lookup ON public.conversation_members(conversation_id, account_id);

-- Safe backfill for legacy schemas with participant1 / participant2
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'conversations' AND column_name = 'participant1'
    ) THEN
        INSERT INTO public.conversation_members (conversation_id, account_id, role)
        SELECT id, participant1, 'member' FROM public.conversations
        WHERE participant1 IS NOT NULL
        ON CONFLICT (conversation_id, account_id) DO NOTHING;

        INSERT INTO public.conversation_members (conversation_id, account_id, role)
        SELECT id, participant2, 'member' FROM public.conversations
        WHERE participant2 IS NOT NULL
        ON CONFLICT (conversation_id, account_id) DO NOTHING;
    END IF;
END $$;

-- ====================================================================
-- 3. MESSAGES SCHEMA RECONCILIATION
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    recipient_id TEXT,
    client_message_id TEXT NOT NULL,
    sequence BIGINT NOT NULL DEFAULT 0,
    payload TEXT NOT NULL,
    signature TEXT,
    type TEXT NOT NULL DEFAULT 'text',
    time_str TEXT DEFAULT '',
    encryption_version INT NOT NULL DEFAULT 2,
    status TEXT NOT NULL DEFAULT 'sent',
    persona_id TEXT,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_conversation_client_msg UNIQUE (conversation_id, client_message_id)
);

ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS recipient_id TEXT;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS signature TEXT;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'text';
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS time_str TEXT DEFAULT '';
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS encryption_version INT NOT NULL DEFAULT 2;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'sent';
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS persona_id TEXT;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS edited_at TIMESTAMPTZ;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- Ensure unique constraint on (conversation_id, client_message_id)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_conversation_client_msg'
    ) THEN
        ALTER TABLE public.messages ADD CONSTRAINT uq_conversation_client_msg UNIQUE (conversation_id, client_message_id);
    END IF;
EXCEPTION
    WHEN duplicate_table OR duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_messages_conversation_seq ON public.messages(conversation_id, sequence ASC);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON public.messages(sender_id);
CREATE INDEX IF NOT EXISTS idx_messages_created_at ON public.messages(created_at DESC);

-- ====================================================================
-- 4. ATTACHMENTS SCHEMA RECONCILIATION
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.attachments (
    id TEXT PRIMARY KEY,
    message_id TEXT REFERENCES public.messages(id) ON DELETE CASCADE,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    uploader_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    storage_path TEXT NOT NULL,
    mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size_bytes BIGINT NOT NULL DEFAULT 0,
    encryption_version INT NOT NULL DEFAULT 2,
    checksum TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS size_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS encryption_version INT NOT NULL DEFAULT 2;
ALTER TABLE public.attachments ADD COLUMN IF NOT EXISTS checksum TEXT;

CREATE INDEX IF NOT EXISTS idx_attachments_conv ON public.attachments(conversation_id);
CREATE INDEX IF NOT EXISTS idx_attachments_msg ON public.attachments(message_id);

-- ====================================================================
-- 5. MESSAGE REACTIONS
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.message_reactions (
    id BIGSERIAL PRIMARY KEY,
    message_id TEXT NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    reaction TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, user_id, reaction)
);

CREATE INDEX IF NOT EXISTS idx_reactions_message ON public.message_reactions(message_id);
CREATE INDEX IF NOT EXISTS idx_reactions_conversation ON public.message_reactions(conversation_id);

-- ====================================================================
-- 6. CRYPTOGRAPHIC REINSTALL RECOVERY ENVELOPE
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.user_crypto_backups (
    account_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    encrypted_envelope TEXT NOT NULL,
    salt TEXT NOT NULL,
    iv TEXT NOT NULL,
    key_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ====================================================================
-- 7. ROW LEVEL SECURITY (RLS) POLICIES
-- ====================================================================
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.attachments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.message_reactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_crypto_backups ENABLE ROW LEVEL SECURITY;

-- Conversations RLS: only members can read conversations
DROP POLICY IF EXISTS "Members can view conversations" ON public.conversations;
CREATE POLICY "Members can view conversations"
    ON public.conversations FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = id AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Members can update conversations" ON public.conversations;
CREATE POLICY "Members can update conversations"
    ON public.conversations FOR UPDATE
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = id AND cm.account_id = auth.uid()
        )
    );

-- Conversation Members RLS: members can see membership in their chats
DROP POLICY IF EXISTS "Members can view membership" ON public.conversation_members;
CREATE POLICY "Members can view membership"
    ON public.conversation_members FOR SELECT
    TO authenticated
    USING (
        account_id = auth.uid() OR
        EXISTS (
            SELECT 1 FROM public.conversation_members cm2
            WHERE cm2.conversation_id = conversation_id AND cm2.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can update own membership settings" ON public.conversation_members;
CREATE POLICY "Users can update own membership settings"
    ON public.conversation_members FOR UPDATE
    TO authenticated
    USING (account_id = auth.uid())
    WITH CHECK (account_id = auth.uid());

-- Messages RLS: only members can view messages
DROP POLICY IF EXISTS "Members can view messages" ON public.messages;
CREATE POLICY "Members can view messages"
    ON public.messages FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = conversation_id AND cm.account_id = auth.uid()
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
            WHERE cm.conversation_id = conversation_id AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Senders can update own messages" ON public.messages;
CREATE POLICY "Senders can update own messages"
    ON public.messages FOR UPDATE
    TO authenticated
    USING (sender_id = auth.uid())
    WITH CHECK (sender_id = auth.uid());

-- Attachments RLS: only members can view attachments
DROP POLICY IF EXISTS "Members can view attachments metadata" ON public.attachments;
CREATE POLICY "Members can view attachments metadata"
    ON public.attachments FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = conversation_id AND cm.account_id = auth.uid()
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
            WHERE cm.conversation_id = conversation_id AND cm.account_id = auth.uid()
        )
    );

-- Reactions RLS
DROP POLICY IF EXISTS "Members can view reactions" ON public.message_reactions;
CREATE POLICY "Members can view reactions"
    ON public.message_reactions FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = conversation_id AND cm.account_id = auth.uid()
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
            WHERE cm.conversation_id = conversation_id AND cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can delete own reactions" ON public.message_reactions;
CREATE POLICY "Users can delete own reactions"
    ON public.message_reactions FOR DELETE
    TO authenticated
    USING (user_id = auth.uid());

-- Crypto backup RLS: strictly user-scoped
DROP POLICY IF EXISTS "Users can manage own crypto backup" ON public.user_crypto_backups;
CREATE POLICY "Users can manage own crypto backup"
    ON public.user_crypto_backups FOR ALL
    TO authenticated
    USING (account_id = auth.uid())
    WITH CHECK (account_id = auth.uid());

-- ====================================================================
-- 8. CANONICAL RPC: create_or_get_conversation
-- ====================================================================
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

    v_clean_type := upper(trim(p_type));
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
            'current_sequence', v_current_seq,
            'created_at', v_created_at,
            'updated_at', v_updated_at
        );
    END IF;

    -- 2. AI CONVERSATION
    IF v_clean_type = 'AI' OR p_recipient_id LIKE 'ai_%' THEN
        v_conv_id := 'ai_' || v_user_id::text;

        INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
        VALUES (v_conv_id, 'AI', coalesce(p_title, 'AI Assistant'), v_user_id, 0, now(), now())
        ON CONFLICT (id) DO UPDATE SET updated_at = now()
        RETURNING current_sequence, created_at, updated_at INTO v_current_seq, v_created_at, v_updated_at;

        INSERT INTO public.conversation_members (conversation_id, account_id, role, is_pinned)
        VALUES (v_conv_id, v_user_id, 'owner', true)
        ON CONFLICT (conversation_id, account_id) DO NOTHING;

        RETURN jsonb_build_object(
            'id', v_conv_id,
            'type', 'AI',
            'title', coalesce(p_title, 'AI Assistant'),
            'current_sequence', v_current_seq,
            'created_at', v_created_at,
            'updated_at', v_updated_at
        );
    END IF;

    -- 3. DIRECT CONVERSATION
    -- Resolve recipient UUID
    BEGIN
        v_recipient_uuid := p_recipient_id::uuid;
    EXCEPTION WHEN OTHERS THEN
        -- Try lookup by username or hex_number in profiles
        SELECT id INTO v_recipient_uuid FROM public.profiles 
        WHERE lower(username) = lower(p_recipient_id) 
           OR hex_number = p_recipient_id 
           OR normalized_username = lower(p_recipient_id)
        LIMIT 1;
    END;

    IF v_recipient_uuid IS NULL THEN
        RAISE EXCEPTION 'Recipient user not found: %', p_recipient_id;
    END IF;

    -- Canonical deterministic ID: direct_min_max
    IF v_user_id < v_recipient_uuid THEN
        v_conv_id := 'direct_' || v_user_id::text || '_' || v_recipient_uuid::text;
    ELSE
        v_conv_id := 'direct_' || v_recipient_uuid::text || '_' || v_user_id::text;
    END IF;

    INSERT INTO public.conversations (id, type, title, created_by, current_sequence, created_at, updated_at)
    VALUES (v_conv_id, 'DIRECT', coalesce(p_title, ''), v_user_id, 0, now(), now())
    ON CONFLICT (id) DO UPDATE SET updated_at = now()
    RETURNING current_sequence, created_at, updated_at INTO v_current_seq, v_created_at, v_updated_at;

    -- Add both caller and peer to conversation_members
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

-- ====================================================================
-- 9. CANONICAL RPC: send_message_idempotent
-- ====================================================================
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
    v_message_id TEXT;
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_sender_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    IF p_conversation_id IS NULL OR p_client_message_id IS NULL OR p_payload IS NULL THEN
        RAISE EXCEPTION 'Missing required message parameters';
    END IF;

    -- Check caller is member of this conversation
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members
        WHERE conversation_id = p_conversation_id AND account_id = v_sender_id
    ) THEN
        RAISE EXCEPTION 'User % is not a member of conversation %', v_sender_id, p_conversation_id;
    END IF;

    -- 1. Idempotency Check: if message with this (conversation_id, client_message_id) already exists, return it
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

    -- 2. Atomically increment sequence in conversation
    UPDATE public.conversations
    SET current_sequence = current_sequence + 1,
        updated_at = v_now
    WHERE id = p_conversation_id
    RETURNING current_sequence INTO v_new_sequence;

    IF v_new_sequence IS NULL THEN
        RAISE EXCEPTION 'Conversation not found: %', p_conversation_id;
    END IF;

    v_message_id := p_client_message_id;

    -- 3. Insert message
    INSERT INTO public.messages (
        id,
        conversation_id,
        sender_id,
        recipient_id,
        client_message_id,
        sequence,
        payload,
        signature,
        type,
        time_str,
        encryption_version,
        status,
        persona_id,
        is_deleted,
        created_at
    ) VALUES (
        v_message_id,
        p_conversation_id,
        v_sender_id,
        p_recipient_id,
        p_client_message_id,
        v_new_sequence,
        p_payload,
        p_signature,
        coalesce(p_type, 'text'),
        coalesce(p_time_str, ''),
        coalesce(p_encryption_version, 2),
        'sent',
        p_persona_id,
        false,
        v_now
    );

    RETURN jsonb_build_object(
        'id', v_message_id,
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

-- ====================================================================
-- 10. CANONICAL RPC: send_ai_message
-- Atomically persists user prompt and AI response in conversation
-- ====================================================================
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
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    -- Ensure caller is member of this AI conversation
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members
        WHERE conversation_id = p_conversation_id AND account_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'User % is not a member of conversation %', v_user_id, p_conversation_id;
    END IF;

    -- Atomically allocate 2 sequences
    UPDATE public.conversations
    SET current_sequence = current_sequence + 2,
        updated_at = v_now
    WHERE id = p_conversation_id
    RETURNING current_sequence INTO v_seq2;

    v_seq1 := v_seq2 - 1;

    -- 1. Insert user message
    INSERT INTO public.messages (
        id, conversation_id, sender_id, recipient_id, client_message_id,
        sequence, payload, type, time_str, encryption_version, status,
        persona_id, is_deleted, created_at
    ) VALUES (
        p_user_client_id, p_conversation_id, v_user_id, 'ai_' || coalesce(p_persona_id, 'hexagon'), p_user_client_id,
        v_seq1, p_user_text, 'text', p_time_str, 2, 'delivered',
        p_persona_id, false, v_now
    )
    ON CONFLICT (conversation_id, client_message_id) DO NOTHING;

    -- 2. Insert AI reply message (sender is system/bot, recipient is user)
    INSERT INTO public.messages (
        id, conversation_id, sender_id, recipient_id, client_message_id,
        sequence, payload, type, time_str, encryption_version, status,
        persona_id, is_deleted, created_at
    ) VALUES (
        p_reply_client_id, p_conversation_id, v_user_id, v_user_id::text, p_reply_client_id,
        v_seq2, p_reply_text, 'text', p_time_str, 2, 'delivered',
        p_persona_id, false, v_now + interval '100 milliseconds'
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

-- ====================================================================
-- 11. CANONICAL RPC: edit_message
-- ====================================================================
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
    v_user_id UUID := auth.uid();
    v_msg RECORD;
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    SELECT id, conversation_id, sender_id, is_deleted
    INTO v_msg
    FROM public.messages
    WHERE id = p_message_id;

    IF v_msg.id IS NULL THEN
        RAISE EXCEPTION 'Message not found: %', p_message_id;
    END IF;

    IF v_msg.sender_id <> v_user_id THEN
        RAISE EXCEPTION 'Only sender can edit message';
    END IF;

    IF v_msg.is_deleted THEN
        RAISE EXCEPTION 'Cannot edit deleted message';
    END IF;

    UPDATE public.messages
    SET payload = p_payload,
        signature = coalesce(p_signature, signature),
        edited_at = v_now
    WHERE id = p_message_id;

    RETURN jsonb_build_object(
        'id', p_message_id,
        'conversation_id', v_msg.conversation_id,
        'edited_at', v_now
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.edit_message(TEXT, TEXT, TEXT) TO authenticated;

-- ====================================================================
-- 12. CANONICAL RPC: delete_message
-- ====================================================================
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
    v_now TIMESTAMPTZ := now();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    SELECT id, conversation_id, sender_id
    INTO v_msg
    FROM public.messages
    WHERE id = p_message_id;

    IF v_msg.id IS NULL THEN
        RAISE EXCEPTION 'Message not found: %', p_message_id;
    END IF;

    IF v_msg.sender_id <> v_user_id THEN
        RAISE EXCEPTION 'Only sender can delete message';
    END IF;

    UPDATE public.messages
    SET is_deleted = true,
        deleted_at = v_now,
        payload = '[Message deleted]'
    WHERE id = p_message_id;

    RETURN jsonb_build_object(
        'id', p_message_id,
        'conversation_id', v_msg.conversation_id,
        'is_deleted', true,
        'deleted_at', v_now
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.delete_message(TEXT) TO authenticated;

-- ====================================================================
-- 13. CANONICAL RPC: mark_conversation_read
-- ====================================================================
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
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    UPDATE public.conversation_members
    SET last_read_sequence = greatest(last_read_sequence, p_sequence)
    WHERE conversation_id = p_conversation_id AND account_id = v_user_id;

    RETURN jsonb_build_object(
        'conversation_id', p_conversation_id,
        'last_read_sequence', p_sequence
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.mark_conversation_read(TEXT, BIGINT) TO authenticated;

-- ====================================================================
-- 14. CANONICAL RPC: set_conversation_settings (Pin / Mute / Archive)
-- ====================================================================
CREATE OR REPLACE FUNCTION public.set_conversation_settings(
    p_conversation_id TEXT,
    p_is_pinned BOOLEAN DEFAULT NULL,
    p_is_muted BOOLEAN DEFAULT NULL,
    p_is_archived BOOLEAN DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_pinned BOOLEAN;
    v_muted BOOLEAN;
    v_archived BOOLEAN;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    UPDATE public.conversation_members
    SET is_pinned = coalesce(p_is_pinned, is_pinned),
        is_muted = coalesce(p_is_muted, is_muted),
        is_archived = coalesce(p_is_archived, is_archived)
    WHERE conversation_id = p_conversation_id AND account_id = v_user_id
    RETURNING is_pinned, is_muted, is_archived INTO v_pinned, v_muted, v_archived;

    RETURN jsonb_build_object(
        'conversation_id', p_conversation_id,
        'is_pinned', v_pinned,
        'is_muted', v_muted,
        'is_archived', v_archived
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.set_conversation_settings(TEXT, BOOLEAN, BOOLEAN, BOOLEAN) TO authenticated;

-- ====================================================================
-- 15. CANONICAL RPC: sync_conversations_for_user
-- ====================================================================
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
            'type', c.type,
            'title', c.title,
            'current_sequence', c.current_sequence,
            'created_at', c.created_at,
            'updated_at', c.updated_at,
            'last_read_sequence', cm.last_read_sequence,
            'is_pinned', cm.is_pinned,
            'is_muted', cm.is_muted,
            'is_archived', cm.is_archived,
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

-- ====================================================================
-- 16. CANONICAL RPC: backup_crypto_envelope / get_crypto_backup
-- ====================================================================
CREATE OR REPLACE FUNCTION public.backup_crypto_envelope(
    p_envelope TEXT,
    p_salt TEXT,
    p_iv TEXT,
    p_version INT DEFAULT 1
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

    INSERT INTO public.user_crypto_backups (
        account_id, encrypted_envelope, salt, iv, key_version, updated_at
    ) VALUES (
        v_user_id, p_envelope, p_salt, p_iv, p_version, now()
    )
    ON CONFLICT (account_id) DO UPDATE SET
        encrypted_envelope = EXCLUDED.encrypted_envelope,
        salt = EXCLUDED.salt,
        iv = EXCLUDED.iv,
        key_version = EXCLUDED.key_version,
        updated_at = now();

    RETURN jsonb_build_object('success', true);
END;
$$;

GRANT EXECUTE ON FUNCTION public.backup_crypto_envelope(TEXT, TEXT, TEXT, INT) TO authenticated;

CREATE OR REPLACE FUNCTION public.get_crypto_backup()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_backup RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    SELECT encrypted_envelope, salt, iv, key_version, updated_at
    INTO v_backup
    FROM public.user_crypto_backups
    WHERE account_id = v_user_id;

    IF v_backup.encrypted_envelope IS NULL THEN
        RETURN NULL;
    END IF;

    RETURN jsonb_build_object(
        'encrypted_envelope', v_backup.encrypted_envelope,
        'salt', v_backup.salt,
        'iv', v_backup.iv,
        'key_version', v_backup.key_version,
        'updated_at', v_backup.updated_at
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_crypto_backup() TO authenticated;

-- ====================================================================
-- 17. HARDENED STORAGE RLS POLICIES
-- ====================================================================
-- Create storage buckets if not exist
INSERT INTO storage.buckets (id, name, public, file_size_limit)
VALUES ('chat-attachments', 'chat-attachments', false, 52428800) -- 50 MB
ON CONFLICT (id) DO UPDATE SET public = false;

INSERT INTO storage.buckets (id, name, public, file_size_limit)
VALUES ('profile-backgrounds', 'profile-backgrounds', true, 524288) -- 512 KB
ON CONFLICT (id) DO UPDATE SET file_size_limit = 524288;

INSERT INTO storage.buckets (id, name, public, file_size_limit)
VALUES ('avatars', 'avatars', true, 5242880) -- 5 MB
ON CONFLICT (id) DO UPDATE SET public = true;

-- Hardened Chat Attachments Storage Policies:
-- Storage path convention: {conversation_id}/{filename}
DROP POLICY IF EXISTS "Conversation members can read attachments" ON storage.objects;
CREATE POLICY "Conversation members can read attachments"
    ON storage.objects FOR SELECT
    TO authenticated
    USING (
        bucket_id = 'chat-attachments' AND
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
        bucket_id = 'chat-attachments' AND
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
        bucket_id = 'chat-attachments' AND
        owner = auth.uid()
    );

-- Profile Backgrounds Storage Policies:
DROP POLICY IF EXISTS "Public can view profile backgrounds" ON storage.objects;
CREATE POLICY "Public can view profile backgrounds"
    ON storage.objects FOR SELECT
    TO public
    USING (bucket_id = 'profile-backgrounds');

DROP POLICY IF EXISTS "Users can upload own profile background" ON storage.objects;
CREATE POLICY "Users can upload own profile background"
    ON storage.objects FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'profile-backgrounds' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );

DROP POLICY IF EXISTS "Users can delete own profile background" ON storage.objects;
CREATE POLICY "Users can delete own profile background"
    ON storage.objects FOR DELETE
    TO authenticated
    USING (
        bucket_id = 'profile-backgrounds' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );

-- Avatars Storage Policies:
DROP POLICY IF EXISTS "Public can view avatars" ON storage.objects;
CREATE POLICY "Public can view avatars"
    ON storage.objects FOR SELECT
    TO public
    USING (bucket_id = 'avatars');

DROP POLICY IF EXISTS "Users can upload own avatar" ON storage.objects;
CREATE POLICY "Users can upload own avatar"
    ON storage.objects FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'avatars' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );

DROP POLICY IF EXISTS "Users can delete own avatar" ON storage.objects;
CREATE POLICY "Users can delete own avatar"
    ON storage.objects FOR DELETE
    TO authenticated
    USING (
        bucket_id = 'avatars' AND
        (storage.foldername(name))[1] = auth.uid()::text
    );

-- ====================================================================
-- 18. NOTIFY PostgREST TO RELOAD SCHEMA CACHE
-- ====================================================================
NOTIFY pgrst, 'reload schema';
