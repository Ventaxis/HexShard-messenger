package com.example.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HexShard Cryptographic Engine
 *
 * Protocol Specification:
 * - Architecture: Versioned Static-ECDH protocol (secp256r1 / NIST P-256)
 * - Key Agreement: ECDH between sender device key and recipient identity key
 * - Key Derivation: HMAC-SHA256 per-message derivation with unique 96-bit nonce
 * - AEAD Cipher: AES-256-GCM (128-bit authentication tag, 96-bit unique IV)
 * - Digital Signature: SHA256withECDSA for sender authentication & anti-spoofing
 * - Self Storage KDF: Hardware-backed Android Keystore HMAC-SHA256 deterministic derivation
 * - Private Keys: Generated and retained strictly inside hardware-backed Android Keystore
 *
 * NOTE: This is a hardened versioned static-ECDH AEAD protocol. It is NOT a Double Ratchet PFS protocol.
 */
object E2ECryptoManager {

    private const val KEY_ALIAS = "hexshard_e2e_keypair"
    private const val SIGNING_KEY_ALIAS = "hexshard_e2e_signing_keypair"
    private const val SELF_STORAGE_HMAC_ALIAS = "hexshard_self_storage_hmac"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
    private const val SIGNING_ALGORITHM = "SHA256withECDSA"
    private const val HASH_ALGORITHM = "SHA-256"

    fun getKeyAlias(accountId: String? = null): String {
        val clean = accountId?.trim()?.takeIf { it.isNotBlank() }
        return if (clean != null) "${KEY_ALIAS}_$clean" else KEY_ALIAS
    }

    fun getSigningKeyAlias(accountId: String? = null): String {
        val clean = accountId?.trim()?.takeIf { it.isNotBlank() }
        return if (clean != null) "${SIGNING_KEY_ALIAS}_$clean" else SIGNING_KEY_ALIAS
    }

    fun getSelfHmacAlias(accountId: String? = null): String {
        val clean = accountId?.trim()?.takeIf { it.isNotBlank() }
        return if (clean != null) "${SELF_STORAGE_HMAC_ALIAS}_$clean" else SELF_STORAGE_HMAC_ALIAS
    }

    fun generateKeyPairIfNeeded(accountId: String? = null) {
        synchronized(this) {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val ecdhAlias = getKeyAlias(accountId)
            val signAlias = getSigningKeyAlias(accountId)
            val hmacAlias = getSelfHmacAlias(accountId)
            
            // 1. ECDH Key Agreement Keypair (P-256)
            if (!keyStore.containsAlias(ecdhAlias)) {
                val keyPairGenerator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    ANDROID_KEYSTORE
                )
                val parameterSpec = KeyGenParameterSpec.Builder(
                    ecdhAlias,
                    KeyProperties.PURPOSE_AGREE_KEY
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
    
                keyPairGenerator.initialize(parameterSpec)
                keyPairGenerator.generateKeyPair()
            }

            // 2. ECDSA Digital Signature Keypair (Sender authentication & anti-spoofing)
            if (!keyStore.containsAlias(signAlias)) {
                val signGen = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    ANDROID_KEYSTORE
                )
                val signSpec = KeyGenParameterSpec.Builder(
                    signAlias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()

                signGen.initialize(signSpec)
                signGen.generateKeyPair()
            }

            // 3. Hardware-backed HMAC key for deterministic self-storage KDF
            if (!keyStore.containsAlias(hmacAlias)) {
                val keyGen = javax.crypto.KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    ANDROID_KEYSTORE
                )
                val hmacSpec = KeyGenParameterSpec.Builder(
                    hmacAlias,
                    KeyProperties.PURPOSE_SIGN
                )
                    .setUserAuthenticationRequired(false)
                    .build()

                keyGen.init(hmacSpec)
                keyGen.generateKey()
            }
        }
    }

    fun getMyPublicKey(accountId: String? = null): PublicKey {
        generateKeyPairIfNeeded(accountId)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = getKeyAlias(accountId)
        val cert = keyStore.getCertificate(alias) ?: keyStore.getCertificate(KEY_ALIAS)
        return cert.publicKey
    }

    fun getMySigningPublicKey(accountId: String? = null): PublicKey {
        generateKeyPairIfNeeded(accountId)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = getSigningKeyAlias(accountId)
        val cert = keyStore.getCertificate(alias) ?: keyStore.getCertificate(SIGNING_KEY_ALIAS)
        return cert.publicKey
    }

    private fun getMyPrivateKey(accountId: String? = null): PrivateKey {
        generateKeyPairIfNeeded(accountId)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = getKeyAlias(accountId)
        val key = keyStore.getKey(alias, null) ?: keyStore.getKey(KEY_ALIAS, null)
        return key as PrivateKey
    }

    private fun getMySigningPrivateKey(accountId: String? = null): PrivateKey {
        generateKeyPairIfNeeded(accountId)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = getSigningKeyAlias(accountId)
        val key = keyStore.getKey(alias, null) ?: keyStore.getKey(SIGNING_KEY_ALIAS, null)
        return key as PrivateKey
    }

    /**
     * Wipes byte array memory to mitigate forensic memory dumps.
     */
    fun zeroize(bytes: ByteArray?) {
        if (bytes != null) {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    /**
     * Derives a 256-bit deterministic symmetric key for encrypting personal saved messages / notes to self.
     * Uses HMAC-SHA256 initialized from AndroidKeyStore over a fixed domain separator.
     * This derivation is 100% deterministic across app sessions and cannot be forged externally.
     */
    fun deriveSelfStorageSecret(): ByteArray {
        generateKeyPairIfNeeded()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey = keyStore.getKey(SELF_STORAGE_HMAC_ALIAS, null) as javax.crypto.SecretKey
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        val domainSeparator = "HEXSHARD_DETERMINISTIC_SELF_STORAGE_V3".toByteArray(Charsets.UTF_8)
        return mac.doFinal(domainSeparator)
    }

    /**
     * Derives shared secret with ANOTHER user's public key using ECDH.
     * Intermediate buffers are safely zeroized.
     */
    fun deriveSharedSecret(otherPublicKey: PublicKey): ByteArray {
        val myPublicKey = getMyPublicKey()
        if (otherPublicKey.encoded.contentEquals(myPublicKey.encoded)) {
            throw IllegalArgumentException(
                "SECURITY VIOLATION: Cannot derive shared secret using your own public key! " +
                "This breaks E2E encryption. Use the RECIPIENT's public key."
            )
        }

        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
        keyAgreement.init(getMyPrivateKey())
        keyAgreement.doPhase(otherPublicKey, true)
        val rawSecret = keyAgreement.generateSecret()
        
        return try {
            val messageDigest = MessageDigest.getInstance(HASH_ALGORITHM)
            messageDigest.digest(rawSecret)
        } finally {
            zeroize(rawSecret)
        }
    }

    /**
     * Derives a unique per-message ephemeral key using HMAC-SHA256 from the ECDH shared secret.
     * Provides per-message domain separation and nonce binding.
     * Note: Per-message key derivation from static ECDH shared secret; not a Double Ratchet PFS implementation.
     */
    fun deriveMessageKey(masterSecret: ByteArray, messageNonce: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterSecret, "HmacSHA256"))
        mac.update("HEXSHARD_MSG_KEY_V2".toByteArray(Charsets.UTF_8))
        return mac.doFinal(messageNonce.toByteArray(Charsets.UTF_8))
    }

    /**
     * Signs data using the sender's private ECDSA key in hardware KeyStore.
     */
    fun signData(data: ByteArray): ByteArray {
        val signer = Signature.getInstance(SIGNING_ALGORITHM)
        signer.initSign(getMySigningPrivateKey())
        signer.update(data)
        return signer.sign()
    }

    /**
     * Verifies sender signature using the sender's public ECDSA key.
     */
    fun verifySignature(data: ByteArray, signature: ByteArray, senderPublicKey: PublicKey): Boolean {
        return try {
            val verifier = Signature.getInstance(SIGNING_ALGORITHM)
            verifier.initVerify(senderPublicKey)
            verifier.update(data)
            verifier.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Encrypts data using AES-256-GCM with a random 96-bit IV.
     * Returns (IV, CipherText) pair. IV must be transmitted with ciphertext.
     * NEVER reuse the same IV with the same key!
     */
    fun encryptData(data: ByteArray, sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        if (sharedSecret.size != 32) {
            throw IllegalArgumentException("Shared secret must be 256 bits (32 bytes), got ${sharedSecret.size}")
        }

        val secretKey = SecretKeySpec(sharedSecret, 0, 32, "AES")
        val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
        
        // Generate cryptographically secure random 96-bit IV (12 bytes)
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val cipherText = cipher.doFinal(data)
        
        return Pair(iv, cipherText)
    }

    /**
     * Decrypts data encrypted with encryptData().
     * Throws exception on authentication failure (GCM tag mismatch = tampering detected).
     */
    fun decryptData(iv: ByteArray, cipherText: ByteArray, sharedSecret: ByteArray): ByteArray {
        if (iv.size != 12) {
            throw IllegalArgumentException("IV must be 96 bits (12 bytes), got ${iv.size}")
        }
        if (sharedSecret.size != 32) {
            throw IllegalArgumentException("Shared secret must be 256 bits (32 bytes), got ${sharedSecret.size}")
        }

        val secretKey = SecretKeySpec(sharedSecret, 0, 32, "AES")
        val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        // Will throw AEADBadTagException if tag verification fails (message tampered)
        return cipher.doFinal(cipherText)
    }

    fun encryptMessage(plainText: String, sharedSecret: ByteArray): Pair<ByteArray, ByteArray> {
        return encryptData(plainText.toByteArray(Charsets.UTF_8), sharedSecret)
    }

    fun decryptMessage(iv: ByteArray, cipherText: ByteArray, sharedSecret: ByteArray): String {
        val plainTextBytes = decryptData(iv, cipherText, sharedSecret)
        return String(plainTextBytes, Charsets.UTF_8)
    }

    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun fromBase64(str: String): ByteArray = Base64.getDecoder().decode(str)

    /**
     * Creates HMAC-SHA256 over combined (IV + CipherText).
     */
    fun createHMAC(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Constant-time verification of HMAC to prevent timing side-channel attacks.
     */
    fun verifyHMAC(data: ByteArray, hmac: ByteArray, key: ByteArray): Boolean {
        val computed = createHMAC(data, key)
        return MessageDigest.isEqual(computed, hmac)
    }

    /**
     * Securely deletes cryptographic keypairs from AndroidKeyStore upon account deletion.
     */
    fun deleteKeys(accountId: String? = null) {
        synchronized(this) {
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val aliasesToDelete = mutableListOf(KEY_ALIAS, SIGNING_KEY_ALIAS, SELF_STORAGE_HMAC_ALIAS)
                if (!accountId.isNullOrBlank()) {
                    aliasesToDelete.add(getKeyAlias(accountId))
                    aliasesToDelete.add(getSigningKeyAlias(accountId))
                    aliasesToDelete.add(getSelfHmacAlias(accountId))
                }
                for (alias in aliasesToDelete.distinct()) {
                    if (keyStore.containsAlias(alias)) {
                        keyStore.deleteEntry(alias)
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Error deleting cryptographic keys from AndroidKeyStore")
            }
        }
    }
}
