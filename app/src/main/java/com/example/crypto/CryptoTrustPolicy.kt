package com.example.crypto

import com.example.data.repository.DecryptionTrustStatus

/**
 * Pure persistence policy for decryption outcomes (review SEC-002): content that
 * was decrypted/verified under an UNACCEPTED served key never persists as plainly
 * verified — it keeps the key_rotated flag until the user accepts the rotation and
 * a re-decrypt yields genuine pinned-key trust.
 */
object CryptoTrustPolicy {

    const val STATUS_INTEGRITY_ERROR = "integrity_error"
    const val STATUS_KEY_ROTATED = "key_rotated"
    const val STATUS_SENT = "sent"
    const val STATUS_DELIVERED = "delivered"

    fun messageStatusFor(trustStatus: DecryptionTrustStatus, isMe: Boolean): String = when (trustStatus) {
        DecryptionTrustStatus.CORRUPT_PAYLOAD,
        DecryptionTrustStatus.DECRYPTION_FAILED,
        DecryptionTrustStatus.INVALID_SIGNATURE,
        DecryptionTrustStatus.MISSING_KEY -> STATUS_INTEGRITY_ERROR
        DecryptionTrustStatus.KEY_ROTATED -> STATUS_KEY_ROTATED
        DecryptionTrustStatus.VERIFIED,
        DecryptionTrustStatus.UNVERIFIED_LEGACY -> if (isMe) STATUS_SENT else STATUS_DELIVERED
    }

    /**
     * Whether a previously persisted row may be overwritten by a successful
     * re-decryption during sync healing. Failed rows and unaccepted rotations are
     * healable; healthy rows are never touched by the healing pass.
     */
    fun canHeal(existingStatus: String, newContentIsGood: Boolean): Boolean =
        (existingStatus == STATUS_INTEGRITY_ERROR || existingStatus == STATUS_KEY_ROTATED) && newContentIsGood
}
