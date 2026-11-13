-- Additive deployment of account-scoped chat deletion. Membership is checked inside
-- every SECURITY DEFINER function; a zero-message AI chat is authorized by its AI id.
CREATE TABLE IF NOT EXISTS public.conversation_deletions (
    account_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    conversation_id TEXT NOT NULL,
    deleted_through BIGINT NOT NULL DEFAULT 0 CHECK (deleted_through >= 0),
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, conversation_id)
);
ALTER TABLE public.conversation_deletions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.conversation_deletions FROM PUBLIC, anon;
DROP POLICY IF EXISTS "deletions_select_own" ON public.conversation_deletions;
CREATE POLICY "deletions_select_own" ON public.conversation_deletions FOR SELECT TO authenticated
USING (account_id = auth.uid());

CREATE OR REPLACE FUNCTION public.delete_conversation_for_me(p_conversation_id TEXT)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE v_uid UUID := auth.uid(); v_max BIGINT;
BEGIN
    IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE = '42501'; END IF;
    IF p_conversation_id IS NULL OR p_conversation_id !~ '^[A-Za-z0-9_-]{1,128}$' THEN
        RAISE EXCEPTION 'invalid conversation id' USING ERRCODE = '22023';
    END IF;
    IF p_conversation_id <> ('ai_' || v_uid::text)
       AND p_conversation_id <> ('self_' || v_uid::text)
       AND NOT EXISTS (SELECT 1 FROM public.conversation_members
                       WHERE conversation_id = p_conversation_id AND account_id = v_uid) THEN
        RAISE EXCEPTION 'not a member of this conversation' USING ERRCODE = '42501';
    END IF;
    SELECT COALESCE(MAX(sequence), 0) INTO v_max FROM public.messages WHERE conversation_id = p_conversation_id;
    INSERT INTO public.conversation_deletions (account_id, conversation_id, deleted_through)
    VALUES (v_uid, p_conversation_id, v_max)
    ON CONFLICT (account_id, conversation_id) DO UPDATE
    SET deleted_through = GREATEST(conversation_deletions.deleted_through, EXCLUDED.deleted_through), deleted_at = now();
    RETURN v_max;
END; $$;

CREATE OR REPLACE FUNCTION public.clear_conversation_messages(p_conversation_id TEXT)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE v_uid UUID := auth.uid(); v_max BIGINT;
BEGIN
    IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE = '42501'; END IF;
    IF p_conversation_id IS NULL OR p_conversation_id !~ '^[A-Za-z0-9_-]{1,128}$' THEN
        RAISE EXCEPTION 'invalid conversation id' USING ERRCODE = '22023';
    END IF;
    IF p_conversation_id <> ('ai_' || v_uid::text)
       AND p_conversation_id <> ('self_' || v_uid::text)
       AND NOT EXISTS (SELECT 1 FROM public.conversation_members
                       WHERE conversation_id = p_conversation_id AND account_id = v_uid) THEN
        RAISE EXCEPTION 'not a member of this conversation' USING ERRCODE = '42501';
    END IF;
    SELECT COALESCE(MAX(sequence), 0) INTO v_max FROM public.messages WHERE conversation_id = p_conversation_id;
    INSERT INTO public.conversation_deletions (account_id, conversation_id, deleted_through)
    VALUES (v_uid, p_conversation_id, v_max)
    ON CONFLICT (account_id, conversation_id) DO UPDATE
    SET deleted_through = GREATEST(conversation_deletions.deleted_through, EXCLUDED.deleted_through), deleted_at = now();
    RETURN v_max;
END; $$;

CREATE OR REPLACE FUNCTION public.delete_conversation_for_both(p_conversation_id TEXT)
RETURNS INTEGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE v_uid UUID := auth.uid(); v_count INTEGER;
BEGIN
    IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE = '42501'; END IF;
    IF p_conversation_id IS NULL OR p_conversation_id !~ '^[A-Za-z0-9_-]{1,128}$' THEN
        RAISE EXCEPTION 'invalid conversation id' USING ERRCODE = '22023';
    END IF;
    IF p_conversation_id <> ('ai_' || v_uid::text)
       AND p_conversation_id <> ('self_' || v_uid::text)
       AND NOT EXISTS (SELECT 1 FROM public.conversation_members
                       WHERE conversation_id = p_conversation_id AND account_id = v_uid) THEN
        RAISE EXCEPTION 'not a member of this conversation' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.messages WHERE conversation_id = p_conversation_id;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    DELETE FROM public.conversations WHERE id = p_conversation_id;
    RETURN v_count;
END; $$;

REVOKE ALL ON FUNCTION public.delete_conversation_for_me(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.clear_conversation_messages(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.delete_conversation_for_both(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_conversation_for_me(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.clear_conversation_messages(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.delete_conversation_for_both(TEXT) TO authenticated;
