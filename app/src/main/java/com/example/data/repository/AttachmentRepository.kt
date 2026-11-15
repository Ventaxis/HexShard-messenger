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
    // Deliberately longer timeouts than SupabaseHttp.client: large media upload/download streams.
    // callTimeout is the total-call safety net — a trickling stalled connection must
    // never hang a transfer (or the UI waiting on it) indefinitely.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private val BUCKET_NAME = "chat-attachments"
    private val secureRandom = SecureRandom()

    /** Short comparable fingerprint of a public key (DER base64 tail) — the
     *  server-side forensics can recompute the same tail from the stored key,
     *  so a DECRYPT failure screenshot pins down exactly which key was used. */
    private fun keyFp(key: java.security.PublicKey): String =
        java.util.Base64.getEncoder().encodeToString(key.encoded).takeLast(6)

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
            // Owner report 2026-11-15 («Не удалось загрузить медиа (DECRYPT)» on the
            // receiver): attachments encrypted to the PINNED key became undecryptable
            // whenever the recipient reinstalled (fresh keypair on the server, stale
            // pin on the sender) — messages kept working because message outbound
            // selects the SERVED key first. Align attachment upload with
            // CryptoTrustPolicy.outboundKey: served-first, pin as fallback.
            val trusted = userRepository.getTrustedUserPublicKey(recipientId)
            val recPubKey = com.example.crypto.CryptoTrustPolicy.outboundKey(trusted?.key, trusted?.pinnedKey)
            if (recPubKey == null) {
                Timber.w("Recipient public key not found for $logLabel upload")
                return null
            }
            // runCatching: the self-key guard in deriveSharedSecret throws outside
            // the enclosing try — a misresolved recipient id must abort the upload
            // cleanly, not crash the caller (regression review F3/#6, 2026-11-15).
            runCatching { E2ECryptoManager.deriveSharedSecret(recPubKey, currentUserId) }.getOrElse {
                Timber.w(it, "Shared secret derivation failed for $logLabel upload")
                return null
            }
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

            val input = source()
            if (input == null) {
                // A null stream must abort BEFORE the cipher writes anything: an
                // IV-only temp file would upload with valid metadata and produce
                // an honest-looking DECRYPT on the receiver (review F2).
                Timber.w("$logLabel upload aborted: selected file could not be opened")
                return null
            }
            input.use {
                FileOutputStream(tempEncryptedFile).use { fileOut ->
                    // Write IV first
                    fileOut.write(iv)
                    CipherOutputStream(fileOut, cipher).use { cipherOut ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        while (it.read(buffer).also { bytesRead = it } != -1) {
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
        val ctx = context ?: return@withContext null.also { DownloadDiagnostics.set("CTX") }
        val currentUserId = SecurePrefsManager.getUserId(ctx)
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(ctx)
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        if (accessToken.isBlank() || anonKey.isBlank()) {
            Timber.w("Attachment download aborted: user not authenticated or Supabase not configured")
            DownloadDiagnostics.set("AUTH")
            return@withContext null
        }

        val cleanPath = remotePath.removePrefix("$BUCKET_NAME/").trim().removePrefix("/")
        if (cleanPath.contains("..") || cleanPath.contains("\\")) {
            Timber.w("Path traversal detected in remote attachment path: $remotePath")
            DownloadDiagnostics.set("PATH")
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
                DownloadDiagnostics.set("HTTP_${resp.code}")
                resp.close()
                return@withContext null
            }
            val respBody = resp.body ?: run { DownloadDiagnostics.set("NET"); resp.close(); return@withContext null }

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
                var servedSecret: ByteArray? = null
                // Key fingerprints for the DECRYPT diagnostics (outer scope: the
                // self branch has no peer key, direct chats carry both tails).
                var keyDiag = "self"
                val sharedSecret: ByteArray = if (isSelf) {
                    E2ECryptoManager.deriveSelfStorageSecret(currentUserId)
                } else {
                    // Mirror message decryption (CryptoRepository.decryptInboundMessage):
                    // the PRIMARY secret derives from the PINNED sender key — the server
                    // is untrusted for identity decisions, and attachments uploaded
                    // BEFORE a peer rotation were encrypted under it.
                    val trustedSender = userRepository.getTrustedUserPublicKey(senderId)
                        ?: return@withContext null.also { DownloadDiagnostics.set("KEY") }
                    val pinnedSecret = (trustedSender.pinnedKey ?: trustedSender.key)?.let { pubKey ->
                        runCatching { E2ECryptoManager.deriveSharedSecret(pubKey, currentUserId) }.getOrNull()
                    }
                    // Derive the served secret ALWAYS, not only on a flagged rotation:
                    // senders encrypt attachments served-first (mirroring message
                    // outbound), so the served-key secret must be in the retry matrix
                    // even when the receiver sees no pin change.
                    if (trustedSender.key != null) {
                        servedSecret = runCatching {
                            E2ECryptoManager.deriveSharedSecret(trustedSender.key, currentUserId)
                        }.getOrNull()?.takeIf { !it.contentEquals(pinnedSecret) }
                    }
                    val resolvedSecret = pinnedSecret ?: servedSecret ?: return@withContext null.also {
                        DownloadDiagnostics.set("KEY_DERIVE")
                    }
                    keyDiag = "p=${(trustedSender.pinnedKey ?: trustedSender.key)?.let { keyFp(it) } ?: "null"} " +
                        "v=${trustedSender.key?.let { keyFp(it) } ?: "null"}"
                    resolvedSecret
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
                    Timber.w(decEx, "Primary key decryption failed, attempting fallbacks")
                }

                // TOFU rotation fallback: attachments uploaded after the peer rotated
                // keys decrypt only under the served key — retry before legacy.
                if (!decryptedOk && servedSecret != null) {
                    val servedKey = if (idempotencyKey.isNotBlank()) {
                        E2ECryptoManager.deriveMessageKey(servedSecret, idempotencyKey)
                    } else {
                        servedSecret
                    }
                    try {
                        decryptedOk = decryptAttachmentWithKey(tempEncFile, tempDecFile, servedKey)
                    } catch (rotationEx: Exception) {
                        tempDecFile.delete()
                        Timber.w(rotationEx, "Served-key attachment decryption failed")
                    }
                }

                // Legacy fallback: attachments encrypted directly with the raw
                // sharedSecret (pre-KDF uploads) — pinned secret first...
                if (!decryptedOk && primaryKey !== sharedSecret) {
                    try {
                        decryptedOk = decryptAttachmentWithKey(tempEncFile, tempDecFile, sharedSecret)
                    } catch (fallbackEx: Exception) {
                        tempDecFile.delete()
                        Timber.e(fallbackEx, "Attachment decryption fallback failed")
                    }
                }

                // ...then raw SERVED secret — legacy uploads encrypted under the
                // key that was served at upload time, even if the pin changed later
                // (appsec review F1, 2026-11-15).
                if (!decryptedOk && servedSecret != null) {
                    try {
                        decryptedOk = decryptAttachmentWithKey(tempEncFile, tempDecFile, servedSecret)
                    } catch (servedRawEx: Exception) {
                        tempDecFile.delete()
                        Timber.w(servedRawEx, "Raw served-secret attachment decryption failed")
                    }
                }

                if (decryptedOk) {
                    // Atomic publish: rename validated temporary file to final target
                    if (cachedFile.exists()) cachedFile.delete()
                    if (tempDecFile.renameTo(cachedFile)) {
                        cachedFile.absolutePath
                    } else {
                        // Fallback publish ALSO through a temp file: a crash mid-copy
                        // must never leave a truncated dec_* file that later reads
                        // as a cache hit (regression review #3, 2026-11-15).
                        val publishTmp = File(accountCacheDir, "pub_${safeName}.$safeExt")
                        tempDecFile.copyTo(publishTmp, overwrite = true)
                        if (!publishTmp.renameTo(cachedFile)) {
                            publishTmp.copyTo(cachedFile, overwrite = true)
                            publishTmp.delete()
                        }
                        cachedFile.absolutePath
                    }
                } else {
                    // Rich diagnostics: the fingerprint tails are comparable against
                    // the server-stored keys, so one screenshot pins the mismatch.
                    DownloadDiagnostics.set(
                        "DECRYPT s=${senderId.take(8)} $keyDiag k=${idempotencyKey.take(8)}"
                    )
                    null
                }
            } finally {
                tempEncFile.delete()
                if (tempDecFile.exists()) tempDecFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download and decrypt attachment")
            DownloadDiagnostics.set("IO")
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
            DownloadDiagnostics.set("IO_READ")
            null
        }
    }

    /**
     * Last failure reason of [downloadAttachment], read by the UI after a null
     * download so the owner sees WHY media did not load (KEY = peer key fetch,
     * DECRYPT = all key attempts failed, HTTP_xxx, AUTH, IO...).
     */
    object DownloadDiagnostics {
        @Volatile
        var lastReason: String? = null
            private set
        internal fun set(reason: String) { lastReason = reason }
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
