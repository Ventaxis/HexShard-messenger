package com.example.data.repository

import android.content.Context
import android.net.Uri
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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ProfileBackgroundInfo(
    val storagePath: String,
    val mimeType: String,
    val publicUrl: String,
    val isVideo: Boolean
)

sealed class BackgroundValidationResult {
    data class Valid(val mimeType: String, val extension: String, val size: Long, val isVideo: Boolean) : BackgroundValidationResult()
    data class Error(val reason: ValidationErrorReason, val details: String = "") : BackgroundValidationResult()
}

enum class ValidationErrorReason {
    FILE_EMPTY,
    FILE_TOO_LARGE,
    UNSUPPORTED_FORMAT,
    READ_ERROR
}

object ProfileBackgroundManager {

    const val MAX_FILE_SIZE_BYTES: Long = 524288L // Strictly 512 KB
    const val BUCKET_NAME: String = "profile-backgrounds"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Validates file size and detects MIME type via magic bytes inspection.
     * Supports:
     * - JPEG (image/jpeg)
     * - PNG (image/png)
     * - WebP (image/webp)
     * - GIF (image/gif)
     * - WebM (video/webm)
     */
    fun validateBytes(bytes: ByteArray, declaredMimeType: String? = null): BackgroundValidationResult {
        val size = bytes.size.toLong()
        if (size <= 0) {
            return BackgroundValidationResult.Error(ValidationErrorReason.FILE_EMPTY, "File size is 0 bytes")
        }
        if (size > MAX_FILE_SIZE_BYTES) {
            return BackgroundValidationResult.Error(
                ValidationErrorReason.FILE_TOO_LARGE,
                "File size ($size bytes) exceeds 512 KB limit ($MAX_FILE_SIZE_BYTES bytes)"
            )
        }

        val detectedFormat = detectFormatFromMagicBytes(bytes)
        if (detectedFormat != null) {
            return detectedFormat
        }

        // Fallback to trusted declared mime type if magic bytes were ambiguous but within valid range
        val cleanDeclared = declaredMimeType?.trim()?.lowercase() ?: ""
        return when {
            cleanDeclared.startsWith("image/jpeg") || cleanDeclared.startsWith("image/jpg") ->
                BackgroundValidationResult.Valid("image/jpeg", "jpg", size, isVideo = false)
            cleanDeclared.startsWith("image/png") ->
                BackgroundValidationResult.Valid("image/png", "png", size, isVideo = false)
            cleanDeclared.startsWith("image/webp") ->
                BackgroundValidationResult.Valid("image/webp", "webp", size, isVideo = false)
            cleanDeclared.startsWith("image/gif") ->
                BackgroundValidationResult.Valid("image/gif", "gif", size, isVideo = false)
            cleanDeclared.startsWith("video/webm") ->
                BackgroundValidationResult.Valid("video/webm", "webm", size, isVideo = true)
            else ->
                BackgroundValidationResult.Error(
                    ValidationErrorReason.UNSUPPORTED_FORMAT,
                    "Unsupported format: $cleanDeclared. Only Images and WebM are permitted."
                )
        }
    }

    private fun detectFormatFromMagicBytes(bytes: ByteArray): BackgroundValidationResult.Valid? {
        if (bytes.size < 4) return null

        // 1. JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return BackgroundValidationResult.Valid("image/jpeg", "jpg", bytes.size.toLong(), isVideo = false)
        }

        // 2. PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() && bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return BackgroundValidationResult.Valid("image/png", "png", bytes.size.toLong(), isVideo = false)
        }

        // 3. GIF: GIF87a or GIF89a
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() && (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) && bytes[5] == 'a'.code.toByte()
        ) {
            return BackgroundValidationResult.Valid("image/gif", "gif", bytes.size.toLong(), isVideo = false)
        }

        // 4. WebP: RIFF .... WEBP
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return BackgroundValidationResult.Valid("image/webp", "webp", bytes.size.toLong(), isVideo = false)
        }

        // 5. WebM (EBML header): 1A 45 DF A3
        if (bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) {
            return BackgroundValidationResult.Valid("video/webm", "webm", bytes.size.toLong(), isVideo = true)
        }

        return null
    }

    /**
     * Reads and validates file from ContentResolver URI.
     */
    fun validateUri(context: Context, uri: Uri): BackgroundValidationResult {
        return try {
            val contentResolver = context.contentResolver
            val declaredMime = contentResolver.getType(uri)

            // Read up to limit + 1 to detect oversized files without loading unbounded streams
            val buffer = ByteArray(MAX_FILE_SIZE_BYTES.toInt() + 1)
            var totalRead = 0

            contentResolver.openInputStream(uri)?.use { stream ->
                while (totalRead < buffer.size) {
                    val read = stream.read(buffer, totalRead, buffer.size - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
            } ?: return BackgroundValidationResult.Error(ValidationErrorReason.READ_ERROR, "Cannot open stream")

            if (totalRead > MAX_FILE_SIZE_BYTES) {
                return BackgroundValidationResult.Error(
                    ValidationErrorReason.FILE_TOO_LARGE,
                    "Selected file exceeds 512 KB maximum limit"
                )
            }

            val validBytes = buffer.copyOf(totalRead)
            validateBytes(validBytes, declaredMime)
        } catch (e: Exception) {
            Timber.e(e, "Error validating URI for profile background")
            BackgroundValidationResult.Error(ValidationErrorReason.READ_ERROR, e.message ?: "Unknown read error")
        }
    }

    /**
     * Constructs public URL for a given storage path.
     */
    fun getPublicUrl(storagePath: String): String {
        val cleanPath = storagePath.trim().removePrefix("/")
        val baseUrl = SupabaseConfig.getBaseUrl()
        return "$baseUrl/storage/v1/object/public/$BUCKET_NAME/$cleanPath"
    }

    /**
     * Uploads and atomically sets a new profile background.
     * Follows strict atomic replacement:
     * 1. Validate file.
     * 2. Upload new file.
     * 3. Update profile row in DB.
     * 4. On success, delete old background file.
     * 5. On failure, remove uploaded new file to avoid orphans and preserve old background.
     */
    suspend fun uploadProfileBackground(context: Context, uri: Uri): Result<ProfileBackgroundInfo> = withContext(Dispatchers.IO) {
        val userId = SecurePrefsManager.getUserId(context).trim()
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(context)
        val anonKey = SupabaseConfig.getAnonKey(context)
        val baseUrl = SupabaseConfig.getBaseUrl()

        if (userId.isBlank() || accessToken.isBlank() || anonKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("User is not authenticated"))
        }

        // 1. Validate
        val validation = validateUri(context, uri)
        if (validation !is BackgroundValidationResult.Valid) {
            val err = (validation as BackgroundValidationResult.Error)
            return@withContext Result.failure(IllegalArgumentException(err.details))
        }

        // Read bytes safely
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IllegalStateException("Failed to read file data"))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }

        if (bytes.size > MAX_FILE_SIZE_BYTES) {
            return@withContext Result.failure(IllegalArgumentException("File size exceeds 512 KB"))
        }

        val oldPath = SecurePrefsManager.getProfileBackgroundPath(context, userId)
        val newFileName = "bg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.${validation.extension}"
        val newPath = "$userId/$newFileName"

        // 2. Upload new file to Supabase Storage
        val uploadUrl = "$baseUrl/storage/v1/object/$BUCKET_NAME/$newPath"
        val requestBody = bytes.toRequestBody(validation.mimeType.toMediaType())

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", validation.mimeType)
            .post(requestBody)
            .build()

        val uploadSuccess = try {
            val resp = httpClient.newCall(uploadReq).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            Timber.e(e, "Supabase storage upload request failed")
            false
        }

        if (!uploadSuccess) {
            return@withContext Result.failure(Exception("Failed to upload file to storage server"))
        }

        // 3. Update public.profiles metadata
        val profileUpdateUrl = "$baseUrl/rest/v1/profiles?id=eq.$userId"
        val profileJson = JSONObject().apply {
            put("profile_background_path", newPath)
            put("profile_background_type", validation.mimeType)
            put("profile_background_updated_at", java.time.Instant.now().toString())
        }.toString().toRequestBody(JSON_MEDIA)

        val profileReq = Request.Builder()
            .url(profileUpdateUrl)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .patch(profileJson)
            .build()

        val dbUpdated = try {
            val resp = httpClient.newCall(profileReq).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            Timber.e(e, "Profile database update failed")
            false
        }

        if (!dbUpdated) {
            // Rollback newly uploaded orphaned file
            deleteStorageObject(baseUrl, anonKey, accessToken, newPath)
            return@withContext Result.failure(Exception("Failed to update profile record in database"))
        }

        // 4. Update local secure preferences
        SecurePrefsManager.setProfileBackground(context, userId, newPath, validation.mimeType)

        // 5. Clean up old background file if exists and different
        if (!oldPath.isNullOrBlank() && oldPath != newPath) {
            try {
                deleteStorageObject(baseUrl, anonKey, accessToken, oldPath)
            } catch (delEx: Exception) {
                Timber.w(delEx, "Non-fatal: failed to delete old background $oldPath")
            }
        }

        val info = ProfileBackgroundInfo(
            storagePath = newPath,
            mimeType = validation.mimeType,
            publicUrl = getPublicUrl(newPath),
            isVideo = validation.isVideo
        )
        Result.success(info)
    }

    /**
     * Removes the profile background completely.
     */
    suspend fun removeProfileBackground(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val userId = SecurePrefsManager.getUserId(context).trim()
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(context)
        val anonKey = SupabaseConfig.getAnonKey(context)
        val baseUrl = SupabaseConfig.getBaseUrl()

        if (userId.isBlank() || accessToken.isBlank() || anonKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("User is not authenticated"))
        }

        val oldPath = SecurePrefsManager.getProfileBackgroundPath(context, userId)

        // 1. Update DB profile row to null
        val profileUpdateUrl = "$baseUrl/rest/v1/profiles?id=eq.$userId"
        val profileJson = JSONObject().apply {
            put("profile_background_path", JSONObject.NULL)
            put("profile_background_type", JSONObject.NULL)
            put("profile_background_updated_at", java.time.Instant.now().toString())
        }.toString().toRequestBody(JSON_MEDIA)

        val profileReq = Request.Builder()
            .url(profileUpdateUrl)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .patch(profileJson)
            .build()

        val dbUpdated = try {
            val resp = httpClient.newCall(profileReq).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove profile background from database")
            false
        }

        if (!dbUpdated) {
            return@withContext Result.failure(Exception("Failed to update profile record"))
        }

        // 2. Clear local prefs
        SecurePrefsManager.clearProfileBackground(context, userId)

        // 3. Delete old file from storage
        if (!oldPath.isNullOrBlank()) {
            try {
                deleteStorageObject(baseUrl, anonKey, accessToken, oldPath)
            } catch (e: Exception) {
                Timber.w(e, "Non-fatal: failed to delete removed background file")
            }
        }

        Result.success(Unit)
    }

    /**
     * Synchronizes profile background metadata from Supabase profiles table.
     */
    suspend fun syncProfileBackground(context: Context): ProfileBackgroundInfo? = withContext(Dispatchers.IO) {
        val userId = SecurePrefsManager.getUserId(context).trim()
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(context)
        val anonKey = SupabaseConfig.getAnonKey(context)
        val baseUrl = SupabaseConfig.getBaseUrl()

        if (userId.isBlank() || anonKey.isBlank()) return@withContext null
        val token = accessToken.ifBlank { anonKey }

        try {
            val url = "$baseUrl/rest/v1/profiles?id=eq.$userId&select=profile_background_path,profile_background_type"
            val req = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()

            if (resp.isSuccessful && body.isNotBlank()) {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val bgPath = obj.optString("profile_background_path", "").takeIf { it.isNotBlank() && it != "null" }
                    val bgType = obj.optString("profile_background_type", "").takeIf { it.isNotBlank() && it != "null" }

                    SecurePrefsManager.setProfileBackground(context, userId, bgPath, bgType)

                    if (bgPath != null) {
                        return@withContext ProfileBackgroundInfo(
                            storagePath = bgPath,
                            mimeType = bgType ?: "image/jpeg",
                            publicUrl = getPublicUrl(bgPath),
                            isVideo = bgType?.contains("webm", ignoreCase = true) == true
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to sync profile background from Supabase")
        }
        null
    }

    private fun deleteStorageObject(baseUrl: String, anonKey: String, accessToken: String, objectPath: String) {
        val clean = objectPath.trim().removePrefix("/")
        val delUrl = "$baseUrl/storage/v1/object/$BUCKET_NAME/$clean"
        val req = Request.Builder()
            .url(delUrl)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()

        httpClient.newCall(req).execute().close()
    }
}
