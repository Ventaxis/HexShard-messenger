-- ====================================================================
-- Migration: Fix Hex Numbers Schema & Canonical Reserve/Confirm RPC
-- Date: 2026-09-28
-- Description:
-- 1. Unifies hex_numbers table: adds missing columns (owner_id, formatted,
--    reserved_at, expires_at, updated_at) while preserving legacy account_id.
-- 2. Keeps owner_id and account_id strictly synchronized.
-- 3. Drops ambiguous overloads and provides a single canonical RPC:
--    public.reserve_hex_number(p_preferred TEXT DEFAULT NULL)
-- 4. Provides canonical public.confirm_hex_number(p_raw_number TEXT)
-- 5. Reloads PostgREST schema cache.
-- ====================================================================

-- 1. Table structure compatibility
CREATE TABLE IF NOT EXISTS public.hex_numbers (
    number VARCHAR(8) PRIMARY KEY,
    formatted VARCHAR(24),
    status TEXT NOT NULL CHECK (status IN ('available', 'reserved', 'active', 'cooldown', 'blocked')) DEFAULT 'available',
    owner_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    account_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    reserved_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now(),
    CONSTRAINT valid_8_digits CHECK (number ~ '^[0-9]{8}$')
);

-- Ensure all columns exist on pre-existing tables
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS formatted VARCHAR(24);
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS owner_id UUID REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS account_id UUID REFERENCES auth.users(id) ON DELETE SET NULL;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS reserved_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS activated_at TIMESTAMPTZ;
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT now();
ALTER TABLE public.hex_numbers ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT now();

-- Synchronize owner_id and account_id across records
UPDATE public.hex_numbers SET owner_id = account_id WHERE owner_id IS NULL AND account_id IS NOT NULL;
UPDATE public.hex_numbers SET account_id = owner_id WHERE account_id IS NULL AND owner_id IS NOT NULL;

-- Backfill formatted representation if missing
UPDATE public.hex_numbers 
SET formatted = '+999 ' || SUBSTRING(number FROM 1 FOR 4) || ' ' || SUBSTRING(number FROM 5 FOR 4)
WHERE formatted IS NULL AND number IS NOT NULL AND number ~ '^[0-9]{8}$';

-- Trigger to maintain owner_id and account_id duality automatically
CREATE OR REPLACE FUNCTION public.sync_hex_numbers_owner()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.owner_id IS NOT NULL AND NEW.account_id IS NULL THEN
        NEW.account_id := NEW.owner_id;
    ELSIF NEW.account_id IS NOT NULL AND NEW.owner_id IS NULL THEN
        NEW.owner_id := NEW.account_id;
    END IF;
    IF (NEW.formatted IS NULL OR NEW.formatted = '') AND NEW.number ~ '^[0-9]{8}$' THEN
        NEW.formatted := '+999 ' || SUBSTRING(NEW.number FROM 1 FOR 4) || ' ' || SUBSTRING(NEW.number FROM 5 FOR 4);
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_sync_hex_numbers_owner ON public.hex_numbers;
CREATE TRIGGER trg_sync_hex_numbers_owner
BEFORE INSERT OR UPDATE ON public.hex_numbers
FOR EACH ROW EXECUTE FUNCTION public.sync_hex_numbers_owner();

-- History table
CREATE TABLE IF NOT EXISTS public.hex_number_history (
    id BIGSERIAL PRIMARY KEY,
    number VARCHAR(8) NOT NULL,
    account_id UUID,
    action TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- RLS
ALTER TABLE public.hex_numbers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hex_number_history ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Authenticated users can read available or their own numbers" ON public.hex_numbers;
CREATE POLICY "Authenticated users can read available or their own numbers"
    ON public.hex_numbers FOR SELECT
    TO authenticated
    USING (status = 'available' OR owner_id = auth.uid() OR account_id = auth.uid());

DROP POLICY IF EXISTS "Users can read own number history" ON public.hex_number_history;
CREATE POLICY "Users can read own number history"
    ON public.hex_number_history FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

-- Indexes for high-performance lookup and isolation
CREATE INDEX IF NOT EXISTS idx_hex_numbers_status_owner ON public.hex_numbers (status, owner_id);
CREATE INDEX IF NOT EXISTS idx_hex_numbers_status_account ON public.hex_numbers (status, account_id);

-- ====================================================================
-- 2. CANONICAL ATOMIC RPC: reserve_hex_number
-- ====================================================================

-- Drop ambiguous overloads to prevent PGRST202 schema cache mismatch
DROP FUNCTION IF EXISTS public.reserve_hex_number();
DROP FUNCTION IF EXISTS public.reserve_hex_number(TEXT);

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
    v_clean_pref VARCHAR(8);
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required';
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
    SELECT number, formatted, expires_at INTO v_cand, v_formatted, v_expires
    FROM public.hex_numbers
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'reserved' 
      AND expires_at > now()
    LIMIT 1;

    IF v_cand IS NOT NULL THEN
        RETURN jsonb_build_object(
            'raw_number', v_cand,
            'formatted', COALESCE(v_formatted, '+999 ' || SUBSTRING(v_cand FROM 1 FOR 4) || ' ' || SUBSTRING(v_cand FROM 5 FOR 4)),
            'expires_at', v_expires,
            'status', 'reserved'
        );
    END IF;

    -- Handle preferred custom number if provided
    IF p_preferred IS NOT NULL AND TRIM(p_preferred) <> '' THEN
        v_clean_pref := regexp_replace(TRIM(p_preferred), '[^0-9]', '', 'g');
        IF char_length(v_clean_pref) != 8 THEN
            RAISE EXCEPTION 'Invalid preferred number: exactly 8 digits required';
        END IF;

        -- Attempt to reserve if available in pool
        UPDATE public.hex_numbers
        SET status = 'reserved', 
            owner_id = v_user_id, 
            account_id = v_user_id, 
            reserved_at = now(), 
            expires_at = v_expires, 
            updated_at = now()
        WHERE number = v_clean_pref AND status = 'available'
        RETURNING number, formatted INTO v_cand, v_formatted;

        IF v_cand IS NULL THEN
            -- Check if number already in use
            IF EXISTS (SELECT 1 FROM public.hex_numbers WHERE number = v_clean_pref) THEN
                RAISE EXCEPTION 'Number is already reserved or in use';
            ELSE
                v_formatted := '+999 ' || SUBSTRING(v_clean_pref FROM 1 FOR 4) || ' ' || SUBSTRING(v_clean_pref FROM 5 FOR 4);
                BEGIN
                    INSERT INTO public.hex_numbers (number, formatted, status, owner_id, account_id, reserved_at, expires_at)
                    VALUES (v_clean_pref, v_formatted, 'reserved', v_user_id, v_user_id, now(), v_expires);
                    v_cand := v_clean_pref;
                EXCEPTION WHEN unique_violation THEN
                    RAISE EXCEPTION 'Number is already reserved or in use';
                END;
            END IF;
        END IF;
    END IF;

    -- If no preferred number or random generation requested
    IF v_cand IS NULL THEN
        -- Pick existing available number with row lock
        SELECT number, formatted INTO v_cand, v_formatted
        FROM public.hex_numbers
        WHERE status = 'available'
        FOR UPDATE SKIP LOCKED
        LIMIT 1;

        IF v_cand IS NOT NULL THEN
            UPDATE public.hex_numbers
            SET status = 'reserved', 
                owner_id = v_user_id, 
                account_id = v_user_id, 
                reserved_at = now(), 
                expires_at = v_expires, 
                updated_at = now()
            WHERE number = v_cand;
        ELSE
            -- Populate a new random 8-digit available candidate
            FOR i IN 1..15 LOOP
                v_cand := LPAD(FLOOR(random() * 90000000 + 10000000)::TEXT, 8, '0');
                v_formatted := '+999 ' || SUBSTRING(v_cand FROM 1 FOR 4) || ' ' || SUBSTRING(v_cand FROM 5 FOR 4);
                
                BEGIN
                    INSERT INTO public.hex_numbers (number, formatted, status, owner_id, account_id, reserved_at, expires_at)
                    VALUES (v_cand, v_formatted, 'reserved', v_user_id, v_user_id, now(), v_expires);
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
        'formatted', COALESCE(v_formatted, '+999 ' || SUBSTRING(v_cand FROM 1 FOR 4) || ' ' || SUBSTRING(v_cand FROM 5 FOR 4)),
        'expires_at', v_expires,
        'status', 'reserved'
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.reserve_hex_number(TEXT) TO authenticated;

-- ====================================================================
-- 3. CANONICAL ATOMIC RPC: confirm_hex_number
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
      AND (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'reserved'
      AND expires_at > now()
    FOR UPDATE;

    IF v_formatted IS NULL THEN
        RAISE EXCEPTION 'Number reservation not found, expired, or not owned by caller';
    END IF;

    -- Transition prior active numbers owned by caller to cooldown
    UPDATE public.hex_numbers
    SET status = 'cooldown', updated_at = now()
    WHERE (owner_id = v_user_id OR account_id = v_user_id) 
      AND status = 'active' 
      AND number != v_clean;

    -- Activate the new number
    UPDATE public.hex_numbers
    SET status = 'active', 
        activated_at = now(), 
        expires_at = NULL, 
        updated_at = now(),
        owner_id = v_user_id,
        account_id = v_user_id
    WHERE number = v_clean AND (owner_id = v_user_id OR account_id = v_user_id);

    INSERT INTO public.hex_number_history (number, account_id, action)
    VALUES (v_clean, v_user_id, 'activated');

    RETURN jsonb_build_object(
        'confirmed', true,
        'raw_number', v_clean,
        'formatted', COALESCE(v_formatted, '+999 ' || SUBSTRING(v_clean FROM 1 FOR 4) || ' ' || SUBSTRING(v_clean FROM 5 FOR 4)),
        'status', 'active'
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.confirm_hex_number(TEXT) TO authenticated;

-- Notify PostgREST to reload its schema cache immediately
NOTIFY pgrst, 'reload schema';
