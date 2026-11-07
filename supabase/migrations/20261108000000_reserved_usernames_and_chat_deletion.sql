-- ============================================================================
-- Reserved usernames registry + account-scoped chat deletion (v1.7.0)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. RESERVED USERNAMES (owner-granted, service-role only)
-- Security model for an OPEN-SOURCE product: nothing here relies on secrecy.
-- The table is invisible to clients (RLS on, all grants revoked), the sign-up
-- flow checks it server-side, and only the operator (service_role / SQL editor)
-- can grant a reserved name to a concrete account by user id.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.reserved_usernames (
    username     TEXT PRIMARY KEY,                          -- lowercased name
    reserved_for UUID REFERENCES auth.users(id) ON DELETE CASCADE,
    reason       TEXT NOT NULL DEFAULT 'brand',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.reserved_usernames ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.reserved_usernames FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.reserved_usernames TO service_role;

-- Seed brand / system names (lowercased). Idempotent seeding.
INSERT INTO public.reserved_usernames (username, reason) VALUES
    ('ventaxis', 'brand'), ('ventaxisai', 'brand'), ('ventaxis_ai', 'brand'),
    ('hexagon', 'brand'), ('hexagonai', 'brand'), ('hexagon_ai', 'brand'),
    ('hexshard', 'brand'), ('hexshardai', 'brand'), ('hexshard_ai', 'brand'),
    ('ventaxiscorp', 'brand'), ('ventaxis_corp', 'brand'),
    ('support', 'system'), ('help', 'system'), ('security', 'system'),
    ('admin', 'system'), ('administrator', 'system'), ('moderator', 'system'),
    ('root', 'system'), ('system', 'system'), ('official', 'system'),
    ('staff', 'system'), ('team', 'system'), ('corp', 'system'),
    ('ai', 'system'), ('ai_assistant', 'system'), ('bot', 'system'),
    ('noreply', 'system'), ('admin_hexshard', 'system')
ON CONFLICT (username) DO NOTHING;

-- Owner-facing grant RPC: assigns a reserved username to a concrete account.
-- Callable ONLY with a service_role JWT — never exposed to clients by RLS/GRANT.
CREATE OR REPLACE FUNCTION public.admin_assign_reserved_username(
    p_username TEXT,
    p_account_id UUID
) RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF current_setting('request.jwt.claim.role', true) IS DISTINCT FROM 'service_role' THEN
        RAISE EXCEPTION 'service role only';
    END IF;
    IF p_username IS NULL OR p_username !~ '^[a-z0-9_]{3,30}$' THEN
        RAISE EXCEPTION 'invalid username format';
    END IF;
    INSERT INTO public.reserved_usernames (username, reserved_for, reason)
    VALUES (lower(p_username), p_account_id, 'owner-grant')
    ON CONFLICT (username) DO UPDATE SET reserved_for = EXCLUDED.reserved_for;
    RETURN lower(p_username) || ' reserved for ' || p_account_id::text;
END;
$$;
REVOKE ALL ON FUNCTION public.admin_assign_reserved_username(TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.admin_assign_reserved_username(TEXT, UUID) TO service_role;

-- Server-side enforcement: profiles.username / normalized_username must not use a
-- reserved name, unless the row's account is exactly the account the name was
-- granted to (reserved_for). Raises 23505 so clients can map it to "username taken".
CREATE OR REPLACE FUNCTION public.enforce_reserved_usernames()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    reserved_for_uuid UUID;
BEGIN
    SELECT reserved_for INTO reserved_for_uuid
    FROM public.reserved_usernames
    WHERE username = lower(COALESCE(NEW.username, ''))
       OR username = lower(COALESCE(NEW.normalized_username, ''))
    LIMIT 1;

    IF reserved_for_uuid IS NOT NULL AND reserved_for_uuid IS DISTINCT FROM auth.uid() THEN
        RAISE EXCEPTION 'username is reserved by the operator'
            USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_enforce_reserved_usernames ON public.profiles;
CREATE TRIGGER trg_enforce_reserved_usernames
    BEFORE INSERT OR UPDATE OF username, normalized_username ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION public.enforce_reserved_usernames();

-- ---------------------------------------------------------------------------
-- 2. ACCOUNT-SCOPED CHAT DELETION ("for me" / "for both") and per-user
--    message clearing ("delete all messages, keep the chat").
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.conversation_deletions (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id     UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    conversation_id TEXT NOT NULL,
    deleted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    scope          TEXT NOT NULL DEFAULT 'self',   -- 'self' | 'cleared'
    UNIQUE (account_id, conversation_id)
);
CREATE INDEX IF NOT EXISTS idx_conv_deletions_account
    ON public.conversation_deletions(account_id, conversation_id);
ALTER TABLE public.conversation_deletions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.conversation_deletions FROM PUBLIC, anon;
CREATE POLICY "deletions_select_own"
    ON public.conversation_deletions FOR SELECT TO authenticated
    USING (account_id = auth.uid());

-- "Delete for me": marker row only. Local client wipes its own copy; the sync
-- catch-up consults this marker and skips everything older than deleted_at.
CREATE OR REPLACE FUNCTION public.delete_conversation_for_me(p_conversation_id TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    max_seq BIGINT;
BEGIN
    IF p_conversation_id IS NULL OR p_conversation_id !~ '^[A-Za-z0-9_-]{1,128}$' THEN
        RAISE EXCEPTION 'invalid conversation id';
    END IF;
    SELECT COALESCE(MAX(sequence), 0) INTO max_seq FROM public.messages WHERE conversation_id = p_conversation_id;
    INSERT INTO public.conversation_deletions (account_id, conversation_id, scope, deleted_at)
    VALUES (auth.uid(), p_conversation_id, 'self', now())
    ON CONFLICT (account_id, conversation_id)
    DO UPDATE SET deleted_at = now(), scope = 'self';
    RETURN max_seq;
END;
$$;
REVOKE ALL ON FUNCTION public.delete_conversation_for_me(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_conversation_for_me(TEXT) TO authenticated;

-- "Clear messages, keep the chat" (for me): same marker semantics, keeps the chat row.
CREATE OR REPLACE FUNCTION public.clear_conversation_messages(p_conversation_id TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    max_seq BIGINT;
BEGIN
    IF p_conversation_id IS NULL OR p_conversation_id !~ '^[A-Za-z0-9_-]{1,128}$' THEN
        RAISE EXCEPTION 'invalid conversation id';
    END IF;
    SELECT COALESCE(MAX(sequence), 0) INTO max_seq FROM public.messages WHERE conversation_id = p_conversation_id;
    INSERT INTO public.conversation_deletions (account_id, conversation_id, scope, deleted_at)
    VALUES (auth.uid(), p_conversation_id, 'cleared', now())
    ON CONFLICT (account_id, conversation_id)
    DO UPDATE SET deleted_at = now(), scope = 'cleared';
    RETURN max_seq;
END;
$$;
REVOKE ALL ON FUNCTION public.clear_conversation_messages(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.clear_conversation_messages(TEXT) TO authenticated;

-- "Delete for both": hard-removes messages of the conversation from the server for
-- EVERY member. Authorization: the caller must be a member (RLS-backed subquery on
-- messages is insufficient because the delete bypasses RLS as a security-definer
-- function, so membership is re-checked explicitly).
CREATE OR REPLACE FUNCTION public.delete_conversation_for_both(p_conversation_id TEXT)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    is_member BOOLEAN;
    removed   INTEGER;
BEGIN
    IF p_conversation_id IS NULL OR p_conversation_id !~ '^[A-Za-z0-9_-]{1,128}$' THEN
        RAISE EXCEPTION 'invalid conversation id';
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM public.messages
        WHERE conversation_id = p_conversation_id
          AND (sender_id = auth.uid() OR recipient_id = auth.uid())
        LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM public.conversations
        WHERE id = p_conversation_id
          AND created_by = auth.uid()
        LIMIT 1
    ) INTO is_member;

    IF NOT is_member THEN
        RAISE EXCEPTION 'not a member of this conversation';
    END IF;

    DELETE FROM public.messages WHERE conversation_id = p_conversation_id;
    GET DIAGNOSTICS removed = ROW_COUNT;
    DELETE FROM public.conversations WHERE id = p_conversation_id;
    RETURN removed;
END;
$$;
REVOKE ALL ON FUNCTION public.delete_conversation_for_both(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_conversation_for_both(TEXT) TO authenticated;
