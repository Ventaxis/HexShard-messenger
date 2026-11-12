package com.example.network.supabase

import android.content.Context
import com.example.crypto.E2ECryptoManager
import com.example.data.AccountType
import com.example.data.SecurePrefsManager
import com.example.data.database.AppDatabase
import com.example.network.SupabaseHttp
import com.example.util.VirtualNumberGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Base64
import com.example.ui.LocalizationManager
import com.example.ui.AppLanguage
import com.example.ui.EnglishStrings
import com.example.ui.RussianStrings

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

sealed class DeleteAccountResult {
    data class Success(val accountId: String) : DeleteAccountResult()
    data class Error(val message: String) : DeleteAccountResult()
}

object SupabaseAuthService {

    /**
     * UX mirror of the server-side reserved_usernames registry (the server remains the
     * only authority — this list merely gives an instant hint at sign-up time).
     */
    private val RESERVED_USERNAMES = setOf(
        "ventaxis", "ventaxisai", "ventaxis_ai", "hexagon", "hexagonai", "hexagon_ai",
        "hexshard", "hexshardai", "hexshard_ai", "ventaxiscorp", "ventaxis_corp",
        "support", "help", "security", "admin", "administrator", "moderator",
        "root", "system", "official", "staff", "team", "corp",
        "ai", "ai_assistant", "bot", "noreply", "admin_hexshard"
    )

    fun validateUsername(username: String): String? {
        val clean = username.trim().removePrefix("@")
        if (clean.length < 3 || clean.length > 32) {
            return "Username must be between 3 and 32 characters"
        }
        if (!clean.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            return "Username can only contain Latin letters, numbers, and underscores"
        }
        if (clean.lowercase() in RESERVED_USERNAMES) {
            return "This username is reserved by the operator"
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
        val token = SessionManager.currentSession.value?.accessToken?.takeIf { it.isNotBlank() }
            ?: (context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() })
            ?: ""
        if (token.isBlank()) {
            Timber.w("Cannot create Telegram challenge: no active access token found")
            return@withContext null
        }
        val result = TelegramService.createChallenge(token, context)
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
        val token = SessionManager.currentSession.value?.accessToken?.takeIf { it.isNotBlank() }
            ?: (context.let { SecurePrefsManager.getSupabaseAccessToken(it) }.takeIf { it.isNotBlank() })
            ?: ""
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
        if (anonKey.isBlank() || anonKey.contains("placeholder", ignoreCase = true)) {
            val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                "Приложение не сконфигурировано (нет Supabase anon key). Пересоберите APK с SUPABASE_ANON_KEY."
            } else {
                "Application is not configured (missing Supabase anon key). Please rebuild APK with SUPABASE_ANON_KEY."
            }
            return@withContext SignUpResult.Error(msg)
        }

        val signupPayload = JSONObject().apply {
            put("email", "$normalized@hexshard.app")
            put("password", password)
            put("data", JSONObject().apply {
                put("username", cleanUsername)
                put("normalized_username", normalized)
            })
        }

        val request = SupabaseHttp.jsonRequest(
            "$baseUrl/auth/v1/signup",
            null,
            anonKey,
            "POST",
            signupPayload.toString()
        ).build()

        try {
            Timber.i("Supabase signUp attempt for @$cleanUsername ($normalized@hexshard.app)")
            val response = SupabaseHttp.client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Timber.w("Supabase signUp error HTTP ${response.code} from ${request.url} - body: $respBody")
                val parsedErrorMsg = try {
                    val j = JSONObject(respBody)
                    j.optString("msg", j.optString("error_description", j.optString("message", "")))
                } catch (_: Exception) { "" }
                val fullErr = (parsedErrorMsg + " " + respBody).lowercase()

                if (response.code == 422 || fullErr.contains("already registered") || fullErr.contains("already exists") || fullErr.contains("user already exists")) {
                    val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                        "Пользователь с таким именем уже существует. Попробуйте войти."
                    } else {
                        "Username @$cleanUsername is already registered. Please sign in."
                    }
                    return@withContext SignUpResult.Error(msg, isUsernameTaken = true)
                }
                if (response.code == 429 || fullErr.contains("rate limit") || fullErr.contains("too many requests")) {
                    val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                        "Слишком много попыток регистрации (rate limit). Подождите пару минут или смените IP/VPN."
                    } else {
                        "Too many registration attempts (rate limit). Please wait a couple minutes or switch IP/VPN."
                    }
                    return@withContext SignUpResult.Error(msg)
                }
                val rawReason = if (parsedErrorMsg.isNotBlank()) parsedErrorMsg else respBody.take(200)
                val fallback = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                    "Ошибка регистрации (HTTP ${response.code}): $rawReason"
                } else {
                    "Registration failed (HTTP ${response.code}): $rawReason"
                }
                return@withContext SignUpResult.Error(fallback)
            }

            val json = JSONObject(respBody)
            val userObj = json.optJSONObject("user") ?: json
            val accountId = userObj.optString("id")
            val accessToken = json.optString("access_token")
            val refreshToken = json.optString("refresh_token")

            if (accountId.isBlank()) {
                return@withContext SignUpResult.Error("Server response did not include a valid user account ID")
            }

            if (accessToken.isBlank()) {
                val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                    "Аккаунт создан, но требует подтверждения email. Отключите 'Confirm email' в настройках Supabase (Auth -> Providers -> Email) или подтвердите аккаунт."
                } else {
                    "Account created, but email confirmation is required before sign-in. Disable 'Confirm email' in Supabase Dashboard (Auth -> Providers -> Email)."
                }
                return@withContext SignUpResult.Error(msg, isUsernameTaken = false)
            }

            // Store server-authoritative session via SessionManager IMMEDIATELY so active session is established
            SessionManager.onLoginSuccess(
                context = context,
                userId = accountId,
                username = cleanUsername,
                accessToken = accessToken,
                refreshToken = refreshToken,
                virtualNumber = "",
                isTelegramVerified = false
            )

            // Ensure cryptographic keypair is generated in hardware Keystore scoped to accountId
            try {
                E2ECryptoManager.generateKeyPairIfNeeded(accountId)
            } catch (e: Exception) {
                Timber.w(e, "Keystore key generation deferred in signUp: ${e.message}")
            }
            val identityPubKey = try {
                Base64.getEncoder().encodeToString(E2ECryptoManager.getMyPublicKey(accountId).encoded)
            } catch (_: Exception) { "" }
            val signingPubKey = try {
                Base64.getEncoder().encodeToString(E2ECryptoManager.getMySigningPublicKey(accountId).encoded)
            } catch (_: Exception) { "" }

            // 1. Create public profile row in profiles table
            try {
                val profilePayload = JSONObject().apply {
                    put("id", accountId)
                    put("username", cleanUsername)
                    put("display_name", cleanUsername)
                    put("normalized_username", normalized)
                    put("public_key", identityPubKey)
                }
                var profReq = SupabaseHttp.request("$baseUrl/rest/v1/profiles", accessToken, anonKey)
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(profilePayload.toString().toRequestBody(SupabaseHttp.JSON_MEDIA))
                    .build()
                var profResp = SupabaseHttp.client.newCall(profReq).execute()
                var pBody = profResp.body?.string() ?: ""
                var retryP = 0
                while (!profResp.isSuccessful && retryP < 3) {
                    profResp.close()
                    retryP++
                    if (pBody.contains("public_key", ignoreCase = true)) {
                        profilePayload.remove("public_key")
                    } else if (pBody.contains("normalized_username", ignoreCase = true)) {
                        profilePayload.remove("normalized_username")
                    } else {
                        break
                    }
                    profReq = SupabaseHttp.request("$baseUrl/rest/v1/profiles", accessToken, anonKey)
                        .header("Prefer", "resolution=merge-duplicates")
                        .post(profilePayload.toString().toRequestBody(SupabaseHttp.JSON_MEDIA))
                        .build()
                    profResp = SupabaseHttp.client.newCall(profReq).execute()
                    pBody = profResp.body?.string() ?: ""
                }
                if (!profResp.isSuccessful) {
                    Timber.w("Profile initial sync returned HTTP ${profResp.code} - $pBody")
                    // A failed profile insert previously vanished silently: the auth user
                    // existed but had no profile (and reserved-username rejections died
                    // here). Surface it to the caller instead.
                    val reserved = pBody.contains("reserved", ignoreCase = true) ||
                            pBody.contains("username", ignoreCase = true) ||
                            pBody.contains("23505")
                    profResp.close()
                    return@withContext if (reserved) {
                        SignUpResult.Error(
                            "Username is already taken or reserved by the operator. Please choose another.",
                            isUsernameTaken = true
                        )
                    } else {
                        SignUpResult.Error("Account created, but profile initialization failed (HTTP ${profResp.code}). Please try signing in.")
                    }
                }
                profResp.close()
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
                val devReq = SupabaseHttp.request("$baseUrl/rest/v1/devices", accessToken, anonKey)
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(devPayload.toString().toRequestBody(SupabaseHttp.JSON_MEDIA))
                    .build()
                val devResp = SupabaseHttp.client.newCall(devReq).execute()
                if (!devResp.isSuccessful) {
                    Timber.w("Device registration returned HTTP ${devResp.code}")
                }
                devResp.close()
            } catch (e: Exception) {
                Timber.d("Device registration deferred: ${e.message}")
            }

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
        if (anonKey.isBlank() || anonKey.contains("placeholder", ignoreCase = true)) {
            val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                "Приложение не сконфигурировано (нет Supabase anon key). Пересоберите APK с SUPABASE_ANON_KEY."
            } else {
                "Application is not configured (missing Supabase anon key). Please rebuild APK with SUPABASE_ANON_KEY."
            }
            return@withContext SignInResult.Error(msg)
        }

        val loginPayload = JSONObject().apply {
            put("email", "$normalized@hexshard.app")
            put("password", password)
        }

        val request = SupabaseHttp.jsonRequest(
            "$baseUrl/auth/v1/token?grant_type=password",
            null,
            anonKey,
            "POST",
            loginPayload.toString()
        ).build()

        try {
            Timber.i("Supabase signIn attempt for @$cleanUsername ($normalized@hexshard.app)")
            val response = SupabaseHttp.client.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Timber.w("Supabase signIn failed: HTTP ${response.code} from ${request.url} - body: $respBody")
                val parsedErrorMsg = try {
                    val j = JSONObject(respBody)
                    j.optString("error_description", j.optString("msg", j.optString("message", "")))
                } catch (_: Exception) { "" }
                val fullErr = (parsedErrorMsg + " " + respBody).lowercase()

                if (fullErr.contains("email not confirmed") || fullErr.contains("email_not_confirmed")) {
                    val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                        "Email не подтверждён в Supabase. Отключите 'Confirm email' в Supabase Dashboard (Auth -> Providers -> Email) или подтвердите аккаунт."
                    } else {
                        "Email not confirmed in Supabase. Disable 'Confirm email' in Supabase Dashboard (Auth -> Providers -> Email) or confirm your account."
                    }
                    return@withContext SignInResult.Error(msg)
                }
                if (response.code == 429 || fullErr.contains("rate limit") || fullErr.contains("too many requests") || fullErr.contains("too many")) {
                    val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                        "Слишком много попыток входа (rate limit). Подождите пару минут или смените IP/VPN."
                    } else {
                        "Too many login attempts (rate limit). Please wait a couple minutes or switch IP/VPN."
                    }
                    return@withContext SignInResult.Error(msg)
                }
                if (fullErr.contains("invalid login credentials") || fullErr.contains("invalid_grant") || fullErr.contains("invalid credentials")) {
                    val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                        RussianStrings.invalidCredentials
                    } else {
                        EnglishStrings.invalidCredentials
                    }
                    return@withContext SignInResult.Error(msg)
                }

                val rawReason = if (parsedErrorMsg.isNotBlank()) parsedErrorMsg else respBody.take(200)
                val fallback = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                    "Ошибка авторизации (HTTP ${response.code}): $rawReason"
                } else {
                    "Authentication failed (HTTP ${response.code}): $rawReason"
                }
                return@withContext SignInResult.Error(fallback)
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

            val userMeta = userObj.optJSONObject("user_metadata")
            val metaHex = userMeta?.optString("hex_number", userMeta.optString("virtual_number", "")) ?: ""
            var cleanMeta = metaHex.filter { it.isDigit() }
            if (cleanMeta.length == 11 && cleanMeta.startsWith("999")) {
                cleanMeta = cleanMeta.substring(3)
            }
            if (cleanMeta.length == 8) {
                vNumber = cleanMeta
                SecurePrefsManager.setPrivateVirtualNumber(context, cleanMeta, accountId)
            }

            // 1. Fetch and restore server-authoritative profile (display_name, bio, DOB, avatar, background, hex_number)
            try {
                val profileRepo = com.example.data.repository.ProfileRepository(context)
                profileRepo.fetchProfileFromServer(accountId, accessToken)
            } catch (e: Exception) {
                Timber.w(e, "Profile sync exception on sign-in")
            }

            // 2. Fetch server-authoritative active +999 HexShard ID via canonical RPC
            try {
                when (val activeState = VirtualNumberService.loadActiveVirtualNumber(accountId, accessToken, context)) {
                    is ActiveVirtualNumberState.Active -> {
                        vNumber = activeState.raw8Digits
                    }
                    is ActiveVirtualNumberState.NoActiveNumber -> {
                        vNumber = ""
                        SecurePrefsManager.clearPrivateVirtualNumber(context, accountId)
                    }
                    is ActiveVirtualNumberState.TemporaryError -> {
                        // Server temporarily unreachable; preserve existing valid account cache as offline fallback
                        val cached = SecurePrefsManager.getRawPrivateVirtualNumber(context, accountId)
                        if (cached.length == 8) {
                            vNumber = cached
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Error loading active virtual number on sign-in")
            }

            try {
                val tgReq = SupabaseHttp.request("$baseUrl/rest/v1/telegram_links?account_id=eq.$accountId&select=verified&limit=1", accessToken, anonKey)
                    .get()
                    .build()
                val tgResp = SupabaseHttp.client.newCall(tgReq).execute()
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
            try {
                E2ECryptoManager.generateKeyPairIfNeeded(accountId)
            } catch (e: Exception) {
                Timber.w(e, "Keystore key generation deferred in signIn: ${e.message}")
            }
            val identityPubKey = try {
                Base64.getEncoder().encodeToString(E2ECryptoManager.getMyPublicKey(accountId).encoded)
            } catch (_: Exception) { "" }
            val signingPubKey = try {
                Base64.getEncoder().encodeToString(E2ECryptoManager.getMySigningPublicKey(accountId).encoded)
            } catch (_: Exception) { "" }

            // Legacy profiles may predate key synchronization. Restore both required public
            // keys from this device's Keystore; private keys never leave the device.
            if (identityPubKey.isNotBlank() && signingPubKey.isNotBlank()) {
                try {
                    val profileKeys = JSONObject().apply {
                        put("public_key", identityPubKey)
                        put("signing_key", signingPubKey)
                    }.toString().toRequestBody(SupabaseHttp.JSON_MEDIA)
                    SupabaseHttp.request("$baseUrl/rest/v1/profiles?id=eq.$accountId", accessToken, anonKey)
                        .patch(profileKeys).build().let {
                            SupabaseHttp.client.newCall(it).execute().close()
                        }
                } catch (e: Exception) {
                    Timber.w(e, "Profile public-key repair deferred")
                }
            }

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
                val devReq = SupabaseHttp.request("$baseUrl/rest/v1/devices", accessToken, anonKey)
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(devPayload.toString().toRequestBody(SupabaseHttp.JSON_MEDIA))
                    .build()
                SupabaseHttp.client.newCall(devReq).execute().close()
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

            val formattedVNum = if (vNumber.isNotBlank()) com.example.util.VirtualNumberGenerator.format8Digits(vNumber) else ""

            SignInResult.Success(
                accountId = accountId,
                username = cleanUsername,
                virtualNumber = formattedVNum,
                telegramVerified = isTgVerified,
                accessToken = accessToken
            )
        } catch (e: Exception) {
            Timber.e(e, "Network exception during Supabase signIn: ${e.message}")
            val msg = if (LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN) {
                "Ошибка подключения к серверу. Проверьте интернет или VPN."
            } else {
                "Unable to connect to server. Please check your internet or VPN."
            }
            SignInResult.Error(msg)
        }
    }

    /**
     * Completely and permanently deletes the account from Supabase and wipes all local data.
     * Server deletion calls Supabase Edge Function / RPC to delete user from auth.users and all tables.
     * Client deletion cleans Room database, hardware Keystore keys, and EncryptedSharedPreferences.
     */
    suspend fun deleteAccount(context: Context, client: OkHttpClient = SupabaseHttp.client): DeleteAccountResult = withContext(Dispatchers.IO) {
        val accountId = SecurePrefsManager.getUserId(context)
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(context)
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)

        if (accountId.isBlank()) {
            return@withContext DeleteAccountResult.Error("No authenticated account to delete")
        }

        var serverDeleted = false
        if (accountId.isNotBlank() && accessToken.isNotBlank() && anonKey.isNotBlank()) {
            // 1. Request server-side complete account deletion via Edge Function
            try {
                val edgePayload = JSONObject().apply {
                    put("user_id", accountId)
                    put("account_id", accountId)
                }
                val deleteReq = SupabaseHttp.jsonRequest(
                    "$baseUrl/functions/v1/delete-account",
                    accessToken,
                    anonKey,
                    "POST",
                    edgePayload.toString()
                ).build()
                val resp = client.newCall(deleteReq).execute()
                val edgeBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    serverDeleted = true
                } else {
                    Timber.w("Edge function delete-account returned HTTP ${resp.code}: $edgeBody, trying RPC fallback")
                }
                resp.close()
            } catch (e: Exception) {
                Timber.w(e, "Edge function delete-account call failed, attempting RPC delete")
            }

            // 2. RPC fallback delete_user_account with schema resilience
            if (!serverDeleted) {
                val rpcPayloads = listOf(
                    JSONObject().apply { put("p_user_id", accountId) },
                    JSONObject().apply { put("p_account_id", accountId) },
                    JSONObject()
                )
                for (payload in rpcPayloads) {
                    try {
                        val rpcReq = SupabaseHttp.jsonRequest(
                            "$baseUrl/rest/v1/rpc/delete_user_account",
                            accessToken,
                            anonKey,
                            "POST",
                            payload.toString()
                        ).build()
                        val rpcResp = client.newCall(rpcReq).execute()
                        val rpcBody = rpcResp.body?.string() ?: ""
                        if (rpcResp.isSuccessful) {
                            serverDeleted = true
                            rpcResp.close()
                            break
                        }
                        rpcResp.close()
                    } catch (e: Exception) {
                        Timber.d("RPC delete_user_account with payload $payload: ${e.message}")
                    }
                }
            }

            // 3. Direct RLS table deletion for devices and profile only after server deletion confirmation
            if (serverDeleted) {
                try {
                    com.example.data.repository.ProfileBackgroundManager.removeProfileBackground(context)
                } catch (bgEx: Exception) {
                    Timber.w(bgEx, "Failed to remove profile background during account deletion")
                }

                try {
                    val delDev = SupabaseHttp.request("$baseUrl/rest/v1/devices?account_id=eq.$accountId", accessToken, anonKey)
                        .delete()
                        .build()
                    client.newCall(delDev).execute().close()

                    val delProf = SupabaseHttp.request("$baseUrl/rest/v1/profiles?id=eq.$accountId", accessToken, anonKey)
                        .delete()
                        .build()
                    client.newCall(delProf).execute().close()
                } catch (e: Exception) {
                    Timber.w(e, "Direct RLS cleanup during account deletion exception")
                }
            }
        }

        if (!serverDeleted) {
            Timber.e("Server account deletion failed. Failing closed to preserve local state and allow retry.")
            return@withContext DeleteAccountResult.Error("Server account deletion failed; local state preserved for retry.")
        }

        // 4. Wipe local Room encrypted database specifically for the target account
        try {
            val db = AppDatabase.getDatabase(context)
            if (accountId.isNotBlank()) {
                db.chatDao().clearAllOutboxForAccount(accountId)
                db.chatDao().clearAllMessagesForAccount(accountId)
                db.chatDao().clearAllChatsForAccount(accountId)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error clearing local Room database for account $accountId")
        }

        // 5. Delete hardware-backed cryptographic keys for target account
        try {
            E2ECryptoManager.deleteKeys(accountId)
        } catch (e: Exception) {
            Timber.w(e, "Error deleting cryptographic keys for account $accountId")
        }

        // 6. Clear active session state
        SessionManager.clearSession(context)

        DeleteAccountResult.Success(accountId)
    }
}
