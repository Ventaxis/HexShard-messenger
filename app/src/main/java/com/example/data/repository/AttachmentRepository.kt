package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.crypto.E2ECryptoManager
import com.example.data.SecurePrefsManager
import com.example.network.SupabaseHttp
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
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
    // Deliberately longer timeouts than SupabaseHttp.client: large media upload/download streams
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
            "image/heic", "image/heif",
            "audio/mp4", "audio/m4a", "audio/ogg", "audio/mpeg", "audio/aac", "audio/wav",
            "video/mp4", "video/webm",
            "application/pdf", "text/plain", "application/octet-stream"
        )

        fun isMimeTypeAllowed(mimeType: String): Boolean {
            // Exact type/subtype match; parameters (e.g. "; charset=utf-8") are stripped.
            // Prefix matching previously accepted hostile look-alikes like "image/png-evil".
            val clean = mimeType.trim().lowercase().substringBefore(';').trim()
            return clean in ALLOWED_MIME_TYPES
        }

        fun clearCacheForAccount(context: Context, accountId: String) {
            if (accountId.isBlank()) return
            try {
                val dir = File(context.cacheDir, "attachments/$accountId")
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            } catch (e: Exception) {
                Timber.w(e, "Error clearing attachment cache for account $accountId")
            }
        }
    }

    /**
     * Encrypts and uploads an attachment stream to Supabase Storage.
     * Uses streaming CipherOutputStream (AES-256-GCM) into a temporary encrypted file
     * to completely avoid loading large files into memory and preventing OOM.
     */
    /**
     * Uploads already-owned raw bytes (e.g. decrypted media being forwarded).
     * Writes them to a temp file and reuses the standard encrypted upload path,
     * producing a NEW ciphertext bound to the target chat's key material.
     */
    suspend fun uploadAttachmentData(
        data: ByteArray,
        conversationId: String,
        fileType: String,
        idempotencyKey: String,
        recipientId: String
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val temp = File.createTempFile("fwd_data_", ".bin", ctx.cacheDir)
        try {
            temp.writeBytes(data)
            uploadAttachment(Uri.fromFile(temp), conversationId, fileType, idempotencyKey, recipientId)
        } finally {
            temp.delete()
        }
    }

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

        encryptAndUploadStream(
            ctx = ctx,
            currentUserId = currentUserId,
            accessToken = accessToken,
            baseUrl = baseUrl,
            anonKey = anonKey,
            conversationId = conversationId,
            recipientId = recipientId,
            idempotencyKey = idempotencyKey,
            mimeType = fileType,
            tempPrefix = "enc_attach_",
            objectName = "${UUID.randomUUID()}.bin",
            source = { ctx.contentResolver.openInputStream(inputUri) },
            logLabel = "attachment"
        )
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

        encryptAndUploadStream(
            ctx = ctx,
            currentUserId = currentUserId,
            accessToken = accessToken,
            baseUrl = baseUrl,
            anonKey = anonKey,
            conversationId = conversationId,
            recipientId = recipientId,
            idempotencyKey = idempotencyKey,
            mimeType = "audio/mp4",
            tempPrefix = "enc_voice_",
            objectName = "voice_${UUID.randomUUID()}.bin",
            source = { FileInputStream(file) },
            logLabel = "voice"
        )
    }

    /**
     * Shared encrypted-upload pipeline for attachments and voice notes: derives the
     * conversation key, streams AES-256-GCM ciphertext (12-byte IV prefix) into a
     * temp file, PUTs it to Storage and registers the attachments-table metadata
     * row. On metadata failure the Storage object is rolled back so no orphaned
     * ciphertext is left behind (uploadVoiceFile previously skipped this rollback).
     * Returns the storage object path, or null when anything failed.
     */
    private suspend fun encryptAndUploadStream(
        ctx: Context,
        currentUserId: String,
        accessToken: String,
        baseUrl: String,
        anonKey: String,
        conversationId: String,
        recipientId: String,
        idempotencyKey: String,
        mimeType: String,
        tempPrefix: String,
        objectName: String,
        source: () -> InputStream?,
        logLabel: String
    ): String? {
        // Substring matching ("ai") would misclassify real peers and encrypt to the wrong key
        val isSelf = recipientId == "self" || recipientId == "me" || recipientId.isBlank() ||
                recipientId == "ai_assistant" || recipientId.startsWith("ai_")
        val sharedSecret = if (isSelf) {
            E2ECryptoManager.deriveSelfStorageSecret(currentUserId)
        } else {
            // TOFU: encrypt to the PINNED key, not whatever the server serves now —
            // same trust model as message encryption (CryptoRepository), otherwise a
            // substituted key would silently re-encrypt the attachment to an attacker.
            val trusted = userRepository.getTrustedUserPublicKey(recipientId)
            val recPubKey = trusted?.pinnedKey ?: trusted?.key
            if (recPubKey == null) {
                Timber.w("Recipient public key not found for $logLabel upload")
                return null
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

        // Stream encrypt from source to temp encrypted file
        val tempEncryptedFile = File.createTempFile(tempPrefix, ".bin", ctx.cacheDir)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))

            source()?.use { input ->
                if (input == null) {
                    Timber.w("$logLabel upload aborted: selected file could not be opened")
                    return null
                }
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
            val objectPath = "$conversationId/$objectName"
            val url = "$baseUrl/storage/v1/object/$BUCKET_NAME/$objectPath"

            val requestBody = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = tempEncryptedFile.length()
                override fun writeTo(sink: BufferedSink) {
                    tempEncryptedFile.source().use { src ->
                        sink.writeAll(src)
                    }
                }
            }

            val req = SupabaseHttp.request(url, accessToken, anonKey)
                .header("Content-Type", "application/octet-stream")
                .post(requestBody)
                .build()

            val resp = httpClient.newCall(req).execute()
            val uploadCode = resp.code
            val uploadBody = resp.body?.string().orEmpty()
            resp.close()
            if (resp.isSuccessful) {
                // Register attachment metadata in attachments table
                var metaSuccess = false
                try {
                    val attachBody = JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("conversation_id", conversationId)
                        // FIX: message rows are created AFTER the upload, so a pre-filled
                        // message_id violated the attachments FK and rolled back every
                        // upload. Insert null and let send_message_idempotent link by
                        // the storage path / idempotency flow.
                        put("message_id", org.json.JSONObject.NULL)
                        put("uploader_id", currentUserId)
                        put("owner_id", currentUserId)
                        put("storage_path", objectPath)
                        put("mime_type", mimeType)
                        put("size_bytes", tempEncryptedFile.length())
                        put("size", tempEncryptedFile.length())
                        put("encryption_version", 2)
                    }.toString().toRequestBody(SupabaseHttp.JSON_MEDIA)

                    val attachReq = SupabaseHttp.request("$baseUrl/rest/v1/attachments", accessToken, anonKey)
                        .post(attachBody)
                        .build()

                    val attachResp = httpClient.newCall(attachReq).execute()
                    val metaBody = attachResp.body?.string().orEmpty()
                    metaSuccess = attachResp.isSuccessful
                    if (!metaSuccess) {
                        Timber.w("$logLabel metadata failed: HTTP ${attachResp.code}: $metaBody")
                    }
                    attachResp.close()
                } catch (metaEx: Exception) {
                    Timber.w(metaEx, "Failed to register $logLabel metadata")
                }

                if (!metaSuccess) {
                    Timber.w("$logLabel metadata insert failed. Rolling back Storage object.")
                    try {
                        val delReq = SupabaseHttp.request(url, accessToken, anonKey)
                            .delete()
                            .build()
                        httpClient.newCall(delReq).execute().close()
                    } catch (delEx: Exception) {
                        Timber.w(delEx, "Failed to rollback Storage object: $objectPath")
                    }
                    return null
                }

                objectPath
            } else {
                Timber.w("Supabase $logLabel upload failed: HTTP $uploadCode: $uploadBody")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error streaming $logLabel upload")
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
        val accountCacheDir = File(ctx.cacheDir, "attachments/$currentUserId").apply {
            if (!exists()) mkdirs()
        }
        val cachedFile = File(accountCacheDir, "dec_${safeName}.$safeExt")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return@withContext cachedFile.absolutePath
        }

        val url = "$baseUrl/storage/v1/object/$BUCKET_NAME/$cleanPath"
        val req = SupabaseHttp.request(url, accessToken, anonKey)
            .get()
            .build()

        try {
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                // Drain and close so the connection returns to the pool
                resp.close()
                return@withContext null
            }
            val respBody = resp.body ?: run { resp.close(); return@withContext null }

            // Download encrypted stream to temp file
            val tempEncFile = File.createTempFile("down_enc_", ".bin", accountCacheDir)
            val tempDecFile = File.createTempFile("down_dec_", ".tmp", accountCacheDir)
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

                // Deterministic shared secret derivation scoped to current account.
                // Must mirror uploadAttachment exactly: AI chats encrypt with the
                // self-storage secret (there is no AI peer key), so their downloads
                // must resolve to isSelf as well, or decryption always fails.
                val isSelf = senderId == "self" || senderId == "me" || senderId == currentUserId || senderId.isBlank() ||
                        senderId == "ai_assistant" || senderId.startsWith("ai_")
                val sharedSecret = if (isSelf) {
                    E2ECryptoManager.deriveSelfStorageSecret(currentUserId)
                } else {
                    // Mirror message decryption: verify against the PINNED sender key,
                    // not whatever the server serves now. Attachments encrypted under
                    // the served key still decrypt via the sharedSecret fallback below.
                    val trustedSender = userRepository.getTrustedUserPublicKey(senderId)
                    val senderPubKey = trustedSender?.pinnedKey ?: trustedSender?.key
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
                    decryptedOk = decryptAttachmentWithKey(tempEncFile, tempDecFile, primaryKey)
                } catch (decEx: Exception) {
                    tempDecFile.delete()
                    Timber.w(decEx, "Primary key decryption failed, attempting legacy sharedSecret fallback")
                }

                // Fallback for legacy attachments encrypted directly with sharedSecret
                if (!decryptedOk && primaryKey !== sharedSecret) {
                    try {
                        decryptedOk = decryptAttachmentWithKey(tempEncFile, tempDecFile, sharedSecret)
                    } catch (fallbackEx: Exception) {
                        tempDecFile.delete()
                        Timber.e(fallbackEx, "Attachment decryption fallback failed")
                    }
                }

                if (decryptedOk) {
                    // Atomic publish: rename validated temporary file to final target
                    if (cachedFile.exists()) cachedFile.delete()
                    if (tempDecFile.renameTo(cachedFile)) {
                        cachedFile.absolutePath
                    } else {
                        // Fallback copy if rename across mount fails
                        tempDecFile.copyTo(cachedFile, overwrite = true)
                        tempDecFile.delete()
                        cachedFile.absolutePath
                    }
                } else {
                    null
                }
            } finally {
                tempEncFile.delete()
                if (tempDecFile.exists()) tempDecFile.delete()
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

    /**
     * Streams [tempEncFile] (12-byte IV prefix + AES-256-GCM ciphertext) into
     * [tempDecFile] using [key]. Returns true when the decrypted output is
     * non-empty; cipher/IO failures propagate to the caller for logging.
     */
    private fun decryptAttachmentWithKey(tempEncFile: File, tempDecFile: File, key: ByteArray): Boolean {
        FileInputStream(tempEncFile).use { fileIn ->
            val iv = ByteArray(12)
            val ivRead = fileIn.read(iv)
            if (ivRead != 12) return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))

            CipherInputStream(fileIn, cipher).use { cipherIn ->
                FileOutputStream(tempDecFile).use { decOut ->
                    val buf = ByteArray(32 * 1024)
                    var readBytes: Int
                    while (cipherIn.read(buf).also { readBytes = it } != -1) {
                        decOut.write(buf, 0, readBytes)
                    }
                }
            }
            return tempDecFile.exists() && tempDecFile.length() > 0
        }
    }
}
