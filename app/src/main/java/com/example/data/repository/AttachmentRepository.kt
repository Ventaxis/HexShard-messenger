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
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
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
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val BUCKET_NAME = "chat-attachments"
    private val secureRandom = SecureRandom()

    companion object {
        private val ALLOWED_MIME_TYPES = setOf(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "audio/mp4", "audio/m4a", "audio/ogg", "audio/mpeg", "audio/aac", "audio/wav",
            "video/mp4", "video/webm",
            "application/pdf", "text/plain", "application/octet-stream"
        )

        fun isMimeTypeAllowed(mimeType: String): Boolean {
            val clean = mimeType.trim().lowercase()
            return ALLOWED_MIME_TYPES.any { clean.startsWith(it) }
        }
    }

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
        if (!isMimeTypeAllowed(fileType)) {
            Timber.w("Upload aborted: disallowed MIME type: $fileType")
            return@withContext null
        }
        val ctx = context ?: return@withContext null
        val currentUserId = SecurePrefsManager.getUserId(ctx)
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(ctx)
        if (accessToken.isBlank()) {
            Timber.w("Attachment upload aborted: user not authenticated")
            return@withContext null
        }
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        val isSelf = recipientId == "self" || recipientId == "me" || recipientId.isBlank() ||
                recipientId == "ai_assistant" || recipientId.startsWith("ai_") || recipientId.contains("ai", ignoreCase = true)
        val sharedSecret = if (isSelf) {
            E2ECryptoManager.deriveSelfStorageSecret(currentUserId)
        } else {
            val recPubKey = userRepository.getOrFetchUserPublicKey(recipientId)
            if (recPubKey == null) {
                Timber.w("Recipient public key not found for attachment upload")
                return@withContext null
            }
            E2ECryptoManager.deriveSharedSecret(recPubKey, currentUserId)
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
                // Register attachment metadata in attachments table
                var metaSuccess = false
                try {
                    val attachBody = JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("conversation_id", conversationId)
                        put("message_id", idempotencyKey.ifBlank { null })
                        put("uploader_id", currentUserId)
                        put("storage_path", objectPath)
                        put("mime_type", fileType)
                        put("size_bytes", tempEncryptedFile.length())
                        put("encryption_version", 2)
                    }.toString().toRequestBody(JSON_MEDIA)

                    val attachReq = Request.Builder()
                        .url("$baseUrl/rest/v1/attachments")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Type", "application/json")
                        .post(attachBody)
                        .build()

                    val attachResp = httpClient.newCall(attachReq).execute()
                    metaSuccess = attachResp.isSuccessful
                    attachResp.close()
                } catch (metaEx: Exception) {
                    Timber.w(metaEx, "Failed to register attachment metadata")
                }

                if (!metaSuccess) {
                    Timber.w("Attachment metadata insert failed. Rolling back Storage object.")
                    try {
                        val delReq = Request.Builder()
                            .url(url)
                            .header("apikey", anonKey)
                            .header("Authorization", "Bearer $accessToken")
                            .delete()
                            .build()
                        httpClient.newCall(delReq).execute().close()
                    } catch (delEx: Exception) {
                        Timber.w(delEx, "Failed to rollback Storage object: $objectPath")
                    }
                    return@withContext null
                }

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
        val currentUserId = SecurePrefsManager.getUserId(ctx)
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(ctx)
        if (accessToken.isBlank()) {
            Timber.w("Voice upload aborted: user not authenticated")
            return@withContext null
        }
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        val isSelf = recipientId == "self" || recipientId == "me" || recipientId.isBlank() ||
                recipientId == "ai_assistant" || recipientId.startsWith("ai_") || recipientId.contains("ai", ignoreCase = true)
        val sharedSecret = if (isSelf) {
            E2ECryptoManager.deriveSelfStorageSecret(currentUserId)
        } else {
            val recPubKey = userRepository.getOrFetchUserPublicKey(recipientId)
                ?: return@withContext null
            E2ECryptoManager.deriveSharedSecret(recPubKey, currentUserId)
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
                // Register voice metadata in attachments table
                try {
                    val attachBody = JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("conversation_id", conversationId)
                        put("message_id", idempotencyKey.ifBlank { null })
                        put("owner_id", currentUserId)
                        put("storage_path", objectPath)
                        put("mime_type", "audio/mp4")
                        put("size", tempEncryptedFile.length())
                        put("encryption_version", 2)
                    }.toString().toRequestBody(JSON_MEDIA)

                    val attachReq = Request.Builder()
                        .url("$baseUrl/rest/v1/attachments")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Type", "application/json")
                        .post(attachBody)
                        .build()

                    httpClient.newCall(attachReq).execute().close()
                } catch (metaEx: Exception) {
                    Timber.w(metaEx, "Failed to register voice metadata")
                }

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
     * Guarantees deterministic key derivation matching upload using idempotencyKey message binding.
     */
    suspend fun downloadAttachment(
        remotePath: String,
        senderId: String,
        idempotencyKey: String = "",
        fileExtension: String = "bin"
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val currentUserId = SecurePrefsManager.getUserId(ctx)
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(ctx)
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        if (accessToken.isBlank() || anonKey.isBlank()) {
            Timber.w("Attachment download aborted: user not authenticated or Supabase not configured")
            return@withContext null
        }

        val cleanPath = remotePath.removePrefix("$BUCKET_NAME/").trim().removePrefix("/")
        if (cleanPath.contains("..") || cleanPath.contains("\\")) {
            Timber.w("Path traversal detected in remote attachment path: $remotePath")
            return@withContext null
        }
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

                // Deterministic shared secret derivation scoped to current account
                val isSelf = senderId == "self" || senderId == "me" || senderId == currentUserId || senderId.isBlank()
                val sharedSecret = if (isSelf) {
                    E2ECryptoManager.deriveSelfStorageSecret(currentUserId)
                } else {
                    val senderPubKey = userRepository.getOrFetchUserPublicKey(senderId)
                        ?: return@withContext null
                    E2ECryptoManager.deriveSharedSecret(senderPubKey, currentUserId)
                }

                val primaryKey = if (idempotencyKey.isNotBlank()) {
                    E2ECryptoManager.deriveMessageKey(sharedSecret, idempotencyKey)
                } else {
                    sharedSecret
                }

                var decryptedOk = false
                // Attempt decryption with primary derived key
                try {
                    FileInputStream(tempEncFile).use { fileIn ->
                        val iv = ByteArray(12)
                        val ivRead = fileIn.read(iv)
                        if (ivRead == 12) {
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(primaryKey, "AES"), GCMParameterSpec(128, iv))

                            CipherInputStream(fileIn, cipher).use { cipherIn ->
                                FileOutputStream(cachedFile).use { decOut ->
                                    val buf = ByteArray(32 * 1024)
                                    var readBytes: Int
                                    while (cipherIn.read(buf).also { readBytes = it } != -1) {
                                        decOut.write(buf, 0, readBytes)
                                    }
                                }
                            }
                            decryptedOk = true
                        }
                    }
                } catch (decEx: Exception) {
                    cachedFile.delete()
                    Timber.w(decEx, "Primary key decryption failed, attempting legacy sharedSecret fallback")
                }

                // Fallback for legacy attachments encrypted directly with sharedSecret
                if (!decryptedOk && primaryKey !== sharedSecret) {
                    try {
                        FileInputStream(tempEncFile).use { fileIn ->
                            val iv = ByteArray(12)
                            val ivRead = fileIn.read(iv)
                            if (ivRead == 12) {
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
                                decryptedOk = true
                            }
                        }
                    } catch (fallbackEx: Exception) {
                        cachedFile.delete()
                        Timber.e(fallbackEx, "Attachment decryption fallback failed")
                    }
                }

                if (decryptedOk) cachedFile.absolutePath else null
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
        senderId: String,
        idempotencyKey: String = ""
    ): ByteArray? = withContext(Dispatchers.IO) {
        val path = downloadAttachment(remotePath, senderId, idempotencyKey) ?: return@withContext null
        try {
            File(path).readBytes()
        } catch (e: Exception) {
            Timber.e(e, "Failed to read downloaded attachment bytes")
            null
        }
    }
}
