-- ====================================================================
-- Migration: 20261005000000_canonical_schema_and_date_of_birth.sql
-- Description:
-- 1. Canonical profiles schema: adds date_of_birth, avatar_url, avatar_path,
--    normalized_username, hex_number, status, last_seen, is_online.
-- 2. Removes dangerous fallback in get_my_active_hex_number() (profiles.hex_number -> hex_numbers).
-- 3. Server-authoritative +999 identity with strict uniqueness constraints:
--    - At most one active number per account.
--    - At most one reserved number per account.
--    - Strict 8-digit raw_number uniqueness for active/reserved states.
-- 4. Safe data normalization for legacy hex_numbers without silent data destruction.
-- 5. Canonical send_message_idempotent RPC, conversations, and messages schema.
-- 6. Reloads PostgREST schema cache.
-- ====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ====================================================================
-- 1. PROFILES SCHEMA REPAIR (Canonical date_of_birth and profile columns)
-- ====================================================================
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS date_of_birth TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_url TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_path TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS normalized_username TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS hex_number VARCHAR(24) DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'offline';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_online BOOLEAN DEFAULT false;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS profile_background_path TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS profile_background_type TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS profile_background_updated_at TIMESTAMPTZ;

-- Backfill normalized_username if missing
UPDATE public.profiles
SET normalized_username = lower(trim(username))
WHERE (normalized_username IS NULL OR normalized_username = '') AND username IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_profiles_normalized_username ON public.profiles(normalized_username);
CREATE INDEX IF NOT EXISTS idx_profiles_hex_number ON public.profiles(hex_number);

-- ====================================================================
-- 2. CANONICAL HEX_NUMBERS SCHEMA & DATA NORMALIZATION
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.hex_numbers (
    number VARCHAR(24) PRIMARY KEY,
    raw_number VARCHAR(8),
    formatted VARCHAR(24),
    status TEXT NOT NULL DEFAULT 'available',
    owner_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    account_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    reserved_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS raw_number VARCHAR(8);
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS formatted VARCHAR(24);
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'available';
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS account_id UUID REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS reserved_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS activated_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT now();
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

-- Quarantine/legacy audit table for malformed or conflicting numbers
CREATE TABLE IF NOT EXISTS public.hex_numbers_quarantine (
    id BIGSERIAL PRIMARY KEY,
    original_number VARCHAR(64),
    raw_extracted TEXT,
    owner_id UUID,
    account_id UUID,
    reason TEXT NOT NULL,
    quarantined_at TIMESTAMPTZ DEFAULT now()
);

-- Safe data normalization: extract only valid 8-digit numbers
DO $$
DECLARE
    r RECORD;
    v_clean TEXT;
BEGIN
    FOR r IN SELECT number, raw_number, owner_id, account_id FROM public.hex_numbers WHERE raw_number IS NULL OR char_length(raw_number) != 8 LOOP
        v_clean := regexp_replace(COALESCE(r.raw_number, r.number), '\D', '', 'g');
        IF char_length(v_clean) = 11 AND v_clean LIKE '999%' THEN
            v_clean := SUBSTRING(v_clean FROM 4 FOR 8);
        END IF;

        IF char_length(v_clean) = 8 THEN
            UPDATE public.hex_numbers
            SET raw_number = v_clean,
                formatted = '+999 ' || SUBSTRING(v_clean FROM 1 FOR 4) || ' ' || SUBSTRING(v_clean FROM 5 FOR 4),
                updated_at = now()
            WHERE number = r.number;
        ELSE
            -- Quarantine malformed entries without losing historical records
            INSERT INTO public.hex_numbers_quarantine (original_number, raw_extracted, owner_id, account_id, reason)
            VALUES (r.number, v_clean, r.owner_id, r.account_id, 'Malformed number length: expected 8 digits');
        END IF;
    END LOOP;
END;
$$;

-- Harmonize owner_id and account_id
UPDATE public.hex_numbers SET owner_id = account_id WHERE owner_id IS NULL AND account_id IS NOT NULL;
UPDATE public.hex_numbers SET account_id = owner_id WHERE account_id IS NULL AND owner_id IS NOT NULL;

-- Resolve any duplicate active numbers per owner deterministically before adding unique constraint
DO $$
DECLARE
    dup RECORD;
BEGIN
    FOR dup IN 
        SELECT owner_id, COUNT(*) as cnt 
        FROM public.hex_numbers 
        WHERE status = 'active' AND owner_id IS NOT NULL 
        GROUP BY owner_id 
        HAVING COUNT(*) > 1 
    LOOP
        -- Keep only the most recently activated row as active, transition others to cooldown
        UPDATE public.hex_numbers
        SET status = 'cooldown', updated_at = now()
        WHERE owner_id = dup.owner_id 
          AND status = 'active'
          AND number NOT IN (
              SELECT number FROM public.hex_numbers
              WHERE owner_id = dup.owner_id AND status = 'active'
              ORDER BY activated_at DESC NULLS LAST, updated_at DESC
              LIMIT 1
          );
    END LOOP;
END;
$$;

-- Release expired reservations
UPDATE public.hex_numbers
SET status = 'available', owner_id = NULL, account_id = NULL, reserved_at = NULL, expires_at = NULL, updated_at = now()
WHERE status = 'reserved' AND expires_at < now();

-- ====================================================================
-- 3. STRICT PARTIAL UNIQUE CONSTRAINTS (Prevent multi-identity bugs)
-- ====================================================================
-- At most one active number per account
CREATE UNIQUE INDEX IF NOT EXISTS uq_hex_numbers_active_owner 
    ON public.hex_numbers(owner_id) 
    WHERE status = 'active' AND owner_id IS NOT NULL;

-- At most one active number per account_id column
CREATE UNIQUE INDEX IF NOT EXISTS uq_hex_numbers_active_account 
    ON public.hex_numbers(account_id) 
    WHERE status = 'active' AND account_id IS NOT NULL;

-- At most one reserved number per account
CREATE UNIQUE INDEX IF NOT EXISTS uq_hex_numbers_reserved_owner 
    ON public.hex_numbers(owner_id) 
    WHERE status = 'reserved' AND owner_id IS NOT NULL;

-- Unique 8-digit raw_number for all active, reserved, and cooldown numbers
CREATE UNIQUE INDEX IF NOT EXISTS uq_hex_numbers_raw_number_active 
    ON public.hex_numbers(raw_number) 
    WHERE raw_number IS NOT NULL AND status IN ('active', 'reserved', 'cooldown');

-- ====================================================================
-- 4. CANONICAL SERVER-AUTHORITATIVE get_my_active_hex_number()
-- (STRICT: NO fallback to profiles.hex_number, NO resuscitate, NO guess)
-- ====================================================================
CREATE OR REPLACE FUNCTION public.get_my_active_hex_number()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_raw VARCHAR(8);
    v_formatted VARCHAR(24);
    v_active_count INT;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    -- Strict data integrity check: count active rows for caller
    SELECT COUNT(*)
    INTO v_active_count
    FROM public.hex_numbers
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'active';

    IF v_active_count > 1 THEN
        -- Severe data integrity violation: fail-closed with explicit error instead of picking random LIMIT 1
        RAISE EXCEPTION 'Server data-integrity error: multiple active numbers found for account %', v_user_id USING ERRCODE = '23505';
    END IF;

    -- Query authoritative active row from hex_numbers (single source of truth)
    SELECT raw_number, formatted
    INTO v_raw, v_formatted
    FROM public.hex_numbers
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'active';

    IF v_raw IS NOT NULL THEN
        IF v_formatted IS NULL OR v_formatted = '' THEN
            v_formatted := '+999 ' || SUBSTRING(v_raw FROM 1 FOR 4) || ' ' || SUBSTRING(v_raw FROM 5 FOR 4);
        END IF;

        -- Mirror to profiles.hex_number for display only
        UPDATE public.profiles
        SET hex_number = v_formatted
        WHERE id = v_user_id AND (hex_number IS NULL OR hex_number <> v_formatted);

        RETURN jsonb_build_object(
            'raw_number', v_raw,
            'formatted', v_formatted,
            'status', 'active'
        );
    END IF;

    -- STRICT: If active row is missing, return explicit NULL (no active number)
    -- NEVER fall back to profiles.hex_number, NEVER insert/revive from profile.
    RETURN NULL;
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_my_active_hex_number() TO authenticated;

-- ====================================================================
-- 5. CANONICAL reserve_hex_number(p_preferred TEXT DEFAULT NULL)
-- ====================================================================
CREATE OR REPLACE FUNCTION public.reserve_hex_number(p_preferred TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_clean_pref VARCHAR(8);
    v_selected_raw VARCHAR(8);
    v_selected_formatted VARCHAR(24);
    v_expires_at TIMESTAMPTZ;
    v_existing_raw VARCHAR(8);
    v_existing_formatted VARCHAR(24);
    v_existing_status TEXT;
    v_existing_expires TIMESTAMPTZ;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    -- Advisory lock per user prevents race conditions between simultaneous reserve calls
    PERFORM pg_advisory_xact_lock(hashtext('hex_reserve_' || v_user_id::text));

    -- Clean up any expired reservations owned by this user
    UPDATE public.hex_numbers
    SET status = 'available', owner_id = NULL, account_id = NULL, reserved_at = NULL, expires_at = NULL, updated_at = now()
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'reserved'
      AND expires_at < now();

    -- Check if user already has an active number
    SELECT raw_number, formatted INTO v_existing_raw, v_existing_formatted
    FROM public.hex_numbers
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'active'
    LIMIT 1;

    IF v_existing_raw IS NOT NULL THEN
        RETURN jsonb_build_object(
            'status', 'already_has_active_number',
            'raw_number', v_existing_raw,
            'formatted', v_existing_formatted,
            'error', 'Account already has an active +999 identity'
        );
    END IF;

    -- Check if user already has a pending valid reservation (idempotency)
    SELECT raw_number, formatted, expires_at INTO v_existing_raw, v_existing_formatted, v_existing_expires
    FROM public.hex_numbers
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'reserved'
      AND expires_at > now()
    LIMIT 1;

    IF v_existing_raw IS NOT NULL THEN
        RETURN jsonb_build_object(
            'raw_number', v_existing_raw,
            'formatted', v_existing_formatted,
            'expires_at', v_existing_expires,
            'status', 'reserved',
            'idempotent', true
        );
    END IF;

    -- Normalize preferred number if provided
    IF p_preferred IS NOT NULL AND p_preferred != '' THEN
        v_clean_pref := regexp_replace(p_preferred, '\D', '', 'g');
        IF char_length(v_clean_pref) = 11 AND v_clean_pref LIKE '999%' THEN
            v_clean_pref := SUBSTRING(v_clean_pref FROM 4 FOR 8);
        END IF;

        IF char_length(v_clean_pref) = 8 THEN
            -- Attempt to reserve the preferred number with row lock
            SELECT raw_number, formatted INTO v_selected_raw, v_selected_formatted
            FROM public.hex_numbers
            WHERE (raw_number = v_clean_pref OR number = v_clean_pref)
              AND status = 'available'
            FOR UPDATE SKIP LOCKED
            LIMIT 1;
        END IF;
    END IF;

    -- If no preferred available, reserve an available number
    IF v_selected_raw IS NULL THEN
        SELECT raw_number, formatted INTO v_selected_raw, v_selected_formatted
        FROM public.hex_numbers
        WHERE status = 'available' AND char_length(raw_number) = 8
        FOR UPDATE SKIP LOCKED
        LIMIT 1;
    END IF;

    -- If pool exhausted, generate a guaranteed valid 8-digit number
    IF v_selected_raw IS NULL THEN
        FOR i IN 1..50 LOOP
            v_selected_raw := lpad((floor(random() * 90000000) + 10000000)::text, 8, '0');
            v_selected_formatted := '+999 ' || SUBSTRING(v_selected_raw FROM 1 FOR 4) || ' ' || SUBSTRING(v_selected_raw FROM 5 FOR 4);
            
            BEGIN
                INSERT INTO public.hex_numbers (number, raw_number, formatted, status, owner_id, account_id, reserved_at, expires_at, created_at, updated_at)
                VALUES (v_selected_raw, v_selected_raw, v_selected_formatted, 'reserved', v_user_id, v_user_id, now(), now() + INTERVAL '15 minutes', now(), now());
                
                v_expires_at := now() + INTERVAL '15 minutes';
                EXIT;
            EXCEPTION WHEN unique_violation THEN
                v_selected_raw := NULL;
            END;
        END LOOP;

        IF v_selected_raw IS NULL THEN
            RAISE EXCEPTION 'Failed to allocate unique +999 identity. Please try again.' USING ERRCODE = '53400';
        END IF;
    ELSE
        v_expires_at := now() + INTERVAL '15 minutes';
        UPDATE public.hex_numbers
        SET status = 'reserved',
            owner_id = v_user_id,
            account_id = v_user_id,
            reserved_at = now(),
            expires_at = v_expires_at,
            updated_at = now()
        WHERE (raw_number = v_selected_raw OR number = v_selected_raw);
    END IF;

    RETURN jsonb_build_object(
        'raw_number', v_selected_raw,
        'formatted', v_selected_formatted,
        'expires_at', v_expires_at,
        'status', 'reserved'
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.reserve_hex_number(TEXT) TO authenticated;

-- ====================================================================
-- 6. CANONICAL confirm_hex_number(p_raw_number TEXT)
-- ====================================================================
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
    v_record RECORD;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    v_clean := regexp_replace(p_raw_number, '\D', '', 'g');
    IF char_length(v_clean) = 11 AND v_clean LIKE '999%' THEN
        v_clean := SUBSTRING(v_clean FROM 4 FOR 8);
    END IF;

    IF char_length(v_clean) != 8 THEN
        RAISE EXCEPTION 'Invalid number format: exactly 8 digits required' USING ERRCODE = '22023';
    END IF;

    -- Row lock and verify reservation belongs to caller and is not expired
    SELECT * INTO v_record
    FROM public.hex_numbers
    WHERE (raw_number = v_clean OR number = v_clean)
      AND (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'reserved'
      AND expires_at > now()
    FOR UPDATE;

    IF v_record IS NULL THEN
        RAISE EXCEPTION 'Number reservation not found, expired, or not owned by caller' USING ERRCODE = 'P0002';
    END IF;

    v_formatted := COALESCE(v_record.formatted, '+999 ' || SUBSTRING(v_clean FROM 1 FOR 4) || ' ' || SUBSTRING(v_clean FROM 5 FOR 4));

    -- Transition prior active numbers owned by caller to cooldown
    UPDATE public.hex_numbers
    SET status = 'cooldown', updated_at = now()
    WHERE (owner_id = v_user_id OR account_id = v_user_id) 
      AND status = 'active' 
      AND raw_number != v_clean 
      AND number != v_clean;

    -- Atomically activate the number
    UPDATE public.hex_numbers
    SET status = 'active', 
        activated_at = now(), 
        expires_at = NULL, 
        updated_at = now(),
        owner_id = v_user_id,
        account_id = v_user_id,
        formatted = v_formatted
    WHERE (raw_number = v_clean OR number = v_clean);

    -- Synchronize profile
    UPDATE public.profiles
    SET hex_number = v_formatted
    WHERE id = v_user_id;

    -- Record history
    INSERT INTO public.hex_number_history (number, account_id, action)
    VALUES (v_clean, v_user_id, 'activated');

    RETURN jsonb_build_object(
        'confirmed', true,
        'raw_number', v_clean,
        'formatted', v_formatted,
        'number', v_formatted,
        'status', 'active'
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.confirm_hex_number(TEXT) TO authenticated;

-- ====================================================================
-- 7. MESSAGES, CONVERSATIONS & IDEMPOTENT RPC
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.conversations (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL DEFAULT 'DIRECT',
    participant1 UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    participant2 UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    client_message_id TEXT NOT NULL,
    sender_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    recipient_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    payload TEXT NOT NULL,
    signature TEXT NOT NULL,
    type TEXT NOT NULL DEFAULT 'text',
    time_str TEXT NOT NULL DEFAULT '',
    encryption_version INT NOT NULL DEFAULT 2,
    created_at TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT uq_messages_conversation_client UNIQUE (conversation_id, client_message_id)
);

ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "conversations_select_policy" ON public.conversations;
CREATE POLICY "conversations_select_policy" ON public.conversations
    FOR SELECT TO authenticated
    USING (participant1 = auth.uid() OR participant2 = auth.uid());

DROP POLICY IF EXISTS "conversations_insert_policy" ON public.conversations;
CREATE POLICY "conversations_insert_policy" ON public.conversations
    FOR INSERT TO authenticated
    WITH CHECK (participant1 = auth.uid() OR participant2 = auth.uid());

DROP POLICY IF EXISTS "messages_select_policy" ON public.messages;
CREATE POLICY "messages_select_policy" ON public.messages
    FOR SELECT TO authenticated
    USING (sender_id = auth.uid() OR recipient_id = auth.uid());

DROP POLICY IF EXISTS "messages_insert_policy" ON public.messages;
CREATE POLICY "messages_insert_policy" ON public.messages
    FOR INSERT TO authenticated
    WITH CHECK (sender_id = auth.uid());

CREATE OR REPLACE FUNCTION public.send_message_idempotent(
    p_conversation_id TEXT,
    p_client_message_id TEXT,
    p_payload TEXT,
    p_signature TEXT,
    p_recipient_id UUID DEFAULT NULL,
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
    v_sender_id UUID := auth.uid();
    v_msg_id BIGINT;
    v_existing RECORD;
BEGIN
    IF v_sender_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    -- Ensure conversation row exists
    INSERT INTO public.conversations (id, type, participant1, participant2, updated_at)
    VALUES (p_conversation_id, 'DIRECT', v_sender_id, p_recipient_id, now())
    ON CONFLICT (id) DO UPDATE
    SET updated_at = now();

    -- Idempotent insert of message
    SELECT id, client_message_id, created_at INTO v_existing
    FROM public.messages
    WHERE conversation_id = p_conversation_id AND client_message_id = p_client_message_id;

    IF v_existing IS NOT NULL THEN
        RETURN jsonb_build_object(
            'success', true,
            'message_id', v_existing.id,
            'client_message_id', v_existing.client_message_id,
            'idempotent', true,
            'created_at', v_existing.created_at
        );
    END IF;

    INSERT INTO public.messages (
        conversation_id,
        client_message_id,
        sender_id,
        recipient_id,
        payload,
        signature,
        type,
        time_str,
        encryption_version,
        created_at
    )
    VALUES (
        p_conversation_id,
        p_client_message_id,
        v_sender_id,
        p_recipient_id,
        p_payload,
        p_signature,
        COALESCE(p_type, 'text'),
        COALESCE(p_time_str, ''),
        COALESCE(p_encryption_version, 2),
        now()
    )
    RETURNING id INTO v_msg_id;

    RETURN jsonb_build_object(
        'success', true,
        'message_id', v_msg_id,
        'client_message_id', p_client_message_id,
        'idempotent', false,
        'created_at', now()
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, UUID, TEXT, TEXT, INT) TO authenticated;

-- Non-prefixed overload for schema cache compatibility
CREATE OR REPLACE FUNCTION public.send_message_idempotent(
    conversation_id TEXT,
    client_message_id TEXT,
    payload TEXT,
    signature TEXT,
    recipient_id UUID DEFAULT NULL,
    type TEXT DEFAULT 'text',
    time_str TEXT DEFAULT '',
    encryption_version INT DEFAULT 2
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN public.send_message_idempotent(
        conversation_id,
        client_message_id,
        payload,
        signature,
        recipient_id,
        type,
        time_str,
        encryption_version
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, UUID, TEXT, TEXT, INT) TO authenticated;

-- ====================================================================
-- 8. RELOAD PostgREST SCHEMA CACHE
-- ====================================================================
NOTIFY pgrst, 'reload schema';
