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

data class DecryptedResult(
    val plainText: String,
    val isSignatureValid: Boolean
)

class CryptoRepository(
    private val userRepository: UserRepository
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
     * and digitally signs the canonical envelope using hardware-backed ECDSA key.
     */
    suspend fun encryptOutboundMessage(
        currentUserId: String,
        recipientId: String,
        conversationId: String,
        idempotencyKey: String,
        plainText: String,
        isSelfOrAi: Boolean
    ): EncryptedMessagePayload {
        E2ECryptoManager.generateKeyPairIfNeeded()

        val sharedSecret = if (isSelfOrAi) {
            E2ECryptoManager.deriveSelfStorageSecret()
        } else {
            val recipientPubKey = userRepository.getOrFetchUserPublicKey(recipientId)
                ?: throw IllegalStateException("Recipient $recipientId public key could not be retrieved")
            E2ECryptoManager.deriveSharedSecret(recipientPubKey)
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
        val signature = E2ECryptoManager.signData(canonicalEnvelope)

        return EncryptedMessagePayload(
            base64Payload = Base64.getEncoder().encodeToString(combined),
            iv = Base64.getEncoder().encodeToString(iv),
            base64Signature = Base64.getEncoder().encodeToString(signature),
            signatureValid = true
        )
    }

    /**
     * Decrypts an inbound message payload, verifies sender signature, checks HMAC, and decrypts ciphertext.
     */
    suspend fun decryptInboundMessage(
        senderId: String,
        recipientId: String,
        conversationId: String,
        idempotencyKey: String,
        base64Payload: String,
        base64Signature: String?
    ): DecryptedResult {
        E2ECryptoManager.generateKeyPairIfNeeded()

        val isSelf = senderId == recipientId || senderId == "self" || senderId == "me"
        val sharedSecret = if (isSelf) {
            E2ECryptoManager.deriveSelfStorageSecret()
        } else {
            val senderPubKey = userRepository.getOrFetchUserPublicKey(senderId)
                ?: return DecryptedResult("[E2EE Error: Sender public key unavailable]", false)
            E2ECryptoManager.deriveSharedSecret(senderPubKey)
        }

        val messageKey = if (idempotencyKey.isNotBlank()) {
            E2ECryptoManager.deriveMessageKey(sharedSecret, idempotencyKey)
        } else {
            sharedSecret
        }

        val combinedBytes = try {
            Base64.getDecoder().decode(base64Payload)
        } catch (_: Exception) {
            return DecryptedResult(base64Payload, false)
        }

        if (combinedBytes.size < 44) { // 32 bytes HMAC + 12 bytes IV
            return DecryptedResult("[E2EE Error: Truncated payload]", false)
        }

        // Verify digital signature
        var signatureValid = false
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
            return DecryptedResult("[E2EE Error: Message integrity verification failed]", false)
        }

        val iv = ByteArray(12)
        System.arraycopy(ivAndCipher, 0, iv, 0, 12)
        val cipherText = ByteArray(ivAndCipher.size - 12)
        System.arraycopy(ivAndCipher, 12, cipherText, 0, cipherText.size)

        return try {
            val decrypted = E2ECryptoManager.decryptMessage(iv, cipherText, messageKey)
            DecryptedResult(decrypted, signatureValid)
        } catch (e: Exception) {
            Timber.e(e, "Decryption failure")
            DecryptedResult("[Decryption Error]", false)
        }
    }
}
