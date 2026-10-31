-- ====================================================================
-- TELEGRAM AUTHENTICATION HARDENING MIGRATION
-- Project: HexShard Messenger
-- ====================================================================

-- 1. Ensure telegram_links table exists with proper constraints
CREATE TABLE IF NOT EXISTS public.telegram_links (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id UUID UNIQUE NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    telegram_user_id BIGINT UNIQUE NOT NULL,
    telegram_username TEXT,
    verified BOOLEAN NOT NULL DEFAULT true,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indices for rapid lookup by account and telegram ID
CREATE INDEX IF NOT EXISTS idx_telegram_links_account ON public.telegram_links (account_id);
CREATE INDEX IF NOT EXISTS idx_telegram_links_tg_user ON public.telegram_links (telegram_user_id);

-- Enable RLS
ALTER TABLE public.telegram_links ENABLE ROW LEVEL SECURITY;

-- SELECT policy: Users can only view their own linked Telegram identity
DROP POLICY IF EXISTS "Users can read their own Telegram link" ON public.telegram_links;
CREATE POLICY "Users can read their own Telegram link"
    ON public.telegram_links FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

-- 2. Ensure telegram_challenges table exists with complete schema
CREATE TABLE IF NOT EXISTS public.telegram_challenges (
    challenge_id TEXT PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    code_hash TEXT,
    telegram_user_id BIGINT,
    telegram_username TEXT,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    is_used BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Composite indices for active challenge lookup and user binding
CREATE INDEX IF NOT EXISTS idx_telegram_challenges_account ON public.telegram_challenges (account_id);
CREATE INDEX IF NOT EXISTS idx_telegram_challenges_expires ON public.telegram_challenges (expires_at);
CREATE INDEX IF NOT EXISTS idx_telegram_challenges_active ON public.telegram_challenges (account_id, is_used, expires_at);
CREATE INDEX IF NOT EXISTS idx_telegram_challenges_tg_user ON public.telegram_challenges (telegram_user_id);

-- Enable RLS
ALTER TABLE public.telegram_challenges ENABLE ROW LEVEL SECURITY;

-- SELECT policy: Authenticated users can view their own challenges
DROP POLICY IF EXISTS "Users can read their active challenges" ON public.telegram_challenges;
CREATE POLICY "Users can read their active challenges"
    ON public.telegram_challenges FOR SELECT
    TO authenticated
    USING (account_id = auth.uid());

-- Grants
GRANT SELECT ON public.telegram_links TO authenticated;
GRANT SELECT ON public.telegram_challenges TO authenticated;
GRANT ALL ON public.telegram_links TO service_role;
GRANT ALL ON public.telegram_challenges TO service_role;
