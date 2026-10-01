-- Migration: 20260910000000_idempotency_location_qr_v12.sql
-- Fixes message idempotency, race-conditions, membership authorization, and provides secure QR profile lookup.

-- 1. Atomic Idempotent Message Sending with Strict Pre-Idempotency Authorization & Conflict Safe Handling
CREATE OR REPLACE FUNCTION public.send_message_idempotent(
    p_conversation_id TEXT,
    p_client_message_id TEXT,
    p_payload TEXT,
    p_signature TEXT DEFAULT '',
    p_recipient_id TEXT DEFAULT NULL,
    p_type TEXT DEFAULT 'text',
    p_time_str TEXT DEFAULT '',
    p_encryption_version INT DEFAULT 2
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_seq BIGINT;
    v_msg_id TEXT := p_client_message_id;
    v_existing_id TEXT;
    v_existing_seq BIGINT;
    v_existing_status TEXT;
    v_inserted_id TEXT;
    v_inserted_seq BIGINT;
BEGIN
    -- 1. Fail-closed caller authentication check
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    IF p_conversation_id IS NULL OR TRIM(p_conversation_id) = '' THEN
        RAISE EXCEPTION 'Invalid conversation_id' USING ERRCODE = '22000';
    END IF;

    IF p_client_message_id IS NULL OR TRIM(p_client_message_id) = '' THEN
        RAISE EXCEPTION 'Invalid client_message_id' USING ERRCODE = '22000';
    END IF;

    -- 2. Ensure conversation exists and caller is authorized to create / participate
    IF NOT EXISTS (SELECT 1 FROM public.conversations WHERE id = p_conversation_id) THEN
        IF p_conversation_id LIKE 'self_%' THEN
            IF p_conversation_id != ('self_' || v_user_id::text) THEN
                RAISE EXCEPTION 'Invalid self conversation ID for caller' USING ERRCODE = '42501';
            END IF;
            INSERT INTO public.conversations (id, type, created_by) VALUES (p_conversation_id, 'SAVED_MESSAGES', v_user_id) ON CONFLICT (id) DO NOTHING;
            INSERT INTO public.conversation_members (conversation_id, account_id, role) VALUES (p_conversation_id, v_user_id, 'owner') ON CONFLICT (conversation_id, account_id) DO NOTHING;
        ELSIF p_conversation_id LIKE 'ai_%' THEN
            IF p_conversation_id != ('ai_' || v_user_id::text) THEN
                RAISE EXCEPTION 'Invalid AI conversation ID for caller' USING ERRCODE = '42501';
            END IF;
            INSERT INTO public.conversations (id, type, created_by) VALUES (p_conversation_id, 'AI', v_user_id) ON CONFLICT (id) DO NOTHING;
            INSERT INTO public.conversation_members (conversation_id, account_id, role) VALUES (p_conversation_id, v_user_id, 'owner') ON CONFLICT (conversation_id, account_id) DO NOTHING;
        ELSIF p_conversation_id LIKE 'direct_%' THEN
            IF p_recipient_id IS NULL OR NOT (p_recipient_id ~ '^[0-9a-fA-F-]{36}$') THEN
                RAISE EXCEPTION 'Valid UUID recipient_id required for direct conversation' USING ERRCODE = '22000';
            END IF;
            IF p_recipient_id = v_user_id::text THEN
                RAISE EXCEPTION 'Direct conversation cannot be with oneself. Use self conversation' USING ERRCODE = '22000';
            END IF;
            IF p_conversation_id != ('direct_' || LEAST(v_user_id::text, p_recipient_id) || '_' || GREATEST(v_user_id::text, p_recipient_id)) THEN
                RAISE EXCEPTION 'Deterministic direct conversation ID mismatch for caller and recipient' USING ERRCODE = '42501';
            END IF;
            INSERT INTO public.conversations (id, type, created_by) VALUES (p_conversation_id, 'DIRECT', v_user_id) ON CONFLICT (id) DO NOTHING;
            INSERT INTO public.conversation_members (conversation_id, account_id, role) VALUES (p_conversation_id, v_user_id, 'owner') ON CONFLICT (conversation_id, account_id) DO NOTHING;
            INSERT INTO public.conversation_members (conversation_id, account_id, role) VALUES (p_conversation_id, p_recipient_id::UUID, 'member') ON CONFLICT (conversation_id, account_id) DO NOTHING;
        ELSE
            RAISE EXCEPTION 'Conversation does not exist and cannot be auto-provisioned' USING ERRCODE = '42501';
        END IF;
    END IF;

    -- 3. Strictly verify caller is an active member of this conversation
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members cm 
        WHERE cm.conversation_id = p_conversation_id AND cm.account_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'Access denied: caller is not a member of conversation %', p_conversation_id USING ERRCODE = '42501';
    END IF;

    -- 4. If conversation is direct, verify recipient_id belongs to the other member
    IF p_conversation_id LIKE 'direct_%' AND p_recipient_id IS NOT NULL THEN
        IF p_recipient_id = v_user_id::text THEN
            RAISE EXCEPTION 'Recipient cannot be caller in direct conversation' USING ERRCODE = '22000';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM public.conversation_members cm
            WHERE cm.conversation_id = p_conversation_id AND cm.account_id = p_recipient_id::UUID
        ) THEN
            RAISE EXCEPTION 'Recipient % is not a member of conversation %', p_recipient_id, p_conversation_id USING ERRCODE = '42501';
        END IF;
    END IF;

    -- 5. Idempotency Check AFTER authorization:
    -- If message with client_message_id was already processed in this conversation, return it.
    SELECT id, sequence, status INTO v_existing_id, v_existing_seq, v_existing_status
    FROM public.messages
    WHERE conversation_id = p_conversation_id AND client_message_id = p_client_message_id
    LIMIT 1;

    IF v_existing_id IS NOT NULL THEN
        RETURN jsonb_build_object(
            'id', v_existing_id,
            'client_message_id', p_client_message_id,
            'sequence', v_existing_seq,
            'status', v_existing_status,
            'is_duplicate', true
        );
    END IF;

    -- 6. Atomically allocate next sequence number with row lock
    UPDATE public.conversations
    SET current_sequence = current_sequence + 1, updated_at = now()
    WHERE id = p_conversation_id
    RETURNING current_sequence INTO v_seq;

    -- 7. Atomic INSERT ... ON CONFLICT to protect against concurrent duplicate race-conditions
    INSERT INTO public.messages (
        id,
        conversation_id,
        sender_id,
        recipient_id,
        client_message_id,
        sequence,
        payload,
        signature,
        type,
        time_str,
        encryption_version,
        status
    )
    VALUES (
        v_msg_id,
        p_conversation_id,
        v_user_id,
        p_recipient_id,
        p_client_message_id,
        v_seq,
        p_payload,
        p_signature,
        p_type,
        p_time_str,
        p_encryption_version,
        'sent'
    )
    ON CONFLICT (conversation_id, client_message_id) DO NOTHING
    RETURNING id, sequence INTO v_inserted_id, v_inserted_seq;

    -- 8. If concurrent request won the insert race, fetch existing and return as duplicate
    IF v_inserted_id IS NULL THEN
        SELECT id, sequence, status INTO v_existing_id, v_existing_seq, v_existing_status
        FROM public.messages
        WHERE conversation_id = p_conversation_id AND client_message_id = p_client_message_id
        LIMIT 1;

        RETURN jsonb_build_object(
            'id', v_existing_id,
            'client_message_id', p_client_message_id,
            'sequence', v_existing_seq,
            'status', v_existing_status,
            'is_duplicate', true
        );
    END IF;

    RETURN jsonb_build_object(
        'id', v_msg_id,
        'client_message_id', p_client_message_id,
        'sequence', v_seq,
        'status', 'sent',
        'is_duplicate', false
    );
END;
$$;

-- 2. Secure Profile Resolver for QR Flow (Restricted Public Information Only)
-- Exposes ONLY user_id, display_name, avatar_url, hex_number, and authoritative public key.
-- Never reveals private keys, session tokens, fcm tokens, or full device hardware specs.
CREATE OR REPLACE FUNCTION public.resolve_profile_by_share_id(
    p_share_id TEXT
)
RETURNS TABLE (
    user_id UUID,
    display_name TEXT,
    avatar_url TEXT,
    hex_number TEXT,
    public_key TEXT,
    key_version INT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_clean TEXT := TRIM(COALESCE(p_share_id, ''));
    v_target_id UUID;
    v_target_number TEXT;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required' USING ERRCODE = '42501';
    END IF;

    IF v_clean = '' THEN
        RETURN;
    END IF;

    -- Check if share_id is UUID
    IF v_clean ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' THEN
        v_target_id := v_clean::UUID;
    ELSE
        -- Check if share_id is hex number (e.g. 101-12345 or 10112345)
        v_target_number := REPLACE(v_clean, '-', '');
        SELECT owner_id INTO v_target_id
        FROM public.hex_numbers
        WHERE number = v_target_number AND status = 'active'
        LIMIT 1;
    END IF;

    IF v_target_id IS NULL THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT 
        p.id AS user_id,
        COALESCE(p.display_name, p.username, 'HexShard User') AS display_name,
        COALESCE(p.avatar_url, '') AS avatar_url,
        COALESCE(hn.formatted, hn.number, '') AS hex_number,
        COALESCE(adk.public_key, '') AS public_key,
        COALESCE(adk.key_version, 1) AS key_version
    FROM public.profiles p
    LEFT JOIN public.hex_numbers hn ON hn.owner_id = p.id AND hn.status = 'active'
    LEFT JOIN LATERAL (
        SELECT dk.public_key, dk.key_version
        FROM public.active_device_keys dk
        WHERE dk.account_id = p.id
        ORDER BY dk.key_version DESC
        LIMIT 1
    ) adk ON true
    WHERE p.id = v_target_id
    LIMIT 1;
END;
$$;

GRANT EXECUTE ON FUNCTION public.send_message_idempotent(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.resolve_profile_by_share_id(TEXT) TO authenticated;
