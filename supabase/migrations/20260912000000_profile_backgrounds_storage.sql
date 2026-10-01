-- Migration: 20260912000000_profile_backgrounds_storage.sql
-- Implements Supabase Storage bucket for profile backgrounds with RLS, 512 KB limit,
-- additive columns to public.profiles, updated resolve RPCs, and account deletion cleanup.

-- ====================================================================
-- 1. ADDITIVE COLUMNS TO public.profiles
-- ====================================================================
ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS profile_background_path TEXT DEFAULT NULL,
ADD COLUMN IF NOT EXISTS profile_background_type TEXT DEFAULT NULL,
ADD COLUMN IF NOT EXISTS profile_background_updated_at TIMESTAMPTZ DEFAULT NULL;

-- ====================================================================
-- 2. STORAGE BUCKET CONFIGURATION
-- Max file size: 512 KB (524288 bytes)
-- Allowed MIME types: images and WebM video
-- Public read: true (enables fast, public visual header streaming without credentials)
-- ====================================================================
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'profile-backgrounds',
    'profile-backgrounds',
    true,
    524288,
    ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'video/webm']
)
ON CONFLICT (id) DO UPDATE SET
    public = true,
    file_size_limit = 524288,
    allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'video/webm'];

-- ====================================================================
-- 3. STORAGE RLS POLICIES
-- Scoped strictly to the authenticated user's own directory (<user_id>/...)
-- ====================================================================
DROP POLICY IF EXISTS "Users can upload their own profile background" ON storage.objects;
CREATE POLICY "Users can upload their own profile background"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'profile-backgrounds'
    AND (name LIKE (auth.uid()::text || '/%'))
);

DROP POLICY IF EXISTS "Users can update their own profile background" ON storage.objects;
CREATE POLICY "Users can update their own profile background"
ON storage.objects FOR UPDATE
TO authenticated
USING (
    bucket_id = 'profile-backgrounds'
    AND (name LIKE (auth.uid()::text || '/%'))
)
WITH CHECK (
    bucket_id = 'profile-backgrounds'
    AND (name LIKE (auth.uid()::text || '/%'))
);

DROP POLICY IF EXISTS "Users can delete their own profile background" ON storage.objects;
CREATE POLICY "Users can delete their own profile background"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'profile-backgrounds'
    AND (name LIKE (auth.uid()::text || '/%'))
);

DROP POLICY IF EXISTS "Profile backgrounds are viewable by authenticated users" ON storage.objects;
CREATE POLICY "Profile backgrounds are viewable by authenticated users"
ON storage.objects FOR SELECT
TO authenticated
USING (
    bucket_id = 'profile-backgrounds'
);

-- ====================================================================
-- 4. UPDATE RPC: resolve_profile_share_token (v2 QR)
-- Returns profile_background_path and profile_background_type
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
    v_clean_token TEXT := trim(COALESCE(p_token, ''));
    v_token_hash TEXT;
    v_account_id UUID;
    v_expires_at TIMESTAMPTZ;
    v_revoked_at TIMESTAMPTZ;
    v_username TEXT;
    v_avatar_url TEXT;
    v_bg_path TEXT;
    v_bg_type TEXT;
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

    -- Fetch authoritative user profile with background metadata
    SELECT 
        p.username,
        COALESCE(p.avatar_url, ''),
        COALESCE(p.profile_background_path, ''),
        COALESCE(p.profile_background_type, '')
    INTO v_username, v_avatar_url, v_bg_path, v_bg_type
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
        'profile_background_path', COALESCE(v_bg_path, ''),
        'profile_background_type', COALESCE(v_bg_type, ''),
        'hex_number', COALESCE(v_hex_number, ''),
        'public_key', COALESCE(v_public_key, ''),
        'key_version', COALESCE(v_key_version, 1)
    );
END;
$$;

-- ====================================================================
-- 5. UPDATE RPC: resolve_profile_by_share_id
-- ====================================================================
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

-- ====================================================================
-- 6. UPDATE RPC: delete_user_account
-- Cleans up profile background files from storage.objects
-- ====================================================================
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

    -- 1. Clean profile backgrounds from storage.objects
    DELETE FROM storage.objects 
    WHERE bucket_id = 'profile-backgrounds' 
      AND (name LIKE (v_user_id::text || '/%'));

    -- 2. Put user's active numbers into cooldown
    UPDATE public.hex_numbers
    SET status = 'cooldown', owner_id = NULL, reserved_at = NULL, expires_at = NULL, updated_at = now()
    WHERE owner_id = v_user_id;

    -- 3. Clean number history
    DELETE FROM public.hex_number_history WHERE account_id = v_user_id;

    -- 4. Clean profile share tokens
    DELETE FROM public.profile_share_tokens WHERE account_id = v_user_id;

    -- 5. Clean attachments uploaded by caller
    DELETE FROM public.attachments WHERE owner_id = v_user_id;

    -- 6. Clean messages sent by caller or direct messages to caller
    DELETE FROM public.messages WHERE sender_id = v_user_id OR recipient_id = v_user_id::text;

    -- 7. Clean conversation memberships
    DELETE FROM public.conversation_members WHERE account_id = v_user_id;

    -- 8. Clean empty conversations created by caller
    DELETE FROM public.conversations WHERE created_by = v_user_id AND id NOT IN (SELECT conversation_id FROM public.conversation_members);

    -- 9. Clean devices
    DELETE FROM public.devices WHERE account_id = v_user_id;

    -- 10. Clean rate limits
    DELETE FROM public.rate_limits WHERE user_id = v_user_id;

    -- 11. Clean Telegram links & challenges
    DELETE FROM public.telegram_links WHERE account_id = v_user_id;
    DELETE FROM public.telegram_challenges WHERE account_id = v_user_id;

    -- 12. Delete profile (cascades where appropriate)
    DELETE FROM public.profiles WHERE id = v_user_id;

    -- 13. Attempt delete from auth.users (if privileges permit)
    BEGIN
        DELETE FROM auth.users WHERE id = v_user_id;
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    RETURN jsonb_build_object('success', true, 'deleted_user_id', v_user_id);
END;
$$;
