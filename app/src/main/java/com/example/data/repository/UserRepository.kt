package com.example.data.repository

import android.content.Context
import com.example.crypto.E2ECryptoManager
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
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class UserRepository(
    private val context: Context? = null
) {
    data class CachedKey(val key: PublicKey, val fetchedAt: Long)

    private val publicKeyCache = ConcurrentHashMap<String, CachedKey>()
    private val signingKeyCache = ConcurrentHashMap<String, CachedKey>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val KEY_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    /**
     * Resolves a username or account ID against the authoritative Supabase profiles table.
     * Returns Pair(accountId, username) or null if not found.
     */
    suspend fun resolveUser(identifier: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val clean = identifier.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext null

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank()) return@withContext null

        try {
            val isUuid = clean.matches(Regex("^[0-9a-fA-F-]{36}$"))
            val url = if (isUuid) {
                "$baseUrl/rest/v1/profiles?or=(id.eq.$clean,username.ilike.$clean)&select=id,username"
            } else {
                "$baseUrl/rest/v1/profiles?or=(username.ilike.$clean,normalized_username.ilike.${clean.lowercase()})&select=id,username"
            }

            val req = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()

            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful && body.isNotEmpty()) {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val uid = obj.optString("id")
                    val uname = obj.optString("username", clean)
                    if (uid.isNotBlank()) {
                        return@withContext Pair(uid, uname)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error resolving user for $clean")
        }
        null
    }

    /**
     * Retrieves the ECDH identity public key for a user from local cache or Supabase devices table.
     */
    suspend fun getOrFetchUserPublicKey(userId: String): PublicKey? = withContext(Dispatchers.IO) {
        val cleanId = userId.trim().removePrefix("@")
        if (cleanId.isBlank() || cleanId == "self" || cleanId == "me") {
            return@withContext try { E2ECryptoManager.getMyPublicKey() } catch (_: Exception) { null }
        }

        val now = System.currentTimeMillis()
        val cached = publicKeyCache[cleanId]
        if (cached != null && (now - cached.fetchedAt < KEY_CACHE_TTL_MS)) {
            return@withContext cached.key
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) } ?: ""
        if (anonKey.isBlank()) return@withContext cached?.key

        try {
            // First check profiles table
            val profUrl = "$baseUrl/rest/v1/profiles?id=eq.$cleanId&select=public_key"
            val profReq = Request.Builder()
                .url(profUrl)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()

            val profResp = httpClient.newCall(profReq).execute()
            val profBody = profResp.body?.string() ?: ""
            if (profResp.isSuccessful && profBody.isNotEmpty()) {
                val arr = JSONArray(profBody)
                if (arr.length() > 0) {
                    val keyPem = arr.getJSONObject(0).optString("public_key")
                    if (keyPem.isNotBlank()) {
                        val key = parsePublicKey(keyPem)
                        if (key != null) {
                            publicKeyCache[cleanId] = CachedKey(key, now)
                            return@withContext key
                        }
                    }
                }
            }

            // Fallback to active_device_keys view using canonical public_key column
            val devUrl = "$baseUrl/rest/v1/active_device_keys?account_id=eq.$cleanId&select=public_key&order=last_seen_at.desc&limit=1"
            val devReq = Request.Builder()
                .url(devUrl)
                .header("apikey", anonKey)
                .header("Authorization", if (token.isNotBlank()) "Bearer $token" else "Bearer $anonKey")
                .get()
                .build()

            val devResp = httpClient.newCall(devReq).execute()
            val devBody = devResp.body?.string() ?: ""
            if (devResp.isSuccessful && devBody.isNotEmpty()) {
                val arr = JSONArray(devBody)
                if (arr.length() > 0) {
                    val keyPem = arr.getJSONObject(0).optString("public_key")
                    if (keyPem.isNotBlank()) {
                        val key = parsePublicKey(keyPem)
                        if (key != null) {
                            publicKeyCache[cleanId] = CachedKey(key, now)
                            return@withContext key
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch public key for user $cleanId")
        }

        cached?.key
    }

    /**
     * Retrieves the ECDSA signing public key for sender identity verification.
     */
    suspend fun getOrFetchUserSigningKey(userId: String): PublicKey? = withContext(Dispatchers.IO) {
        val cleanId = userId.trim().removePrefix("@")
        if (cleanId.isBlank() || cleanId == "self" || cleanId == "me") {
            return@withContext try { E2ECryptoManager.getMySigningPublicKey(cleanId) } catch (_: Exception) { null }
        }

        val now = System.currentTimeMillis()
        val cached = signingKeyCache[cleanId]
        if (cached != null && (now - cached.fetchedAt < KEY_CACHE_TTL_MS)) {
            return@withContext cached.key
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) } ?: ""
        if (anonKey.isBlank()) return@withContext cached?.key

        try {
            val devUrl = "$baseUrl/rest/v1/active_device_keys?account_id=eq.$cleanId&select=signing_key&order=last_seen_at.desc&limit=1"
            val devReq = Request.Builder()
                .url(devUrl)
                .header("apikey", anonKey)
                .header("Authorization", if (token.isNotBlank()) "Bearer $token" else "Bearer $anonKey")
                .get()
                .build()

            val devResp = httpClient.newCall(devReq).execute()
            val devBody = devResp.body?.string() ?: ""
            if (devResp.isSuccessful && devBody.isNotEmpty()) {
                val arr = JSONArray(devBody)
                if (arr.length() > 0) {
                    val keyPem = arr.getJSONObject(0).optString("signing_key")
                    if (keyPem.isNotBlank()) {
                        val key = parsePublicKey(keyPem)
                        if (key != null) {
                            signingKeyCache[cleanId] = CachedKey(key, now)
                            return@withContext key
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch signing key for user $cleanId")
        }

        cached?.key
    }

    private fun parsePublicKey(base64Pem: String): PublicKey? {
        return try {
            val keyBytes = Base64.getDecoder().decode(base64Pem.trim())
            val spec = X509EncodedKeySpec(keyBytes)
            val kf = KeyFactory.getInstance("EC")
            kf.generatePublic(spec)
        } catch (e: Exception) {
            Timber.w(e, "Error parsing EC public key")
            null
        }
    }

    fun clearKeyCaches() {
        publicKeyCache.clear()
        signingKeyCache.clear()
    }

    suspend fun uploadMyPublicKeys(userId: String): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val ctx = context
        val token = ctx?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
        if (token.isNullOrBlank() || anonKey.isBlank()) {
            Timber.w("Cannot upload public keys: user is not authenticated or Supabase not configured")
            return@withContext false
        }

        try {
            val pubKey = E2ECryptoManager.getMyPublicKey(userId)
            val signKey = E2ECryptoManager.getMySigningPublicKey(userId)
            val pubBase64 = Base64.getEncoder().encodeToString(pubKey.encoded)
            val signBase64 = Base64.getEncoder().encodeToString(signKey.encoded)
            val deviceId = ctx.let { SecurePrefsManager.getDeviceId(it) }

            // Update profiles table
            val profileBody = JSONObject().apply {
                put("public_key", pubBase64)
            }.toString().toRequestBody(JSON_MEDIA)

            val profileReq = Request.Builder()
                .url("$baseUrl/rest/v1/profiles?id=eq.$userId")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .patch(profileBody)
                .build()

            httpClient.newCall(profileReq).execute().close()

            // Update devices table using canonical schema columns (account_id, device_id, public_key, signing_key, last_seen_at)
            val deviceBody = JSONObject().apply {
                put("account_id", userId)
                put("device_id", deviceId)
                put("public_key", pubBase64)
                put("signing_key", signBase64)
                put("platform", "android")
                put("device_name", "Android Device")
                put("last_seen_at", java.time.Instant.now().toString())
            }.toString().toRequestBody(JSON_MEDIA)

            val deviceReq = Request.Builder()
                .url("$baseUrl/rest/v1/devices")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates")
                .post(deviceBody)
                .build()

            httpClient.newCall(deviceReq).execute().close()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload public keys to Supabase")
            false
        }
    }
}
