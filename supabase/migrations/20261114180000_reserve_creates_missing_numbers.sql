-- Owner bug report 2026-11-14: "хочу поменять номер, выбираю его — unavailable,
-- хотя номер свободный, так на все номера". Evidence: hex_numbers held only 6
-- rows total — any pick outside the tiny seeded pool had no row at all, and the
-- strict preferred path raised P0002 on a missing row. A free number that is
-- merely absent must be CREATED and reserved, not rejected.

CREATE OR REPLACE FUNCTION public.reserve_hex_number(p_preferred text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_clean_pref VARCHAR(8);
    v_selected_raw VARCHAR(8);
    v_selected_formatted VARCHAR(24);
    v_expires_at TIMESTAMPTZ;
    v_existing_raw VARCHAR(8);
    v_existing_formatted VARCHAR(24);
    v_existing_expires TIMESTAMPTZ;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('hex_reserve_' || v_user_id::text));
    UPDATE public.hex_numbers
    SET status = 'available', owner_id = NULL, account_id = NULL, reserved_at = NULL, expires_at = NULL, updated_at = now()
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'reserved' AND expires_at < now();

    SELECT raw_number, formatted INTO v_existing_raw, v_existing_formatted
    FROM public.hex_numbers
    WHERE (owner_id = v_user_id OR account_id = v_user_id) AND status = 'active' LIMIT 1;
    IF v_existing_raw IS NOT NULL THEN
        RAISE EXCEPTION 'Account already has an active +999 identity' USING ERRCODE = 'P0002';
    END IF;

    SELECT raw_number, formatted, expires_at INTO v_existing_raw, v_existing_formatted, v_existing_expires
    FROM public.hex_numbers
    WHERE (owner_id = v_user_id OR account_id = v_user_id)
      AND status = 'reserved' AND expires_at > now() LIMIT 1;

    IF p_preferred IS NOT NULL AND btrim(p_preferred) <> '' THEN
        v_clean_pref := regexp_replace(p_preferred, '\D', '', 'g');
        IF char_length(v_clean_pref) = 11 AND v_clean_pref LIKE '999%' THEN
            v_clean_pref := substring(v_clean_pref FROM 4 FOR 8);
        END IF;
        IF char_length(v_clean_pref) <> 8 THEN
            RAISE EXCEPTION 'A +999 identity must contain exactly 8 digits' USING ERRCODE = '22023';
        END IF;
        IF v_existing_raw IS NOT NULL AND v_existing_raw <> v_clean_pref THEN
            UPDATE public.hex_numbers
            SET status = 'available', owner_id = NULL, account_id = NULL, reserved_at = NULL, expires_at = NULL, updated_at = now()
            WHERE raw_number = v_existing_raw AND status = 'reserved'
              AND (owner_id = v_user_id OR account_id = v_user_id);
        END IF;
        IF v_existing_raw = v_clean_pref AND v_existing_raw IS NOT NULL THEN
            -- Re-reserving the number already pending for this account: idempotent.
            UPDATE public.hex_numbers
            SET reserved_at = now(), expires_at = now() + INTERVAL '15 minutes', updated_at = now()
            WHERE raw_number = v_existing_raw AND status = 'reserved';
            RETURN jsonb_build_object('raw_number', v_existing_raw, 'formatted', v_existing_formatted,
                'expires_at', now() + INTERVAL '15 minutes', 'status', 'reserved');
        END IF;
        SELECT raw_number, formatted INTO v_selected_raw, v_selected_formatted
        FROM public.hex_numbers
        WHERE (raw_number = v_clean_pref OR number = v_clean_pref)
          AND (status = 'available' OR (status = 'reserved' AND (owner_id = v_user_id OR account_id = v_user_id)))
        FOR UPDATE LIMIT 1;
        IF v_selected_raw IS NULL THEN
            -- The number may simply not exist yet. Create it unless it is held
            -- (active/reserved-by-other) — the unique index arbitrates races.
            BEGIN
                INSERT INTO public.hex_numbers
                    (number, raw_number, formatted, status, owner_id, account_id, reserved_at, expires_at, created_at, updated_at)
                VALUES
                    (v_clean_pref, v_clean_pref,
                     '+999 ' || substring(v_clean_pref FROM 1 FOR 4) || ' ' || substring(v_clean_pref FROM 5 FOR 4),
                     'reserved', v_user_id, v_user_id, now(), now() + INTERVAL '15 minutes', now(), now());
                v_selected_raw := v_clean_pref;
                v_selected_formatted := '+999 ' || substring(v_clean_pref FROM 1 FOR 4) || ' ' || substring(v_clean_pref FROM 5 FOR 4);
            EXCEPTION WHEN unique_violation THEN
                v_selected_raw := NULL;
            END;
            IF v_selected_raw IS NULL THEN
                -- Row exists but was not selectable: held by someone else or active.
                SELECT raw_number, formatted INTO v_selected_raw, v_selected_formatted
                FROM public.hex_numbers
                WHERE (raw_number = v_clean_pref OR number = v_clean_pref)
                  AND (status = 'available' OR (status = 'reserved' AND (owner_id = v_user_id OR account_id = v_user_id)))
                FOR UPDATE LIMIT 1;
            END IF;
        END IF;
        IF v_selected_raw IS NULL THEN
            RAISE EXCEPTION 'The requested +999 identity is unavailable. Choose another number.' USING ERRCODE = 'P0002';
        END IF;
        v_expires_at := now() + INTERVAL '15 minutes';
        UPDATE public.hex_numbers
        SET status = 'reserved', owner_id = v_user_id, account_id = v_user_id,
            reserved_at = now(), expires_at = v_expires_at, updated_at = now()
        WHERE raw_number = v_selected_raw;
        RETURN jsonb_build_object('raw_number', v_selected_raw, 'formatted', v_selected_formatted,
            'expires_at', v_expires_at, 'status', 'reserved');
    END IF;

    IF v_existing_raw IS NOT NULL THEN
        RETURN jsonb_build_object('raw_number', v_existing_raw, 'formatted', v_existing_formatted,
            'expires_at', v_existing_expires, 'status', 'reserved', 'idempotent', true);
    END IF;
    SELECT raw_number, formatted INTO v_selected_raw, v_selected_formatted
    FROM public.hex_numbers WHERE status = 'available' AND char_length(raw_number) = 8
    FOR UPDATE SKIP LOCKED LIMIT 1;
    IF v_selected_raw IS NULL THEN
        FOR i IN 1..50 LOOP
            v_selected_raw := lpad((floor(random() * 90000000) + 10000000)::text, 8, '0');
            v_selected_formatted := '+999 ' || substring(v_selected_raw FROM 1 FOR 4) || ' ' || substring(v_selected_raw FROM 5 FOR 4);
            BEGIN
                INSERT INTO public.hex_numbers (number, raw_number, formatted, status, owner_id, account_id, reserved_at, expires_at, created_at, updated_at)
                VALUES (v_selected_raw, v_selected_raw, v_selected_formatted, 'reserved', v_user_id, v_user_id, now(), now() + INTERVAL '15 minutes', now(), now());
                v_expires_at := now() + INTERVAL '15 minutes';
                EXIT;
            EXCEPTION WHEN unique_violation THEN v_selected_raw := NULL;
            END;
        END LOOP;
        IF v_selected_raw IS NULL THEN
            RAISE EXCEPTION 'Failed to allocate unique +999 identity. Please try again.' USING ERRCODE = '53400';
        END IF;
    ELSE
        v_expires_at := now() + INTERVAL '15 minutes';
        UPDATE public.hex_numbers SET status = 'reserved', owner_id = v_user_id, account_id = v_user_id,
            reserved_at = now(), expires_at = v_expires_at, updated_at = now()
        WHERE raw_number = v_selected_raw;
    END IF;
    RETURN jsonb_build_object('raw_number', v_selected_raw, 'formatted', v_selected_formatted,
        'expires_at', v_expires_at, 'status', 'reserved');
END; $$;
