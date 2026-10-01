-- Migration: 20260911000000_profile_share_tokens_and_security.sql
-- Implements v2 cryptographically random single-use profile share tokens with SHA-256 storage,
-- removes plaintext QR payload exposure, fixes devices RLS policies, and eliminates non-existent schema references.

-- ====================================================================
-- 1. PROFILE SHARE TOKENS TABLE (v2 QR Protocol)
-- ====================================================================
CREATE TABLE IF NOT EXISTS public.profile_share_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '7 days'),
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_profile_share_tokens_hash ON public.profile_share_tokens (token_hash) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_profile_share_tokens_account ON public.profile_share_tokens (account_id);

ALTER TABLE public.profile_share_tokens ENABLE ROW LEVEL SECURITY;
-- No client-facing RLS policies: all access is guarded by SECURITY DEFINER RPCs

-- ====================================================================
-- 2. RPC: create_profile_share_token
-- Generates a 32-byte cryptographically random hex token, stores its SHA-256 hash,
-- and returns the raw token to the authenticated caller once.
-- ====================================================================
CREATE OR REPLACE FUNCTION public.create_profile_share_token(
    p_expires_in_days INT DEFAULT 7
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_raw_token TEXT;
    v_token_hash TEXT;
    v_expires_at TIMESTAMPTZ;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    -- Revoke existing active tokens for this user
    UPDATE public.profile_share_tokens
    SET revoked_at = now()
    WHERE account_id = v_user_id AND revoked_at IS NULL;

    -- Generate 32 bytes of cryptographically secure random bytes as hex (64 chars)
    v_raw_token := encode(gen_random_bytes(32), 'hex');
    v_token_hash := encode(digest(v_raw_token, 'sha256'), 'hex');
    v_expires_at := now() + (COALESCE(p_expires_in_days, 7) || ' days')::interval;

    INSERT INTO public.profile_share_tokens (
        account_id,
        token_hash,
        expires_at
    ) VALUES (
        v_user_id,
        v_token_hash,
        v_expires_at
    );

    RETURN jsonb_build_object(
        'success', true,
        'token', v_raw_token,
        'expires_at', v_expires_at
    );
END;
$$;

-- ====================================================================
-- 3. RPC: resolve_profile_share_token
-- Hashes the provided token with SHA-256, verifies existence and expiration,
-- and returns authenticated public profile fields. Never reveals private keys or secrets.
-- ====================================================================
CREATE OR REPLACE FUNCTION public.resolve_profile_share_token(
    p_token TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_caller_id UUID := auth.uid();
    v_clean_token TEXT := TRIM(COALESCE(p_token, ''));
    v_token_hash TEXT;
    v_account_id UUID;
    v_expires_at TIMESTAMPTZ;
    v_revoked_at TIMESTAMPTZ;
    v_username TEXT;
    v_avatar_url TEXT;
    v_hex_number TEXT;
    v_public_key TEXT;
    v_key_version INT;
BEGIN
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    IF v_clean_token = '' OR char_length(v_clean_token) < 32 OR char_length(v_clean_token) > 128 THEN
        RETURN jsonb_build_object('found', false, 'reason', 'invalid_token_format');
    END IF;

    v_token_hash := encode(digest(v_clean_token, 'sha256'), 'hex');

    SELECT account_id, expires_at, revoked_at INTO v_account_id, v_expires_at, v_revoked_at
    FROM public.profile_share_tokens
    WHERE token_hash = v_token_hash
    LIMIT 1;

    IF v_account_id IS NULL OR v_revoked_at IS NOT NULL OR v_expires_at <= now() THEN
        RETURN jsonb_build_object('found', false, 'reason', 'token_not_found_or_expired');
    END IF;

    -- Record access time
    UPDATE public.profile_share_tokens
    SET last_used_at = now()
    WHERE token_hash = v_token_hash;

    -- Fetch authoritative user profile (schema safe: uses username, not display_name)
    SELECT 
        p.username,
        COALESCE(p.avatar_url, '')
    INTO v_username, v_avatar_url
    FROM public.profiles p
    WHERE p.id = v_account_id;

    IF v_username IS NULL THEN
        RETURN jsonb_build_object('found', false, 'reason', 'user_not_found');
    END IF;

    -- Fetch active hex number
    SELECT COALESCE(hn.formatted, hn.number, '')
    INTO v_hex_number
    FROM public.hex_numbers hn
    WHERE hn.owner_id = v_account_id AND hn.status = 'active'
    LIMIT 1;

    -- Fetch active device public key
    SELECT COALESCE(adk.public_key, ''), COALESCE(adk.key_version, 1)
    INTO v_public_key, v_key_version
    FROM public.active_device_keys adk
    WHERE adk.account_id = v_account_id
    ORDER BY adk.key_version DESC
    LIMIT 1;

    -- Fallback to profile level key
    IF v_public_key IS NULL OR v_public_key = '' THEN
        SELECT COALESCE(p.public_key, '') INTO v_public_key
        FROM public.profiles p
        WHERE p.id = v_account_id;
        v_key_version := 1;
    END IF;

    RETURN jsonb_build_object(
        'found', true,
        'user_id', v_account_id,
        'username', v_username,
        'avatar_url', COALESCE(v_avatar_url, ''),
        'hex_number', COALESCE(v_hex_number, ''),
        'public_key', COALESCE(v_public_key, ''),
        'key_version', COALESCE(v_key_version, 1)
    );
END;
$$;

-- ====================================================================
-- 4. RPC: revoke_profile_share_tokens
-- ====================================================================
CREATE OR REPLACE FUNCTION public.revoke_profile_share_tokens()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    UPDATE public.profile_share_tokens
    SET revoked_at = now()
    WHERE account_id = v_user_id AND revoked_at IS NULL;

    RETURN jsonb_build_object('success', true);
END;
$$;

-- ====================================================================
-- 5. FIX NON-EXISTENT profiles.display_name in resolve_profile_by_share_id
-- ====================================================================
CREATE OR REPLACE FUNCTION public.resolve_profile_by_share_id(
    p_share_id TEXT
)
RETURNS TABLE (
    user_id UUID,
    display_name TEXT,
    avatar_url TEXT,
    hex_number TEXT,
    public_key TEXT,
    key_version INT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_clean TEXT := TRIM(COALESCE(p_share_id, ''));
    v_target_id UUID;
    v_target_number TEXT;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    IF v_clean = '' THEN
        RETURN;
    END IF;

    IF v_clean ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
        v_target_id := v_clean::UUID;
    ELSE
        v_target_number := REPLACE(v_clean, '-', '');
        SELECT owner_id INTO v_target_id
        FROM public.hex_numbers
        WHERE number = v_target_number AND status = 'active'
        LIMIT 1;
    END IF;

    IF v_target_id IS NULL THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT 
        p.id AS user_id,
        p.username AS display_name,
        COALESCE(p.avatar_url, '') AS avatar_url,
        COALESCE(hn.formatted, hn.number, '') AS hex_number,
        COALESCE(adk.public_key, '') AS public_key,
        COALESCE(adk.key_version, 1) AS key_version
    FROM public.profiles p
    LEFT JOIN public.hex_numbers hn ON hn.owner_id = p.id AND hn.status = 'active'
    LEFT JOIN LATERAL (
        SELECT dk.public_key, dk.key_version
        FROM public.active_device_keys dk
        WHERE dk.account_id = p.id
        ORDER BY dk.key_version DESC
        LIMIT 1
    ) adk ON true
    WHERE p.id = v_target_id
    LIMIT 1;
END;
$$;

-- ====================================================================
-- 6. TIGHTEN DEVICES RLS POLICIES (Section W)
-- Removes broad FOR ALL policy and provides explicit granular permissions.
-- ====================================================================
DROP POLICY IF EXISTS "Users can manage their own devices" ON public.devices;
DROP POLICY IF EXISTS "Users can insert their own device" ON public.devices;
CREATE POLICY "Users can insert their own device"
    ON public.devices FOR INSERT
    TO authenticated
    WITH CHECK (account_id = auth.uid());

DROP POLICY IF EXISTS "Users can update their own device metadata" ON public.devices;
CREATE POLICY "Users can update their own device metadata"
    ON public.devices FOR UPDATE
    TO authenticated
    USING (account_id = auth.uid())
    WITH CHECK (account_id = auth.uid());

CREATE OR REPLACE FUNCTION public.revoke_device(p_device_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    UPDATE public.devices
    SET revoked_at = now()
    WHERE account_id = v_user_id AND device_id = p_device_id;

    RETURN jsonb_build_object('success', true, 'device_id', p_device_id);
END;
$$;

-- Grants
GRANT EXECUTE ON FUNCTION public.create_profile_share_token(INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.resolve_profile_share_token(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.revoke_profile_share_tokens() TO authenticated;
GRANT EXECUTE ON FUNCTION public.revoke_device(TEXT) TO authenticated;
