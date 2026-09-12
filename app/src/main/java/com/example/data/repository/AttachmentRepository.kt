package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.crypto.E2ECryptoManager
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AttachmentRepository(
    private val context: Context?,
    private val userRepository: UserRepository
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val BUCKET_NAME = "chat-attachments"
    private val secureRandom = SecureRandom()

    /**
     * Encrypts and uploads an attachment stream to Supabase Storage.
     * Uses streaming CipherOutputStream (AES-256-GCM) into a temporary encrypted file
     * to completely avoid loading large files into memory and preventing OOM.
     */
    suspend fun uploadAttachment(
        inputUri: Uri,
        conversationId: String,
        fileType: String,
        idempotencyKey: String,
        recipientId: String
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val currentUserId = SecurePrefsManager.getUserId(ctx)
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(ctx)
        if (accessToken.isBlank()) {
            Timber.w("Attachment upload aborted: user not authenticated")
            return@withContext null
        }
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        val isSelf = recipientId == "self" || recipientId == "me" || recipientId.isBlank()
        val sharedSecret = if (isSelf) {
            E2ECryptoManager.deriveSelfStorageSecret()
        } else {
            val recPubKey = userRepository.getOrFetchUserPublicKey(recipientId)
            if (recPubKey == null) {
                Timber.w("Recipient public key not found for attachment upload")
                return@withContext null
            }
            E2ECryptoManager.deriveSharedSecret(recPubKey)
        }

        val fileKey = if (idempotencyKey.isNotBlank()) {
            E2ECryptoManager.deriveMessageKey(sharedSecret, idempotencyKey)
        } else {
            sharedSecret
        }

        // Generate 12-byte IV for GCM
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        // Stream encrypt from URI to temp encrypted file
        val tempEncryptedFile = File.createTempFile("enc_attach_", ".bin", ctx.cacheDir)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))

            ctx.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(tempEncryptedFile).use { fileOut ->
                    // Write IV first
                    fileOut.write(iv)
                    CipherOutputStream(fileOut, cipher).use { cipherOut ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            cipherOut.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            // Stream upload to Supabase Storage
            val objectPath = "$conversationId/${UUID.randomUUID()}.bin"
            val url = "$baseUrl/storage/v1/object/$BUCKET_NAME/$objectPath"

            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = tempEncryptedFile.length()
                override fun writeTo(sink: BufferedSink) {
                    tempEncryptedFile.source().use { source ->
                        sink.writeAll(source)
                    }
                }
            }

            val req = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/octet-stream")
                .post(requestBody)
                .build()

            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                objectPath
            } else {
                Timber.w("Supabase storage upload failed: HTTP ${resp.code}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error streaming attachment upload")
            null
        } finally {
            tempEncryptedFile.delete()
        }
    }

    /**
     * Uploads an existing local voice file to Supabase Storage.
     */
    suspend fun uploadVoiceFile(
        file: File,
        conversationId: String,
        idempotencyKey: String = "",
        recipientId: String
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(ctx)
        if (accessToken.isBlank()) {
            Timber.w("Voice upload aborted: user not authenticated")
            return@withContext null
        }
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        val isSelf = recipientId == "self" || recipientId == "me" || recipientId.isBlank()
        val sharedSecret = if (isSelf) {
            E2ECryptoManager.deriveSelfStorageSecret()
        } else {
            val recPubKey = userRepository.getOrFetchUserPublicKey(recipientId)
                ?: return@withContext null
            E2ECryptoManager.deriveSharedSecret(recPubKey)
        }

        val fileKey = if (idempotencyKey.isNotBlank()) {
            E2ECryptoManager.deriveMessageKey(sharedSecret, idempotencyKey)
        } else {
            sharedSecret
        }

        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val tempEncryptedFile = File.createTempFile("enc_voice_", ".bin", ctx.cacheDir)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))

            FileInputStream(file).use { input ->
                FileOutputStream(tempEncryptedFile).use { fileOut ->
                    fileOut.write(iv)
                    CipherOutputStream(fileOut, cipher).use { cipherOut ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            cipherOut.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }

            val objectPath = "$conversationId/voice_${UUID.randomUUID()}.bin"
            val url = "$baseUrl/storage/v1/object/$BUCKET_NAME/$objectPath"

            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = tempEncryptedFile.length()
                override fun writeTo(sink: BufferedSink) {
                    tempEncryptedFile.source().use { source ->
                        sink.writeAll(source)
                    }
                }
            }

            val req = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/octet-stream")
                .post(requestBody)
                .build()

            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                objectPath
            } else {
                Timber.w("Voice upload failed: HTTP ${resp.code}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error streaming voice upload")
            null
        } finally {
            tempEncryptedFile.delete()
        }
    }

    /**
     * Downloads and decrypts an attachment from Supabase Storage into app-private cache.
     */
    suspend fun downloadAttachment(
        remotePath: String,
        senderId: String,
        fileExtension: String = "bin"
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(ctx)
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        if (accessToken.isBlank() || anonKey.isBlank()) {
            Timber.w("Attachment download aborted: user not authenticated or Supabase not configured")
            return@withContext null
        }

        val cleanPath = remotePath.removePrefix("$BUCKET_NAME/").trim().removePrefix("/")
        val safeName = cleanPath.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val safeExt = fileExtension.replace(Regex("[^a-zA-Z0-9]"), "")
        val cachedFile = File(ctx.cacheDir, "dec_${safeName}.$safeExt")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return@withContext cachedFile.absolutePath
        }

        val url = "$baseUrl/storage/v1/object/$BUCKET_NAME/$cleanPath"
        val req = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        try {
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val respBody = resp.body ?: return@withContext null

            // Download encrypted stream to temp file
            val tempEncFile = File.createTempFile("down_enc_", ".bin", ctx.cacheDir)
            try {
                FileOutputStream(tempEncFile).use { out ->
                    respBody.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                }

                // Decrypt file
                val isSelf = senderId == "self" || senderId == "me" || senderId.isBlank()
                val sharedSecret = if (isSelf) {
                    E2ECryptoManager.deriveSelfStorageSecret()
                } else {
                    val senderPubKey = userRepository.getOrFetchUserPublicKey(senderId)
                        ?: return@withContext null
                    E2ECryptoManager.deriveSharedSecret(senderPubKey)
                }

                FileInputStream(tempEncFile).use { fileIn ->
                    val iv = ByteArray(12)
                    val ivRead = fileIn.read(iv)
                    if (ivRead != 12) return@withContext null

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), GCMParameterSpec(128, iv))

                    CipherInputStream(fileIn, cipher).use { cipherIn ->
                        FileOutputStream(cachedFile).use { decOut ->
                            val buf = ByteArray(32 * 1024)
                            var readBytes: Int
                            while (cipherIn.read(buf).also { readBytes = it } != -1) {
                                decOut.write(buf, 0, readBytes)
                            }
                        }
                    }
                }
                cachedFile.absolutePath
            } finally {
                tempEncFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download and decrypt attachment")
            null
        }
    }

    suspend fun downloadAttachmentBytes(
        remotePath: String,
        senderId: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val path = downloadAttachment(remotePath, senderId) ?: return@withContext null
        try {
            File(path).readBytes()
        } catch (e: Exception) {
            Timber.e(e, "Failed to read downloaded attachment bytes")
            null
        }
    }
}
