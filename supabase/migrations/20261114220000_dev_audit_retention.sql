-- Review backlog: dev_audit grows unbounded. Opportunistic purge runs inside
-- dev_rate_limit (already called on every dev-mode action) — cheap single
-- statement, no cron needed.
CREATE OR REPLACE FUNCTION public.dev_rate_limit(p_account_id uuid, p_action text, p_details jsonb DEFAULT '{}'::jsonb)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_count int;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('dev_rate_' || p_account_id::text));
    DELETE FROM public.dev_audit WHERE created_at < now() - interval '30 days';
    SELECT count(*) INTO v_count FROM public.dev_audit
    WHERE account_id = p_account_id AND created_at > now() - interval '1 hour';
    IF v_count >= 30 THEN RETURN false; END IF;
    INSERT INTO public.dev_audit (account_id, action, details)
    VALUES (p_account_id, p_action, p_details);
    RETURN true;
END $$;
