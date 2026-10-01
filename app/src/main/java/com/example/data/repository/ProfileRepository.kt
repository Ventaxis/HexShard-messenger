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
    val bio: String,
    val dateOfBirth: String,
    val avatarUrl: String,
    val hexNumber: String
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
     */
    suspend fun fetchProfileFromServer(userId: String, token: String): UserProfileData? = withContext(Dispatchers.IO) {
        if (userId.isBlank() || token.isBlank()) return@withContext null
        val ctx = context ?: return@withContext null
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        try {
            val url = "$baseUrl/rest/v1/profiles?id=eq.$userId&select=id,username,bio,date_of_birth,avatar_url,hex_number&limit=1"
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
                    val profile = UserProfileData(
                        id = obj.optString("id", userId),
                        username = obj.optString("username", ""),
                        bio = obj.optString("bio", ""),
                        dateOfBirth = obj.optString("date_of_birth", ""),
                        avatarUrl = obj.optString("avatar_url", ""),
                        hexNumber = obj.optString("hex_number", "")
                    )

                    // Synchronize with local storage
                    if (profile.bio.isNotBlank()) SecurePrefsManager.setBio(ctx, profile.bio)
                    if (profile.dateOfBirth.isNotBlank()) SecurePrefsManager.setDateOfBirth(ctx, profile.dateOfBirth)
                    if (profile.avatarUrl.isNotBlank()) SecurePrefsManager.setAvatarUri(ctx, profile.avatarUrl)
                    if (profile.hexNumber.isNotBlank()) {
                        val clean = profile.hexNumber.filter { it.isDigit() }
                        if (clean.length == 8) {
                            SecurePrefsManager.setPrivateVirtualNumber(ctx, clean, userId)
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
     * Synchronizes profile fields (bio, date_of_birth, username) to Supabase profiles table.
     */
    suspend fun syncProfileToServer(
        userId: String,
        token: String,
        bio: String? = null,
        dob: String? = null,
        username: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (userId.isBlank() || token.isBlank()) return@withContext false
        val ctx = context ?: return@withContext false
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)

        try {
            val payload = JSONObject()
            if (bio != null) payload.put("bio", bio)
            if (dob != null) payload.put("date_of_birth", dob)
            if (username != null) {
                val cleanUser = username.trim().removePrefix("@")
                payload.put("username", cleanUser)
                payload.put("normalized_username", cleanUser.lowercase(java.util.Locale.ROOT))
            }

            if (payload.length() == 0) return@withContext true

            val url = "$baseUrl/rest/v1/profiles?id=eq.$userId"
            val request = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .patch(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Timber.e(e, "Error syncing profile to server for user $userId")
            false
        }
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
            val objectPath = "$userId/avatar_${System.currentTimeMillis()}.jpg"
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
                Timber.w("Avatar upload failed with code ${uploadResponse.code}")
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
                .patch(patchPayload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val patchResp = httpClient.newCall(patchReq).execute()
            if (patchResp.isSuccessful) {
                SecurePrefsManager.setAvatarUri(ctx, publicUrl)
                return@withContext publicUrl
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception uploading avatar for user $userId")
        }
        null
    }
}
