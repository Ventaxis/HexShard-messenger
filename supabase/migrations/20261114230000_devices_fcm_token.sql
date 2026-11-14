-- FCM push tokens: one column per device row; client updates its own row
-- after login and on token refresh (RLS WITH CHECK account_id = auth.uid()).
ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS fcm_token TEXT;
