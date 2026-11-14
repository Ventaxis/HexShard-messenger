-- Review SEC-001 hardening: call signaling topics become PRIVATE Realtime
-- channels. Authorization is enforced by Realtime against realtime.messages RLS:
-- only the two UUIDs that make up the deterministic pair topic
-- (call_<lowerUid>_<higherUid>) may receive or send broadcasts on it.
-- The topic name may arrive with or without the "realtime:" Phoenix prefix
-- depending on server version — both forms are matched, the uid-pair constraint
-- is the actual security boundary. The public chat topic is unaffected: these
-- policies only gate channels joined with private: true.

CREATE POLICY "call_pair_topic_receive" ON realtime.messages
FOR SELECT TO authenticated
USING (
    topic ~ ('^(realtime:)?call_' || auth.uid()::text || '_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR topic ~ ('^(realtime:)?call_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_' || auth.uid()::text || '$')
);

CREATE POLICY "call_pair_topic_send" ON realtime.messages
FOR INSERT TO authenticated
WITH CHECK (
    topic ~ ('^(realtime:)?call_' || auth.uid()::text || '_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
    OR topic ~ ('^(realtime:)?call_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_' || auth.uid()::text || '$')
);
