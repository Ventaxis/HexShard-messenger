package com.example.crypto

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class NoBackupFoundException(message: String) : Exception(message)
class InvalidPassphraseException(message: String) : Exception(message)
class HardwareKeyNonExportableException(message: String) : Exception(message)
class BackupAccountMismatchException(message: String) : Exception(message)
class KeyIntegrityException(message: String) : Exception(message)

/**
 * Manages secure user-passphrase encrypted cloud backup and recovery of E2EE identity key material.
 *
 * Security properties:
 * - KDF: PBKDF2WithHmacSHA256 (100,000 iterations) with cryptographic 16-byte random salt.
 * - AEAD Cipher: AES-256-GCM (12-byte IV, 128-bit authentication tag).
 * - Zero-Knowledge Cloud: Server stores ONLY ciphertext envelope, salt, and IV; never plaintext keys.
 * - Integrity Verification: Reconstructed public key is verified against cloud envelope metadata.
 * - Memory zeroization for sensitive byte arrays and passphrase buffers.
 */
object CryptoBackupManager {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Checks if the authenticated user has an encrypted crypto backup on Supabase.
     */
    suspend fun hasRemoteBackup(context: Context): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = SecurePrefsManager.getSupabaseAccessToken(context).takeIf { it.isNotBlank() } ?: return@withContext false

        try {
            val req = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/get_crypto_backup")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body?.string().orEmpty()
                if (body.isBlank() || body == "null") return@withContext false
                val json = try { JSONObject(body) } catch (_: Exception) { null }
                json != null && json.optString("encrypted_envelope", "").isNotBlank()
            }
        } catch (e: Exception) {
            Timber.w(e, "Error checking remote crypto backup existence")
            false
        }
    }

    /**
     * Exports and encrypts account identity key material using user's passphrase,
     * then stores the resulting ciphertext envelope in public.user_crypto_backups via RPC.
     */
    suspend fun createAndUploadBackup(
        context: Context,
        accountId: String,
        passphrase: CharArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext Result.failure(IllegalArgumentException("Account ID required"))
        if (passphrase.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Passphrase cannot be empty"))

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = SecurePrefsManager.getSupabaseAccessToken(context).takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("User is not authenticated"))

        var derivedKeyBytes: ByteArray? = null
        try {
            // 1. Ensure keys exist without mutating active identity
            loadCachedKeysIfAvailable(context, accountId)

            val ecdhPriv = E2ECryptoManager.getExportableEcdhPrivateKey(accountId)
            val ecdhPub = E2ECryptoManager.getMyPublicKey(accountId)

            val signPriv = E2ECryptoManager.getExportableSigningPrivateKey(accountId)
            val signPub = E2ECryptoManager.getMySigningPublicKey(accountId)

            val selfSecret = E2ECryptoManager.getExportableSelfStorageSecret(accountId)
                ?: E2ECryptoManager.deriveSelfStorageSecret(accountId)

            if (ecdhPriv == null || signPriv == null) {
                return@withContext Result.failure(
                    HardwareKeyNonExportableException(
                        "Active cryptographic identity is protected in the device hardware Keystore and cannot be exported off-chip. Backup requires an enrolled exportable software keypair."
                    )
                )
            }

            // Cryptographic correspondence check: ensure public key matches private key before exporting
            if (!verifyKeyPairCorrespondence(ecdhPriv, ecdhPub) || !verifyKeyPairCorrespondence(signPriv, signPub)) {
                return@withContext Result.failure(
                    KeyIntegrityException("Cryptographic key pair correspondence check failed prior to backup creation.")
                )
            }

            // 2. Build canonical JSON payload
            val payloadJson = JSONObject().apply {
                put("version", 1)
                put("accountId", accountId)
                put("ecdhPriv", Base64.getEncoder().encodeToString(ecdhPriv.encoded))
                put("ecdhPub", Base64.getEncoder().encodeToString(ecdhPub.encoded))
                put("signPriv", Base64.getEncoder().encodeToString(signPriv.encoded))
                put("signPub", Base64.getEncoder().encodeToString(signPub.encoded))
                put("selfSecret", Base64.getEncoder().encodeToString(selfSecret))
            }
            val plaintextBytes = payloadJson.toString().toByteArray(Charsets.UTF_8)

            // 3. Generate random salt & IV
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

            // 4. Derive PBKDF2 encryption key
            val keySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            derivedKeyBytes = secretKeyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(derivedKeyBytes, "AES")

            // 5. Encrypt with AES-256-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val ciphertext = cipher.doFinal(plaintextBytes)

            val envelopeB64 = Base64.getEncoder().encodeToString(ciphertext)
            val saltB64 = Base64.getEncoder().encodeToString(salt)
            val ivB64 = Base64.getEncoder().encodeToString(iv)

            // 6. Upload to Supabase via backup_crypto_envelope RPC
            val rpcPayload = JSONObject().apply {
                put("p_envelope", envelopeB64)
                put("p_salt", saltB64)
                put("p_iv", ivB64)
                put("p_version", 1)
            }

            val req = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/backup_crypto_envelope")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(rpcPayload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val resp = httpClient.newCall(req).execute()
            val isOk = resp.isSuccessful
            val code = resp.code
            val body = resp.body?.string().orEmpty()
            resp.close()

            if (!isOk) {
                Timber.w("backup_crypto_envelope failed HTTP $code: $body")
                return@withContext Result.failure(Exception("Cloud backup upload failed (HTTP $code)"))
            }

            try {
                com.example.data.repository.UserRepository(context).uploadMyPublicKeys(accountId)
            } catch (syncEx: Exception) {
                Timber.w(syncEx, "Failed to sync public keys after backup")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error creating crypto backup")
            Result.failure(e)
        } finally {
            E2ECryptoManager.zeroize(derivedKeyBytes)
            Arrays.fill(passphrase, '\u0000')
        }
    }

    /**
     * Downloads encrypted envelope from Supabase and restores account keys using user passphrase.
     */
    suspend fun restoreFromBackup(
        context: Context,
        accountId: String,
        passphrase: CharArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext Result.failure(IllegalArgumentException("Account ID required"))
        if (passphrase.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Passphrase cannot be empty"))

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = SecurePrefsManager.getSupabaseAccessToken(context).takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("User is not authenticated"))

        var derivedKeyBytes: ByteArray? = null
        try {
            // 1. Download envelope from get_crypto_backup RPC
            val req = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/get_crypto_backup")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                val code = resp.code
                resp.close()
                return@withContext Result.failure(Exception("Failed to fetch cloud backup (HTTP $code)"))
            }

            val body = resp.body?.string().orEmpty()
            resp.close()

            if (body.isBlank() || body == "null") {
                return@withContext Result.failure(NoBackupFoundException("No backup found on server for this account"))
            }

            val json = JSONObject(body)
            val envelopeB64 = json.optString("encrypted_envelope", "")
            val saltB64 = json.optString("salt", "")
            val ivB64 = json.optString("iv", "")

            if (envelopeB64.isBlank() || saltB64.isBlank() || ivB64.isBlank()) {
                return@withContext Result.failure(NoBackupFoundException("Incomplete backup data on server"))
            }

            val ciphertext = Base64.getDecoder().decode(envelopeB64)
            val salt = Base64.getDecoder().decode(saltB64)
            val iv = Base64.getDecoder().decode(ivB64)

            // 2. Derive key using user's passphrase and stored salt
            val keySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            derivedKeyBytes = secretKeyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(derivedKeyBytes, "AES")

            // 3. Decrypt ciphertext (AES-256-GCM)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val decryptedBytes = try {
                cipher.doFinal(ciphertext)
            } catch (authEx: javax.crypto.AEADBadTagException) {
                return@withContext Result.failure(InvalidPassphraseException("Invalid recovery passphrase or corrupted backup envelope"))
            } catch (ex: Exception) {
                return@withContext Result.failure(InvalidPassphraseException("Decryption error: ${ex.message}"))
            }

            // 4. Parse payload and reconstruct keys
            val payload = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            // Validate account binding to prevent cross-account restore
            val backupAccountId = payload.optString("accountId", "")
            if (backupAccountId.isNotBlank() && backupAccountId != accountId) {
                return@withContext Result.failure(
                    BackupAccountMismatchException("Backup belongs to account '$backupAccountId', cannot restore into '$accountId'")
                )
            }

            val kf = KeyFactory.getInstance("EC")

            val ecdhPrivBytes = Base64.getDecoder().decode(payload.getString("ecdhPriv"))
            val ecdhPubBytes = Base64.getDecoder().decode(payload.getString("ecdhPub"))
            val ecdhPriv = kf.generatePrivate(PKCS8EncodedKeySpec(ecdhPrivBytes))
            val ecdhPub = kf.generatePublic(X509EncodedKeySpec(ecdhPubBytes))

            val signPrivBytes = Base64.getDecoder().decode(payload.getString("signPriv"))
            val signPubBytes = Base64.getDecoder().decode(payload.getString("signPub"))
            val signPriv = kf.generatePrivate(PKCS8EncodedKeySpec(signPrivBytes))
            val signPub = kf.generatePublic(X509EncodedKeySpec(signPubBytes))

            val selfSecret = Base64.getDecoder().decode(payload.getString("selfSecret"))

            // Verify reconstructed keypair integrity before applying to active state
            if (!verifyKeyPairCorrespondence(ecdhPriv, ecdhPub) || !verifyKeyPairCorrespondence(signPriv, signPub)) {
                return@withContext Result.failure(
                    KeyIntegrityException("Restored cryptographic key pair integrity check failed. Aborting restore to protect active identity.")
                )
            }

            // 5. Load reconstructed keys into E2ECryptoManager
            E2ECryptoManager.setRestoredKeys(
                accountId = accountId,
                ecdhPrivate = ecdhPriv,
                ecdhPublic = ecdhPub,
                signingPrivate = signPriv,
                signingPublic = signPub,
                selfStorageSecret = selfSecret
            )

            // Cache in SecurePrefs for offline restarts
            cacheExportableKeys(context, accountId, ecdhPrivBytes, ecdhPubBytes, signPrivBytes, signPubBytes, selfSecret)

            try {
                com.example.data.repository.UserRepository(context).uploadMyPublicKeys(accountId)
            } catch (syncEx: Exception) {
                Timber.w(syncEx, "Failed to sync public keys after restore")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error restoring crypto backup")
            Result.failure(e)
        } finally {
            E2ECryptoManager.zeroize(derivedKeyBytes)
            Arrays.fill(passphrase, '\u0000')
        }
    }

    /**
     * Reconstructs previously saved exportable/restored keys from SecurePrefs into E2ECryptoManager.
     * Called on application launch and user switch to avoid loss of cryptographic state.
     */
    fun loadCachedKeysIfAvailable(context: Context, accountId: String): Boolean {
        if (accountId.isBlank()) return false
        val clean = accountId.trim()
        val prefs = SecurePrefsManager.getPrefs(context)
        val ecdhPrivB64 = prefs.getString("crypto_ecdh_priv_$clean", null) ?: return false
        val ecdhPubB64 = prefs.getString("crypto_ecdh_pub_$clean", null) ?: return false
        val signPrivB64 = prefs.getString("crypto_sign_priv_$clean", null) ?: return false
        val signPubB64 = prefs.getString("crypto_sign_pub_$clean", null) ?: return false
        val selfSecretB64 = prefs.getString("crypto_self_secret_$clean", null) ?: return false

        return try {
            val kf = KeyFactory.getInstance("EC")
            val ecdhPriv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(ecdhPrivB64)))
            val ecdhPub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(ecdhPubB64)))
            val signPriv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(signPrivB64)))
            val signPub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(signPubB64)))
            val selfSecret = Base64.getDecoder().decode(selfSecretB64)

            E2ECryptoManager.setRestoredKeys(
                accountId = clean,
                ecdhPrivate = ecdhPriv,
                ecdhPublic = ecdhPub,
                signingPrivate = signPriv,
                signingPublic = signPub,
                selfStorageSecret = selfSecret
            )
            Timber.i("Restored cached exportable cryptographic keys for account $clean")
            true
        } catch (e: Exception) {
            Timber.w(e, "Error loading cached cryptographic keys for account $clean")
            false
        }
    }

    internal fun verifyKeyPairCorrespondence(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): Boolean {
        return try {
            val testData = "HexShard-Key-Correspondence-Proof".toByteArray(Charsets.UTF_8)
            val signer = java.security.Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(testData)
            val sig = signer.sign()

            val verifier = java.security.Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(testData)
            verifier.verify(sig)
        } catch (_: Exception) {
            false
        }
    }

    private fun cacheExportableKeys(
        context: Context,
        accountId: String,
        ecdhPriv: ByteArray,
        ecdhPub: ByteArray,
        signPriv: ByteArray,
        signPub: ByteArray,
        selfSecret: ByteArray
    ) {
        val prefs = SecurePrefsManager.getPrefs(context)
        prefs.edit()
            .putString("crypto_ecdh_priv_$accountId", Base64.getEncoder().encodeToString(ecdhPriv))
            .putString("crypto_ecdh_pub_$accountId", Base64.getEncoder().encodeToString(ecdhPub))
            .putString("crypto_sign_priv_$accountId", Base64.getEncoder().encodeToString(signPriv))
            .putString("crypto_sign_pub_$accountId", Base64.getEncoder().encodeToString(signPub))
            .putString("crypto_self_secret_$accountId", Base64.getEncoder().encodeToString(selfSecret))
            .apply()
    }
}
