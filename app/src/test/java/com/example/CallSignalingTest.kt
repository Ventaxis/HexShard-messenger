package com.example

import com.example.calls.CallSignaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit tests for the pure call-signaling helpers.
 * No Android framework, no Robolectric — everything in [CallSignaling]
 * is deterministic string/map construction.
 */
class CallSignalingTest {

    @Test
    fun topicIsDeterministicAndOrderIndependent() {
        val a = "11111111-aaaa-4bbb-8ccc-111111111111"
        val b = "22222222-bbbb-4ccc-8ddd-222222222222"

        assertEquals(CallSignaling.topicFor(a, b), CallSignaling.topicFor(b, a))
        assertEquals("realtime:call_11111111-aaaa-4bbb-8ccc-111111111111_22222222-bbbb-4ccc-8ddd-222222222222", CallSignaling.topicFor(a, b))
    }

    @Test
    fun topicSortsLexicographically() {
        assertEquals(CallSignaling.sortedPair("bbb", "aaa"), listOf("aaa", "bbb"))
        // Shorter string sorts before its longer prefix
        assertEquals("realtime:call_abc_abcz", CallSignaling.topicFor("abcz", "abc"))
    }

    @Test
    fun topicTrimsWhitespaceBeforeSorting() {
        assertEquals(CallSignaling.topicFor(" uid1 ", "uid2"), CallSignaling.topicFor("uid1", "uid2"))
    }

    @Test
    fun offerPayloadCarriesKindSdpFromUidAndCallId() {
        val payload = CallSignaling.offerPayload(fromUid = "uid1", callId = "1700000000000", sdp = "v=0\r\n...")

        assertEquals(CallSignaling.KIND_OFFER, payload["kind"])
        assertEquals("uid1", payload["fromUid"])
        assertEquals("1700000000000", payload["callId"])
        assertEquals("v=0\r\n...", payload["sdp"])
    }

    @Test
    fun answerPayloadCarriesKindAnswer() {
        val payload = CallSignaling.answerPayload(fromUid = "uid2", callId = "42", sdp = "v=0\r\nanswer")

        assertEquals(CallSignaling.KIND_ANSWER, payload["kind"])
        assertEquals("v=0\r\nanswer", payload["sdp"])
        assertEquals("42", payload["callId"])
    }

    @Test
    fun icePayloadCarriesCandidateCoordinates() {
        val payload = CallSignaling.icePayload(
            fromUid = "uid1",
            callId = "42",
            candidateSdp = "candidate:1 1 udp 2130706431 192.168.1.2 50000 typ host",
            sdpMid = "0",
            sdpMLineIndex = 0
        )

        assertEquals(CallSignaling.KIND_ICE, payload["kind"])
        assertEquals("candidate:1 1 udp 2130706431 192.168.1.2 50000 typ host", payload["candidate"])
        assertEquals("0", payload["sdpMid"])
        assertEquals(0, payload["sdpMLineIndex"])
    }

    @Test
    fun icePayloadSubstitutesEmptySdpMidWhenNull() {
        val payload = CallSignaling.icePayload("uid1", "42", "candidate:2", sdpMid = null, sdpMLineIndex = 3)

        assertEquals("", payload["sdpMid"])
        assertEquals(3, payload["sdpMLineIndex"])
    }

    @Test
    fun byePayloadOmitsEmptyReason() {
        val withoutReason = CallSignaling.byePayload("uid1", "42")
        val withReason = CallSignaling.byePayload("uid1", "42", reason = "busy")

        assertEquals(CallSignaling.KIND_BYE, withoutReason["kind"])
        assertTrue(!withoutReason.containsKey("reason"))
        assertEquals("busy", withReason["reason"])
    }

    @Test
    fun broadcastEnvelopeWrapsSignalForSupabaseRealtime() {
        val signal = CallSignaling.offerPayload("uid1", "42", "v=0")
        val envelope = CallSignaling.broadcastEnvelope("realtime:call_uid1_uid2", signal)

        assertEquals("realtime:call_uid1_uid2", envelope["topic"])
        assertEquals("broadcast", envelope["event"])

        val payload = envelope["payload"] as Map<*, *>
        assertEquals("broadcast", payload["type"])
        assertEquals(CallSignaling.SIGNAL_EVENT, payload["event"])
        assertEquals(signal, payload["payload"])
    }

    @Test
    fun joinEnvelopeCarriesBroadcastConfigAndAccessToken() {
        val envelope = CallSignaling.joinEnvelope("realtime:call_uid1_uid2", ref = "7", accessToken = "secret-token")

        assertEquals("realtime:call_uid1_uid2", envelope["topic"])
        assertEquals("phx_join", envelope["event"])
        assertEquals("7", envelope["ref"])
        assertEquals("7", envelope["join_ref"])

        val payload = envelope["payload"] as Map<*, *>
        assertEquals("secret-token", payload["access_token"])
        val config = payload["config"] as Map<*, *>
        val broadcast = config["broadcast"] as Map<*, *>
        assertEquals(false, broadcast["self"])
    }
}
