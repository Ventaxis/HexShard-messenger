package com.example.data.repository

import com.example.crypto.E2ECryptoManager
import timber.log.Timber
import java.security.PublicKey
import java.util.Base64

data class EncryptedMessagePayload(
    val base64Payload: String,
    val iv: String,
    val base64Signature: String,
    val signatureValid: Boolean
)

enum class DecryptionTrustStatus {
    VERIFIED,
    UNVERIFIED_LEGACY,
    INVALID_SIGNATURE,
    MISSING_KEY,
    CORRUPT_PAYLOAD,
    DECRYPTION_FAILED
}

data class DecryptedResult(
    val plainText: String,
    val isSignatureValid: Boolean,
    val senderKeyChanged: Boolean = false,
    val trustStatus: DecryptionTrustStatus = if (isSignatureValid) DecryptionTrustStatus.VERIFIED else DecryptionTrustStatus.INVALID_SIGNATURE
)

class PeerKeyUnavailableException(val recipientId: String) : Exception("Recipient $recipientId public key is not available")

class CryptoRepository(
    val userRepository: UserRepository
) {

    companion object {
        // v3 payload envelope: MAGIC || HMAC(32) || IV(12) || CT, with GCM AAD bound
        // to the canonical header (chat, sender, recipient, message id). Prevents
        // cross-chat replay and context substitution that v2 (raw HMAC||IV||CT) allowed.
        private val V3_MAGIC = byteArrayOf(0x48, 0x58, 0x33) // "HX3"
    }

    fun createCanonicalEnvelope(
        protocolVersion: Int,
        conversationId: String,
        messageId: String,
        senderId: String,
        recipientId: String,
        idempotencyKey: String,
        payloadBytes: ByteArray
    ): ByteArray {
        val header = canonicalHeader(protocolVersion, conversationId, messageId, senderId, recipientId, idempotencyKey)
        val envelope = ByteArray(header.size + payloadBytes.size)
        System.arraycopy(header, 0, envelope, 0, header.size)
        System.arraycopy(payloadBytes, 0, envelope, header.size, payloadBytes.size)
        return envelope
    }

    private fun canonicalHeader(
        protocolVersion: Int,
        conversationId: String,
        messageId: String,
        senderId: String,
        recipientId: String,
        idempotencyKey: String
    ): ByteArray = "v$protocolVersion\n$conversationId\n$messageId\n$senderId\n$recipientId\n$idempotencyKey\n".toByteArray(Charsets.UTF_8)

    /**
     * Encrypts an outbound message using ECDH shared secret + per-message HMAC KDF + AES-GCM-256,
     * and digitally signs the canonical envelope using hardware-backed ECDSA key scoped to currentUserId.
     */
    suspend fun encryptOutboundMessage(
        currentUserId: String,
        recipientId: String,
        conversationId: String,
        idempotencyKey: String,
        plainText: String,
        isSelfOrAi: Boolean
    ): EncryptedMessagePayload {
        E2ECryptoManager.generateKeyPairIfNeeded(currentUserId)

        val sharedSecret = if (isSelfOrAi) {
            E2ECryptoManager.deriveSelfStorageSecret(currentUserId)
        } else {
            // TOFU: encrypt to the PINNED key, not whatever the server serves now —
            // otherwise a substituted key would silently re-encrypt traffic to an attacker
            val trusted = userRepository.getTrustedUserPublicKey(recipientId)
            val recipientPubKey = trusted?.pinnedKey ?: trusted?.key
            if (recipientPubKey != null) {
                E2ECryptoManager.deriveSharedSecret(recipientPubKey, currentUserId)
            } else {
                throw PeerKeyUnavailableException(recipientId)
            }
        }

        // v3 key schedule: separate encryption and MAC keys, KDF-bound even for a
        // blank idempotency nonce (v2 fell back to the RAW ECDH secret as AES key).
        val encKey = E2ECryptoManager.deriveEncryptionKey(sharedSecret, idempotencyKey)
        val macKey = E2ECryptoManager.deriveMacKey(sharedSecret, idempotencyKey)

        // AAD binds the ciphertext to (chat, sender, recipient, message id):
        // a payload replayed into another conversation fails GCM authentication.
        val aad = canonicalHeader(3, conversationId, idempotencyKey, currentUserId, recipientId, idempotencyKey)
        val (iv, cipherText) = E2ECryptoManager.encryptMessage(plainText, encKey, aad)

        // Envelope v3: MAGIC(3) || HMAC(32) || IV(12) || CipherText
        val ivAndCipher = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, ivAndCipher, 0, iv.size)
        System.arraycopy(cipherText, 0, ivAndCipher, iv.size, cipherText.size)
        val hmac = E2ECryptoManager.createHMAC(ivAndCipher, macKey)

        val combined = ByteArray(V3_MAGIC.size + 32 + ivAndCipher.size)
        System.arraycopy(V3_MAGIC, 0, combined, 0, V3_MAGIC.size)
        System.arraycopy(hmac, 0, combined, V3_MAGIC.size, 32)
        System.arraycopy(ivAndCipher, 0, combined, V3_MAGIC.size + 32, ivAndCipher.size)

        // Sender digital signature over canonical envelope
        val canonicalEnvelope = createCanonicalEnvelope(
            protocolVersion = 3,
            conversationId = conversationId,
            messageId = idempotencyKey,
            senderId = currentUserId,
            recipientId = recipientId,
            idempotencyKey = idempotencyKey,
            payloadBytes = combined
        )
        val signature = E2ECryptoManager.signData(canonicalEnvelope, currentUserId)

        return EncryptedMessagePayload(
            base64Payload = Base64.getEncoder().encodeToString(combined),
            iv = Base64.getEncoder().encodeToString(iv),
            base64Signature = Base64.getEncoder().encodeToString(signature),
            signatureValid = true
        )
    }

    /**
     * Decrypts an inbound message payload, verifies sender signature, checks HMAC, and decrypts ciphertext.
     * Uses recipientId as the local accountId for keystore isolation.
     *
     * [envelopeSenderId]/[envelopeRecipientId] are the identities written into the
     * canonical envelope/AAD at encryption time. They normally equal [senderId]/
     * [recipientId], but for messages sent BY this account (outbox resync) the crypto
     * peer and the envelope identities differ, and the v3 AAD binding fails without
     * the exact original pair.
     */
    suspend fun decryptInboundMessage(
        senderId: String,
        recipientId: String,
        conversationId: String,
        idempotencyKey: String,
        base64Payload: String,
        base64Signature: String?,
        envelopeSenderId: String = senderId,
        envelopeRecipientId: String = recipientId
    ): DecryptedResult {
        E2ECryptoManager.generateKeyPairIfNeeded(recipientId)

        val isSelf = senderId == recipientId || senderId == "self" || senderId == "me"

        val combinedBytes = try {
            Base64.getDecoder().decode(base64Payload)
        } catch (_: Exception) {
            return DecryptedResult(
                plainText = "[Corrupt payload: invalid encoding]",
                isSignatureValid = false,
                trustStatus = DecryptionTrustStatus.CORRUPT_PAYLOAD
            )
        }

        val isV3 = startsWithV3Magic(combinedBytes)

        // TOFU-guarded sender keys: prefer the PINNED key when the server presents
        // a different one, and let the caller warn the user about the rotation.
        var senderKeyChanged = false
        var sharedSecret: ByteArray? = null
        var servedSecret: ByteArray? = null
        if (isSelf) {
            sharedSecret = E2ECryptoManager.deriveSelfStorageSecret(recipientId)
        } else {
            val trusted = userRepository.getTrustedUserPublicKey(senderId)
            if (trusted == null) {
                return DecryptedResult(
                    plainText = "[Waiting for sender public key...]",
                    isSignatureValid = false,
                    trustStatus = DecryptionTrustStatus.MISSING_KEY
                )
            }
            senderKeyChanged = trusted.keyChanged
            // Verify/decrypt against the PINNED identity, not the newly served one:
            // the server is untrusted for identity decisions.
            val keyForCrypto = trusted.pinnedKey ?: trusted.key
            sharedSecret = try {
                E2ECryptoManager.deriveSharedSecret(keyForCrypto, recipientId)
            } catch (e: Exception) {
                Timber.w(e, "Shared secret derivation failed for sender $senderId")
                null
            }
            // TOFU key rotation fallback: after a peer reinstalled (keys regenerated),
            // messages encrypted under the NEW identity would fail against the pinned
            // key forever and be branded corrupted. Derive the served-key secret as a
            // retry; the payload decryptors use it only if the pinned HMAC fails, and
            // senderKeyChanged still surfaces the rotation warning to the user.
            if (trusted.keyChanged && trusted.key != null) {
                servedSecret = try {
                    E2ECryptoManager.deriveSharedSecret(trusted.key, recipientId)
                } catch (e: Exception) {
                    Timber.w(e, "Served-key secret derivation failed for sender $senderId")
                    null
                }
                if (servedSecret != null && sharedSecret != null && servedSecret.contentEquals(sharedSecret)) {
                    servedSecret = null
                }
            }
        }

        val masterSecret = sharedSecret
            ?: return DecryptedResult(
                plainText = "[Decryption Error: Key mismatch or corrupted data]",
                isSignatureValid = false,
                senderKeyChanged = senderKeyChanged,
                trustStatus = DecryptionTrustStatus.DECRYPTION_FAILED
            )

        // Signature verification (TOFU-guarded signing key, fail-closed for peers).
        var signatureValid = false
        val signaturePresent = !base64Signature.isNullOrBlank()
        if (signaturePresent && !isSelf) {
            try {
                val trustedSign = userRepository.getTrustedUserSigningKey(senderId)
                val senderSigningKey = trustedSign?.pinnedKey ?: trustedSign?.key
                if (trustedSign?.keyChanged == true) senderKeyChanged = true
                if (senderSigningKey != null) {
                    val canonicalEnvelope = createCanonicalEnvelope(
                        protocolVersion = if (isV3) 3 else 2,
                        conversationId = conversationId,
                        messageId = idempotencyKey,
                        senderId = envelopeSenderId,
                        recipientId = envelopeRecipientId,
                        idempotencyKey = idempotencyKey,
                        payloadBytes = combinedBytes
                    )
                    val sigBytes = Base64.getDecoder().decode(base64Signature)
                    signatureValid = E2ECryptoManager.verifySignature(canonicalEnvelope, sigBytes, senderSigningKey)
                    // Rotation: a signature that fails against the PINNED signing key may
                    // legitimately verify under the peer's NEW signing key. Verify there
                    // too — senderKeyChanged still keeps the rotation warning visible,
                    // but the message no longer renders as a withheld security alert.
                    if (!signatureValid && trustedSign?.keyChanged == true && trustedSign.key != null) {
                        signatureValid = E2ECryptoManager.verifySignature(canonicalEnvelope, sigBytes, trustedSign.key)
                    }
                } else {
                    Timber.w("Sender signing key unavailable for verification: $senderId")
                }
            } catch (e: Exception) {
                Timber.w(e, "Signature verification failed for sender $senderId")
            }
        } else if (isSelf) {
            signatureValid = true
        }

        if (isV3) {
            return decryptV3Payload(
                combinedBytes, masterSecret, servedSecret, conversationId, idempotencyKey,
                envelopeSenderId, envelopeRecipientId, isSelf, signatureValid, senderKeyChanged
            )
        }
        return decryptV2Payload(
            combinedBytes, masterSecret, servedSecret, idempotencyKey, isSelf,
            signatureValid, signaturePresent, senderKeyChanged
        )
    }

    /** v3 envelope: MAGIC || HMAC(macKey) || IV || CT, GCM AAD = canonical header. */
    private fun decryptV3Payload(
        combinedBytes: ByteArray,
        masterSecret: ByteArray,
        servedSecret: ByteArray?,
        conversationId: String,
        idempotencyKey: String,
        senderId: String,
        recipientId: String,
        isSelf: Boolean,
        signatureValid: Boolean,
        senderKeyChanged: Boolean
    ): DecryptedResult {
        val body = combinedBytes.copyOfRange(V3_MAGIC.size, combinedBytes.size)
        if (body.size < 44) { // 32 bytes HMAC + 12 bytes IV
            return DecryptedResult(
                plainText = "[Corrupt payload: truncated envelope]",
                isSignatureValid = false,
                senderKeyChanged = senderKeyChanged,
                trustStatus = DecryptionTrustStatus.CORRUPT_PAYLOAD
            )
        }

        val receivedHmac = body.copyOfRange(0, 32)
        val ivAndCipher = body.copyOfRange(32, body.size)

        var activeSecret = masterSecret
        if (!E2ECryptoManager.verifyHMAC(ivAndCipher, receivedHmac, E2ECryptoManager.deriveMacKey(masterSecret, idempotencyKey))) {
            // Pinned-key HMAC failed: retry with the served key before declaring
            // the payload tampered — the peer may have legitimately rotated keys.
            if (servedSecret == null ||
                !E2ECryptoManager.verifyHMAC(ivAndCipher, receivedHmac, E2ECryptoManager.deriveMacKey(servedSecret, idempotencyKey))
            ) {
                return DecryptedResult(
                    plainText = "[Message integrity verification failed: tampered ciphertext]",
                    isSignatureValid = false,
                    senderKeyChanged = senderKeyChanged,
                    trustStatus = DecryptionTrustStatus.CORRUPT_PAYLOAD
                )
            }
            activeSecret = servedSecret
        }

        val encKey = E2ECryptoManager.deriveEncryptionKey(activeSecret, idempotencyKey)

        val iv = ivAndCipher.copyOfRange(0, 12)
        val cipherText = ivAndCipher.copyOfRange(12, ivAndCipher.size)
        val aad = canonicalHeader(3, conversationId, idempotencyKey, senderId, recipientId, idempotencyKey)

        return try {
            val decrypted = E2ECryptoManager.decryptMessage(iv, cipherText, encKey, aad)
            when {
                isSelf || signatureValid -> DecryptedResult(
                    plainText = decrypted,
                    isSignatureValid = true,
                    senderKeyChanged = senderKeyChanged,
                    trustStatus = DecryptionTrustStatus.VERIFIED
                )
                else -> {
                    // v3 contract: signature is mandatory. A stripped signature means
                    // the relay tampered with the record — content must NOT be shown.
                    DecryptedResult(
                        plainText = "[⚠️ Security Alert: Message signature missing or invalid. Content withheld.]",
                        isSignatureValid = false,
                        senderKeyChanged = senderKeyChanged,
                        trustStatus = DecryptionTrustStatus.INVALID_SIGNATURE
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "v3 decryption failure")
            DecryptedResult(
                plainText = "[Decryption Error: Key mismatch or corrupted data]",
                isSignatureValid = false,
                senderKeyChanged = senderKeyChanged,
                trustStatus = DecryptionTrustStatus.DECRYPTION_FAILED
            )
        }
    }

    /** v2 legacy envelope: HMAC(messageKey) || IV || CT, no AAD. Kept for old messages. */
    private fun decryptV2Payload(
        combinedBytes: ByteArray,
        masterSecret: ByteArray,
        servedSecret: ByteArray?,
        idempotencyKey: String,
        isSelf: Boolean,
        signatureValid: Boolean,
        signaturePresent: Boolean,
        senderKeyChanged: Boolean
    ): DecryptedResult {
        if (combinedBytes.size < 44) { // 32 bytes HMAC + 12 bytes IV
            return DecryptedResult(
                plainText = "[Corrupt payload: truncated envelope]",
                isSignatureValid = false,
                senderKeyChanged = senderKeyChanged,
                trustStatus = DecryptionTrustStatus.CORRUPT_PAYLOAD
            )
        }

        val payloadLength = combinedBytes.size - 32
        val ivAndCipher = ByteArray(payloadLength)
        System.arraycopy(combinedBytes, 32, ivAndCipher, 0, payloadLength)

        val receivedHmac = ByteArray(32)
        System.arraycopy(combinedBytes, 0, receivedHmac, 0, 32)

        var activeKey = if (idempotencyKey.isNotBlank()) {
            E2ECryptoManager.deriveMessageKey(masterSecret, idempotencyKey)
        } else {
            masterSecret
        }
        if (!E2ECryptoManager.verifyHMAC(ivAndCipher, receivedHmac, activeKey)) {
            // Pinned-key HMAC failed: retry with the served key before declaring
            // the payload tampered — the peer may have legitimately rotated keys.
            val servedKey = servedSecret?.let {
                if (idempotencyKey.isNotBlank()) E2ECryptoManager.deriveMessageKey(it, idempotencyKey) else it
            }
            if (servedKey == null || !E2ECryptoManager.verifyHMAC(ivAndCipher, receivedHmac, servedKey)) {
                return DecryptedResult(
                    plainText = "[Message integrity verification failed: tampered ciphertext]",
                    isSignatureValid = false,
                    senderKeyChanged = senderKeyChanged,
                    trustStatus = DecryptionTrustStatus.CORRUPT_PAYLOAD
                )
            }
            activeKey = servedKey
        }

        val iv = ByteArray(12)
        System.arraycopy(ivAndCipher, 0, iv, 0, 12)
        val cipherText = ByteArray(ivAndCipher.size - 12)
        System.arraycopy(ivAndCipher, 12, cipherText, 0, cipherText.size)

        return try {
            val decrypted = E2ECryptoManager.decryptMessage(iv, cipherText, activeKey)
            when {
                isSelf || signatureValid -> DecryptedResult(
                    plainText = decrypted,
                    isSignatureValid = true,
                    senderKeyChanged = senderKeyChanged,
                    trustStatus = DecryptionTrustStatus.VERIFIED
                )
                !signaturePresent -> {
                    // Legacy v2 message without signature: shown, explicitly unverified
                    DecryptedResult(
                        plainText = decrypted,
                        isSignatureValid = false,
                        senderKeyChanged = senderKeyChanged,
                        trustStatus = DecryptionTrustStatus.UNVERIFIED_LEGACY
                    )
                }
                else -> {
                    // Signature was supplied but failed cryptographic verification:
                    // NEVER present untrusted payload as verified authentic text!
                    DecryptedResult(
                        plainText = "[⚠️ Security Alert: Message digital signature verification failed. Content unverified.]",
                        isSignatureValid = false,
                        senderKeyChanged = senderKeyChanged,
                        trustStatus = DecryptionTrustStatus.INVALID_SIGNATURE
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "v2 decryption failure")
            DecryptedResult(
                plainText = "[Decryption Error: Key mismatch or corrupted data]",
                isSignatureValid = false,
                senderKeyChanged = senderKeyChanged,
                trustStatus = DecryptionTrustStatus.DECRYPTION_FAILED
            )
        }
    }

    private fun startsWithV3Magic(bytes: ByteArray): Boolean {
        if (bytes.size < V3_MAGIC.size) return false
        for (i in V3_MAGIC.indices) {
            if (bytes[i] != V3_MAGIC[i]) return false
        }
        return true
    }
}
