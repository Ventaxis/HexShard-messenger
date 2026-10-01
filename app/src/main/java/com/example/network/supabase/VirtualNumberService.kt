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

    /**
     * Checks if response body or HTTP status indicates a PostgREST schema cache miss
     * or function signature mismatch (e.g. PGRST202).
     */
    private fun isSchemaCacheError(body: String, code: Int): Boolean {
        if (code == 404) return true
        val lower = body.lowercase()
        return lower.contains("schema cache") ||
                lower.contains("could not find the function") ||
                lower.contains("pgrst202") ||
                lower.contains("searched for the function")
    }

    /**
     * Ensures that technical database internals (schema cache, PGRST202, SQL syntax, etc.)
     * are never displayed raw to the user.
     */
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
            val rawField = json.optString("raw_number", json.optString("number", json.optString("raw8Digits", json.optString("hex_number", ""))))
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
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Atomically reserves a +999 identity number via Supabase Edge Function or RPC.
     * Schema-cache resilient: never sends empty JSON when overloads expect named parameters,
     * tries payload variants, and falls through gracefully without exposing internal database errors.
     */
    suspend fun reserveCandidateNumber(
        userId: String,
        accessToken: String,
        preferred: String? = null,
        context: Context? = null
    ): VirtualNumberReservationResult = withContext(Dispatchers.IO) {
        if (userId.isBlank() || accessToken.isBlank()) {
            return@withContext VirtualNumberReservationResult.Error("Authentication is required to reserve a number")
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)

        // 1. Try Edge Function /functions/v1/reserve-virtual-number
        try {
            val edgePayload = JSONObject().apply {
                if (!preferred.isNullOrBlank()) {
                    put("preferred", preferred.trim())
                    put("p_preferred", preferred.trim())
                } else {
                    put("preferred", JSONObject.NULL)
                    put("p_preferred", JSONObject.NULL)
                }
            }
            val edgeReq = Request.Builder()
                .url("$baseUrl/functions/v1/reserve-virtual-number")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post(edgePayload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val edgeResp = httpClient.newCall(edgeReq).execute()
            val edgeBody = edgeResp.body?.string() ?: ""

            if (edgeResp.isSuccessful && edgeBody.isNotEmpty()) {
                val parsed = parseReservationJson(edgeBody)
                if (parsed != null) {
                    return@withContext parsed
                }
            } else if (edgeResp.code == 409 || edgeResp.code == 429) {
                Timber.w("reserve-virtual-number Edge Function returned business error ${edgeResp.code}: $edgeBody")
                val err = parseErrorMessage(edgeBody, "No virtual numbers currently available")
                return@withContext VirtualNumberReservationResult.Error(err)
            } else {
                Timber.w("reserve-virtual-number Edge Function failed with HTTP ${edgeResp.code}: $edgeBody; attempting schema-resilient RPC")
            }
        } catch (e: Exception) {
            Timber.w(e, "reserve-virtual-number Edge Function call failed; attempting RPC fallback")
        }

        // 2. Schema-resilient fallback to atomic RPC reserve_hex_number
        try {
            // PostgREST maps JSON fields to named parameters.
            // Prepare payload sequence to handle both p_preferred, preferred, explicit NULL, and zero-arg fallback
            val payloadVariants = mutableListOf<JSONObject>()
            if (!preferred.isNullOrBlank()) {
                payloadVariants.add(JSONObject().apply { put("p_preferred", preferred.trim()) })
                payloadVariants.add(JSONObject().apply { put("preferred", preferred.trim()) })
                payloadVariants.add(JSONObject().apply { put("p_preferred", JSONObject.NULL) })
                payloadVariants.add(JSONObject())
            } else {
                payloadVariants.add(JSONObject().apply { put("p_preferred", JSONObject.NULL) })
                payloadVariants.add(JSONObject().apply { put("preferred", JSONObject.NULL) })
                payloadVariants.add(JSONObject())
            }

            var lastCode = 0
            var lastBody = ""

            for (rpcPayload in payloadVariants) {
                val rpcReq = Request.Builder()
                    .url("$baseUrl/rest/v1/rpc/reserve_hex_number")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .post(rpcPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val resp = httpClient.newCall(rpcReq).execute()
                val body = resp.body?.string() ?: ""
                lastCode = resp.code
                lastBody = body

                if (resp.isSuccessful && body.isNotEmpty()) {
                    val parsed = parseReservationJson(body)
                    if (parsed != null) {
                        return@withContext parsed
                    }
                }

                // Business error: do NOT retry, no free numbers or rate limit
                if (resp.code == 409 || resp.code == 429) {
                    val err = parseErrorMessage(body, "No virtual numbers currently available")
                    return@withContext VirtualNumberReservationResult.Error(err)
                }

                if (isSchemaCacheError(body, resp.code)) {
                    Timber.w("RPC reserve_hex_number schema cache miss with payload $rpcPayload (HTTP ${resp.code}: $body), trying next variant...")
                    continue
                } else {
                    Timber.w("RPC reserve_hex_number failed with HTTP ${resp.code}: $body")
                    break
                }
            }

            val friendlyFallback = "В данный момент не удалось зарезервировать номер. Попробуйте позже."
            val err = parseErrorMessage(lastBody, friendlyFallback)
            return@withContext VirtualNumberReservationResult.Error(err)
        } catch (e: Exception) {
            Timber.e(e, "RPC reserve_hex_number network failure")
            return@withContext VirtualNumberReservationResult.Error("Network error during number reservation: ${e.message}")
        }
    }

    /**
     * Confirms the reserved +999 number and activates it on the server.
     * STRICT: Only returns success if server database marks it active.
     */
    suspend fun confirmVirtualNumber(
        userId: String,
        accessToken: String,
        raw8Digits: String,
        context: Context
    ): Boolean = withContext(Dispatchers.IO) {
        when (val res = confirmVirtualNumberDetailed(userId, accessToken, raw8Digits, context)) {
            is VirtualNumberConfirmationResult.Success -> true
            is VirtualNumberConfirmationResult.Error -> false
        }
    }

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
            return@withContext VirtualNumberConfirmationResult.Error("Number must contain exactly 8 digits")
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val formatted = VirtualNumberGenerator.format8Digits(cleanDigits)

        // 1. Try Edge Function /functions/v1/confirm-virtual-number
        try {
            val edgePayload = JSONObject().apply {
                put("raw_number", cleanDigits)
                put("p_raw_number", cleanDigits)
            }
            val edgeReq = Request.Builder()
                .url("$baseUrl/functions/v1/confirm-virtual-number")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post(edgePayload.toString().toRequestBody(JSON_MEDIA))
                .build()

            val edgeResp = httpClient.newCall(edgeReq).execute()
            val edgeBody = edgeResp.body?.string() ?: ""

            if (edgeResp.isSuccessful && edgeBody.isNotEmpty()) {
                val json = JSONObject(edgeBody)
                val confirmed = json.optBoolean("confirmed", json.optBoolean("success", true))
                if (confirmed) {
                    val serverFormatted = json.optString("formatted", formatted)
                    SessionManager.updateVirtualNumber(context, cleanDigits)
                    SecurePrefsManager.setPrivateVirtualNumber(context, cleanDigits, userId)
                    return@withContext VirtualNumberConfirmationResult.Success(cleanDigits, serverFormatted)
                }
            } else if (edgeResp.code == 409 || edgeResp.code == 400 || edgeResp.code == 403) {
                Timber.w("confirm-virtual-number Edge Function returned HTTP ${edgeResp.code}: $edgeBody")
            } else {
                Timber.w("confirm-virtual-number Edge Function returned HTTP ${edgeResp.code}: $edgeBody; attempting RPC fallback")
            }
        } catch (e: Exception) {
            Timber.w(e, "confirm-virtual-number Edge Function call failed; attempting RPC fallback")
        }

        // 2. Authoritative atomic RPC confirm_hex_number with schema-cache resilience
        try {
            val payloadVariants = listOf(
                JSONObject().apply { put("p_raw_number", cleanDigits) },
                JSONObject().apply { put("raw_number", cleanDigits) },
                JSONObject().apply { put("p_number", cleanDigits) },
                JSONObject().apply { put("number", cleanDigits) }
            )

            var lastCode = 0
            var lastBody = ""

            for (rpcPayload in payloadVariants) {
                val rpcReq = Request.Builder()
                    .url("$baseUrl/rest/v1/rpc/confirm_hex_number")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .post(rpcPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val rpcResp = httpClient.newCall(rpcReq).execute()
                val rpcBody = rpcResp.body?.string() ?: ""
                lastCode = rpcResp.code
                lastBody = rpcBody

                if (rpcResp.isSuccessful && rpcBody.isNotEmpty()) {
                    val json = JSONObject(rpcBody)
                    val confirmed = json.optBoolean("confirmed", json.optBoolean("success", true))
                    if (confirmed) {
                        val serverFormatted = json.optString("formatted", formatted)
                        SessionManager.updateVirtualNumber(context, cleanDigits)
                        SecurePrefsManager.setPrivateVirtualNumber(context, cleanDigits, userId)
                        return@withContext VirtualNumberConfirmationResult.Success(cleanDigits, serverFormatted)
                    }
                }

                if (rpcResp.code == 409) {
                    val err = parseErrorMessage(rpcBody, "Number has already been taken or expired")
                    return@withContext VirtualNumberConfirmationResult.Error(err)
                }

                if (isSchemaCacheError(rpcBody, rpcResp.code)) {
                    Timber.w("RPC confirm_hex_number schema mismatch on payload $rpcPayload, trying next variant...")
                    continue
                } else {
                    break
                }
            }

            val friendlyFallback = "Не удалось подтвердить номер на сервере. Попробуйте снова."
            val err = parseErrorMessage(lastBody, friendlyFallback)
            return@withContext VirtualNumberConfirmationResult.Error(err)
        } catch (e: Exception) {
            Timber.e(e, "RPC confirm_hex_number failure")
            return@withContext VirtualNumberConfirmationResult.Error("Network error during number confirmation: ${e.message}")
        }
    }

    /**
     * Unified, canonical method to query server-authoritative active HexShard ID.
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

        // Try querying by account_id first (production schema), then owner_id, then user_id
        val candidateUrls = listOf(
            "$baseUrl/rest/v1/hex_numbers?account_id=eq.$accountId&status=eq.active&select=*&limit=1",
            "$baseUrl/rest/v1/hex_numbers?owner_id=eq.$accountId&status=eq.active&select=*&limit=1",
            "$baseUrl/rest/v1/hex_numbers?user_id=eq.$accountId&status=eq.active&select=*&limit=1"
        )

        var lastError: String? = null
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
                            return@withContext ActiveVirtualNumberState.Active(clean, formatted)
                        }
                    }
                    // Server authoritatively confirmed 0 active numbers exist for this account
                    return@withContext ActiveVirtualNumberState.NoActiveNumber
                } else if (resp.code == 400 && body.contains("does not exist")) {
                    // Column name mismatch between schemas, try fallback url
                    lastError = "Column mismatch"
                    continue
                } else {
                    lastError = "Server HTTP ${resp.code}"
                }
            } catch (e: Exception) {
                Timber.w(e, "Error loading active virtual number from $url")
                lastError = e.message ?: "Network error"
            }
        }

        val friendlyError = sanitizeErrorMessage(lastError ?: "Failed to query active virtual number", "Не удалось загрузить активный номер")
        ActiveVirtualNumberState.TemporaryError(friendlyError)
    }

    /**
     * Queries Supabase for the active virtual number assigned to [accountId] and synchronizes session state.
     * If server returns active row, updates local cache and session with canonical 8 digits.
     * If server returns no record or an error, returns null.
     */
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
