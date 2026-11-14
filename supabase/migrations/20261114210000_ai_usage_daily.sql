-- Durable daily AI quota (review: the in-memory dailyQuotaMap resets on every
-- isolate restart, so the 500/day cap was advisory at best). One atomic
-- SECURITY DEFINER RPC: upsert-increment per user per UTC day, fail-open is
-- decided by the CALLER (edge function keeps AI working on DB hiccups).
CREATE TABLE IF NOT EXISTS public.ai_usage_daily (
    account_id UUID NOT NULL,
    day DATE NOT NULL,
    used INT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, day)
);

ALTER TABLE public.ai_usage_daily ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.ai_usage_daily FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.ai_daily_quota_consume(p_account_id uuid, p_limit int)
RETURNS int LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_used int;
BEGIN
    INSERT INTO public.ai_usage_daily (account_id, day, used)
    VALUES (p_account_id, CURRENT_DATE, 1)
    ON CONFLICT (account_id, day) DO UPDATE
        SET used = ai_usage_daily.used + 1
    RETURNING used INTO v_used;
    RETURN v_used;
END $$;

REVOKE ALL ON FUNCTION public.ai_daily_quota_consume(uuid, int) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.ai_daily_quota_consume(uuid, int) TO authenticated;
