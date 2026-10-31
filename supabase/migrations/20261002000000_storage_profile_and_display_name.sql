-- Migration: 20261002000000_storage_profile_and_display_name.sql
-- Forensic repair:
-- 1. display_name column in public.profiles table
-- 2. Supabase Storage buckets: avatars, profile-backgrounds (2 MiB limit, image/* and video/*), chat-attachments
-- 3. Storage RLS policies for avatars, profile-backgrounds, and chat-attachments
-- 4. Storage helper functions & permissions

-- ====================================================================
-- 1. ADD COLUMNS TO public.profiles (display_name, date_of_birth, avatar_url, hex_number, backgrounds)
-- ====================================================================
ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS display_name TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS date_of_birth TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS avatar_url TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS avatar_storage_path TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS hex_number TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS profile_background_path TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS profile_background_type TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS profile_background_updated_at TIMESTAMPTZ;

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS background_storage_path TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS background_mime_type TEXT DEFAULT '';

ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS background_updated_at TIMESTAMPTZ;

-- Backfill display_name from username if empty
UPDATE public.profiles
SET display_name = username
WHERE display_name IS NULL OR display_name = '';

-- ====================================================================
-- 2. STORAGE BUCKETS CONFIGURATION
-- ====================================================================

-- 2.1. 'avatars' bucket (Public read, max 5 MiB, all standard images)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'avatars',
    'avatars',
    true,
    5242880,
    ARRAY[
        'image/jpeg', 'image/png', 'image/webp', 'image/gif',
        'image/bmp', 'image/heic', 'image/heif', 'image/avif', 'image/svg+xml'
    ]
)
ON CONFLICT (id) DO UPDATE SET
    public = true,
    file_size_limit = 5242880,
    allowed_mime_types = ARRAY[
        'image/jpeg', 'image/png', 'image/webp', 'image/gif',
        'image/bmp', 'image/heic', 'image/heif', 'image/avif', 'image/svg+xml'
    ];

-- 2.2. 'profile-backgrounds' bucket (Public read, 2 MiB = 2097152 bytes, all standard images & videos)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'profile-backgrounds',
    'profile-backgrounds',
    true,
    2097152,
    ARRAY[
        'image/jpeg', 'image/png', 'image/webp', 'image/gif',
        'image/bmp', 'image/heic', 'image/heif', 'image/avif', 'image/svg+xml',
        'video/webm', 'video/mp4', 'video/quicktime', 'video/3gpp', 'video/x-matroska'
    ]
)
ON CONFLICT (id) DO UPDATE SET
    public = true,
    file_size_limit = 2097152,
    allowed_mime_types = ARRAY[
        'image/jpeg', 'image/png', 'image/webp', 'image/gif',
        'image/bmp', 'image/heic', 'image/heif', 'image/avif', 'image/svg+xml',
        'video/webm', 'video/mp4', 'video/quicktime', 'video/3gpp', 'video/x-matroska'
    ];

-- 2.3. 'chat-attachments' bucket (Private, max 50 MiB)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'chat-attachments',
    'chat-attachments',
    false,
    52428800,
    NULL
)
ON CONFLICT (id) DO UPDATE SET
    public = false,
    file_size_limit = 52428800;

-- ====================================================================
-- 3. STORAGE RLS POLICIES FOR 'avatars'
-- ====================================================================
DROP POLICY IF EXISTS "Public can view avatars" ON storage.objects;
CREATE POLICY "Public can view avatars"
ON storage.objects FOR SELECT
USING (bucket_id = 'avatars');

DROP POLICY IF EXISTS "Users can upload their own avatar" ON storage.objects;
CREATE POLICY "Users can upload their own avatar"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'avatars'
    AND (name LIKE (auth.uid()::text || '/%'))
);

DROP POLICY IF EXISTS "Users can update their own avatar" ON storage.objects;
CREATE POLICY "Users can update their own avatar"
ON storage.objects FOR UPDATE
TO authenticated
USING (
    bucket_id = 'avatars'
    AND (name LIKE (auth.uid()::text || '/%'))
);

DROP POLICY IF EXISTS "Users can delete their own avatar" ON storage.objects;
CREATE POLICY "Users can delete their own avatar"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'avatars'
    AND (name LIKE (auth.uid()::text || '/%'))
);

-- ====================================================================
-- 4. STORAGE RLS POLICIES FOR 'profile-backgrounds'
-- ====================================================================
DROP POLICY IF EXISTS "Public can view profile backgrounds" ON storage.objects;
CREATE POLICY "Public can view profile backgrounds"
ON storage.objects FOR SELECT
USING (bucket_id = 'profile-backgrounds');

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
);

DROP POLICY IF EXISTS "Users can delete their own profile background" ON storage.objects;
CREATE POLICY "Users can delete their own profile background"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'profile-backgrounds'
    AND (name LIKE (auth.uid()::text || '/%'))
);

-- ====================================================================
-- 5. STORAGE RLS POLICIES FOR 'chat-attachments'
-- ====================================================================
DROP POLICY IF EXISTS "Authenticated users can read attachments" ON storage.objects;
CREATE POLICY "Authenticated users can read attachments"
ON storage.objects FOR SELECT
TO authenticated
USING (bucket_id = 'chat-attachments');

DROP POLICY IF EXISTS "Authenticated users can upload attachments" ON storage.objects;
CREATE POLICY "Authenticated users can upload attachments"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (bucket_id = 'chat-attachments');

DROP POLICY IF EXISTS "Authenticated users can delete their attachments" ON storage.objects;
CREATE POLICY "Authenticated users can delete their attachments"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'chat-attachments'
    AND (owner = auth.uid() OR (name LIKE (auth.uid()::text || '/%')))
);

-- Notify PostgREST to reload schema cache
NOTIFY pgrst, 'reload schema';
