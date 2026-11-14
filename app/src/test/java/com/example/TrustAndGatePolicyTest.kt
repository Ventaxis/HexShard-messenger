package com.example

import com.example.calls.CallSignaling
import com.example.crypto.CryptoTrustPolicy
import com.example.data.repository.DecryptionTrustStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy contracts fixed during the v1.11.0→HEAD security review:
 * SEC-002 (served-key fallback must never look silently VERIFIED) and
 * SEC-001 (call signaling only from accepted contacts, bye matched by callId).
 */
class TrustAndGatePolicyTest {

    // --- SEC-002: trust → persistence status mapping ---

    @Test
    fun servedRotationNeverPersistsAsPlainDelivered() {
        assertEquals(
            "key_rotated",
            CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.KEY_ROTATED, isMe = false)
        )
        assertEquals(
            "key_rotated",
            CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.KEY_ROTATED, isMe = true)
        )
    }

    @Test
    fun verifiedAndLegacyContentMapsToDeliveredOrSent() {
        assertEquals("sent", CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.VERIFIED, isMe = true))
        assertEquals("delivered", CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.VERIFIED, isMe = false))
        assertEquals(
            "delivered",
            CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.UNVERIFIED_LEGACY, isMe = false)
        )
    }

    @Test
    fun corruptedAndFailedAndUnsignedMapToIntegrityError() {
        assertEquals(
            "integrity_error",
            CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.CORRUPT_PAYLOAD, isMe = false)
        )
        assertEquals(
            "integrity_error",
            CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.DECRYPTION_FAILED, isMe = false)
        )
        assertEquals(
            "integrity_error",
            CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.INVALID_SIGNATURE, isMe = false)
        )
        assertEquals(
            "integrity_error",
            CryptoTrustPolicy.messageStatusFor(DecryptionTrustStatus.MISSING_KEY, isMe = false)
        )
    }

    // --- SEC-002: healing policy ---

    @Test
    fun failedRowsHealWhenNewContentIsGood() {
        assertTrue(CryptoTrustPolicy.canHeal("integrity_error", newContentIsGood = true))
    }

    @Test
    fun keyRotatedRowsHealOnlyAfterGoodReDecryption() {
        // After the user accepts the rotation, re-decrypt yields VERIFIED content:
        // the row must then be rewritten as delivered/sent, not stay flagged.
        assertTrue(CryptoTrustPolicy.canHeal("key_rotated", newContentIsGood = true))
    }

    @Test
    fun healthyRowsAreNeverRewrittenByHealing() {
        assertFalse(CryptoTrustPolicy.canHeal("delivered", newContentIsGood = true))
        assertFalse(CryptoTrustPolicy.canHeal("sent", newContentIsGood = true))
        assertFalse(CryptoTrustPolicy.canHeal("read", newContentIsGood = true))
        assertFalse(CryptoTrustPolicy.canHeal("key_rotated", newContentIsGood = false))
        assertFalse(CryptoTrustPolicy.canHeal("integrity_error", newContentIsGood = false))
    }

    // --- 2026-11-14: outbound encryption follows the published key ---

    @Test
    fun outboundEncryptionPrefersServedKeyOverStalePin() {
        val served = ByteArray(32) { 1 }
        val pinned = ByteArray(32) { 2 }
        // Re-login rotated the peer's keys: the pin is stale and must lose.
        assertEquals(served, CryptoTrustPolicy.outboundKey(served, pinned))
    }

    @Test
    fun outboundEncryptionFallsBackToPinWhenNothingServed() {
        val pinned = ByteArray(32) { 2 }
        assertEquals(pinned, CryptoTrustPolicy.outboundKey(null, pinned))
    }

    @Test
    fun outboundEncryptionYieldsNullWithoutAnyKey() {
        assertEquals(null, CryptoTrustPolicy.outboundKey(null, null))
    }

    // --- SEC-001: call signaling gates ---

    @Test
    fun offerRingsOnlyForAcceptedContacts() {
        val contacts = setOf("uid-a", "uid-b")
        assertTrue(CallSignaling.offerAllowed(contacts, "uid-a"))
        assertFalse(CallSignaling.offerAllowed(contacts, "stranger-uid"))
        assertFalse(CallSignaling.offerAllowed(contacts, ""))
        assertFalse(CallSignaling.offerAllowed(emptySet(), "uid-a"))
    }

    @Test
    fun byeIsHonoredOnlyWithMatchingCallId() {
        assertTrue(CallSignaling.byeMatches("42", "42"))
        assertFalse(CallSignaling.byeMatches("42", "43"))
        assertFalse(CallSignaling.byeMatches("42", ""))
        assertFalse(CallSignaling.byeMatches("", ""))
    }

    @Test
    fun broadcastsOutsideJoinedTopicsAreIgnored() {
        val joined = setOf("realtime:call_a_b", "realtime:call_c_d")
        assertTrue(CallSignaling.broadcastFromJoinedTopic("realtime:call_a_b", joined))
        assertFalse(CallSignaling.broadcastFromJoinedTopic("realtime:call_x_y", joined))
        assertFalse(CallSignaling.broadcastFromJoinedTopic("", joined))
    }

    @Test
    fun callIdsAreNotPredictableTimestamps() {
        val a = CallSignaling.newCallId()
        val b = CallSignaling.newCallId()
        assertTrue(a.isNotBlank() && b.isNotBlank())
        assertTrue(a != b)
        assertFalse(a.matches(Regex("^\\d+$")))
    }
}
