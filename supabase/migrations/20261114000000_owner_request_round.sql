-- Owner-request round: number change, public number lookup, number search,
-- and server-side protection of AI conversations.
--
-- 1. The three deletion RPCs previously exempted ai_<uid> conversations from the
--    membership check, which allowed deleting the AI assistant chat. The assistant
--    is a system chat: reject it outright (the client hides the menu items too).
-- 2. release_hex_number(): the missing half of the "change number" flow — frees the
--    account's active identity so reserve_hex_number can allocate the new one.
-- 3. get_public_hex_number(): profiles could not show a peer's HexShard ID because
--    RLS hides other users' active numbers. Expose the minimum: formatted number.
-- 4. search_users_by_number(): find an account by (part of) its +999 number without
--    weakening hex_numbers RLS, via a SECURITY DEFINER function returning only
--    public profile fields.

CREATE OR REPLACE FUNCTION public.delete_conversation_for_me(p_conversation_id TEXT)
RETURNS BIGINT LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE v_uid UUID := auth.uid(); v_max BIGINT;
BEGIN
    IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE = '42501'; END IF;
    IF p_conversation_id IS NULL OR p_conversation_id !~ '^[A-Za-z0-9_-]{1,128}$' THEN
        RAISE EXCEPTION 'invalid conversation id' USING ERRCODE = '22023';
    END IF;
    IF p_conversation_id = ('ai_' || v_uid::text) THEN
        RAISE EXCEPTION 'the AI assistant conversation cannot be deleted' USING ERRCODE = 'P0001';
    END IF;
    IF p_conversation_id <> ('self_' || v_uid::text)
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
    -- Clearing the AI chat's messages stays allowed; only deletion is forbidden.
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
    IF p_conversation_id = ('ai_' || v_uid::text) THEN
        RAISE EXCEPTION 'the AI assistant conversation cannot be deleted' USING ERRCODE = 'P0001';
    END IF;
    IF p_conversation_id <> ('self_' || v_uid::text)
       AND NOT EXISTS (SELECT 1 FROM public.conversation_members
                       WHERE conversation_id = p_conversation_id AND account_id = v_uid) THEN
        RAISE EXCEPTION 'not a member of this conversation' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.messages WHERE conversation_id = p_conversation_id;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    DELETE FROM public.conversations WHERE id = p_conversation_id;
    RETURN v_count;
END; $$;

-- Free the caller's active +999 identity so a new one can be reserved.
CREATE OR REPLACE FUNCTION public.release_hex_number()
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_released VARCHAR(8);
BEGIN
    IF v_uid IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE = '42501'; END IF;
    PERFORM pg_advisory_xact_lock(hashtext('hex_reserve_' || v_uid::text));
    SELECT raw_number INTO v_released FROM public.hex_numbers
    WHERE (owner_id = v_uid OR account_id = v_uid) AND status = 'active' LIMIT 1;
    IF v_released IS NULL THEN
        RAISE EXCEPTION 'No active +999 identity to release' USING ERRCODE = 'P0002';
    END IF;
    UPDATE public.hex_numbers
    SET status = 'available', owner_id = NULL, account_id = NULL,
        reserved_at = NULL, expires_at = NULL, updated_at = now()
    WHERE raw_number = v_released AND status = 'active';
    UPDATE public.profiles SET hex_number = '' WHERE id = v_uid;
    RETURN jsonb_build_object('released', v_released);
END; $$;

-- Minimum public projection of a peer's active identity (profile screens).
CREATE OR REPLACE FUNCTION public.get_public_hex_number(p_account_id UUID)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_row RECORD;
BEGIN
    IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE = '42501'; END IF;
    IF p_account_id IS NULL THEN RAISE EXCEPTION 'invalid account' USING ERRCODE = '22023'; END IF;
    SELECT h.formatted, p.username INTO v_row
    FROM public.hex_numbers h
    LEFT JOIN public.profiles p ON p.id = h.account_id
    WHERE h.account_id = p_account_id AND h.status = 'active' LIMIT 1;
    IF v_row IS NULL OR v_row.formatted IS NULL THEN
        RETURN jsonb_build_object('formatted', NULL, 'username', NULL);
    END IF;
    RETURN jsonb_build_object('formatted', v_row.formatted, 'username', v_row.username);
END; $$;

-- Find accounts by (part of) their active +999 number. 4..8 digits, or the full
-- 999XXXXXXXX form. Returns only public profile fields; authenticated callers only.
CREATE OR REPLACE FUNCTION public.search_users_by_number(p_query TEXT)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_digits TEXT;
    v_rows jsonb;
BEGIN
    IF auth.uid() IS NULL THEN RAISE EXCEPTION 'authentication required' USING ERRCODE = '42501'; END IF;
    v_digits := regexp_replace(coalesce(p_query, ''), '\D', '', 'g');
    IF char_length(v_digits) = 11 AND v_digits LIKE '999%' THEN
        v_digits := substring(v_digits FROM 4 FOR 8);
    END IF;
    IF char_length(v_digits) < 4 THEN
        RETURN jsonb_build_object('results', '[]'::jsonb);
    END IF;
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'account_id', h.account_id,
        'username', p.username,
        'display_name', p.display_name,
        'formatted', h.formatted
    ) ORDER BY h.raw_number), '[]'::jsonb)
    INTO v_rows
    FROM (
        SELECT * FROM public.hex_numbers
        WHERE status = 'active' AND char_length(raw_number) = 8
          AND raw_number LIKE '%' || v_digits || '%'
        ORDER BY CASE WHEN raw_number = right(v_digits, 8) THEN 0 ELSE 1 END, raw_number
        LIMIT 10
    ) h
    LEFT JOIN public.profiles p ON p.id = h.account_id;
    RETURN jsonb_build_object('results', v_rows);
END; $$;

REVOKE ALL ON FUNCTION public.delete_conversation_for_me(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.clear_conversation_messages(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.delete_conversation_for_both(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.release_hex_number() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.get_public_hex_number(UUID) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.search_users_by_number(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.delete_conversation_for_me(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.clear_conversation_messages(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.delete_conversation_for_both(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.release_hex_number() TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_public_hex_number(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.search_users_by_number(TEXT) TO authenticated;
