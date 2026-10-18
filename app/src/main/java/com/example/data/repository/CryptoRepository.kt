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
    val trustStatus: DecryptionTrustStatus = if (isSignatureValid) DecryptionTrustStatus.VERIFIED else DecryptionTrustStatus.INVALID_SIGNATURE
)

class PeerKeyUnavailableException(val recipientId: String) : Exception("Recipient $recipientId public key is not available")

class CryptoRepository(
    val userRepository: UserRepository
) {

    fun createCanonicalEnvelope(
        protocolVersion: Int,
        conversationId: String,
        messageId: String,
        senderId: String,
        recipientId: String,
        idempotencyKey: String,
        payloadBytes: ByteArray
    ): ByteArray {
        val header = "v$protocolVersion\n$conversationId\n$messageId\n$senderId\n$recipientId\n$idempotencyKey\n".toByteArray(Charsets.UTF_8)
        val envelope = ByteArray(header.size + payloadBytes.size)
        System.arraycopy(header, 0, envelope, 0, header.size)
        System.arraycopy(payloadBytes, 0, envelope, header.size, payloadBytes.size)
        return envelope
    }

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
            val recipientPubKey = userRepository.getOrFetchUserPublicKey(recipientId)
            if (recipientPubKey != null) {
                E2ECryptoManager.deriveSharedSecret(recipientPubKey, currentUserId)
            } else {
                throw PeerKeyUnavailableException(recipientId)
            }
        }

        val messageKey = if (idempotencyKey.isNotBlank()) {
            E2ECryptoManager.deriveMessageKey(sharedSecret, idempotencyKey)
        } else {
            sharedSecret
        }

        val (iv, cipherText) = E2ECryptoManager.encryptMessage(plainText, messageKey)

        // Envelope: 32 bytes HMAC || IV || CipherText
        val ivAndCipher = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, ivAndCipher, 0, iv.size)
        System.arraycopy(cipherText, 0, ivAndCipher, iv.size, cipherText.size)
        val hmac = E2ECryptoManager.createHMAC(ivAndCipher, messageKey)

        val combined = ByteArray(32 + ivAndCipher.size)
        System.arraycopy(hmac, 0, combined, 0, 32)
        System.arraycopy(ivAndCipher, 0, combined, 32, ivAndCipher.size)

        // Sender digital signature over canonical envelope
        val canonicalEnvelope = createCanonicalEnvelope(
            protocolVersion = 2,
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
     */
    suspend fun decryptInboundMessage(
        senderId: String,
        recipientId: String,
        conversationId: String,
        idempotencyKey: String,
        base64Payload: String,
        base64Signature: String?
    ): DecryptedResult {
        E2ECryptoManager.generateKeyPairIfNeeded(recipientId)

        val isSelf = senderId == recipientId || senderId == "self" || senderId == "me"
        val sharedSecret = if (isSelf) {
            E2ECryptoManager.deriveSelfStorageSecret(recipientId)
        } else {
            val senderPubKey = userRepository.getOrFetchUserPublicKey(senderId)
            if (senderPubKey != null) {
                E2ECryptoManager.deriveSharedSecret(senderPubKey, recipientId)
            } else {
                return DecryptedResult(
                    plainText = "[Waiting for sender public key...]",
                    isSignatureValid = false,
                    trustStatus = DecryptionTrustStatus.MISSING_KEY
                )
            }
        }

        val messageKey = if (idempotencyKey.isNotBlank()) {
            E2ECryptoManager.deriveMessageKey(sharedSecret, idempotencyKey)
        } else {
            sharedSecret
        }

        val combinedBytes = try {
            Base64.getDecoder().decode(base64Payload)
        } catch (_: Exception) {
            return DecryptedResult(
                plainText = "[Corrupt payload: invalid encoding]",
                isSignatureValid = false,
                trustStatus = DecryptionTrustStatus.CORRUPT_PAYLOAD
            )
        }

        if (combinedBytes.size < 44) { // 32 bytes HMAC + 12 bytes IV
            return DecryptedResult(
                plainText = "[Corrupt payload: truncated envelope]",
                isSignatureValid = false,
                trustStatus = DecryptionTrustStatus.CORRUPT_PAYLOAD
            )
        }

        // Verify digital signature - fail-closed for peer messages
        var signatureValid = false
        val isLegacyMessage = base64Signature.isNullOrBlank() && !isSelf

        if (!base64Signature.isNullOrBlank() && !isSelf) {
            try {
                val senderSigningKey = userRepository.getOrFetchUserSigningKey(senderId)
                if (senderSigningKey != null) {
                    val canonicalEnvelope = createCanonicalEnvelope(
                        protocolVersion = 2,
                        conversationId = conversationId,
                        messageId = idempotencyKey,
                        senderId = senderId,
                        recipientId = recipientId,
                        idempotencyKey = idempotencyKey,
                        payloadBytes = combinedBytes
                    )
                    val sigBytes = Base64.getDecoder().decode(base64Signature)
                    signatureValid = E2ECryptoManager.verifySignature(canonicalEnvelope, sigBytes, senderSigningKey)
                } else {
                    Timber.w("Sender signing key unavailable for verification: $senderId")
                }
            } catch (e: Exception) {
                Timber.w(e, "Signature verification failed for sender $senderId")
            }
        } else if (isSelf) {
            signatureValid = true
        }

        // Extract HMAC, IV, and CipherText
        val payloadLength = combinedBytes.size - 32
        val ivAndCipher = ByteArray(payloadLength)
        System.arraycopy(combinedBytes, 32, ivAndCipher, 0, payloadLength)

        val receivedHmac = ByteArray(32)
        System.arraycopy(combinedBytes, 0, receivedHmac, 0, 32)

        if (!E2ECryptoManager.verifyHMAC(ivAndCipher, receivedHmac, messageKey)) {
            return DecryptedResult(
                plainText = "[Message integrity verification failed: tampered ciphertext]",
                isSignatureValid = false,
                trustStatus = DecryptionTrustStatus.CORRUPT_PAYLOAD
            )
        }

        val iv = ByteArray(12)
        System.arraycopy(ivAndCipher, 0, iv, 0, 12)
        val cipherText = ByteArray(ivAndCipher.size - 12)
        System.arraycopy(ivAndCipher, 12, cipherText, 0, cipherText.size)

        return try {
            val decrypted = E2ECryptoManager.decryptMessage(iv, cipherText, messageKey)
            when {
                isSelf || signatureValid -> {
                    DecryptedResult(
                        plainText = decrypted,
                        isSignatureValid = true,
                        trustStatus = DecryptionTrustStatus.VERIFIED
                    )
                }
                isLegacyMessage -> {
                    // Legacy message without signature: explicitly tagged as unverified
                    DecryptedResult(
                        plainText = decrypted,
                        isSignatureValid = false,
                        trustStatus = DecryptionTrustStatus.UNVERIFIED_LEGACY
                    )
                }
                else -> {
                    // Signature was supplied but failed cryptographic verification:
                    // NEVER present untrusted payload as verified authentic text!
                    DecryptedResult(
                        plainText = "[⚠️ Security Alert: Message digital signature verification failed. Content unverified.]",
                        isSignatureValid = false,
                        trustStatus = DecryptionTrustStatus.INVALID_SIGNATURE
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Decryption failure")
            DecryptedResult(
                plainText = "[Decryption Error: Key mismatch or corrupted data]",
                isSignatureValid = false,
                trustStatus = DecryptionTrustStatus.DECRYPTION_FAILED
            )
        }
    }
}
