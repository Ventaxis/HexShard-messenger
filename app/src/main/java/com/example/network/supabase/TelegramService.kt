package com.example.network.supabase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

sealed class TelegramVerifyResult {
    object Success : TelegramVerifyResult()
    data class Error(val message: String) : TelegramVerifyResult()
}

sealed class TelegramChallengeResult {
    data class Success(val challenge: TelegramChallenge) : TelegramChallengeResult()
    data class Error(val message: String) : TelegramChallengeResult()
}

object TelegramService {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Calls Supabase Edge Function to create a server-side Telegram verification challenge.
     * The Edge Function issues a cryptographically secure challenge token and formats the
     * deep link for @HexShardBot: https://t.me/HexShardBot?start=<token>.
     */
    suspend fun createChallenge(accessToken: String, context: Context? = null): TelegramChallengeResult = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) {
            return@withContext TelegramChallengeResult.Error("Authentication token is required")
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)

        val req = Request.Builder()
            .url("$baseUrl/functions/v1/create-telegram-challenge")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()

        try {
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful && body.isNotEmpty()) {
                val json = JSONObject(body)
                val challengeId = json.optString("challenge_id")
                val deepLink = json.optString("deep_link", "${SupabaseConfig.TELEGRAM_BOT_URL}?start=$challengeId")
                val expiresAt = json.optLong("expires_at", System.currentTimeMillis() + 300_000L)

                if (challengeId.isNotBlank()) {
                    return@withContext TelegramChallengeResult.Success(
                        TelegramChallenge(
                            challengeId = challengeId,
                            deepLink = deepLink,
                            expiresAtMillis = expiresAt
                        )
                    )
                }
            }
            Timber.w("Failed to create Telegram challenge on server: HTTP ${resp.code}: $body")
            TelegramChallengeResult.Error("Failed to initiate Telegram verification on server")
        } catch (e: Exception) {
            Timber.e(e, "Network error during create-telegram-challenge")
            TelegramChallengeResult.Error("Unable to reach verification server. Please check your connection.")
        }
    }

    /**
     * Calls Supabase Edge Function to verify the 6-digit code received from @HexShardBot.
     * Checks challenge ownership, expiration, single-use, max attempts, and binds telegram identity.
     */
    suspend fun verifyChallenge(
        accessToken: String,
        challengeId: String,
        code: String,
        context: Context
    ): TelegramVerifyResult = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().filter { it.isDigit() }
        if (cleanCode.length != 6) {
            return@withContext TelegramVerifyResult.Error("Please enter a valid 6-digit verification code")
        }

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)

        val payload = JSONObject().apply {
            put("challenge_id", challengeId)
            put("code", cleanCode)
        }

        val req = Request.Builder()
            .url("$baseUrl/functions/v1/verify-telegram")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (resp.isSuccessful && body.isNotEmpty()) {
                val json = JSONObject(body)
                val verified = json.optBoolean("verified", false)
                if (verified) {
                    SessionManager.updateTelegramVerified(context, true)
                    return@withContext TelegramVerifyResult.Success
                }
            }

            val errorMsg = try {
                val errJson = JSONObject(body)
                errJson.optString("error", "Invalid or expired verification code")
            } catch (_: Exception) {
                "Invalid or expired verification code"
            }
            TelegramVerifyResult.Error(errorMsg)
        } catch (e: Exception) {
            Timber.e(e, "Network error during verify-telegram")
            TelegramVerifyResult.Error("Network error. Please try again.")
        }
    }
}
