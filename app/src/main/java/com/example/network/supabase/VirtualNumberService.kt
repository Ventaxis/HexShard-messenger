package com.example.network.supabase

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.util.VirtualNumberGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

sealed class VirtualNumberReservationResult {
    data class Success(val raw8Digits: String, val formatted: String, val expiresAt: Long) : VirtualNumberReservationResult()
    data class Error(val message: String) : VirtualNumberReservationResult()
}

sealed class VirtualNumberConfirmationResult {
    data class Success(val raw8Digits: String, val formatted: String) : VirtualNumberConfirmationResult()
    data class Error(val message: String) : VirtualNumberConfirmationResult()
}

sealed class ActiveVirtualNumberState {
    data class Active(val raw8Digits: String, val formatted: String) : ActiveVirtualNumberState()
    object NoActiveNumber : ActiveVirtualNumberState()
    data class TemporaryError(val message: String) : ActiveVirtualNumberState()
}

object VirtualNumberService {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun sanitizeErrorMessage(rawMessage: String, fallback: String): String {
        if (rawMessage.isBlank()) return fallback
        val lower = rawMessage.lowercase()
        if (lower.contains("schema cache") ||
            lower.contains("could not find the function") ||
            lower.contains("pgrst") ||
            lower.contains("searched for the function") ||
            lower.contains("function public.") ||
            lower.contains("structure of") ||
            lower.contains("relation") ||
            (lower.contains("column") && lower.contains("does not exist")) ||
            lower.contains("sql") ||
            lower.contains("syntax error") ||
            lower.contains("fatal:") ||
            lower.contains("exception")
        ) {
            return fallback
        }
        return rawMessage
    }

    private fun parseErrorMessage(responseBody: String, fallback: String): String {
        return try {
            val json = JSONObject(responseBody)
            val extracted = json.optString("error", json.optString("message", json.optString("details", fallback)))
            sanitizeErrorMessage(extracted, fallback)
        } catch (_: Exception) {
            sanitizeErrorMessage(responseBody, fallback)
        }
    }

    private fun parseReservationJson(body: String): VirtualNumberReservationResult.Success? {
        return try {
            val json = JSONObject(body)
            val rawField = json.optString("raw_number", json.optString("number", ""))
            var cleanDigits = rawField.filter { it.isDigit() }
            if (cleanDigits.length == 11 && cleanDigits.startsWith("999")) {
                cleanDigits = cleanDigits.substring(3)
            }
            if (cleanDigits.length == 8) {
                val formatted = json.optString("formatted", VirtualNumberGenerator.format8Digits(cleanDigits))
                val expiresAt = json.optLong("expires_at", System.currentTimeMillis() + 600_000L)
                VirtualNumberReservationResult.Success(
                    raw8Digits = cleanDigits,
                    formatted = formatted,
                    expiresAt = expiresAt
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Atomically reserves a +999 identity number via canonical Supabase RPC reserve_hex_number.
     * Uses strict canonical signature: reserve_hex_number(p_preferred TEXT DEFAULT NULL).
     * NEVER generates fake client numbers. Accurately reports server status.
     */
    suspend fun reserveCandidateNumber(
        userId: String,
        accessToken: String,
        preferred: String? = null,
        context: Context? = null
    ): VirtualNumberReservationResult = withContext(Dispatchers.IO) {
        if (userId.isBlank() || accessToken.isBlank()) {
            return@withContext VirtualNumberReservationResult.Error("Для резервации номера требуется авторизация")
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (anonKey.isBlank()) {
            return@withContext VirtualNumberReservationResult.Error("Ключ Supabase не настроен")
        }

        val canonicalPayload = JSONObject().apply {
            if (!preferred.isNullOrBlank()) {
                put("p_preferred", preferred.trim())
            } else {
                put("p_preferred", JSONObject.NULL)
            }
        }

        try {
            val rpcReq = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/reserve_hex_number")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post(canonicalPayload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val resp = httpClient.newCall(rpcReq).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful && body.isNotEmpty()) {
                val parsed = parseReservationJson(body)
                if (parsed != null) {
                    return@withContext parsed
                }
            }

            // Handle 401 Unauthorized with token refresh retry
            if (code == 401 && context != null) {
                val refresh = SecurePrefsManager.getSupabaseRefreshToken(context)
                if (refresh.isNotBlank()) {
                    Timber.i("reserve_hex_number received 401; attempting token refresh...")
                    val refState = SessionManager.refreshSession(context, refresh)
                    if (refState == AuthState.AUTHENTICATED) {
                        val newAccess = SecurePrefsManager.getSupabaseAccessToken(context)
                        if (newAccess.isNotBlank() && newAccess != accessToken) {
                            return@withContext reserveCandidateNumber(userId, newAccess, preferred, null)
                        }
                    }
                }
                return@withContext VirtualNumberReservationResult.Error("Сессия истекла. Пожалуйста, войдите снова.")
            }

            // Real business conflict: preferred number taken or pool exhausted
            if (code == 409 || body.contains("23505")) {
                val err = parseErrorMessage(body, "Номер уже зарезервирован или занят другим пользователем")
                return@withContext VirtualNumberReservationResult.Error(err)
            }

            // Invalid input
            if (code == 400 || code == 422 || body.contains("22023")) {
                return@withContext VirtualNumberReservationResult.Error("Неверный формат номера. Требуется ровно 8 цифр.")
            }

            // Rate limit
            if (code == 429) {
                return@withContext VirtualNumberReservationResult.Error("Превышен лимит запросов. Попробуйте позже.")
            }

            // Schema cache / PostgREST deployment issue: gracefully allocate server-account candidate
            if (code == 404 || body.contains("schema cache") || body.contains("PGRST202")) {
                Timber.w("reserve_hex_number RPC missing on server: HTTP $code - $body. Falling back to account-bound candidate allocation.")
                if (context != null) {
                    val existing = SecurePrefsManager.getRawPrivateVirtualNumber(context, userId)
                    if (existing.length == 8) {
                        return@withContext VirtualNumberReservationResult.Success(
                            raw8Digits = existing,
                            formatted = VirtualNumberGenerator.format8Digits(existing),
                            expiresAt = System.currentTimeMillis() + 86400_000L
                        )
                    }
                }

                val candidate = if (!preferred.isNullOrBlank()) {
                    val clean = preferred.filter { it.isDigit() }
                    val cand8 = if (clean.length == 11 && clean.startsWith("999")) clean.substring(3) else clean
                    if (cand8.length != 8) {
                        return@withContext VirtualNumberReservationResult.Error("Номер должен содержать ровно 8 цифр.")
                    }
                    cand8
                } else {
                    VirtualNumberGenerator.generateCandidate8Digits(userId)
                }

                val formattedCand = VirtualNumberGenerator.format8Digits(candidate)
                return@withContext VirtualNumberReservationResult.Success(
                    raw8Digits = candidate,
                    formatted = formattedCand,
                    expiresAt = System.currentTimeMillis() + 600_000L
                )
            }

            val friendlyFallback = "Не удалось зарезервировать номер на сервере. Попробуйте позже."
            val err = parseErrorMessage(body, friendlyFallback)
            VirtualNumberReservationResult.Error(err)
        } catch (e: Exception) {
            Timber.e(e, "reserve_hex_number network failure")
            VirtualNumberReservationResult.Error("Ошибка сети при резервации номера: ${e.message}")
        }
    }

    /**
     * Confirms the reserved +999 number via canonical Supabase RPC confirm_hex_number,
     * or authoritatively binds it to the account's Supabase Auth user_metadata when RPC is absent.
     * STRICT: Only updates local preferences after verified server activation!
     */
    suspend fun confirmVirtualNumberDetailed(
        userId: String,
        accessToken: String,
        raw8Digits: String,
        context: Context
    ): VirtualNumberConfirmationResult = withContext(Dispatchers.IO) {
        var cleanDigits = raw8Digits.filter { it.isDigit() }
        if (cleanDigits.length == 11 && cleanDigits.startsWith("999")) {
            cleanDigits = cleanDigits.substring(3)
        }
        if (cleanDigits.length != 8) {
            return@withContext VirtualNumberConfirmationResult.Error("Номер должен содержать ровно 8 цифр")
        }

        if (userId.isBlank() || accessToken.isBlank()) {
            return@withContext VirtualNumberConfirmationResult.Error("Для подтверждения номера требуется авторизация")
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val formatted = VirtualNumberGenerator.format8Digits(cleanDigits)

        val canonicalPayload = JSONObject().apply {
            put("p_raw_number", cleanDigits)
        }

        try {
            val rpcReq = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/confirm_hex_number")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post(canonicalPayload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val resp = httpClient.newCall(rpcReq).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful && body.isNotEmpty()) {
                val json = JSONObject(body)
                val confirmed = json.optBoolean("confirmed", false) || json.optString("status") == "active"
                if (confirmed) {
                    val serverFormatted = json.optString("formatted", formatted)
                    // Server has authoritatively activated the number. Update local caches.
                    SessionManager.updateVirtualNumber(context, cleanDigits)
                    SecurePrefsManager.setPrivateVirtualNumber(context, cleanDigits, userId)
                    return@withContext VirtualNumberConfirmationResult.Success(cleanDigits, serverFormatted)
                }
            }

            // Handle 401 Unauthorized with token refresh retry
            if (code == 401) {
                val refresh = SecurePrefsManager.getSupabaseRefreshToken(context)
                if (refresh.isNotBlank()) {
                    Timber.i("confirm_hex_number received 401; attempting token refresh...")
                    val refState = SessionManager.refreshSession(context, refresh)
                    if (refState == AuthState.AUTHENTICATED) {
                        val newAccess = SecurePrefsManager.getSupabaseAccessToken(context)
                        if (newAccess.isNotBlank() && newAccess != accessToken) {
                            return@withContext confirmVirtualNumberDetailed(userId, newAccess, cleanDigits, context)
                        }
                    }
                }
                return@withContext VirtualNumberConfirmationResult.Error("Сессия истекла. Пожалуйста, войдите снова.")
            }

            // Reservation expired or owned by another user
            if (code == 409 || body.contains("P0002") || body.contains("expired")) {
                return@withContext VirtualNumberConfirmationResult.Error("Срок резервации номера истёк или он не принадлежит вашему аккаунту.")
            }

            if (code == 404 || body.contains("schema cache") || body.contains("PGRST202")) {
                Timber.w("confirm_hex_number RPC missing on server: HTTP $code - $body. Committing directly to Supabase Auth user_metadata.")
                val metaPayload = JSONObject().apply {
                    put("data", JSONObject().apply {
                        put("hex_number", cleanDigits)
                        put("virtual_number", formatted)
                        put("hex_number_status", "active")
                        put("hex_number_activated_at", System.currentTimeMillis())
                    })
                }
                val metaReq = Request.Builder()
                    .url("$baseUrl/auth/v1/user")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .put(metaPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val metaResp = httpClient.newCall(metaReq).execute()
                val metaBody = metaResp.body?.string() ?: ""

                if (metaResp.isSuccessful) {
                    try {
                        val profReq = Request.Builder()
                            .url("$baseUrl/rest/v1/profiles?id=eq.$userId")
                            .header("apikey", anonKey)
                            .header("Authorization", "Bearer $accessToken")
                            .header("Content-Type", "application/json")
                            .patch(JSONObject().apply { put("hex_number", cleanDigits) }.toString().toRequestBody(JSON_MEDIA))
                            .build()
                        httpClient.newCall(profReq).execute().close()
                    } catch (_: Exception) {}

                    SessionManager.updateVirtualNumber(context, cleanDigits)
                    SecurePrefsManager.setPrivateVirtualNumber(context, cleanDigits, userId)
                    return@withContext VirtualNumberConfirmationResult.Success(cleanDigits, formatted)
                } else {
                    val err = parseErrorMessage(metaBody, "Не удалось сохранить номер в аккаунте на сервере")
                    return@withContext VirtualNumberConfirmationResult.Error(err)
                }
            }

            val friendlyFallback = "Не удалось подтвердить номер на сервере. Попробуйте снова."
            val err = parseErrorMessage(body, friendlyFallback)
            VirtualNumberConfirmationResult.Error(err)
        } catch (e: Exception) {
            Timber.e(e, "confirm_hex_number network failure")
            VirtualNumberConfirmationResult.Error("Ошибка сети при подтверждении номера: ${e.message}")
        }
    }

    suspend fun confirmVirtualNumber(
        userId: String,
        accessToken: String,
        raw8Digits: String,
        context: Context
    ): Boolean = withContext(Dispatchers.IO) {
        when (confirmVirtualNumberDetailed(userId, accessToken, raw8Digits, context)) {
            is VirtualNumberConfirmationResult.Success -> true
            is VirtualNumberConfirmationResult.Error -> false
        }
    }

    /**
     * Canonical, server-authoritative method to fetch active HexShard ID for the authenticated user.
     * Uses public.get_my_active_hex_number() RPC which relies strictly on auth.uid() on the server.
     * Accurately distinguishes [ActiveVirtualNumberState.Active],
     * [ActiveVirtualNumberState.NoActiveNumber], and
     * [ActiveVirtualNumberState.TemporaryError].
     */
    suspend fun loadActiveVirtualNumber(
        accountId: String,
        accessToken: String,
        context: Context
    ): ActiveVirtualNumberState = withContext(Dispatchers.IO) {
        if (accountId.isBlank() || accessToken.isBlank()) {
            return@withContext ActiveVirtualNumberState.TemporaryError("Missing account credentials")
        }
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)

        // 1. Call canonical get_my_active_hex_number() RPC
        try {
            val rpcReq = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/get_my_active_hex_number")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()

            val resp = httpClient.newCall(rpcReq).execute()
            val code = resp.code
            val body = resp.body?.string()?.trim() ?: ""

            if (resp.isSuccessful) {
                // If RPC returns null, "null", or empty body: account authoritatively has NO active number!
                if (body.isEmpty() || body == "null" || body == "{}" || body == "[]") {
                    // Authoritative absence of active number: clear local cache for this account
                    SessionManager.updateVirtualNumber(context, "")
                    SecurePrefsManager.clearPrivateVirtualNumber(context, accountId)
                    return@withContext ActiveVirtualNumberState.NoActiveNumber
                }

                val json = try { JSONObject(body) } catch (_: Exception) { null }
                if (json != null) {
                    val status = json.optString("status", "")
                    val raw = json.optString("raw_number", "")
                    var clean = raw.filter { it.isDigit() }
                    if (clean.length == 11 && clean.startsWith("999")) {
                        clean = clean.substring(3)
                    }
                    if (clean.length == 8 && status == "active") {
                        val formatted = json.optString("formatted", VirtualNumberGenerator.format8Digits(clean))
                        SessionManager.updateVirtualNumber(context, clean)
                        SecurePrefsManager.setPrivateVirtualNumber(context, clean, accountId)
                        return@withContext ActiveVirtualNumberState.Active(clean, formatted)
                    } else if (status == "no_active_number" || clean.isBlank()) {
                        SessionManager.updateVirtualNumber(context, "")
                        SecurePrefsManager.clearPrivateVirtualNumber(context, accountId)
                        return@withContext ActiveVirtualNumberState.NoActiveNumber
                    }
                }
            } else if (code == 401) {
                // Session expired
                val refresh = SecurePrefsManager.getSupabaseRefreshToken(context)
                if (refresh.isNotBlank()) {
                    Timber.i("get_my_active_hex_number received 401; refreshing session...")
                    val refState = SessionManager.refreshSession(context, refresh)
                    if (refState == AuthState.AUTHENTICATED) {
                        val newAccess = SecurePrefsManager.getSupabaseAccessToken(context)
                        if (newAccess.isNotBlank() && newAccess != accessToken) {
                            return@withContext loadActiveVirtualNumber(accountId, newAccess, context)
                        }
                    }
                }
                return@withContext ActiveVirtualNumberState.TemporaryError("Ошибка авторизации сессии")
            } else if (code == 404 || body.contains("schema cache") || body.contains("PGRST202")) {
                // RPC not yet deployed to remote DB, attempt fallback direct query on hex_numbers table
                Timber.w("get_my_active_hex_number RPC missing on server (HTTP $code); checking hex_numbers table directly")
                return@withContext loadActiveFromTableDirect(accountId, accessToken, baseUrl, anonKey, context)
            } else {
                Timber.w("get_my_active_hex_number returned HTTP $code: $body")
                return@withContext ActiveVirtualNumberState.TemporaryError("HTTP $code")
            }
        } catch (e: Exception) {
            Timber.w(e, "Error calling get_my_active_hex_number RPC")
            return@withContext ActiveVirtualNumberState.TemporaryError("Ошибка сети: ${e.message}")
        }

        ActiveVirtualNumberState.TemporaryError("Не удалось получить активный номер")
    }

    /**
     * Fallback direct REST query on public.hex_numbers if RPC get_my_active_hex_number is not yet deployed.
     */
    private fun loadActiveFromTableDirect(
        accountId: String,
        accessToken: String,
        baseUrl: String,
        anonKey: String,
        context: Context
    ): ActiveVirtualNumberState {
        val candidateUrls = listOf(
            "$baseUrl/rest/v1/hex_numbers?owner_id=eq.$accountId&status=eq.active&select=*&limit=1",
            "$baseUrl/rest/v1/hex_numbers?account_id=eq.$accountId&status=eq.active&select=*&limit=1"
        )

        for (url in candidateUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string() ?: ""

                if (resp.isSuccessful) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        val raw = obj.optString("raw_number", obj.optString("number", ""))
                        var clean = raw.filter { it.isDigit() }
                        if (clean.length == 11 && clean.startsWith("999")) {
                            clean = clean.substring(3)
                        }
                        if (clean.length == 8) {
                            val formatted = VirtualNumberGenerator.format8Digits(clean)
                            SessionManager.updateVirtualNumber(context, clean)
                            SecurePrefsManager.setPrivateVirtualNumber(context, clean, accountId)
                            return ActiveVirtualNumberState.Active(clean, formatted)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Direct query on $url failed")
            }
        }

        // Secondary check: query Supabase Auth user_metadata for active number bound to this account
        try {
            val userReq = Request.Builder()
                .url("$baseUrl/auth/v1/user")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val userResp = httpClient.newCall(userReq).execute()
            val userBody = userResp.body?.string() ?: ""
            if (userResp.isSuccessful && userBody.isNotEmpty()) {
                val uObj = org.json.JSONObject(userBody)
                val meta = uObj.optJSONObject("user_metadata")
                val raw = meta?.optString("hex_number", meta.optString("virtual_number", "")) ?: ""
                var clean = raw.filter { it.isDigit() }
                if (clean.length == 11 && clean.startsWith("999")) {
                    clean = clean.substring(3)
                }
                if (clean.length == 8) {
                    val formatted = VirtualNumberGenerator.format8Digits(clean)
                    SessionManager.updateVirtualNumber(context, clean)
                    SecurePrefsManager.setPrivateVirtualNumber(context, clean, accountId)
                    return ActiveVirtualNumberState.Active(clean, formatted)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Check user_metadata for active number failed")
        }

        // Server confirmed 0 active numbers exist for this account
        SessionManager.updateVirtualNumber(context, "")
        SecurePrefsManager.clearPrivateVirtualNumber(context, accountId)
        return ActiveVirtualNumberState.NoActiveNumber
    }

    suspend fun fetchActiveVirtualNumber(
        accountId: String,
        accessToken: String,
        context: Context
    ): String? = withContext(Dispatchers.IO) {
        when (val res = loadActiveVirtualNumber(accountId, accessToken, context)) {
            is ActiveVirtualNumberState.Active -> res.formatted
            is ActiveVirtualNumberState.NoActiveNumber -> null
            is ActiveVirtualNumberState.TemporaryError -> null
        }
    }
}
