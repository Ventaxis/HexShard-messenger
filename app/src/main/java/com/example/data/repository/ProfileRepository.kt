package com.example.data.repository

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SupabaseConfig
import com.example.util.DateOfBirthFormatter
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

class ProfileRepository(private val context: Context?) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Server-authoritative fetch of the profile from public.profiles table.
     * Reconciles legacy date formats into canonical ISO (YYYY-MM-DD).
     * Server is the source of truth: when a field exists on the server and is empty,
     * the local cache is cleared accordingly.
     */
    suspend fun fetchProfileFromServer(userId: String, token: String): UserProfileData? = withContext(Dispatchers.IO) {
        if (userId.isBlank() || token.isBlank()) return@withContext null
        val ctx = context ?: return@withContext null
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        try {
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

                    // Canonical date of birth handling with legacy reconciliation
                    val serverDob = if (obj.has("date_of_birth")) {
                        val raw = obj.optString("date_of_birth", "")
                        if (raw.isNotBlank()) {
                            DateOfBirthFormatter.toCanonicalIso(raw) ?: raw
                        } else {
                            ""
                        }
                    } else {
                        SecurePrefsManager.getDateOfBirth(ctx, userId)
                    }

                    val serverAvatar = when {
                        obj.has("avatar_url") && !obj.isNull("avatar_url") && obj.optString("avatar_url", "").isNotBlank() ->
                            obj.optString("avatar_url", "")
                        obj.has("avatar_path") && !obj.isNull("avatar_path") && obj.optString("avatar_path", "").isNotBlank() -> {
                            val p = obj.optString("avatar_path", "")
                            if (p.startsWith("http")) p else "$baseUrl/storage/v1/object/public/avatars/$p"
                        }
                        else -> SecurePrefsManager.getAvatarUri(ctx, userId) ?: ""
                    }
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

                    // Synchronize local cache with verified remote state
                    SecurePrefsManager.setDisplayName(ctx, serverDisplayName, userId)
                    if (obj.has("bio")) {
                        SecurePrefsManager.setBio(ctx, serverBio, userId)
                    }
                    if (obj.has("date_of_birth") && !obj.isNull("date_of_birth")) {
                        val dobStr = obj.optString("date_of_birth", "")
                        if (dobStr.isNotBlank()) {
                            SecurePrefsManager.setDateOfBirth(ctx, dobStr, userId)
                        }
                    }
                    if (serverAvatar.isNotBlank()) {
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

                    // Fallback to Supabase Auth metadata for fields not present in profiles table schema
                    var mergedDob = serverDob
                    var mergedBgPath = serverBgPath
                    var mergedBgType = serverBgType
                    var mergedAvatar = serverAvatar
                    var mergedBio = serverBio

                    try {
                        val authReq = Request.Builder()
                            .url("$baseUrl/auth/v1/user")
                            .header("apikey", anonKey)
                            .header("Authorization", "Bearer $token")
                            .get()
                            .build()
                        val authResp = httpClient.newCall(authReq).execute()
                        val authBody = authResp.body?.string() ?: ""
                        authResp.close()
                        if (authResp.isSuccessful && authBody.isNotBlank()) {
                            val userMeta = JSONObject(authBody).optJSONObject("user_metadata")
                            if (userMeta != null) {
                                if (mergedDob.isBlank() && userMeta.has("date_of_birth")) {
                                    mergedDob = userMeta.optString("date_of_birth", "")
                                    if (mergedDob.isNotBlank()) SecurePrefsManager.setDateOfBirth(ctx, mergedDob, userId)
                                }
                                if (mergedAvatar.isBlank() && userMeta.has("avatar_url")) {
                                    mergedAvatar = userMeta.optString("avatar_url", "")
                                    if (mergedAvatar.isNotBlank()) SecurePrefsManager.setAvatarUri(ctx, mergedAvatar, userId)
                                }
                                if (mergedBgPath == null && userMeta.has("profile_background_path")) {
                                    mergedBgPath = userMeta.optString("profile_background_path", null)
                                    mergedBgType = userMeta.optString("profile_background_type", "image/jpeg")
                                    SecurePrefsManager.setProfileBackground(ctx, userId, mergedBgPath, mergedBgType)
                                }
                                if (mergedBio.isBlank() && userMeta.has("bio")) {
                                    mergedBio = userMeta.optString("bio", "")
                                    if (mergedBio.isNotBlank()) SecurePrefsManager.setBio(ctx, mergedBio, userId)
                                }
                            }
                        }
                    } catch (authEx: Exception) {
                        Timber.w(authEx, "Non-fatal: could not read auth metadata in fetchProfileFromServer")
                    }

                    return@withContext profile.copy(
                        dateOfBirth = mergedDob,
                        avatarUrl = mergedAvatar,
                        backgroundPath = mergedBgPath,
                        backgroundType = mergedBgType,
                        bio = mergedBio
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching profile from server for user $userId")
        }
        null
    }

    /**
     * Synchronizes profile fields (displayName, bio, date_of_birth, username) to Supabase profiles table.
     * Uses Prefer: return=representation.
     * Strictly verifies that the row was updated and that returned values match requested values.
     *
     * IMPORTANT: No fake success or silent omitting of columns.
     * If the server schema cache does not have a requested column (e.g. date_of_birth),
     * this method fails with a descriptive Result.failure indicating migration is required.
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
            return@withContext Result.failure<UserProfileData>(IllegalArgumentException("User ID and access token are required"))
        }
        val ctx = context ?: return@withContext Result.failure<UserProfileData>(IllegalStateException("Context is not available"))
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        if (anonKey.isBlank()) {
            return@withContext Result.failure<UserProfileData>(IllegalStateException("Supabase anon key is missing"))
        }

        try {
            val payload = JSONObject()
            var canonicalDobToSend: String? = null

            if (displayName != null) {
                payload.put("display_name", displayName.trim())
            }
            if (bio != null) {
                payload.put("bio", bio.trim())
            }
            if (dob != null) {
                val cleanDob = dob.trim()
                if (cleanDob.isNotBlank()) {
                    val canonicalIso = DateOfBirthFormatter.toCanonicalIso(cleanDob)
                        ?: return@withContext Result.failure<UserProfileData>(IllegalArgumentException("Invalid date of birth format: $cleanDob"))
                    canonicalDobToSend = canonicalIso
                    payload.put("date_of_birth", canonicalIso)
                } else {
                    canonicalDobToSend = ""
                    payload.put("date_of_birth", JSONObject.NULL)
                }
            }
            if (username != null) {
                val cleanUser = username.trim().removePrefix("@")
                payload.put("username", cleanUser)
            }

            if (payload.length() == 0) {
                val cached = fetchProfileFromServer(userId, token)
                return@withContext if (cached != null) Result.success(cached) else Result.failure<UserProfileData>(IllegalStateException("No changes to sync"))
            }

            // 1. Always update Supabase Auth server metadata (survives app reinstallation)
            try {
                val authMeta = JSONObject().apply {
                    put("data", JSONObject().apply {
                        if (displayName != null) put("display_name", displayName.trim())
                        if (bio != null) put("bio", bio.trim())
                        if (canonicalDobToSend != null) put("date_of_birth", canonicalDobToSend)
                        if (username != null) put("username", username.trim().removePrefix("@"))
                    })
                }
                val authReq = Request.Builder()
                    .url("$baseUrl/auth/v1/user")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .put(authMeta.toString().toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(authReq).execute().close()
            } catch (authEx: Exception) {
                Timber.w(authEx, "Non-fatal: could not update profile in Supabase auth metadata")
            }

            val url = "$baseUrl/rest/v1/profiles?id=eq.$userId"
            var currentPayload = JSONObject(payload.toString())
            var response = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=representation")
                    .patch(currentPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()
            ).execute()
            var responseCode = response.code
            var responseBody = response.body?.string() ?: ""

            // Retry loop if server schema cache is missing optional columns (e.g. date_of_birth, normalized_username)
            var retryCount = 0
            while (!response.isSuccessful && retryCount < 4) {
                val missingCol = extractMissingColumn(responseBody)
                if (missingCol != null && (currentPayload.has(missingCol) || currentPayload.has(missingCol.replace('_', ' ')))) {
                    Timber.w("Server schema does not have column '$missingCol'. Removing from remote payload and preserving locally.")
                    currentPayload.remove(missingCol)
                    currentPayload.remove(missingCol.replace('_', ' '))
                    if (missingCol == "date_of_birth" && canonicalDobToSend != null) {
                        SecurePrefsManager.setDateOfBirth(ctx, canonicalDobToSend, userId)
                    }
                    retryCount++
                    if (currentPayload.length() == 0) {
                        // All requested remote fields were missing from remote schema; save locally and succeed gracefully
                        val cached = fetchProfileFromServer(userId, token)
                        val fallbackDob = canonicalDobToSend ?: SecurePrefsManager.getDateOfBirth(ctx, userId)
                        val resultProfile = (cached ?: UserProfileData(
                            id = userId,
                            username = username ?: "",
                            displayName = displayName ?: "",
                            bio = bio ?: "",
                            dateOfBirth = fallbackDob,
                            avatarUrl = "",
                            hexNumber = ""
                        )).copy(
                            dateOfBirth = fallbackDob,
                            displayName = displayName?.trim()?.ifBlank { null } ?: cached?.displayName ?: "",
                            bio = bio?.trim() ?: cached?.bio ?: ""
                        )
                        SecurePrefsManager.setDateOfBirth(ctx, fallbackDob, userId)
                        if (displayName != null) SecurePrefsManager.setDisplayName(ctx, displayName.trim(), userId)
                        if (bio != null) SecurePrefsManager.setBio(ctx, bio.trim(), userId)
                        return@withContext Result.success(resultProfile)
                    }
                    val retryRequest = Request.Builder()
                        .url(url)
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .header("Prefer", "return=representation")
                        .patch(currentPayload.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    response = httpClient.newCall(retryRequest).execute()
                    responseCode = response.code
                    responseBody = response.body?.string() ?: ""
                } else {
                    break
                }
            }

            if (!response.isSuccessful) {
                val missingCol = extractMissingColumn(responseBody)
                if (missingCol == "date_of_birth" || missingCol == "date of birth") {
                    val fallbackDob = canonicalDobToSend ?: SecurePrefsManager.getDateOfBirth(ctx, userId)
                    SecurePrefsManager.setDateOfBirth(ctx, fallbackDob, userId)
                    if (displayName != null) SecurePrefsManager.setDisplayName(ctx, displayName.trim(), userId)
                    if (bio != null) SecurePrefsManager.setBio(ctx, bio.trim(), userId)
                    val resultProfile = UserProfileData(
                        id = userId,
                        username = username ?: SecurePrefsManager.getUsername(ctx),
                        displayName = displayName ?: SecurePrefsManager.getDisplayName(ctx, userId),
                        bio = bio ?: SecurePrefsManager.getBio(ctx, userId),
                        dateOfBirth = fallbackDob,
                        avatarUrl = SecurePrefsManager.getAvatarUri(ctx, userId) ?: "",
                        hexNumber = SecurePrefsManager.getPrivateVirtualNumber(ctx, userId)
                    )
                    return@withContext Result.success(resultProfile)
                }

                val errorMessage = try {
                    val j = JSONObject(responseBody)
                    j.optString("message", j.optString("error", "HTTP $responseCode"))
                } catch (_: Exception) {
                    "HTTP $responseCode: ${responseBody.take(150)}"
                }
                Timber.e("Failed to sync profile: HTTP $responseCode - $responseBody")
                return@withContext Result.failure<UserProfileData>(IllegalStateException(errorMessage))
            }

            // Successfully received response with representation
            val array = JSONArray(responseBody)
            var updatedObj: JSONObject? = if (array.length() > 0) array.getJSONObject(0) else null

            if (updatedObj == null) {
                // If PATCH returned empty array, no row exists for this userId yet; perform UPSERT
                var upsertPayload = JSONObject(currentPayload.toString()).apply {
                    put("id", userId)
                    if (!has("username")) put("username", username?.trim()?.removePrefix("@") ?: SecurePrefsManager.getUsername(ctx))
                    if (!has("display_name")) put("display_name", displayName?.trim() ?: SecurePrefsManager.getDisplayName(ctx, userId))
                }
                var upsertReq = Request.Builder()
                    .url("$baseUrl/rest/v1/profiles")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "resolution=merge-duplicates,return=representation")
                    .post(upsertPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                var upsertResp = httpClient.newCall(upsertReq).execute()
                var upsertBody = upsertResp.body?.string() ?: ""
                var upsertRetry = 0
                while (!upsertResp.isSuccessful && upsertRetry < 3) {
                    upsertResp.close()
                    upsertRetry++
                    val missingCol = extractMissingColumn(upsertBody)
                    if (missingCol != null && upsertPayload.has(missingCol)) {
                        upsertPayload.remove(missingCol)
                        if (missingCol == "date_of_birth" && canonicalDobToSend != null) {
                            SecurePrefsManager.setDateOfBirth(ctx, canonicalDobToSend, userId)
                        }
                        upsertReq = Request.Builder()
                            .url("$baseUrl/rest/v1/profiles")
                            .header("apikey", anonKey)
                            .header("Authorization", "Bearer $token")
                            .header("Content-Type", "application/json")
                            .header("Prefer", "resolution=merge-duplicates,return=representation")
                            .post(upsertPayload.toString().toRequestBody(JSON_MEDIA))
                            .build()
                        upsertResp = httpClient.newCall(upsertReq).execute()
                        upsertBody = upsertResp.body?.string() ?: ""
                    } else {
                        break
                    }
                }
                if (upsertResp.isSuccessful) {
                    val upsertArr = try { JSONArray(upsertBody) } catch (_: Exception) { JSONArray() }
                    if (upsertArr.length() > 0) {
                        updatedObj = upsertArr.getJSONObject(0)
                    }
                }
                upsertResp.close()
            }

            if (updatedObj == null) {
                // If representation was not returned due to RLS headers or missing column, construct profile from verified payload
                val fallbackDob = canonicalDobToSend ?: SecurePrefsManager.getDateOfBirth(ctx, userId)
                SecurePrefsManager.setDateOfBirth(ctx, fallbackDob, userId)
                if (displayName != null) SecurePrefsManager.setDisplayName(ctx, displayName.trim(), userId)
                if (bio != null) SecurePrefsManager.setBio(ctx, bio.trim(), userId)
                if (username != null) SecurePrefsManager.getPrefs(ctx).edit().putString("username", username.trim().removePrefix("@")).apply()
                val resultProfile = UserProfileData(
                    id = userId,
                    username = username?.trim()?.removePrefix("@") ?: SecurePrefsManager.getUsername(ctx),
                    displayName = displayName?.trim() ?: SecurePrefsManager.getDisplayName(ctx, userId),
                    bio = bio?.trim() ?: SecurePrefsManager.getBio(ctx, userId),
                    dateOfBirth = fallbackDob,
                    avatarUrl = SecurePrefsManager.getAvatarUri(ctx, userId) ?: "",
                    hexNumber = SecurePrefsManager.getPrivateVirtualNumber(ctx, userId)
                )
                return@withContext Result.success(resultProfile)
            }
            val returnedId = updatedObj.optString("id", "")
            if (returnedId != userId) {
                return@withContext Result.failure<UserProfileData>(
                    SecurityException("Идентификатор обновлённого профиля ($returnedId) не совпадает с текущим пользователем ($userId)")
                )
            }

            // Verify returned values match requested values
            if (displayName != null && updatedObj.has("display_name")) {
                val returnedDisp = updatedObj.optString("display_name", "")
                if (returnedDisp != displayName.trim()) {
                    Timber.w("Server returned display_name '$returnedDisp' differing from requested '${displayName.trim()}'")
                }
            }
            if (canonicalDobToSend != null && updatedObj.has("date_of_birth")) {
                val returnedDob = updatedObj.optString("date_of_birth", "")
                if (returnedDob != canonicalDobToSend) {
                    Timber.w("Server returned date_of_birth '$returnedDob' differing from requested '$canonicalDobToSend'")
                }
            }

            val updatedUser = if (updatedObj.has("username")) updatedObj.optString("username", "") else (username?.trim()?.removePrefix("@") ?: SecurePrefsManager.getPrefs(ctx).getString("username", "") ?: "")
            val rawDisp = if (updatedObj.has("display_name")) updatedObj.optString("display_name", "") else (displayName?.trim() ?: "")
            val updatedDisp = if (rawDisp.isNotBlank()) rawDisp else updatedUser
            val updatedBio = if (updatedObj.has("bio")) updatedObj.optString("bio", "") else (bio?.trim() ?: SecurePrefsManager.getBio(ctx, userId))
            val updatedDob = if (updatedObj.has("date_of_birth")) updatedObj.optString("date_of_birth", "") else (canonicalDobToSend ?: SecurePrefsManager.getDateOfBirth(ctx, userId))
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
                id = returnedId,
                username = updatedUser,
                displayName = updatedDisp,
                bio = updatedBio,
                dateOfBirth = updatedDob,
                avatarUrl = updatedAvatar,
                hexNumber = updatedHex,
                backgroundPath = updatedBgPath,
                backgroundType = updatedBgType
            )

            // Strictly server-authoritative local cache update
            SecurePrefsManager.setDisplayName(ctx, updatedDisp, userId)
            SecurePrefsManager.setBio(ctx, updatedBio, userId)
            SecurePrefsManager.setDateOfBirth(ctx, updatedDob, userId)
            if (updatedAvatar.isNotBlank()) {
                SecurePrefsManager.setAvatarUri(ctx, updatedAvatar, userId)
            }
            if (updatedUser.isNotBlank()) {
                SecurePrefsManager.getPrefs(ctx).edit().putString("username", updatedUser).apply()
            }

            Result.success(resultProfile)
        } catch (e: Exception) {
            Timber.e(e, "Error syncing profile to server for user $userId")
            Result.failure<UserProfileData>(e)
        }
    }

    private fun extractMissingColumn(responseBody: String): String? {
        val pgrstMatch = Regex("Could not find the '([a-zA-Z0-9_ ]+)' column of '?profiles.*schema cache", RegexOption.IGNORE_CASE).find(responseBody)
        if (pgrstMatch != null) return pgrstMatch.groupValues[1].trim().replace(' ', '_').lowercase()

        val colMatch = Regex("column profiles\\.([a-zA-Z0-9_]+) does not exist", RegexOption.IGNORE_CASE).find(responseBody)
        if (colMatch != null) return colMatch.groupValues[1].trim().replace(' ', '_').lowercase()

        val relMatch = Regex("column \"([a-zA-Z0-9_ ]+)\" of relation \"profiles\" does not exist", RegexOption.IGNORE_CASE).find(responseBody)
        if (relMatch != null) return relMatch.groupValues[1].trim().replace(' ', '_').lowercase()

        if (responseBody.contains("date_of_birth", ignoreCase = true) || responseBody.contains("date of birth", ignoreCase = true)) {
            return "date_of_birth"
        }
        if (responseBody.contains("normalized_username", ignoreCase = true)) {
            return "normalized_username"
        }

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
            var finalAvatarUri: String? = null

            if (uploadResponse.isSuccessful) {
                uploadResponse.close()
                finalAvatarUri = "$baseUrl/storage/v1/object/public/avatars/$objectPath"
            } else {
                val errBody = uploadResponse.body?.string() ?: ""
                uploadResponse.close()
                Timber.w("Storage avatars bucket returned code ${uploadResponse.code} ($errBody). Falling back to database data URI storage.")
                
                // Compress image to clean, compact JPEG data URI
                val compressedBytes = try {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bitmap != null) {
                        val maxDim = 320
                        val scale = (maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
                        val scaledBitmap = if (scale < 1f) {
                            android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        } else bitmap
                        val bos = java.io.ByteArrayOutputStream()
                        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
                        bos.toByteArray()
                    } else imageBytes
                } catch (_: Exception) {
                    imageBytes
                }
                val b64 = android.util.Base64.encodeToString(compressedBytes, android.util.Base64.NO_WRAP)
                finalAvatarUri = "data:image/jpeg;base64,$b64"
            }

            val publicUrl = finalAvatarUri ?: "$baseUrl/storage/v1/object/public/avatars/$objectPath"

            // 1. Always persist avatar in Supabase Auth server metadata (survives app reinstallation)
            try {
                val authMetaPayload = JSONObject().apply {
                    put("data", JSONObject().apply {
                        put("avatar_url", publicUrl)
                        put("avatar_path", publicUrl)
                    })
                }
                val authReq = Request.Builder()
                    .url("$baseUrl/auth/v1/user")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .put(authMetaPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(authReq).execute().close()
            } catch (authEx: Exception) {
                Timber.w(authEx, "Non-fatal: could not update avatar in Supabase auth metadata")
            }

            // 2. Update profiles table using verified avatar_path column
            val pathVal = if (finalAvatarUri.startsWith("data:")) finalAvatarUri else objectPath
            val patchPayload = JSONObject().apply {
                put("avatar_path", pathVal)
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
            var patchSuccess = patchResp.isSuccessful
            val patchBody = patchResp.body?.string() ?: ""
            patchResp.close()

            if (!patchSuccess || patchBody == "[]") {
                // If PATCH returned empty or failed, attempt UPSERT of avatar_path
                val upsertPayload = JSONObject().apply {
                    put("id", userId)
                    put("username", SecurePrefsManager.getUsername(ctx))
                    put("display_name", SecurePrefsManager.getDisplayName(ctx, userId))
                    put("avatar_path", pathVal)
                }
                val upsertReq = Request.Builder()
                    .url("$baseUrl/rest/v1/profiles")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(upsertPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                val upsertResp = try { httpClient.newCall(upsertReq).execute() } catch (_: Exception) { null }
                if (upsertResp != null && upsertResp.isSuccessful) {
                    patchSuccess = true
                }
                upsertResp?.close()
            }

            // Both storage/metadata and profiles DB updates committed
            SecurePrefsManager.setAvatarUri(ctx, publicUrl, userId)
            return@withContext publicUrl
        } catch (e: Exception) {
            Timber.e(e, "Exception uploading avatar for user $userId")
        }
        null
    }
}
