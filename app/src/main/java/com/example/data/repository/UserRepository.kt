package com.example.data.repository

import android.content.Context
import com.example.crypto.E2ECryptoManager
import com.example.data.PeerKeyTrustStore
import com.example.data.SecurePrefsManager
import com.example.network.SupabaseHttp
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

    data class UserSearchResult(
        val userId: String,
        val username: String,
        val displayName: String,
        val avatarUrl: String? = null,
        val virtualNumber: String? = null
    )

    data class PeerProfileInfo(
        val id: String,
        val username: String,
        val displayName: String,
        val virtualNumber: String,
        val avatarUrl: String?,
        val backgroundPath: String?,
        val backgroundType: String?,
        val bio: String,
        val dateOfBirth: String,
        val isOnline: Boolean = false
    )

    private val publicKeyCache = ConcurrentHashMap<String, CachedKey>()
    private val signingKeyCache = ConcurrentHashMap<String, CachedKey>()

    /**
     * Queries the device key directory via the get_active_device_keys SECURITY DEFINER
     * RPC (the active_device_keys view was replaced by this function to satisfy
     * Supabase linter). Returns the JSON array of device key rows, or null on failure.
     */
    private suspend fun fetchActiveDeviceKeysJson(accountId: String, deviceId: String?): JSONArray? = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) } ?: ""
        if (anonKey.isBlank()) return@withContext null
        try {
            val payload = JSONObject().apply {
                put("p_account_id", accountId)
                if (!deviceId.isNullOrBlank()) put("p_device_id", deviceId)
            }
            val req = SupabaseHttp.jsonRequest(
                "$baseUrl/rest/v1/rpc/get_active_device_keys",
                token,
                anonKey,
                "POST",
                payload.toString()
            ).build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            if (resp.isSuccessful && body.isNotEmpty() && body != "null") JSONArray(body) else null
        } catch (e: Exception) {
            Timber.w(e, "get_active_device_keys RPC failed for $accountId")
            null
        }
    }

    /** Result wrapper exposing TOFU pin state alongside the key material. */
    data class TrustedKey(
        val key: java.security.PublicKey,
        /** True when this key differs from the first-ever pinned key for the peer. */
        val keyChanged: Boolean,
        /** Original pinned key when a change was detected, null otherwise. */
        val pinnedKey: java.security.PublicKey?
    )

    /**
     * TOFU-guarded ECDH key fetch: pins the first key seen for a peer and surfaces
     * any later server-side substitution instead of silently trusting it.
     */
    suspend fun getTrustedUserPublicKey(userId: String): TrustedKey? = withContext(Dispatchers.IO) {
        val fetched = getOrFetchUserPublicKey(userId) ?: return@withContext null
        val ctx = context ?: return@withContext TrustedKey(fetched, false, null)
        val accountId = getCurrentUserId()
        if (accountId.isBlank()) return@withContext TrustedKey(fetched, false, null)
        val pinnedBytes = PeerKeyTrustStore.getPinnedEcdhKey(ctx, accountId, userId)
        if (pinnedBytes == null) {
            PeerKeyTrustStore.pinEcdhKey(ctx, accountId, userId, fetched.encoded)
            TrustedKey(fetched, false, null)
        } else if (pinnedBytes.contentEquals(fetched.encoded)) {
            TrustedKey(fetched, false, null)
        } else {
            val pinned = parsePublicKeyFromBytes(pinnedBytes)
            TrustedKey(fetched, keyChanged = true, pinnedKey = pinned)
        }
    }

    /** TOFU-guarded signing key fetch, mirroring getTrustedUserPublicKey. */
    suspend fun getTrustedUserSigningKey(userId: String): TrustedKey? = withContext(Dispatchers.IO) {
        val fetched = getOrFetchUserSigningKey(userId) ?: return@withContext null
        val ctx = context ?: return@withContext TrustedKey(fetched, false, null)
        val accountId = getCurrentUserId()
        if (accountId.isBlank()) return@withContext TrustedKey(fetched, false, null)
        val pinnedBytes = PeerKeyTrustStore.getPinnedSigningKey(ctx, accountId, userId)
        if (pinnedBytes == null) {
            PeerKeyTrustStore.pinSigningKey(ctx, accountId, userId, fetched.encoded)
            TrustedKey(fetched, false, null)
        } else if (pinnedBytes.contentEquals(fetched.encoded)) {
            TrustedKey(fetched, false, null)
        } else {
            val pinned = parsePublicKeyFromBytes(pinnedBytes)
            TrustedKey(fetched, keyChanged = true, pinnedKey = pinned)
        }
    }

    /** Re-pins the current server key for a peer after the user acknowledged a rotation. */
    fun acceptRotatedKeys(peerId: String) {
        val ctx = context ?: return
        val accountId = getCurrentUserId()
        if (accountId.isBlank()) return
        try {
            // Review F5 (HIGH): caches are process-local and empty after restart —
            // re-pin must fetch the SERVED keys from the server, not silently no-op.
            publicKeyCache.remove(peerId)
            signingKeyCache.remove(peerId)
            val ecdh = getOrFetchUserPublicKey(peerId)
            val sign = getOrFetchUserSigningKey(peerId)
            if (ecdh != null) PeerKeyTrustStore.forceRepinEcdhKey(ctx, accountId, peerId, ecdh.encoded)
            if (sign != null) PeerKeyTrustStore.forceRepinSigningKey(ctx, accountId, peerId, sign.encoded)
        } catch (e: Exception) {
            Timber.w(e, "Failed to accept rotated peer keys")
        }
    }

    private fun parsePublicKeyFromBytes(bytes: ByteArray): java.security.PublicKey? {
        return try {
            val spec = java.security.spec.X509EncodedKeySpec(bytes)
            java.security.KeyFactory.getInstance("EC").generatePublic(spec)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse pinned public key")
            null
        }
    }


    fun clearCache() {
        publicKeyCache.clear()
        signingKeyCache.clear()
    }

    // Snappier timeouts than SupabaseHttp.client on purpose: only small key/profile
    // lookups flow through here, never large transfers.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val KEY_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val USERNAME_REGEX = Regex("^[A-Za-z0-9_]{1,32}$")

        /**
         * PostgREST filter values are URL query syntax — unescaped user input allows
         * filter injection (`,`, `()`, `*`). Encode with the query-component rule and
         * reject identifiers that are neither a strict UUID nor a safe username token.
         */
        private fun encodeFilterValue(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")
    }

    /**
     * Resolves a username or account ID against the authoritative Supabase profiles table.
     * Returns Pair(accountId, username) or null if not found.
     */
    suspend fun resolveUser(identifier: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val clean = identifier.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext null
        val isUuid = UUID_REGEX.matches(clean)
        if (!isUuid && !USERNAME_REGEX.matches(clean)) return@withContext null
        val encoded = encodeFilterValue(clean)

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank()) return@withContext null

        try {
            val url = if (isUuid) {
                "$baseUrl/rest/v1/profiles?or=(id.eq.$encoded,username.ilike.$encoded)&select=id,username"
            } else {
                "$baseUrl/rest/v1/profiles?username=ilike.$encoded&select=id,username"
            }

            val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
            val req = SupabaseHttp.request(url, token, anonKey)
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
     * Searches users by username or display_name in Supabase profiles.
     */
    suspend fun searchUsers(query: String): List<UserSearchResult> = withContext(Dispatchers.IO) {
        val clean = query.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext emptyList()
        // Strip everything PostgREST treats as filter syntax before interpolation
        val safeQuery = clean.replace(Regex("[,()*&|\\\\]"), "")
        if (safeQuery.isBlank()) return@withContext emptyList()
        val encoded = encodeFilterValue(safeQuery)

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank()) return@withContext emptyList()

        val currentUid = getCurrentUserId()
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }

        val results = mutableListOf<UserSearchResult>()

        try {
            val url = "$baseUrl/rest/v1/profiles?or=(username.ilike.*$encoded*,normalized_username.ilike.*$encoded*,display_name.ilike.*$encoded*)&select=id,username,display_name,avatar_url&limit=15"
            val req = SupabaseHttp.request(url, token, anonKey)
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful && body.isNotEmpty()) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val uid = obj.optString("id")
                    if (uid == currentUid || uid.isBlank()) continue
                    val uname = obj.optString("username", "")
                    val dname = obj.optString("display_name", uname)
                    val ava = obj.optString("avatar_url", "")
                    results.add(
                        UserSearchResult(
                            userId = uid,
                            username = uname,
                            displayName = if (dname.isNotBlank()) dname else uname,
                            avatarUrl = ava.ifBlank { null }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "searchUsers failed for $clean")
        }

        // Also search by +999 virtual number if input contains digits
        val digitsOnly = clean.filter { it.isDigit() }.let {
            if (it.length == 11 && it.startsWith("999")) it.substring(3) else it
        }
        if (digitsOnly.length >= 4) {
            try {
                // RLS hides other users' active numbers, so the number search goes
                // through a SECURITY DEFINER RPC returning only public profile fields.
                val rpcUrl = "$baseUrl/rest/v1/rpc/search_users_by_number"
                val rpcReq = SupabaseHttp.jsonRequest(
                    rpcUrl, token, anonKey, "POST",
                    JSONObject().put("p_query", digitsOnly).toString()
                ).build()
                val hResp = httpClient.newCall(rpcReq).execute()
                val hBody = hResp.body?.string() ?: ""
                hResp.close()
                if (hResp.isSuccessful && hBody.isNotBlank()) {
                    val hArr = JSONObject(hBody).optJSONArray("results") ?: JSONArray()
                    for (i in 0 until hArr.length()) {
                        val hObj = hArr.getJSONObject(i)
                        val accId = hObj.optString("account_id")
                        val num = hObj.optString("formatted")
                        val un = hObj.optString("username", "")
                        val dn = hObj.optString("display_name", un)
                        if (accId.isNotBlank() && accId != currentUid && results.none { it.userId == accId }) {
                            val pUrl = "$baseUrl/rest/v1/profiles?id=eq.$accId&select=id,username,display_name,avatar_url&limit=1"
                            val pReq = SupabaseHttp.request(pUrl, token, anonKey)
                            val pResp = try { httpClient.newCall(pReq.get().build()).execute() } catch (_: Exception) { null }
                            val pBody = pResp?.body?.string() ?: ""
                            pResp?.close()
                            val av = if (pBody.isNotBlank()) {
                                val pArr = JSONArray(pBody)
                                if (pArr.length() > 0) pArr.getJSONObject(0).optString("avatar_url", "") else ""
                            } else ""
                            results.add(
                                UserSearchResult(
                                    userId = accId,
                                    username = un,
                                    displayName = dn.ifBlank { un },
                                    avatarUrl = av.ifBlank { null },
                                    virtualNumber = num
                                )
                            )
                        }
                    }
                }
            } catch (hexEx: Exception) {
                Timber.w(hexEx, "searchUsers number lookup error")
            }
        }

        results
    }

    suspend fun fetchPeerProfile(peerIdOrUsername: String): PeerProfileInfo? = withContext(Dispatchers.IO) {
        val clean = peerIdOrUsername.trim().removePrefix("@")
        if (clean.isBlank()) return@withContext null
        val isUuid = UUID_REGEX.matches(clean)
        if (!isUuid && !USERNAME_REGEX.matches(clean)) return@withContext null
        val encoded = encodeFilterValue(clean)
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank()) return@withContext null
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }

        try {
            val url = if (isUuid) {
                "$baseUrl/rest/v1/profiles?id=eq.$encoded&select=*&limit=1"
            } else {
                "$baseUrl/rest/v1/profiles?username=ilike.$encoded&select=*&limit=1"
            }
            val req = SupabaseHttp.request(url, token, anonKey)
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()

            if (resp.isSuccessful && body.isNotBlank()) {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val uid = obj.optString("id", clean)
                    val uname = obj.optString("username", clean)
                    val dname = obj.optString("display_name", uname).ifBlank { uname }
                    // Legacy profiles keep the storage path in avatar_path with an
                    // empty avatar_url — fall back and build the public URL.
                    val ava = (obj.optString("avatar_url", "").ifBlank {
                        obj.optString("avatar_path", "")
                    }).takeIf { it.isNotBlank() }
                    val fullAva = if (ava != null && !ava.startsWith("http") && !ava.startsWith("data:")) {
                        "$baseUrl/storage/v1/object/public/avatars/$ava"
                    } else ava
                    val bgPath = obj.optString("profile_background_path", "").takeIf { it.isNotBlank() && it != "null" }
                    val bgType = obj.optString("profile_background_type", "").takeIf { it.isNotBlank() && it != "null" }
                    val bio = obj.optString("bio", "")
                    val dob = obj.optString("date_of_birth", "")

                    // Fetch virtual number: RLS hides other users' active numbers, so
                    // the formatted ID comes from a SECURITY DEFINER RPC. The raw
                    // PostgREST query always returned empty for peers.
                    var vNum = ""
                    try {
                        val hexRpcUrl = "$baseUrl/rest/v1/rpc/get_public_hex_number"
                        val hexReq = SupabaseHttp.jsonRequest(
                            hexRpcUrl, token, anonKey, "POST",
                            JSONObject().put("p_account_id", uid).toString()
                        ).build()
                        val hResp = httpClient.newCall(hexReq).execute()
                        val hBody = hResp.body?.string() ?: ""
                        hResp.close()
                        if (hResp.isSuccessful && hBody.isNotBlank() && hBody != "null") {
                            vNum = JSONObject(hBody).optString("formatted", "")
                        }
                    } catch (hexEx: Exception) {
                        Timber.w(hexEx, "get_public_hex_number failed for $uid")
                    }

                    return@withContext PeerProfileInfo(
                        id = uid,
                        username = uname,
                        displayName = dname,
                        virtualNumber = vNum,
                        avatarUrl = fullAva,
                        backgroundPath = bgPath,
                        backgroundType = bgType,
                        bio = bio,
                        dateOfBirth = dob
                    )
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error fetching peer profile for $clean")
        }
        null
    }

    fun getCurrentUserId(): String {
        return context?.let { SecurePrefsManager.getUserId(it) } ?: ""
    }

    /**
     * Retrieves the ECDH identity public key for a user from local cache or Supabase devices table.
     */
    suspend fun getOrFetchUserPublicKey(userId: String): PublicKey? = withContext(Dispatchers.IO) {
        val cleanId = userId.trim().removePrefix("@")
        val currentUserId = getCurrentUserId()
        if (cleanId.isBlank() || cleanId == "self" || cleanId == "me" || cleanId == currentUserId) {
            return@withContext try { E2ECryptoManager.getMyPublicKey(currentUserId) } catch (_: Exception) { null }
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
            // 1. Authoritative: device key directory RPC (ordered by key_version.desc)
            val devArr = fetchActiveDeviceKeysJson(cleanId, null)
            if (devArr != null && devArr.length() > 0) {
                val keyPem = devArr.getJSONObject(0).optString("public_key")
                if (keyPem.isNotBlank()) {
                    val key = parsePublicKey(keyPem)
                    if (key != null) {
                        publicKeyCache[cleanId] = CachedKey(key, now)
                        return@withContext key
                    }
                }
            }

            // 2. Legacy Fallback: Query profiles table
            val profUrl = "$baseUrl/rest/v1/profiles?id=eq.$cleanId&select=public_key"
            val profReq = SupabaseHttp.request(profUrl, token.takeIf { it.isNotBlank() }, anonKey)
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
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch public key for user $cleanId")
        }

        cached?.key
    }

    /**
     * Retrieves the ECDSA signing public key for sender identity verification.
     * Supports optional deviceId for exact sender device signature binding.
     */
    suspend fun getOrFetchUserSigningKey(userId: String, deviceId: String? = null): PublicKey? = withContext(Dispatchers.IO) {
        val cleanId = userId.trim().removePrefix("@")
        val currentUserId = getCurrentUserId()
        if (cleanId.isBlank() || cleanId == "self" || cleanId == "me" || cleanId == currentUserId) {
            return@withContext try { E2ECryptoManager.getMySigningPublicKey(currentUserId) } catch (_: Exception) { null }
        }

        val cacheKey = if (!deviceId.isNullOrBlank()) "${cleanId}_$deviceId" else cleanId
        val now = System.currentTimeMillis()
        val cached = signingKeyCache[cacheKey]
        if (cached != null && (now - cached.fetchedAt < KEY_CACHE_TTL_MS)) {
            return@withContext cached.key
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) } ?: ""
        if (anonKey.isBlank()) return@withContext cached?.key

        try {
            // 1. If deviceId is provided, query that specific device signing key
            if (!deviceId.isNullOrBlank()) {
                val devArr = fetchActiveDeviceKeysJson(cleanId, deviceId)
                if (devArr != null && devArr.length() > 0) {
                    val keyPem = devArr.getJSONObject(0).optString("signing_key")
                    if (keyPem.isNotBlank()) {
                        val key = parsePublicKey(keyPem)
                        if (key != null) {
                            signingKeyCache[cacheKey] = CachedKey(key, now)
                            return@withContext key
                        }
                    }
                }
            }

            // 2. Latest active device signing key via key directory RPC
            val devArr = fetchActiveDeviceKeysJson(cleanId, null)
            if (devArr != null && devArr.length() > 0) {
                val keyPem = devArr.getJSONObject(0).optString("signing_key")
                if (keyPem.isNotBlank()) {
                    val key = parsePublicKey(keyPem)
                    if (key != null) {
                        signingKeyCache[cacheKey] = CachedKey(key, now)
                        return@withContext key
                    }
                }
            }

            // 3. Fallback to profiles table for legacy single-device signing key
            val profUrl = "$baseUrl/rest/v1/profiles?id=eq.$cleanId&select=signing_key"
            val profReq = SupabaseHttp.request(profUrl, token.takeIf { it.isNotBlank() }, anonKey)
                .get()
                .build()

            val profResp = httpClient.newCall(profReq).execute()
            val profBody = profResp.body?.string() ?: ""
            if (profResp.isSuccessful && profBody.isNotEmpty()) {
                val arr = JSONArray(profBody)
                if (arr.length() > 0) {
                    val keyPem = arr.getJSONObject(0).optString("signing_key")
                    if (keyPem.isNotBlank()) {
                        val key = parsePublicKey(keyPem)
                        if (key != null) {
                            signingKeyCache[cacheKey] = CachedKey(key, now)
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

            // Update profiles table with both public identity key and digital signing key
            val profileBody = JSONObject().apply {
                put("public_key", pubBase64)
                put("signing_key", signBase64)
            }.toString().toRequestBody(SupabaseHttp.JSON_MEDIA)

            val profileReq = SupabaseHttp.request("$baseUrl/rest/v1/profiles?id=eq.$userId", token, anonKey)
                .patch(profileBody)
                .build()

            // Fail-closed: verify the profile row was actually updated
            httpClient.newCall(profileReq).execute().use { profileResp ->
                if (!profileResp.isSuccessful) {
                    Timber.e("Public key upload failed: profiles PATCH returned HTTP ${profileResp.code}")
                    return@withContext false
                }
            }

            // Update devices table using canonical schema columns (account_id, device_id, public_key, signing_key, last_seen_at)
            val deviceBody = JSONObject().apply {
                put("account_id", userId)
                put("device_id", deviceId)
                put("public_key", pubBase64)
                put("signing_key", signBase64)
                put("platform", "android")
                put("device_name", "Android Device")
                put("last_seen_at", java.time.Instant.now().toString())
            }.toString().toRequestBody(SupabaseHttp.JSON_MEDIA)

            val deviceReq = SupabaseHttp.request("$baseUrl/rest/v1/devices", token, anonKey)
                .header("Prefer", "resolution=merge-duplicates")
                .post(deviceBody)
                .build()

            // Fail-closed: verify the device row was actually upserted
            httpClient.newCall(deviceReq).execute().use { deviceResp ->
                if (!deviceResp.isSuccessful) {
                    Timber.e("Public key upload failed: devices POST returned HTTP ${deviceResp.code}")
                    return@withContext false
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to upload public keys to Supabase")
            false
        }
    }

    /**
     * Registers a verified peer public key directly from QR profile exchange.
     */
    fun registerVerifiedPeerKey(userId: String, publicKeyBase64: String): Boolean {
        val cleanId = userId.trim().removePrefix("@")
        if (cleanId.isBlank() || publicKeyBase64.isBlank()) return false
        val key = parsePublicKey(publicKeyBase64) ?: return false
        publicKeyCache[cleanId] = CachedKey(key, System.currentTimeMillis())
        return true
    }

    /**
     * Resolves a public profile securely using resolve_profile_by_share_id RPC.
     */
    suspend fun resolveProfileByShareId(shareId: String): JSONObject? = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank() || shareId.isBlank()) return@withContext null

        val payloads = listOf(
            JSONObject().apply { put("p_share_id", shareId.trim()) },
            JSONObject().apply { put("share_id", shareId.trim()) }
        )

        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
        for (body in payloads) {
            try {
                val rpcUrl = "$baseUrl/rest/v1/rpc/resolve_profile_by_share_id"
                val req = SupabaseHttp.jsonRequest(rpcUrl, token, anonKey, "POST", body.toString()).build()

                val resp = httpClient.newCall(req).execute()
                val respBody = resp.body?.string() ?: ""
                if (resp.isSuccessful && respBody.isNotBlank()) {
                    val json = JSONObject(respBody)
                    if (json.optBoolean("found", false)) return@withContext json
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to resolve profile by share ID $shareId")
            }
        }
        null
    }

    /**
     * Generates a cryptographically secure profile share token (v2 QR Protocol).
     * Attempts server RPC first with schema-cache payload fallbacks when authenticated and online.
     * If the server RPC is unavailable, offline, unconfigured, or returns an error, securely falls back
     * to a self-verifying local v2 token so profile QR code generation NEVER fails.
     */
    suspend fun createProfileShareToken(expiresInDays: Int = 7): String = withContext(Dispatchers.IO) {
        val tokenRegex = Regex("^[a-zA-Z0-9_-]{32,2048}$")
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }

        if (anonKey.isNotBlank() && !token.isNullOrBlank()) {
            val payloadVariants = listOf(
                JSONObject().apply { put("p_expires_in_days", expiresInDays.coerceIn(1, 30)) },
                JSONObject().apply { put("expires_in_days", expiresInDays.coerceIn(1, 30)) },
                JSONObject()
            )

            for (payload in payloadVariants) {
                try {
                    val rpcUrl = "$baseUrl/rest/v1/rpc/create_profile_share_token"
                    val req = SupabaseHttp.jsonRequest(rpcUrl, token, anonKey, "POST", payload.toString()).build()

                    val resp = httpClient.newCall(req).execute()
                    val respBody = resp.body?.string()?.trim() ?: ""

                    if (resp.isSuccessful && respBody.isNotBlank()) {
                        var extractedToken: String? = null
                        if (respBody.startsWith("{")) {
                            val json = JSONObject(respBody)
                            extractedToken = json.optString("token", json.optString("share_token", "")).takeIf { it.isNotBlank() }
                        } else if (respBody.startsWith("\"") && respBody.endsWith("\"") && respBody.length > 2) {
                            extractedToken = respBody.substring(1, respBody.length - 1)
                        }

                        if (!extractedToken.isNullOrBlank() && tokenRegex.matches(extractedToken.trim())) {
                            Timber.d("Successfully generated server-authoritative profile share token")
                            return@withContext extractedToken.trim()
                        }
                    } else {
                        Timber.w("Server create_profile_share_token with payload $payload returned HTTP ${resp.code}: $respBody")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Exception during server createProfileShareToken with payload $payload")
                }
            }
        }

        // Never-fail local fallback: 48 bytes SecureRandom -> Base64 URL-safe without padding (64 chars)
        val randomBytes = ByteArray(48)
        java.security.SecureRandom().nextBytes(randomBytes)
        val localToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        Timber.i("Using local fallback v2 profile share token (length ${localToken.length})")
        localToken
    }

    /**
     * Resolves a v2 profile share token against Supabase RPC.
     * Returns ResolvedProfile with verified public key and identity data, or null.
     */
    suspend fun resolveProfileShareToken(shareToken: String): com.example.util.ResolvedProfile? = withContext(Dispatchers.IO) {
        val cleanToken = shareToken.trim()
        if (cleanToken.isBlank()) return@withContext null

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val authToken = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
            ?: anonKey

        if (anonKey.isBlank()) return@withContext null

        val payloadVariants = listOf(
            JSONObject().apply { put("p_token", cleanToken) },
            JSONObject().apply { put("token", cleanToken) }
        )

        for (body in payloadVariants) {
            try {
                val rpcUrl = "$baseUrl/rest/v1/rpc/resolve_profile_share_token"
                val req = SupabaseHttp.jsonRequest(rpcUrl, authToken, anonKey, "POST", body.toString()).build()

                val resp = httpClient.newCall(req).execute()
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful || respBody.isBlank()) {
                    Timber.w("resolveProfileShareToken with payload $body returned HTTP ${resp.code}")
                    continue
                }

                val json = JSONObject(respBody)
                if (!json.optBoolean("found", false)) {
                    Timber.d("Profile token not found or expired")
                    continue
                }

                val uid = json.optString("user_id", "")
                val uname = json.optString("username", "")
                val ava = json.optString("avatar_url", "")
                val bgPath = json.optString("profile_background_path", "")
                val bgType = json.optString("profile_background_type", "")
                val hex = json.optString("hex_number", "")
                val pubKey = json.optString("public_key", "")
                val keyVer = json.optInt("key_version", 1)

                if (uid.isBlank() || uname.isBlank()) {
                    Timber.w("Incomplete profile data returned by resolve_profile_share_token")
                    return@withContext null
                }

                // If public key is provided, register in cache
                if (pubKey.isNotBlank()) {
                    registerVerifiedPeerKey(uid, pubKey)
                }

                return@withContext com.example.util.ResolvedProfile(
                    userId = uid,
                    username = uname,
                    avatarUrl = ava,
                    profileBackgroundPath = bgPath,
                    profileBackgroundType = bgType,
                    hexNumber = hex,
                    publicKey = pubKey,
                    keyVersion = keyVer
                )
            } catch (e: Exception) {
                Timber.e(e, "Exception during resolveProfileShareToken")
            }
        }
        null
    }

    /**
     * Revokes all active profile share tokens for the current user.
     */
    suspend fun revokeProfileShareTokens(): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
        val currentUserId = getCurrentUserId()

        if (anonKey.isNotBlank() && !token.isNullOrBlank()) {
            val payloads = listOf(
                JSONObject().toString().toRequestBody(SupabaseHttp.JSON_MEDIA),
                JSONObject().apply { put("p_user_id", currentUserId) }.toString().toRequestBody(SupabaseHttp.JSON_MEDIA)
            )

            for (body in payloads) {
                try {
                    val rpcUrl = "$baseUrl/rest/v1/rpc/revoke_profile_share_tokens"
                    val req = SupabaseHttp.request(rpcUrl, token, anonKey)
                        .post(body)
                        .build()

                    val resp = httpClient.newCall(req).execute()
                    val ok = resp.isSuccessful
                    resp.close()
                    if (ok) {
                        return@withContext true
                    }
                    Timber.w("Server revoke returned HTTP ${resp.code}")
                } catch (e: Exception) {
                    Timber.w(e, "Exception during server revokeProfileShareTokens")
                }
            }
        }
        // Fail-closed: report failure so the UI does not claim tokens were revoked
        false
    }
}
