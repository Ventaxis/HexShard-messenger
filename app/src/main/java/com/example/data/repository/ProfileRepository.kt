package com.example.data.repository

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

data class UserProfileData(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val dateOfBirth: String,
    val avatarUrl: String,
    val hexNumber: String,
    val backgroundPath: String? = null,
    val backgroundType: String? = null
)

class ProfileRepository(
    private val context: Context? = null
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * Fetches the user profile from Supabase profiles table.
     * Server is the authoritative source of truth:
     * If a field is empty or null on the server, local cache is cleared accordingly.
     */
    suspend fun fetchProfileFromServer(userId: String, token: String): UserProfileData? = withContext(Dispatchers.IO) {
        if (userId.isBlank() || token.isBlank()) return@withContext null
        val ctx = context ?: return@withContext null
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        try {
            // Use select=* so PostgREST returns all present columns without failing if optional schema columns are missing
            val url = "$baseUrl/rest/v1/profiles?id=eq.$userId&select=*&limit=1"
            val request = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.isNotEmpty()) {
                val array = JSONArray(body)
                if (array.length() > 0) {
                    val obj = array.getJSONObject(0)
                    val rawUsername = obj.optString("username", "")
                    val rawDisplayName = obj.optString("display_name", "")
                    val serverDisplayName = if (rawDisplayName.isNotBlank()) rawDisplayName else rawUsername
                    val serverBio = if (obj.has("bio")) obj.optString("bio", "") else SecurePrefsManager.getBio(ctx, userId)
                    val serverDob = if (obj.has("date_of_birth")) obj.optString("date_of_birth", "") else SecurePrefsManager.getDateOfBirth(ctx, userId)
                    val serverAvatar = if (obj.has("avatar_url")) obj.optString("avatar_url", "") else (SecurePrefsManager.getAvatarUri(ctx, userId) ?: "")
                    val serverHex = if (obj.has("hex_number")) obj.optString("hex_number", "") else SecurePrefsManager.getPrivateVirtualNumber(ctx, userId)
                    val serverBgPath = if (obj.has("profile_background_path")) {
                        if (obj.isNull("profile_background_path")) null else obj.optString("profile_background_path", "").takeIf { it.isNotBlank() }
                    } else {
                        SecurePrefsManager.getProfileBackgroundPath(ctx, userId)
                    }
                    val serverBgType = if (obj.has("profile_background_type")) {
                        if (obj.isNull("profile_background_type")) null else obj.optString("profile_background_type", "").takeIf { it.isNotBlank() }
                    } else {
                        SecurePrefsManager.getProfileBackgroundType(ctx, userId)
                    }

                    val profile = UserProfileData(
                        id = obj.optString("id", userId),
                        username = rawUsername,
                        displayName = serverDisplayName,
                        bio = serverBio,
                        dateOfBirth = serverDob,
                        avatarUrl = serverAvatar,
                        hexNumber = serverHex,
                        backgroundPath = serverBgPath,
                        backgroundType = serverBgType
                    )

                    // Synchronize with local storage - strictly account-scoped, server-authoritative for present columns
                    SecurePrefsManager.setDisplayName(ctx, serverDisplayName, userId)
                    if (obj.has("bio")) {
                        SecurePrefsManager.setBio(ctx, serverBio, userId)
                    }
                    if (obj.has("date_of_birth")) {
                        SecurePrefsManager.setDateOfBirth(ctx, serverDob, userId)
                    }
                    if (obj.has("avatar_url")) {
                        SecurePrefsManager.setAvatarUri(ctx, serverAvatar, userId)
                    }
                    if (obj.has("profile_background_path")) {
                        SecurePrefsManager.setProfileBackground(ctx, userId, serverBgPath, serverBgType)
                    }

                    if (obj.has("hex_number")) {
                        if (serverHex.isNotBlank()) {
                            val clean = serverHex.filter { it.isDigit() }
                            if (clean.length == 8) {
                                SecurePrefsManager.setPrivateVirtualNumber(ctx, clean, userId)
                            }
                        } else {
                            SecurePrefsManager.clearPrivateVirtualNumber(ctx, userId)
                        }
                    }

                    return@withContext profile
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching profile from server for user $userId")
        }
        null
    }

    /**
     * Synchronizes profile fields (displayName, bio, date_of_birth, username) to Supabase profiles table.
     * Uses Prefer: return=representation and strictly verifies that the row was updated.
     * Resilient to schema cache mismatches: if optional columns (like date_of_birth) are not yet in the
     * remote schema cache, persists them locally and retries remote sync without failing.
     * Returns Result<UserProfileData> to eliminate fake successes.
     */
    suspend fun syncProfileToServer(
        userId: String,
        token: String,
        displayName: String? = null,
        bio: String? = null,
        dob: String? = null,
        username: String? = null
    ): Result<UserProfileData> = withContext(Dispatchers.IO) {
        if (userId.isBlank() || token.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("User ID and access token are required"))
        }
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("Context is not available"))
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        if (anonKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Supabase anon key is missing"))
        }

        try {
            val payload = JSONObject()
            if (displayName != null) {
                payload.put("display_name", displayName.trim())
            }
            if (bio != null) {
                payload.put("bio", bio.trim())
            }
            if (dob != null) {
                payload.put("date_of_birth", dob.trim())
            }
            if (username != null) {
                val cleanUser = username.trim().removePrefix("@")
                payload.put("username", cleanUser)
                payload.put("normalized_username", cleanUser.lowercase(java.util.Locale.ROOT))
            }

            if (payload.length() == 0) {
                val cached = fetchProfileFromServer(userId, token)
                return@withContext if (cached != null) Result.success(cached) else Result.failure(IllegalStateException("No changes to sync"))
            }

            val omittedColumns = mutableSetOf<String>()
            var lastResponseBody = ""
            var lastResponseCode = 0
            var finalObj: JSONObject? = null

            // Retry loop in case server schema cache is missing optional columns (e.g. date_of_birth)
            while (payload.length() > 0) {
                val url = "$baseUrl/rest/v1/profiles?id=eq.$userId"
                val request = Request.Builder()
                    .url(url)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=representation")
                    .patch(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = httpClient.newCall(request).execute()
                lastResponseCode = response.code
                lastResponseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val array = JSONArray(lastResponseBody)
                    if (array.length() > 0) {
                        finalObj = array.getJSONObject(0)
                    }
                    break
                }

                // Check for missing column error in schema cache
                val missingColumn = extractMissingColumn(lastResponseBody)
                if (missingColumn != null && payload.has(missingColumn)) {
                    Timber.w("Supabase profiles table is missing column '$missingColumn' in schema cache. Omitting from remote sync and preserving locally.")
                    payload.remove(missingColumn)
                    omittedColumns.add(missingColumn)
                    if (missingColumn == "username" && payload.has("normalized_username")) {
                        payload.remove("normalized_username")
                    } else if (missingColumn == "normalized_username" && payload.has("normalized_username")) {
                        payload.remove("normalized_username")
                    }
                } else {
                    // Non-recoverable error
                    break
                }
            }

            // If we successfully updated the server (or payload became empty due to unsupported server columns)
            if (finalObj != null || (omittedColumns.isNotEmpty() && payload.length() == 0)) {
                val updatedObj = finalObj ?: JSONObject()
                val updatedUser = if (updatedObj.has("username")) updatedObj.optString("username", "") else (username?.trim()?.removePrefix("@") ?: SecurePrefsManager.getPrefs(ctx).getString("username", "") ?: "")
                val rawDisp = if (updatedObj.has("display_name")) updatedObj.optString("display_name", "") else (displayName?.trim() ?: "")
                val updatedDisp = if (rawDisp.isNotBlank()) rawDisp else updatedUser
                val updatedBio = if (updatedObj.has("bio")) updatedObj.optString("bio", "") else (bio?.trim() ?: SecurePrefsManager.getBio(ctx, userId))
                val updatedDob = if (updatedObj.has("date_of_birth")) updatedObj.optString("date_of_birth", "") else (dob?.trim() ?: SecurePrefsManager.getDateOfBirth(ctx, userId))
                val updatedAvatar = if (updatedObj.has("avatar_url")) updatedObj.optString("avatar_url", "") else (SecurePrefsManager.getAvatarUri(ctx, userId) ?: "")
                val updatedHex = if (updatedObj.has("hex_number")) updatedObj.optString("hex_number", "") else SecurePrefsManager.getPrivateVirtualNumber(ctx, userId)
                val updatedBgPath = if (updatedObj.has("profile_background_path")) {
                    if (updatedObj.isNull("profile_background_path")) null else updatedObj.optString("profile_background_path", "").takeIf { it.isNotBlank() }
                } else {
                    SecurePrefsManager.getProfileBackgroundPath(ctx, userId)
                }
                val updatedBgType = if (updatedObj.has("profile_background_type")) {
                    if (updatedObj.isNull("profile_background_type")) null else updatedObj.optString("profile_background_type", "").takeIf { it.isNotBlank() }
                } else {
                    SecurePrefsManager.getProfileBackgroundType(ctx, userId)
                }

                val resultProfile = UserProfileData(
                    id = if (updatedObj.has("id")) updatedObj.optString("id", userId) else userId,
                    username = updatedUser,
                    displayName = updatedDisp,
                    bio = updatedBio,
                    dateOfBirth = updatedDob,
                    avatarUrl = updatedAvatar,
                    hexNumber = updatedHex,
                    backgroundPath = updatedBgPath,
                    backgroundType = updatedBgType
                )

                // Authoritative cache update - persists all fields including omitted ones (e.g. date_of_birth)
                SecurePrefsManager.setDisplayName(ctx, updatedDisp, userId)
                SecurePrefsManager.setBio(ctx, updatedBio, userId)
                SecurePrefsManager.setDateOfBirth(ctx, updatedDob, userId)
                if (updatedAvatar.isNotBlank()) {
                    SecurePrefsManager.setAvatarUri(ctx, updatedAvatar, userId)
                }
                if (updatedUser.isNotBlank()) {
                    SecurePrefsManager.getPrefs(ctx).edit().putString("username", updatedUser).apply()
                }

                if (omittedColumns.isNotEmpty()) {
                    Timber.w("Profile saved. Columns omitted from server sync because they are not yet in Supabase schema cache: $omittedColumns. Run migrations to enable remote persistence.")
                }

                return@withContext Result.success(resultProfile)
            }

            val errorMsg = try {
                val j = JSONObject(lastResponseBody)
                j.optString("message", j.optString("error", "HTTP $lastResponseCode"))
            } catch (_: Exception) {
                "HTTP $lastResponseCode: ${lastResponseBody.take(150)}"
            }
            Result.failure(Exception(errorMsg))
        } catch (e: Exception) {
            Timber.e(e, "Error syncing profile to server for user $userId")
            Result.failure(e)
        }
    }

    private fun extractMissingColumn(responseBody: String): String? {
        val pgrstMatch = Regex("Could not find the '([a-zA-Z0-9_]+)' column of '?profiles.*schema cache", RegexOption.IGNORE_CASE).find(responseBody)
        if (pgrstMatch != null) return pgrstMatch.groupValues[1]

        val colMatch = Regex("column profiles\\.([a-zA-Z0-9_]+) does not exist", RegexOption.IGNORE_CASE).find(responseBody)
        if (colMatch != null) return colMatch.groupValues[1]

        val relMatch = Regex("column \"([a-zA-Z0-9_]+)\" of relation \"profiles\" does not exist", RegexOption.IGNORE_CASE).find(responseBody)
        if (relMatch != null) return relMatch.groupValues[1]

        return null
    }

    /**
     * Uploads an avatar image to Supabase Storage avatars bucket and updates profiles table.
     */
    suspend fun uploadAvatar(
        userId: String,
        token: String,
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): String? = withContext(Dispatchers.IO) {
        if (userId.isBlank() || token.isBlank() || imageBytes.isEmpty()) return@withContext null
        val ctx = context ?: return@withContext null
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        try {
            val extension = when {
                mimeType.contains("png", ignoreCase = true) -> "png"
                mimeType.contains("webp", ignoreCase = true) -> "webp"
                mimeType.contains("gif", ignoreCase = true) -> "gif"
                else -> "jpg"
            }
            val objectPath = "$userId/avatar_${System.currentTimeMillis()}.$extension"
            val uploadUrl = "$baseUrl/storage/v1/object/avatars/$objectPath"

            val mediaType = mimeType.toMediaType()
            val requestBody = imageBytes.toRequestBody(mediaType)

            val uploadRequest = Request.Builder()
                .url(uploadUrl)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", mimeType)
                .post(requestBody)
                .build()

            val uploadResponse = httpClient.newCall(uploadRequest).execute()
            if (!uploadResponse.isSuccessful) {
                Timber.w("Avatar upload failed with code ${uploadResponse.code} - body: ${uploadResponse.body?.string()}")
                return@withContext null
            }

            val publicUrl = "$baseUrl/storage/v1/object/public/avatars/$objectPath"

            // Update profiles table with avatar_url
            val patchPayload = JSONObject().apply {
                put("avatar_url", publicUrl)
            }
            val patchReq = Request.Builder()
                .url("$baseUrl/rest/v1/profiles?id=eq.$userId")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .patch(patchPayload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val patchResp = httpClient.newCall(patchReq).execute()
            val patchSuccess = patchResp.isSuccessful
            val patchBody = patchResp.body?.string() ?: ""
            patchResp.close()

            // Avatar is successfully stored in Supabase Storage avatars bucket.
            // Persist locally in account-scoped secure preferences.
            SecurePrefsManager.setAvatarUri(ctx, publicUrl, userId)
            if (!patchSuccess) {
                Timber.w("Avatar uploaded to Storage, but patching profiles returned: $patchBody")
            }
            return@withContext publicUrl
        } catch (e: Exception) {
            Timber.e(e, "Exception uploading avatar for user $userId")
        }
        null
    }
}
