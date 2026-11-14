-- Owner bug report 2026-11-14: fresh oooo→oopp messages showed
-- "Message integrity verification failed: tampered ciphertext".
-- Root cause: the crypto model owns exactly ONE ECDH keypair per ACCOUNT
-- (Keystore alias = accountId), but devices allowed several rows per account
-- (PK is (account_id, device_id) and the client regenerates device_id on
-- re-login). get_active_device_keys ordered by key_version DESC — all rows
-- share version 1, so the tie was broken arbitrarily and peers could fetch a
-- STALE public key → shared-secret mismatch → tampered ciphertext.
--
-- 1. Keep only the newest device row per account (its key is the live one).
-- 2. Enforce one row per account going forward (unique index).
-- 3. Make the RPC deterministic: newest row first.

DELETE FROM public.devices d
WHERE EXISTS (
    SELECT 1 FROM public.devices d2
    WHERE d2.account_id = d.account_id AND d2.created_at > d.created_at
);

CREATE UNIQUE INDEX IF NOT EXISTS devices_account_id_unique ON public.devices(account_id);

CREATE OR REPLACE FUNCTION public.get_active_device_keys(p_account_id uuid, p_device_id text DEFAULT NULL::text)
RETURNS TABLE(account_id uuid, device_id text, public_key text, signing_key text, key_version integer)
LANGUAGE sql
STABLE SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
    SELECT d.account_id, d.device_id, d.public_key, d.signing_key, d.key_version
    FROM public.devices d
    WHERE d.account_id = p_account_id
      AND d.revoked_at IS NULL
      AND (p_device_id IS NULL OR d.device_id = p_device_id)
    ORDER BY d.created_at DESC;
$function$;
