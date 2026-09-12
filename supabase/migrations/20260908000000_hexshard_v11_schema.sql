-- HexShard Messenger v11 Unified Schema
-- Authoritative Supabase PostgreSQL Schema with Row-Level Security (RLS) and Atomic RPCs

-- ====================================================================
-- 1. EXTENSIONS & TYPES
-- ====================================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ====================================================================
-- 2. PROFILES TABLE
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    username TEXT UNIQUE NOT NULL,
    normalized_username TEXT UNIQUE NOT NULL,
    public_key TEXT NOT NULL DEFAULT '',
    signing_key TEXT NOT NULL DEFAULT '',
    avatar_url TEXT DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT username_length CHECK (char_length(username) >= 3 AND char_length(username) <= 32),
    CONSTRAINT username_alphanumeric CHECK (username ~* '^[a-zA-Z0-9_]+$')
);

CREATE INDEX IF NOT EXISTS idx_profiles_normalized_username ON public.profiles (normalized_username);

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Profiles are readable by authenticated users" ON public.profiles;
CREATE POLICY "Profiles are readable by authenticated users"
    ON public.profiles FOR SELECT
    TO authenticated
    USING (true);

DROP POLICY IF EXISTS "Users can insert their own profile" ON public.profiles;
CREATE POLICY "Users can insert their own profile"
    ON public.profiles FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = id);

DROP POLICY IF EXISTS "Users can update their own profile" ON public.profiles;
CREATE POLICY "Users can update their own profile"
    ON public.profiles FOR UPDATE
    TO authenticated
    USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);

-- ====================================================================
-- 3. HEX NUMBERS (+999 Virtual Private Numbers)
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.hex_numbers (
    number VARCHAR(8) PRIMARY KEY,
    formatted VARCHAR(24) NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('available', 'reserved', 'active', 'cooldown', 'blocked')) DEFAULT 'available',
    owner_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    reserved_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT valid_8_digits CHECK (number ~ '^[0-9]{8}$')
);

CREATE INDEX IF NOT EXISTS idx_hex_numbers_status_owner ON public.hex_numbers (status, owner_id);

ALTER TABLE public.hex_numbers ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Authenticated users can read available or their own numbers" ON public.hex_numbers;
CREATE POLICY "Authenticated users can read available or their own numbers"
    ON public.hex_numbers FOR SELECT
    TO authenticated
    USING (status = 'available' OR owner_id = auth.uid());

CREATE TABLE IF NOT EXISTS public.hex_number_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    number VARCHAR(8) NOT NULL REFERENCES public.hex_numbers(number) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    action TEXT NOT NULL CHECK (action IN ('reserved', 'activated', 'released', 'expired', 'cooldown', 'blocked')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hex_history_account ON public.hex_number_history (account_id, created_at DESC);

ALTER TABLE public.hex_number_history ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view their own number history" ON public.hex_number_history;
CREATE POLICY "Users can view their own number history"
    ON public.hex_number_history FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

-- ====================================================================
-- 4. TELEGRAM LINKS & CHALLENGES
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.telegram_links (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id UUID UNIQUE NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    telegram_user_id BIGINT UNIQUE NOT NULL,
    telegram_username TEXT,
    verified BOOLEAN NOT NULL DEFAULT true,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_telegram_links_account ON public.telegram_links (account_id);
CREATE INDEX IF NOT EXISTS idx_telegram_links_tg_user ON public.telegram_links (telegram_user_id);

ALTER TABLE public.telegram_links ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read their own Telegram link" ON public.telegram_links;
CREATE POLICY "Users can read their own Telegram link"
    ON public.telegram_links FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

CREATE TABLE IF NOT EXISTS public.telegram_challenges (
    challenge_id TEXT PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    code_hash TEXT,
    telegram_user_id BIGINT,
    telegram_username TEXT,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    is_used BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_telegram_challenges_account ON public.telegram_challenges (account_id);
CREATE INDEX IF NOT EXISTS idx_telegram_challenges_expires ON public.telegram_challenges (expires_at);

ALTER TABLE public.telegram_challenges ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read their active challenges" ON public.telegram_challenges;
CREATE POLICY "Users can read their active challenges"
    ON public.telegram_challenges FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

-- ====================================================================
-- 5. DEVICES TABLE
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.devices (
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    public_key TEXT NOT NULL DEFAULT '',
    signing_key TEXT NOT NULL DEFAULT '',
    platform TEXT NOT NULL DEFAULT 'android',
    device_name TEXT NOT NULL DEFAULT 'Android Device',
    fcm_token TEXT NOT NULL DEFAULT '',
    key_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_devices_account_active ON public.devices (account_id) WHERE revoked_at IS NULL;

ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read all devices for public keys" ON public.devices;
CREATE POLICY "Users can read their own devices"
    ON public.devices FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

-- Secure view exposing ONLY public cryptographic keys of active devices without sensitive metadata (fcm_token, device_name, etc.)
CREATE OR REPLACE VIEW public.active_device_keys WITH (security_invoker = false) AS
SELECT 
    account_id,
    device_id,
    public_key,
    signing_key,
    key_version
FROM public.devices
WHERE revoked_at IS NULL;

GRANT SELECT ON public.active_device_keys TO authenticated;

-- ====================================================================
-- 5.1 RATE LIMITS TABLE (Durable DB-backed rate limiting)
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.rate_limits (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    last_request_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    request_count INT NOT NULL DEFAULT 1
);

ALTER TABLE public.rate_limits ENABLE ROW LEVEL SECURITY;
-- No public/authenticated RLS policy: only accessible via service_role/Edge Functions

DROP POLICY IF EXISTS "Users can manage their own devices" ON public.devices;
CREATE POLICY "Users can manage their own devices"
    ON public.devices FOR ALL
    TO authenticated
    USING (account_id = auth.uid())
    WITH CHECK (account_id = auth.uid());

-- ====================================================================
-- 6. CONVERSATIONS & CONVERSATION MEMBERS
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.conversations (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL CHECK (type IN ('DIRECT', 'GROUP', 'SAVED_MESSAGES', 'AI')),
    title TEXT,
    created_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    current_sequence BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS public.conversation_members (
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member')) DEFAULT 'member',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_sequence BIGINT NOT NULL DEFAULT 0,
    is_muted BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (conversation_id, account_id)
);

CREATE INDEX IF NOT EXISTS idx_conv_members_account ON public.conversation_members (account_id);

ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Members can view their conversation memberships" ON public.conversation_members;
CREATE POLICY "Members can view their conversation memberships"
    ON public.conversation_members FOR SELECT
    TO authenticated
    USING (
        account_id = auth.uid()
        OR conversation_id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can insert membership when joining or creating" ON public.conversation_members;
CREATE POLICY "Users can insert membership when joining or creating"
    ON public.conversation_members FOR INSERT
    TO authenticated
    WITH CHECK (
        account_id = auth.uid()
        OR conversation_id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm
            WHERE cm.account_id = auth.uid() AND cm.role IN ('owner', 'admin')
        )
    );

DROP POLICY IF EXISTS "Members can update their own membership" ON public.conversation_members;
CREATE POLICY "Members can update their own membership"
    ON public.conversation_members FOR UPDATE
    TO authenticated
    USING (account_id = auth.uid())
    WITH CHECK (account_id = auth.uid());

DROP POLICY IF EXISTS "Users can view conversations they belong to" ON public.conversations;
CREATE POLICY "Users can view conversations they belong to"
    ON public.conversations FOR SELECT
    TO authenticated
    USING (
        id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Authenticated users can create conversations" ON public.conversations;
CREATE POLICY "Authenticated users can create conversations"
    ON public.conversations FOR INSERT
    TO authenticated
    WITH CHECK (created_by = auth.uid());

DROP POLICY IF EXISTS "Members can update conversations" ON public.conversations;
CREATE POLICY "Members can update conversations"
    ON public.conversations FOR UPDATE
    TO authenticated
    USING (
        id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
    );

-- ====================================================================
-- 7. MESSAGES TABLE
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    recipient_id TEXT,
    client_message_id TEXT NOT NULL,
    sequence BIGINT NOT NULL DEFAULT 0,
    payload TEXT NOT NULL,
    signature TEXT NOT NULL DEFAULT '',
    type TEXT NOT NULL DEFAULT 'text',
    time_str TEXT DEFAULT '',
    encryption_version INT NOT NULL DEFAULT 2,
    status TEXT NOT NULL CHECK (status IN ('sending', 'sent', 'delivered', 'read', 'error')) DEFAULT 'sent',
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_conversation_client_message UNIQUE (conversation_id, client_message_id)
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_seq ON public.messages (conversation_id, sequence ASC);
CREATE INDEX IF NOT EXISTS idx_messages_sender ON public.messages (sender_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_recipient ON public.messages (recipient_id, status);

ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Members can view messages in their conversations" ON public.messages;
CREATE POLICY "Members can view messages in their conversations"
    ON public.messages FOR SELECT
    TO authenticated
    USING (
        conversation_id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
        OR recipient_id = auth.uid()::text
        OR sender_id = auth.uid()
    );

DROP POLICY IF EXISTS "Senders can insert messages into conversations they belong to" ON public.messages;
CREATE POLICY "Senders can insert messages into conversations they belong to"
    ON public.messages FOR INSERT
    TO authenticated
    WITH CHECK (
        sender_id = auth.uid()
        AND conversation_id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Senders and recipients can update message status" ON public.messages;
CREATE POLICY "Senders and recipients can update message status"
    ON public.messages FOR UPDATE
    TO authenticated
    USING (
        sender_id = auth.uid()
        OR recipient_id = auth.uid()::text
        OR conversation_id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
    )
    WITH CHECK (
        sender_id = auth.uid()
        OR recipient_id = auth.uid()::text
        OR conversation_id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
    );

-- Trigger: Protect message immutability and restrict recipient updates strictly to delivery/read status
CREATE OR REPLACE FUNCTION public.protect_message_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    -- Core identity columns cannot be altered by anyone
    IF NEW.id != OLD.id OR NEW.conversation_id != OLD.conversation_id OR NEW.sender_id != OLD.sender_id OR NEW.sequence != OLD.sequence OR NEW.client_message_id != OLD.client_message_id OR NEW.created_at != OLD.created_at OR (NEW.recipient_id IS DISTINCT FROM OLD.recipient_id) THEN
        RAISE EXCEPTION 'Immutable message columns cannot be changed';
    END IF;

    -- Non-senders can ONLY modify delivery/read status
    IF auth.uid() != OLD.sender_id THEN
        IF NEW.payload != OLD.payload OR NEW.signature != OLD.signature OR NEW.type != OLD.type OR NEW.is_deleted != OLD.is_deleted OR NEW.encryption_version != OLD.encryption_version THEN
            RAISE EXCEPTION 'Recipients are only permitted to update delivery or read status';
        END IF;
    END IF;

    -- Record timestamp when payload is edited by sender
    IF NEW.payload != OLD.payload THEN
        NEW.edited_at := now();
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_protect_message_immutability ON public.messages;
CREATE TRIGGER trg_protect_message_immutability
    BEFORE UPDATE ON public.messages
    FOR EACH ROW
    EXECUTE FUNCTION public.protect_message_immutability();

-- ====================================================================
-- 8. ATTACHMENTS TABLE
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.attachments (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    message_id TEXT REFERENCES public.messages(id) ON DELETE CASCADE,
    owner_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    storage_path TEXT NOT NULL,
    mime_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size BIGINT NOT NULL DEFAULT 0,
    encryption_version INT NOT NULL DEFAULT 2,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_attachments_conv ON public.attachments (conversation_id);

ALTER TABLE public.attachments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Members can view attachments in their conversations" ON public.attachments;
CREATE POLICY "Members can view attachments in their conversations"
    ON public.attachments FOR SELECT
    TO authenticated
    USING (
        conversation_id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Owners can insert attachments" ON public.attachments;
CREATE POLICY "Owners can insert attachments"
    ON public.attachments FOR INSERT
    TO authenticated
    WITH CHECK (
        owner_id = auth.uid()
        AND conversation_id IN (
            SELECT cm.conversation_id FROM public.conversation_members cm WHERE cm.account_id = auth.uid()
        )
    );

-- ====================================================================
-- 9. APP CONFIG
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.app_config (
    key TEXT PRIMARY KEY,
    value JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.app_config ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Authenticated users can read app_config" ON public.app_config;
CREATE POLICY "Authenticated users can read app_config"
    ON public.app_config FOR SELECT
    TO authenticated
    USING (true);

-- ====================================================================
-- 10. REALTIME PUBLICATIONS
-- ====================================================================
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.messages;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'conversations'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.conversations;
    END IF;
END $$;

-- ====================================================================
-- 11. ATOMIC STORED PROCEDURES & RPCS
-- ====================================================================

-- Function: Atomic +999 Virtual Number Reservation
CREATE OR REPLACE FUNCTION public.reserve_hex_number(p_preferred TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_cand VARCHAR(8);
    v_formatted VARCHAR(24);
    v_expires TIMESTAMPTZ := now() + INTERVAL '10 minutes';
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required';
    END IF;

    -- Clean up expired reservations back to available
    UPDATE public.hex_numbers
    SET status = 'available', owner_id = NULL, reserved_at = NULL, expires_at = NULL
    WHERE status = 'reserved' AND expires_at < now();

    -- Check if user already holds an active or unexpired reserved number
    SELECT number, formatted, expires_at INTO v_cand, v_formatted, v_expires
    FROM public.hex_numbers
    WHERE owner_id = v_user_id AND status = 'reserved' AND expires_at > now()
    LIMIT 1;

    IF v_cand IS NOT NULL THEN
        RETURN jsonb_build_object(
            'raw_number', v_cand,
            'formatted', v_formatted,
            'expires_at', v_expires,
            'status', 'reserved'
        );
    END IF;

    -- If preferred number was passed, attempt to reserve it
    IF p_preferred IS NOT NULL AND p_preferred ~ '^[0-9]{8}$' THEN
        UPDATE public.hex_numbers
        SET status = 'reserved', owner_id = v_user_id, reserved_at = now(), expires_at = v_expires, updated_at = now()
        WHERE number = p_preferred AND status = 'available'
        RETURNING number, formatted INTO v_cand, v_formatted;
    END IF;

    -- If not reserved yet, pick an available number with row lock
    IF v_cand IS NULL THEN
        SELECT number, formatted INTO v_cand, v_formatted
        FROM public.hex_numbers
        WHERE status = 'available'
        FOR UPDATE SKIP LOCKED
        LIMIT 1;

        IF v_cand IS NOT NULL THEN
            UPDATE public.hex_numbers
            SET status = 'reserved', owner_id = v_user_id, reserved_at = now(), expires_at = v_expires, updated_at = now()
            WHERE number = v_cand;
        ELSE
            -- Generate a new random 8-digit available candidate if pool needs population
            FOR i IN 1..10 LOOP
                v_cand := LPAD(FLOOR(random() * 90000000 + 10000000)::TEXT, 8, '0');
                v_formatted := '+999 ' || SUBSTRING(v_cand FROM 1 FOR 3) || ' ' || SUBSTRING(v_cand FROM 4 FOR 5);
                
                BEGIN
                    INSERT INTO public.hex_numbers (number, formatted, status, owner_id, reserved_at, expires_at)
                    VALUES (v_cand, v_formatted, 'reserved', v_user_id, now(), v_expires);
                    EXIT;
                EXCEPTION WHEN unique_violation THEN
                    v_cand := NULL;
                END;
            END LOOP;
        END IF;
    END IF;

    IF v_cand IS NULL THEN
        RAISE EXCEPTION 'No virtual numbers available for reservation';
    END IF;

    INSERT INTO public.hex_number_history (number, account_id, action)
    VALUES (v_cand, v_user_id, 'reserved');

    RETURN jsonb_build_object(
        'raw_number', v_cand,
        'formatted', v_formatted,
        'expires_at', v_expires,
        'status', 'reserved'
    );
END;
$$;

-- Overload: Atomic +999 Virtual Number Reservation without parameters (PostgREST schema cache compatibility)
CREATE OR REPLACE FUNCTION public.reserve_hex_number()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN public.reserve_hex_number(NULL::TEXT);
END;
$$;

GRANT EXECUTE ON FUNCTION public.reserve_hex_number() TO authenticated;
GRANT EXECUTE ON FUNCTION public.reserve_hex_number(TEXT) TO authenticated;

-- Function: Atomic +999 Virtual Number Confirmation & Activation
CREATE OR REPLACE FUNCTION public.confirm_hex_number(p_raw_number TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_clean VARCHAR(8);
    v_formatted VARCHAR(24);
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required';
    END IF;

    v_clean := regexp_replace(p_raw_number, '[^0-9]', '', 'g');
    IF char_length(v_clean) != 8 THEN
        RAISE EXCEPTION 'Invalid number format: exactly 8 digits required';
    END IF;

    -- Verify reservation belongs to caller and has not expired
    SELECT formatted INTO v_formatted
    FROM public.hex_numbers
    WHERE number = v_clean
      AND owner_id = v_user_id
      AND status = 'reserved'
      AND expires_at > now()
    FOR UPDATE;

    IF v_formatted IS NULL THEN
        RAISE EXCEPTION 'Number reservation not found, expired, or not owned by caller';
    END IF;

    -- Transition prior active numbers owned by caller to cooldown
    UPDATE public.hex_numbers
    SET status = 'cooldown', updated_at = now()
    WHERE owner_id = v_user_id AND status = 'active' AND number != v_clean;

    -- Activate the new number
    UPDATE public.hex_numbers
    SET status = 'active', activated_at = now(), expires_at = NULL, updated_at = now()
    WHERE number = v_clean AND owner_id = v_user_id;

    INSERT INTO public.hex_number_history (number, account_id, action)
    VALUES (v_clean, v_user_id, 'activated');

    RETURN jsonb_build_object(
        'confirmed', true,
        'raw_number', v_clean,
        'formatted', v_formatted,
        'status', 'active'
    );
END;
$$;

-- Function: Atomic Idempotent Message Sending with Monotonic Sequence
CREATE OR REPLACE FUNCTION public.send_message_idempotent(
    p_conversation_id TEXT,
    p_client_message_id TEXT,
    p_payload TEXT,
    p_signature TEXT DEFAULT '',
    p_recipient_id TEXT DEFAULT NULL,
    p_type TEXT DEFAULT 'text',
    p_time_str TEXT DEFAULT '',
    p_encryption_version INT DEFAULT 2
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_seq BIGINT;
    v_msg_id TEXT := p_client_message_id;
    v_existing_id TEXT;
    v_existing_seq BIGINT;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required';
    END IF;

    -- Check for idempotency: if message already processed, return existing
    SELECT id, sequence INTO v_existing_id, v_existing_seq
    FROM public.messages
    WHERE conversation_id = p_conversation_id AND client_message_id = p_client_message_id
    LIMIT 1;

    IF v_existing_id IS NOT NULL THEN
        RETURN jsonb_build_object(
            'id', v_existing_id,
            'client_message_id', p_client_message_id,
            'sequence', v_existing_seq,
            'status', 'sent',
            'is_duplicate', true
        );
    END IF;

    -- Ensure conversation row exists with strict deterministic validation
    IF NOT EXISTS (SELECT 1 FROM public.conversations WHERE id = p_conversation_id) THEN
        IF p_conversation_id LIKE 'self_%' THEN
            IF p_conversation_id != ('self_' || v_user_id::text) THEN
                RAISE EXCEPTION 'Invalid self conversation ID for caller' USING ERRCODE = '42501';
            END IF;
            INSERT INTO public.conversations (id, type, created_by) VALUES (p_conversation_id, 'SAVED_MESSAGES', v_user_id) ON CONFLICT (id) DO NOTHING;
            INSERT INTO public.conversation_members (conversation_id, account_id, role) VALUES (p_conversation_id, v_user_id, 'owner') ON CONFLICT (conversation_id, account_id) DO NOTHING;
        ELSIF p_conversation_id LIKE 'ai_%' THEN
            IF p_conversation_id != ('ai_' || v_user_id::text) THEN
                RAISE EXCEPTION 'Invalid AI conversation ID for caller' USING ERRCODE = '42501';
            END IF;
            INSERT INTO public.conversations (id, type, created_by) VALUES (p_conversation_id, 'AI', v_user_id) ON CONFLICT (id) DO NOTHING;
            INSERT INTO public.conversation_members (conversation_id, account_id, role) VALUES (p_conversation_id, v_user_id, 'owner') ON CONFLICT (conversation_id, account_id) DO NOTHING;
        ELSIF p_conversation_id LIKE 'direct_%' THEN
            IF p_recipient_id IS NULL OR NOT (p_recipient_id ~ '^[0-9a-fA-F-]{36}$') THEN
                RAISE EXCEPTION 'Valid UUID recipient_id required for direct conversation' USING ERRCODE = '22000';
            END IF;
            IF p_recipient_id = v_user_id::text THEN
                RAISE EXCEPTION 'Direct conversation cannot be with oneself. Use self conversation' USING ERRCODE = '22000';
            END IF;
            IF p_conversation_id != ('direct_' || LEAST(v_user_id::text, p_recipient_id) || '_' || GREATEST(v_user_id::text, p_recipient_id)) THEN
                RAISE EXCEPTION 'Deterministic direct conversation ID mismatch for caller and recipient' USING ERRCODE = '42501';
            END IF;
            INSERT INTO public.conversations (id, type, created_by) VALUES (p_conversation_id, 'DIRECT', v_user_id) ON CONFLICT (id) DO NOTHING;
            INSERT INTO public.conversation_members (conversation_id, account_id, role) VALUES (p_conversation_id, v_user_id, 'owner') ON CONFLICT (conversation_id, account_id) DO NOTHING;
            INSERT INTO public.conversation_members (conversation_id, account_id, role) VALUES (p_conversation_id, p_recipient_id::UUID, 'member') ON CONFLICT (conversation_id, account_id) DO NOTHING;
        ELSE
            RAISE EXCEPTION 'Conversation does not exist and cannot be auto-provisioned' USING ERRCODE = '42501';
        END IF;
    END IF;

    -- Enforce membership: caller MUST be an active member of this conversation
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members cm 
        WHERE cm.conversation_id = p_conversation_id AND cm.account_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'Caller is not a member of conversation %', p_conversation_id USING ERRCODE = '42501';
    END IF;

    -- Atomically increment conversation sequence with row lock
    UPDATE public.conversations
    SET current_sequence = current_sequence + 1, updated_at = now()
    WHERE id = p_conversation_id
    RETURNING current_sequence INTO v_seq;

    -- Insert message
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
        status
    )
    VALUES (
        v_msg_id,
        p_conversation_id,
        v_user_id,
        p_recipient_id,
        p_client_message_id,
        v_seq,
        p_payload,
        p_signature,
        p_type,
        p_time_str,
        p_encryption_version,
        'sent'
    );

    RETURN jsonb_build_object(
        'id', v_msg_id,
        'client_message_id', p_client_message_id,
        'sequence', v_seq,
        'status', 'sent',
        'is_duplicate', false
    );
END;
$$;

-- Function: Incremental Synchronization Cursor
CREATE OR REPLACE FUNCTION public.get_incremental_messages(
    p_conversation_id TEXT,
    p_after_sequence BIGINT DEFAULT 0,
    p_limit INT DEFAULT 50
)
RETURNS TABLE (
    id TEXT,
    conversation_id TEXT,
    sender_id UUID,
    recipient_id TEXT,
    client_message_id TEXT,
    sequence BIGINT,
    payload TEXT,
    signature TEXT,
    type TEXT,
    time_str TEXT,
    encryption_version INT,
    status TEXT,
    is_deleted BOOLEAN,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required';
    END IF;

    -- Verify caller is strictly an active conversation member
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members cm 
        WHERE cm.conversation_id = p_conversation_id AND cm.account_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'Access denied: caller is not a member of conversation' USING ERRCODE = '42501';
    END IF;

    RETURN QUERY
    SELECT 
        m.id,
        m.conversation_id,
        m.sender_id,
        m.recipient_id,
        m.client_message_id,
        m.sequence,
        m.payload,
        m.signature,
        m.type,
        m.time_str,
        m.encryption_version,
        m.status,
        m.is_deleted,
        m.created_at
    FROM public.messages m
    WHERE m.conversation_id = p_conversation_id
      AND m.sequence > p_after_sequence
    ORDER BY m.sequence ASC
    LIMIT LEAST(p_limit, 100);
END;
$$;

-- Function: Server-Side Complete Account Deletion
CREATE OR REPLACE FUNCTION public.delete_user_account()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required';
    END IF;

    -- 1. Put user's active numbers into cooldown
    UPDATE public.hex_numbers
    SET status = 'cooldown', owner_id = NULL, reserved_at = NULL, expires_at = NULL, updated_at = now()
    WHERE owner_id = v_user_id;

    -- 2. Clean number history
    DELETE FROM public.hex_number_history WHERE account_id = v_user_id;

    -- 3. Clean attachments uploaded by caller (owner_id)
    DELETE FROM public.attachments WHERE owner_id = v_user_id;

    -- 4. Clean messages sent by caller or direct messages to caller
    DELETE FROM public.messages WHERE sender_id = v_user_id OR recipient_id = v_user_id::text;

    -- 5. Clean conversation memberships
    DELETE FROM public.conversation_members WHERE account_id = v_user_id;

    -- 6. Clean empty conversations created by caller
    DELETE FROM public.conversations WHERE created_by = v_user_id AND id NOT IN (SELECT conversation_id FROM public.conversation_members);

    -- 7. Clean devices
    DELETE FROM public.devices WHERE account_id = v_user_id;

    -- 8. Clean rate limits
    DELETE FROM public.rate_limits WHERE user_id = v_user_id;

    -- 9. Clean Telegram links & challenges
    DELETE FROM public.telegram_links WHERE account_id = v_user_id;
    DELETE FROM public.telegram_challenges WHERE account_id = v_user_id;

    -- 10. Delete profile (cascades where appropriate)
    DELETE FROM public.profiles WHERE id = v_user_id;

    -- 11. Attempt delete from auth.users (if privileges permit, otherwise handled by Edge Function admin client)
    BEGIN
        DELETE FROM auth.users WHERE id = v_user_id;
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    RETURN jsonb_build_object('success', true, 'deleted_user_id', v_user_id);
END;
$$;

-- Grant execution to authenticated role
GRANT EXECUTE ON FUNCTION public.reserve_hex_number(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.confirm_hex_number(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_incremental_messages(TEXT, BIGINT, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.delete_user_account() TO authenticated;
