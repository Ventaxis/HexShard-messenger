package com.example.network.supabase

import android.content.Context
import com.example.crypto.E2ECryptoManager
import com.example.data.AccountType
import com.example.data.SecurePrefsManager
import com.example.data.database.AppDatabase
import com.example.util.VirtualNumberGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Base64
import java.util.concurrent.TimeUnit

sealed class SignUpResult {
    data class Success(val accountId: String, val username: String, val accessToken: String) : SignUpResult()
    data class Error(val message: String, val isUsernameTaken: Boolean = false) : SignUpResult()
}

data class TelegramChallenge(
    val challengeId: String,
    val deepLink: String,
    val expiresAtMillis: Long
)

sealed class ChallengeVerifyResult {
    object Success : ChallengeVerifyResult()
    data class Error(val message: String) : ChallengeVerifyResult()
}

sealed class SignInResult {
    data class Success(
        val accountId: String,
        val username: String,
        val virtualNumber: String,
        val telegramVerified: Boolean,
        val accessToken: String
    ) : SignInResult()
    data class Error(val message: String) : SignInResult()
}

object SupabaseAuthService {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun validateUsername(username: String): String? {
        val clean = username.trim().removePrefix("@")
        if (clean.length < 3 || clean.length > 32) {
            return "Username must be between 3 and 32 characters"
        }
        if (!clean.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            return "Username can only contain Latin letters, numbers, and underscores"
        }
        return null
    }

    fun validatePassword(password: String): String? {
        if (password.length < 8) {
            return "Password must be at least 8 characters"
        }
        return null
    }

    /**
     * Requests the server-side Edge Function to create a cryptographically secure Telegram challenge.
     * ZERO client-side simulation. Returns server challenge ID and bot deep link.
     */
    suspend fun createTelegramChallenge(accountId: String, context: Context? = null): TelegramChallenge? = withContext(Dispatchers.IO) {
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() } ?: ""
        val result = TelegramService.createChallenge(token)
        when (result) {
            is TelegramChallengeResult.Success -> result.challenge
            is TelegramChallengeResult.Error -> {
                Timber.w("Telegram challenge creation failed: ${result.message}")
                null
            }
        }
    }

    /**
     * Calls server-side verify-telegram Edge Function.
     * Server verifies the 6-digit code sent by the bot and binds the Telegram identity.
     */
    suspend fun verifyTelegramChallenge(challengeToken: String, code: String, context: Context): ChallengeVerifyResult = withContext(Dispatchers.IO) {
        val token = SecurePrefsManager.getSupabaseAccessToken(context)
        val result = TelegramService.verifyChallenge(
            accessToken = token,
            challengeId = challengeToken,
            code = code,
            context = context
        )
        when (result) {
            is TelegramVerifyResult.Success -> ChallengeVerifyResult.Success
            is TelegramVerifyResult.Error -> ChallengeVerifyResult.Error(result.message)
        }
    }

    /**
     * Atomically checks server and reserves a virtual number.
     * STRICT NO-FAIL-OPEN: If server fails, returns null. Never generates a fake client number.
     */
    suspend fun reserveVirtualNumber(accountId: String, context: Context, preferred: String? = null): VirtualNumberReservationResult = withContext(Dispatchers.IO) {
        val token = SecurePrefsManager.getSupabaseAccessToken(context)
        VirtualNumberService.reserveCandidateNumber(accountId, token, preferred, context)
    }

    suspend fun savePrivateVirtualNumber(accountId: String, formattedOrRaw: String, context: Context): Boolean = withContext(Dispatchers.IO) {
        val cleanDigits = formattedOrRaw.filter { it.isDigit() }
        val token = SecurePrefsManager.getSupabaseAccessToken(context)
        if (token.isBlank()) return@withContext false
        VirtualNumberService.confirmVirtualNumber(accountId, token, cleanDigits, context)
    }

    /**
     * Authenticates registration strictly through Supabase Auth (GoTrue).
     * Server-authoritative: returns error if registration fails.
     * No fake tokens or offline fallback accounts.
     */
    suspend fun signUp(
        username: String,
        password: String,
        context: Context
    ): SignUpResult = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim().removePrefix("@")
        val normalized = cleanUsername.lowercase(java.util.Locale.ROOT)

        val uError = validateUsername(cleanUsername)
        if (uError != null) return@withContext SignUpResult.Error(uError)

        val pError = validatePassword(password)
        if (pError != null) return@withContext SignUpResult.Error(pError)

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)

        val signupPayload = JSONObject().apply {
            put("email", "$normalized@hexshard.app")
            put("password", password)
            put("data", JSONObject().apply {
                put("username", cleanUsername)
                put("normalized_username", normalized)
            })
        }

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/signup")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
            .post(signupPayload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                if (response.code == 422 || respBody.contains("already registered", ignoreCase = true) || respBody.contains("already exists", ignoreCase = true)) {
                    return@withContext SignUpResult.Error("Username @$cleanUsername is already registered", isUsernameTaken = true)
                }
                val msg = try {
                    val j = JSONObject(respBody)
                    j.optString("msg", j.optString("error_description", "Registration failed: HTTP ${response.code}"))
                } catch (_: Exception) {
                    "Registration failed: HTTP ${response.code}"
                }
                return@withContext SignUpResult.Error(msg)
            }

            val json = JSONObject(respBody)
            val userObj = json.optJSONObject("user") ?: json
            val accountId = userObj.optString("id")
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")

            if (accountId.isBlank()) {
                return@withContext SignUpResult.Error("Server response did not include a valid user account ID")
            }

            // Ensure cryptographic keypair is generated in hardware Keystore
            E2ECryptoManager.generateKeyPairIfNeeded()
            val identityPubKey = try {
                Base64.getEncoder().encodeToString(E2ECryptoManager.getMyPublicKey().encoded)
            } catch (_: Exception) { "" }
            val signingPubKey = try {
                Base64.getEncoder().encodeToString(E2ECryptoManager.getMySigningPublicKey().encoded)
            } catch (_: Exception) { "" }

            val authHeader = if (accessToken.isNotBlank()) "Bearer $accessToken" else "Bearer $anonKey"

            // 1. Create public profile row in profiles table
            try {
                val profilePayload = JSONObject().apply {
                    put("id", accountId)
                    put("username", cleanUsername)
                    put("normalized_username", normalized)
                    put("public_key", identityPubKey)
                }
                val profReq = Request.Builder()
                    .url("$baseUrl/rest/v1/profiles")
                    .header("apikey", anonKey)
                    .header("Authorization", authHeader)
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(profilePayload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(profReq).execute().close()
            } catch (e: Exception) {
                Timber.w(e, "Profile initial sync exception")
            }

            // 2. Register current device keys in devices table
            try {
                val deviceId = SecurePrefsManager.getDeviceId(context)
                val devPayload = JSONObject().apply {
                    put("account_id", accountId)
                    put("device_id", deviceId)
                    put("public_key", identityPubKey)
                    put("signing_key", signingPubKey)
                    put("platform", "android")
                    put("device_name", "Android Device")
                    put("last_seen_at", java.time.Instant.now().toString())
                }
                val devReq = Request.Builder()
                    .url("$baseUrl/rest/v1/devices")
                    .header("apikey", anonKey)
                    .header("Authorization", authHeader)
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(devPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(devReq).execute().close()
            } catch (e: Exception) {
                Timber.d("Device registration deferred: ${e.message}")
            }

            // Store server-authoritative session via SessionManager
            SessionManager.onLoginSuccess(
                context = context,
                userId = accountId,
                username = cleanUsername,
                accessToken = accessToken,
                refreshToken = refreshToken,
                virtualNumber = "",
                isTelegramVerified = false
            )

            SignUpResult.Success(
                accountId = accountId,
                username = cleanUsername,
                accessToken = accessToken
            )
        } catch (e: Exception) {
            Timber.e(e, "Network error during signUp")
            SignUpResult.Error("Unable to connect to server. Please check your network connection.")
        }
    }

    /**
     * Authenticates login strictly through Supabase Auth (GoTrue).
     * Server-authoritative: returns error if credentials fail.
     * No offline local fallback or fake token creation.
     */
    suspend fun signIn(
        username: String,
        password: String,
        context: Context
    ): SignInResult = withContext(Dispatchers.IO) {
        val cleanUsername = username.trim().removePrefix("@")
        val normalized = cleanUsername.lowercase(java.util.Locale.ROOT)

        if (cleanUsername.isBlank() || password.isBlank()) {
            return@withContext SignInResult.Error("Please enter username and password")
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)

        val loginPayload = JSONObject().apply {
            put("email", "$normalized@hexshard.app")
            put("password", password)
        }

        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=password")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
            .post(loginPayload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                if (response.code == 400 || response.code == 401) {
                    return@withContext SignInResult.Error("Invalid username or password")
                }
                return@withContext SignInResult.Error("Authentication failed: HTTP ${response.code}")
            }

            val json = JSONObject(respBody)
            val userObj = json.optJSONObject("user") ?: json
            val accountId = userObj.optString("id")
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")

            if (accountId.isBlank() || accessToken.isBlank()) {
                return@withContext SignInResult.Error("Server response did not include valid session tokens")
            }

            // Fetch active virtual number and verified Telegram status from Supabase
            var vNumber = ""
            var isTgVerified = false

            try {
                val numReq = Request.Builder()
                    .url("$baseUrl/rest/v1/hex_numbers?owner_id=eq.$accountId&status=eq.active&select=*&limit=1")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                val nResp = httpClient.newCall(numReq).execute()
                val nBody = nResp.body?.string() ?: ""
                if (nResp.isSuccessful && nBody.isNotEmpty()) {
                    val arr = JSONArray(nBody)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        vNumber = obj.optString("number", obj.optString("raw_number", ""))
                    }
                }
            } catch (e: Exception) {
                Timber.d("Error fetching hex_number: ${e.message}")
            }

            try {
                val tgReq = Request.Builder()
                    .url("$baseUrl/rest/v1/telegram_links?account_id=eq.$accountId&select=verified&limit=1")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                val tgResp = httpClient.newCall(tgReq).execute()
                val tgBody = tgResp.body?.string() ?: ""
                if (tgResp.isSuccessful && tgBody.isNotEmpty()) {
                    val arr = JSONArray(tgBody)
                    if (arr.length() > 0) {
                        isTgVerified = arr.getJSONObject(0).optBoolean("verified", false)
                    }
                }
            } catch (e: Exception) {
                Timber.d("Error fetching telegram_links: ${e.message}")
            }

            // Ensure cryptographic keypair exists in Keystore
            E2ECryptoManager.generateKeyPairIfNeeded(accountId)
            val identityPubKey = try {
                Base64.getEncoder().encodeToString(E2ECryptoManager.getMyPublicKey(accountId).encoded)
            } catch (_: Exception) { "" }
            val signingPubKey = try {
                Base64.getEncoder().encodeToString(E2ECryptoManager.getMySigningPublicKey(accountId).encoded)
            } catch (_: Exception) { "" }

            // Sync device public keys to Supabase devices table
            try {
                val deviceId = SecurePrefsManager.getDeviceId(context)
                val devPayload = JSONObject().apply {
                    put("account_id", accountId)
                    put("device_id", deviceId)
                    put("public_key", identityPubKey)
                    put("signing_key", signingPubKey)
                    put("platform", "android")
                    put("device_name", "Android Device")
                    put("last_seen_at", java.time.Instant.now().toString())
                }
                val devReq = Request.Builder()
                    .url("$baseUrl/rest/v1/devices")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(devPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(devReq).execute().close()
            } catch (e: Exception) {
                Timber.d("Device key update deferred: ${e.message}")
            }

            // Commit verified session
            SessionManager.onLoginSuccess(
                context = context,
                userId = accountId,
                username = cleanUsername,
                accessToken = accessToken,
                refreshToken = refreshToken,
                virtualNumber = vNumber,
                isTelegramVerified = isTgVerified
            )

            SignInResult.Success(
                accountId = accountId,
                username = cleanUsername,
                virtualNumber = vNumber,
                telegramVerified = isTgVerified,
                accessToken = accessToken
            )
        } catch (e: Exception) {
            Timber.e(e, "Network error during signIn")
            SignInResult.Error("Unable to connect to authentication server. Please check your network connection.")
        }
    }

    /**
     * Completely and permanently deletes the account from Supabase and wipes all local data.
     * Server deletion calls Supabase Edge Function / RPC to delete user from auth.users and all tables.
     * Client deletion cleans Room database, hardware Keystore keys, and EncryptedSharedPreferences.
     */
    suspend fun deleteAccount(context: Context): Boolean = withContext(Dispatchers.IO) {
        val accountId = SecurePrefsManager.getUserId(context)
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(context)
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)

        var serverDeleted = false
        if (accountId.isNotBlank() && accessToken.isNotBlank() && anonKey.isNotBlank()) {
            // 1. Request server-side complete account deletion via Edge Function
            try {
                val deleteReq = Request.Builder()
                    .url("$baseUrl/functions/v1/delete-account")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .post("{}".toRequestBody(JSON_MEDIA))
                    .build()
                val resp = httpClient.newCall(deleteReq).execute()
                if (resp.isSuccessful) {
                    serverDeleted = true
                }
                resp.close()
            } catch (e: Exception) {
                Timber.w(e, "Edge function delete-account call failed, attempting RPC delete")
            }

            // 2. RPC fallback delete_user_account
            if (!serverDeleted) {
                try {
                    val rpcReq = Request.Builder()
                        .url("$baseUrl/rest/v1/rpc/delete_user_account")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Type", "application/json")
                        .post("{}".toRequestBody(JSON_MEDIA))
                        .build()
                    val rpcResp = httpClient.newCall(rpcReq).execute()
                    if (rpcResp.isSuccessful) {
                        serverDeleted = true
                    }
                    rpcResp.close()
                } catch (e: Exception) {
                    Timber.d("RPC delete_user_account: ${e.message}")
                }
            }

            // 3. Direct RLS table deletion for devices and profile
            try {
                val delDev = Request.Builder()
                    .url("$baseUrl/rest/v1/devices?account_id=eq.$accountId")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
                httpClient.newCall(delDev).execute().close()

                val delProf = Request.Builder()
                    .url("$baseUrl/rest/v1/profiles?id=eq.$accountId")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .delete()
                    .build()
                httpClient.newCall(delProf).execute().close()
            } catch (e: Exception) {
                Timber.w(e, "Direct RLS cleanup during account deletion exception")
            }
        }

        // 3. Wipe local Room encrypted database
        try {
            val db = AppDatabase.getDatabase(context)
            if (accountId.isNotBlank()) {
                db.chatDao().clearAllOutboxForAccount(accountId)
                db.chatDao().clearAllMessagesForAccount(accountId)
                db.chatDao().clearAllChatsForAccount(accountId)
            }
            db.chatDao().clearAllOutboxForAccount("")
            db.chatDao().clearAllMessagesForAccount("")
            db.chatDao().clearAllChatsForAccount("")
        } catch (e: Exception) {
            Timber.w(e, "Error clearing local Room database during account deletion")
        }

        // 4. Delete hardware-backed cryptographic keys from AndroidKeyStore
        try {
            E2ECryptoManager.deleteKeys(accountId)
        } catch (e: Exception) {
            Timber.w(e, "Error deleting cryptographic keys")
        }

        // 5. Cryptographically wipe SQLCipher database passphrase
        try {
            com.example.data.database.SQLCipherUtils.clearPassphrase(context)
        } catch (e: Exception) {
            Timber.w(e, "Error clearing SQLCipher passphrase")
        }

        // 6. Clear session state and secure preferences
        SessionManager.clearSession(context)
        SecurePrefsManager.clear(context)

        serverDeleted || accountId.isBlank()
    }
}
