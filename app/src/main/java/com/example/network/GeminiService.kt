package com.example.network

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

sealed class AiResult {
    data class Success(val reply: String, val persona: String = "") : AiResult()
    object Unauthorized : AiResult()
    object FunctionNotFound : AiResult()
    object DeploymentUnavailable : AiResult()
    object ModelUnavailable : AiResult()
    object ConfigurationError : AiResult()
    data class RateLimited(val retryAfterSeconds: Long = 2) : AiResult()
    object InvalidRequest : AiResult()
    object NetworkError : AiResult()
    data class UpstreamError(val code: String, val message: String) : AiResult()
    data class Error(val code: String, val message: String) : AiResult()
    object Loading : AiResult()
}

@Singleton
class GeminiService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val lastRequestTimestamp = AtomicLong(0L)
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val MAX_PROMPT_LENGTH = 4000
        private const val MIN_INTERVAL_MS = 600L
    }

    suspend fun requestAssistant(
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        modelOption: AiModelOption = AiModelOption.DEFAULT
    ): AiResult = withContext(Dispatchers.IO) {
        val sanitizedPrompt = prompt.trim().take(MAX_PROMPT_LENGTH)
        if (sanitizedPrompt.isBlank()) {
            return@withContext AiResult.InvalidRequest
        }

        val now = System.currentTimeMillis()
        val last = lastRequestTimestamp.get()
        if (now - last < MIN_INTERVAL_MS) {
            val retrySec = ((MIN_INTERVAL_MS - (now - last)) / 1000).coerceAtLeast(1)
            return@withContext AiResult.RateLimited(retryAfterSeconds = retrySec)
        }
        lastRequestTimestamp.set(now)

        // All AI requests route exclusively through the server-side gemini-assistant Edge Function
        callSupabaseEdgeFunction(sanitizedPrompt, history, modelOption)
    }

    /**
     * Server-side AI call via authenticated Supabase Edge Function (gemini-assistant).
     * System prompts and model choices are strictly owned by the server.
     */
    private suspend fun callSupabaseEdgeFunction(
        prompt: String,
        history: List<Pair<String, String>>,
        modelOption: AiModelOption
    ): AiResult = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val userToken = SecurePrefsManager.getSupabaseAccessToken(context)

        if (userToken.isBlank()) {
            return@withContext AiResult.Unauthorized
        }

        val authHeader = "Bearer $userToken"

        val messagesArray = JSONArray()
        val recentHistory = history.takeLast(10)
        for ((role, text) in recentHistory) {
            val cleanText = text.trim().take(MAX_PROMPT_LENGTH)
            if (cleanText.isBlank()) continue
            val roleStr = if (role.equals("model", ignoreCase = true) || role.equals("assistant", ignoreCase = true)) {
                "assistant"
            } else {
                "user"
            }
            messagesArray.put(JSONObject().apply {
                put("role", roleStr)
                put("content", cleanText)
            })
        }

        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })

        val requestPayload = JSONObject().apply {
            put("persona_id", modelOption.personaId)
            put("messages", messagesArray)
        }

        val edgeFunctionUrl = "$baseUrl/functions/v1/gemini-assistant"
        val req = Request.Builder()
            .url(edgeFunctionUrl)
            .header("apikey", anonKey)
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .post(requestPayload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            httpClient.newCall(req).execute().use { response ->
                val respString = response.body?.string().orEmpty()
                val code = response.code

                if (response.isSuccessful && respString.isNotBlank()) {
                    val json = JSONObject(respString)
                    val ok = json.optBoolean("ok", true)
                    if (ok) {
                        val reply = json.optString("reply", "")
                        val persona = json.optString("persona", modelOption.personaId)
                        if (reply.isNotBlank()) {
                            return@withContext AiResult.Success(reply.trim(), persona)
                        }
                    }
                }

                Timber.w("AI edge function responded with HTTP $code: $respString")
                val jsonError = try { JSONObject(respString) } catch (_: Exception) { null }
                val errorCode = jsonError?.optString("code")?.ifBlank { null } ?: "HTTP_$code"
                val errorMsg = when {
                    !jsonError?.optString("message").isNullOrBlank() -> jsonError!!.getString("message")
                    !jsonError?.optString("error").isNullOrBlank() -> jsonError!!.getString("error")
                    else -> "AI error ($code)"
                }

                when {
                    code == 404 || errorCode == "NOT_FOUND" -> {
                        AiResult.FunctionNotFound
                    }
                    code == 401 || errorCode == "AI_UNAUTHORIZED" -> {
                        AiResult.Unauthorized
                    }
                    code == 429 || errorCode == "AI_RATE_LIMITED" -> {
                        val retrySec = jsonError?.optLong("retry_after_seconds", 2L) ?: 2L
                        AiResult.RateLimited(retrySec)
                    }
                    errorCode == "AI_MODEL_UNAVAILABLE" -> {
                        AiResult.ModelUnavailable
                    }
                    errorCode == "AI_UNAVAILABLE" || code == 503 -> {
                        AiResult.DeploymentUnavailable
                    }
                    errorCode == "AI_CONFIG_ERROR" -> {
                        AiResult.ConfigurationError
                    }
                    code == 400 || errorCode == "AI_INVALID_REQUEST" || errorCode == "UNKNOWN_PERSONA" -> {
                        AiResult.InvalidRequest
                    }
                    code == 502 || errorCode == "AI_UPSTREAM_ERROR" -> {
                        AiResult.UpstreamError(errorCode, errorMsg)
                    }
                    else -> {
                        AiResult.Error(errorCode, errorMsg)
                    }
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "Network error calling gemini-assistant edge function")
            AiResult.NetworkError
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error calling gemini-assistant edge function")
            AiResult.Error("CLIENT_ERROR", e.message ?: "Client processing error")
        }
    }
}
