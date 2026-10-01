-- Secure devices table RLS and active_device_keys view
-- Restricts full device metadata to the owner and provides a sanitized public keys view

DROP POLICY IF EXISTS "Users can read all devices for public keys" ON public.devices;
DROP POLICY IF EXISTS "Users can read their own devices" ON public.devices;

CREATE POLICY "Users can read their own devices"
    ON public.devices FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

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
