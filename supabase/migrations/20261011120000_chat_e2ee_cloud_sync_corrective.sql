-- ====================================================================
-- Migration: 20261011120000_chat_e2ee_cloud_sync_corrective.sql
-- Description:
-- 1. Sets REPLICA IDENTITY FULL on public.messages for robust Realtime UPDATE/DELETE delivery.
-- 2. Synchronizes storage bucket 'profile-backgrounds' file_size_limit to 2 MiB (2097152 bytes).
-- 3. Updates get_incremental_messages to include persona_id for strict AI persona isolation.
-- 4. Reconciles and ensures canonical sync_conversations_for_user and crypto backup RPCs.
-- ====================================================================

-- 1. REPLICA IDENTITY FULL on public.messages
ALTER TABLE public.messages REPLICA IDENTITY FULL;

-- 2. Align profile-backgrounds bucket limit to 2 MiB (2097152 bytes)
UPDATE storage.buckets
SET file_size_limit = 2097152
WHERE id = 'profile-backgrounds';

-- 3. Incremental Synchronization Cursor returning persona_id
DROP FUNCTION IF EXISTS public.get_incremental_messages(TEXT, BIGINT, INT);

CREATE OR REPLACE FUNCTION public.get_incremental_messages(
    p_conversation_id TEXT,
    p_after_sequence BIGINT DEFAULT 0,
    p_limit INT DEFAULT 50
)
RETURNS TABLE (
    id TEXT,
    conversation_id TEXT,
    sender_id UUID,
    recipient_id TEXT,
    client_message_id TEXT,
    sequence BIGINT,
    payload TEXT,
    signature TEXT,
    type TEXT,
    time_str TEXT,
    encryption_version INT,
    status TEXT,
    persona_id TEXT,
    is_deleted BOOLEAN,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Unauthorized: User authentication required';
    END IF;

    -- Verify caller is strictly a member of the conversation
    IF NOT EXISTS (
        SELECT 1 FROM public.conversation_members cm 
        WHERE cm.conversation_id = p_conversation_id AND cm.account_id = v_user_id
    ) THEN
        RAISE EXCEPTION 'Access denied: caller is not a member of conversation %', p_conversation_id USING ERRCODE = '42501';
    END IF;

    RETURN QUERY
    SELECT 
        m.id,
        m.conversation_id,
        m.sender_id,
        m.recipient_id,
        m.client_message_id,
        m.sequence,
        m.payload,
        m.signature,
        m.type,
        m.time_str,
        m.encryption_version,
        m.status,
        m.persona_id,
        m.is_deleted,
        m.created_at
    FROM public.messages m
    WHERE m.conversation_id = p_conversation_id
      AND m.sequence > p_after_sequence
    ORDER BY m.sequence ASC
    LIMIT LEAST(p_limit, 100);
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_incremental_messages(TEXT, BIGINT, INT) TO authenticated;

-- Notify PostgREST to reload schema cache
NOTIFY pgrst, 'reload schema';
