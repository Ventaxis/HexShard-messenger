-- ====================================================================
-- Migration: 20260930000000_canonical_hex_numbers_rpc.sql
-- Description:
-- 1. Unifies hex_numbers table schema across all previous migrations.
-- 2. Strictly drops ambiguous parameterless overload reserve_hex_number().
-- 3. Provides single canonical public.reserve_hex_number(p_preferred TEXT DEFAULT NULL).
-- 4. Provides authoritative public.confirm_hex_number(p_raw_number TEXT).
-- 5. Updates profiles.hex_number on activation.
-- 6. Reloads PostgREST schema cache.
-- ====================================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- 1. Ensure all columns exist on public.hex_numbers with flexible compatibility
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

-- Ensure columns exist if table was created in an older migration
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS raw_number VARCHAR(8);
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS formatted VARCHAR(24);
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS account_id UUID REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS reserved_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS activated_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT now();
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

-- Ensure profiles columns exist
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS hex_number VARCHAR(24) DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS bio TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS date_of_birth TEXT DEFAULT '';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_url TEXT DEFAULT '';

-- Reconcile dual owner columns
UPDATE public.hex_numbers SET owner_id = account_id WHERE owner_id IS NULL AND account_id IS NOT NULL;
UPDATE public.hex_numbers SET account_id = owner_id WHERE account_id IS NULL AND owner_id IS NOT NULL;

-- Backfill raw_number from number if missing
UPDATE public.hex_numbers 
SET raw_number = regexp_replace(number, '\D', '', 'g')
WHERE (raw_number IS NULL OR raw_number = '') AND number IS NOT NULL;

-- Backfill formatted if missing
UPDATE public.hex_numbers
SET formatted = '+999 ' || SUBSTRING(raw_number FROM 1 FOR 4) || ' ' || SUBSTRING(raw_number FROM 5 FOR 4)
WHERE (formatted IS NULL OR formatted = '') AND raw_number IS NOT NULL AND length(raw_number) = 8;

-- Performance & Isolation Indexes
CREATE INDEX IF NOT EXISTS idx_hex_numbers_raw_number ON public.hex_numbers(raw_number);
CREATE INDEX IF NOT EXISTS idx_hex_numbers_status_owner ON public.hex_numbers(status, owner_id);
CREATE INDEX IF NOT EXISTS idx_hex_numbers_status_account ON public.hex_numbers(status, account_id);

-- Ensure history table exists
CREATE TABLE IF NOT EXISTS public.hex_number_history (
    id BIGSERIAL PRIMARY KEY,
    number VARCHAR(24) NOT NULL,
    account_id UUID,
    action TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Row Level Security
ALTER TABLE public.hex_numbers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hex_number_history ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "hex_numbers_authenticated_read" ON public.hex_numbers;
CREATE POLICY "hex_numbers_authenticated_read"
    ON public.hex_numbers FOR SELECT
    TO authenticated
    USING (status = 'available' OR owner_id = auth.uid() OR account_id = auth.uid());

DROP POLICY IF EXISTS "hex_history_authenticated_read" ON public.hex_number_history;
CREATE POLICY "hex_history_authenticated_read"
    ON public.hex_number_history FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

-- ====================================================================
-- 2. DROP CONFLICTING OVERLOADS
-- ====================================================================
DROP FUNCTION IF EXISTS public.reserve_hex_number();
DROP FUNCTION IF EXISTS public.reserve_hex_number(TEXT);

-- ====================================================================
-- 3. SINGLE CANONICAL RPC: reserve_hex_number(p_preferred TEXT DEFAULT NULL)
-- ====================================================================
CREATE OR REPLACE FUNCTION public.reserve_hex_number(p_preferred TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_cand_raw VARCHAR(8);
    v_cand_num VARCHAR(24);
    v_formatted VARCHAR(24);
    v_expires TIMESTAMPTZ := now() + INTERVAL '10 minutes';
    v_clean_pref VARCHAR(8);
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    -- Clean up expired reservations back to available
    UPDATE public.hex_numbers
    SET status = 'available', 
        owner_id = NULL, 
        account_id = NULL, 
        reserved_at = NULL, 
        expires_at = NULL, 
        updated_at = now()
    WHERE status = 'reserved' AND expires_at < now();

    -- Check if user already holds an unexpired reservation
    SELECT raw_number, number, formatted, expires_at 
    INTO v_cand_raw, v_cand_num, v_formatted, v_expires
    FROM public.hex_numbers
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'reserved' 
      AND expires_at > now()
    LIMIT 1;

    IF v_cand_raw IS NOT NULL THEN
        IF v_formatted IS NULL OR v_formatted = '' THEN
            v_formatted := '+999 ' || SUBSTRING(v_cand_raw FROM 1 FOR 4) || ' ' || SUBSTRING(v_cand_raw FROM 5 FOR 4);
        END IF;
        RETURN jsonb_build_object(
            'raw_number', v_cand_raw,
            'number', v_formatted,
            'formatted', v_formatted,
            'expires_at', EXTRACT(EPOCH FROM v_expires) * 1000,
            'status', 'reserved'
        );
    END IF;

    -- Handle preferred custom number if provided
    IF p_preferred IS NOT NULL AND TRIM(p_preferred) <> '' THEN
        v_clean_pref := regexp_replace(TRIM(p_preferred), '[^0-9]', '', 'g');
        IF char_length(v_clean_pref) != 8 THEN
            RAISE EXCEPTION 'Invalid preferred number: exactly 8 digits required' USING ERRCODE = '22023';
        END IF;

        -- Attempt to reserve if available in pool
        UPDATE public.hex_numbers
        SET status = 'reserved', 
            owner_id = v_user_id, 
            account_id = v_user_id, 
            reserved_at = now(), 
            expires_at = v_expires, 
            updated_at = now()
        WHERE (raw_number = v_clean_pref OR number = v_clean_pref) AND status = 'available'
        RETURNING raw_number, formatted INTO v_cand_raw, v_formatted;

        IF v_cand_raw IS NULL THEN
            -- Check if number already in use
            IF EXISTS (SELECT 1 FROM public.hex_numbers WHERE raw_number = v_clean_pref OR number = v_clean_pref) THEN
                RAISE EXCEPTION 'Number is already reserved or in use' USING ERRCODE = '23505';
            ELSE
                v_formatted := '+999 ' || SUBSTRING(v_clean_pref FROM 1 FOR 4) || ' ' || SUBSTRING(v_clean_pref FROM 5 FOR 4);
                BEGIN
                    INSERT INTO public.hex_numbers (number, raw_number, formatted, status, owner_id, account_id, reserved_at, expires_at)
                    VALUES (v_clean_pref, v_clean_pref, v_formatted, 'reserved', v_user_id, v_user_id, now(), v_expires);
                    v_cand_raw := v_clean_pref;
                EXCEPTION WHEN unique_violation THEN
                    RAISE EXCEPTION 'Number is already reserved or in use' USING ERRCODE = '23505';
                END;
            END IF;
        END IF;
    END IF;

    -- If no preferred number or preferred not set, pick available or generate
    IF v_cand_raw IS NULL THEN
        -- Pick existing available number with row lock
        SELECT raw_number, formatted INTO v_cand_raw, v_formatted
        FROM public.hex_numbers
        WHERE status = 'available'
        FOR UPDATE SKIP LOCKED
        LIMIT 1;

        IF v_cand_raw IS NOT NULL THEN
            UPDATE public.hex_numbers
            SET status = 'reserved', 
                owner_id = v_user_id, 
                account_id = v_user_id, 
                reserved_at = now(), 
                expires_at = v_expires, 
                updated_at = now()
            WHERE raw_number = v_cand_raw OR number = v_cand_raw;
        ELSE
            -- Populate a new random 8-digit candidate
            FOR i IN 1..20 LOOP
                v_cand_raw := LPAD(FLOOR(random() * 90000000 + 10000000)::TEXT, 8, '0');
                v_formatted := '+999 ' || SUBSTRING(v_cand_raw FROM 1 FOR 4) || ' ' || SUBSTRING(v_cand_raw FROM 5 FOR 4);
                
                BEGIN
                    INSERT INTO public.hex_numbers (number, raw_number, formatted, status, owner_id, account_id, reserved_at, expires_at)
                    VALUES (v_cand_raw, v_cand_raw, v_formatted, 'reserved', v_user_id, v_user_id, now(), v_expires);
                    EXIT;
                EXCEPTION WHEN unique_violation THEN
                    v_cand_raw := NULL;
                END;
            END LOOP;
        END IF;
    END IF;

    IF v_cand_raw IS NULL THEN
        RAISE EXCEPTION 'No virtual numbers available for reservation' USING ERRCODE = 'P0002';
    END IF;

    IF v_formatted IS NULL OR v_formatted = '' THEN
        v_formatted := '+999 ' || SUBSTRING(v_cand_raw FROM 1 FOR 4) || ' ' || SUBSTRING(v_cand_raw FROM 5 FOR 4);
    END IF;

    -- Record history
    INSERT INTO public.hex_number_history (number, account_id, action)
    VALUES (v_cand_raw, v_user_id, 'reserved');

    RETURN jsonb_build_object(
        'raw_number', v_cand_raw,
        'number', v_formatted,
        'formatted', v_formatted,
        'expires_at', EXTRACT(EPOCH FROM v_expires) * 1000,
        'status', 'reserved'
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.reserve_hex_number(TEXT) TO authenticated;

-- ====================================================================
-- 4. AUTHORITATIVE RPC: confirm_hex_number(p_raw_number TEXT)
-- ====================================================================
DROP FUNCTION IF EXISTS public.confirm_hex_number(TEXT);

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

    v_clean := regexp_replace(p_raw_number, '[^0-9]', '', 'g');
    IF char_length(v_clean) = 11 AND v_clean LIKE '999%' THEN
        v_clean := SUBSTRING(v_clean FROM 4 FOR 8);
    END IF;

    IF char_length(v_clean) != 8 THEN
        RAISE EXCEPTION 'Invalid number format: exactly 8 digits required' USING ERRCODE = '22023';
    END IF;

    -- Verify reservation belongs to caller and has not expired
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

    -- Activate the new number
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

-- Notify PostgREST to reload schema cache
NOTIFY pgrst, 'reload schema';
