-- Developer-mode audit trail. Written by the dev-mode edge function only
-- (service role); clients have no grants and no policies — nothing leaks.

CREATE TABLE IF NOT EXISTS public.dev_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id UUID NOT NULL,
    action TEXT NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.dev_audit ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.dev_audit FROM PUBLIC, anon, authenticated;
