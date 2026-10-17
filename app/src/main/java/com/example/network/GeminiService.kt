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
import com.example.ui.LocalizationManager
import com.example.ui.AppLanguage

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
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val lastRequestTimestamp = AtomicLong(0L)
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val MAX_PROMPT_LENGTH = 4000
        private const val MIN_INTERVAL_MS = 400L

        const val VENTAXIS_SYSTEM_PROMPT = """You are Ventaxis AI, the advanced analytical AI persona built directly into HexShard Messenger.

HexShard Ecosystem & Architecture:
- You operate natively inside HexShard Messenger — a privacy-first, decentralized messenger styled with a sleek Spotify-like dark green UI (#121212 and #1DB954).
- Identity (+999): Every account possesses a decentralized, anonymous 8-digit virtual phone number (+999 XXXX XXXX) generated without physical SIM cards or SMS.
- End-to-End Encryption (E2EE): Cryptographic keypairs (Ed25519 for signing, X25519 for key agreement) are generated and stored exclusively on-device in Android Keystore. The Supabase server acts only as a blind encrypted relay and never has access to plaintext.
- Local Storage: Encrypted via 256-bit AES SQLCipher.
- Features: Saved Messages ("Избранное") with self cloud sync, custom profile backgrounds (solid colors, gradients, photos, animated WebM videos), voice messages, QR code contact exchange, and media attachments.
- Multimodal Vision: You HAVE FULL MULTIMODAL VISION CAPABILITY. You CAN see, inspect, analyze, and describe photos and images sent directly by the user in this chat! When an image is provided, thoroughly analyze its visual content, recognize objects, extract visible text or code, explain diagrams, and answer questions.

Personality:
Calm, intellectual, deeply analytical, structured, technically precise, polite, privacy-conscious.
Behavior:
- Give comprehensive, well-structured, intelligent answers. Never use generic or canned template replies.
- Respond in the language of the user (Russian or English)."""

        const val HEXAGON_SYSTEM_PROMPT = """You are Hexagon AI, the ultra-fast, pragmatic built-in AI persona inside HexShard Messenger.

HexShard Ecosystem & Architecture:
- You operate natively inside HexShard Messenger — a privacy-first, decentralized messenger styled with a sleek Spotify-like dark green UI (#121212 and #1DB954).
- Identity (+999): Anonymous 8-digit virtual phone numbers without physical SIM cards.
- End-to-End Encryption (E2EE): On-device encryption with Android Keystore, SQLCipher local cache, zero server surveillance.
- Features: Saved Messages ("Избранное") with instant cloud sync, custom profile backgrounds (including animated WebM), voice notes, QR scanning.
- Multimodal Vision: You HAVE FULL MULTIMODAL VISION CAPABILITY. You CAN inspect, understand, solve tasks, extract text, and give feedback on photos and images sent in this chat!

Personality:
High-speed, sharp, concise, pragmatic, actionable, direct.
Behavior:
- Get straight to the point without filler or canned template responses.
- Respond in the language of the user (Russian or English)."""
    }

    suspend fun requestAssistant(
        prompt: String,
        imageBytes: ByteArray? = null,
        imageMimeType: String? = null,
        history: List<Pair<String, String>> = emptyList(),
        modelOption: AiModelOption = AiModelOption.DEFAULT
    ): AiResult = withContext(Dispatchers.IO) {
        val sanitizedPrompt = prompt.trim().take(MAX_PROMPT_LENGTH)
        if (sanitizedPrompt.isBlank() && (imageBytes == null || imageBytes.isEmpty())) {
            return@withContext AiResult.InvalidRequest
        }
        val effectivePrompt = if (sanitizedPrompt.isNotBlank()) sanitizedPrompt else "Пожалуйста, посмотри и проанализируй эту фотографию. Расскажи подробно, что на ней изображено."

        val now = System.currentTimeMillis()
        val last = lastRequestTimestamp.get()
        if (now - last < MIN_INTERVAL_MS) {
            val retrySec = ((MIN_INTERVAL_MS - (now - last)) / 1000).coerceAtLeast(1)
            return@withContext AiResult.RateLimited(retryAfterSeconds = retrySec)
        }
        lastRequestTimestamp.set(now)

        // Cloud-first: call Supabase Edge Function (server-side Gemini in Supabase secrets)
        val userToken = SecurePrefsManager.getSupabaseAccessToken(context)
        if (userToken.isBlank()) {
            return@withContext AiResult.Unauthorized
        }

        callSupabaseEdgeFunction(effectivePrompt, imageBytes, imageMimeType, history, modelOption)
    }

    private suspend fun callSupabaseEdgeFunction(
        prompt: String,
        imageBytes: ByteArray?,
        imageMimeType: String?,
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
                "model"
            } else {
                "user"
            }
            messagesArray.put(JSONObject().apply {
                put("role", roleStr)
                put("text", cleanText)
            })
        }

        val currentMsgObj = JSONObject().apply {
            put("role", "user")
            put("text", prompt)
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val b64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                put("image", b64)
                put("mime_type", imageMimeType ?: "image/jpeg")
            }
        }
        messagesArray.put(currentMsgObj)

        val requestPayload = JSONObject().apply {
            put("persona_id", modelOption.personaId.lowercase())
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
                    val ok = if (json.has("ok")) json.getBoolean("ok") else true
                    val reply = json.optString("reply", "")
                    if (ok && reply.isNotBlank()) {
                        val persona = json.optString("persona_id", json.optString("persona", modelOption.personaId))
                        return@withContext AiResult.Success(reply.trim(), persona)
                    }
                }

                Timber.w("AI edge function responded with HTTP $code: $respString")
                val jsonError = try { JSONObject(respString) } catch (_: Exception) { null }
                val errorCode = jsonError?.optString("code")?.ifBlank { null } ?: "HTTP_$code"
                val errorMsg = jsonError?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: jsonError?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "AI error ($code)"

                when {
                    code == 404 || errorCode == "NOT_FOUND" -> AiResult.FunctionNotFound
                    code == 401 || errorCode == "AI_UNAUTHORIZED" -> AiResult.Unauthorized
                    code == 429 || errorCode == "AI_RATE_LIMITED" || errorCode == "AI_QUOTA_EXCEEDED" -> {
                        val retrySec = jsonError?.optLong("retry_after_seconds", 5L) ?: 5L
                        AiResult.RateLimited(retrySec)
                    }
                    errorCode == "AI_MODEL_UNAVAILABLE" -> AiResult.ModelUnavailable
                    errorCode == "AI_UNAVAILABLE" || code == 503 -> AiResult.DeploymentUnavailable
                    errorCode == "AI_CONFIG_ERROR" -> AiResult.ConfigurationError
                    code == 400 || errorCode == "AI_INVALID_REQUEST" || errorCode == "UNKNOWN_PERSONA" -> AiResult.InvalidRequest
                    code == 502 || errorCode == "AI_UPSTREAM_ERROR" -> AiResult.UpstreamError(errorCode, errorMsg)
                    else -> AiResult.Error(errorCode, errorMsg)
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

    fun generateFallbackResult(
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        modelOption: AiModelOption = AiModelOption.DEFAULT
    ): AiResult {
        val reply = generateFallbackReply(prompt, history, modelOption)
        return AiResult.Success(reply, modelOption.personaId)
    }

    fun generateFallbackReply(
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        modelOption: AiModelOption = AiModelOption.DEFAULT
    ): String {
        val isVentaxis = modelOption.persona == com.example.data.AiPersona.VENTAXIS
        val isRussian = com.example.ui.LocalizationManager.currentLanguage.value == com.example.ui.AppLanguage.RUSSIAN ||
                prompt.any { it in '\u0400'..'\u04FF' }
        val lower = prompt.lowercase(java.util.Locale.ROOT)

        if (isRussian) {
            if (lower.contains("+999") || lower.contains("номер") || lower.contains("hexshard")) {
                return "В HexShard анонимный виртуальный номер формата +999 выдается при первой авторизации. Он обеспечивает приватность и сквозную защиту вашей личности."
            }
            if (isVentaxis) {
                if (lower.contains("привет") || lower.contains("здравствуй")) {
                    return "Здравствуйте! Я Ventaxis AI — ваш интеллектуальный помощник по стратегическим и техническим вопросам. Чем могу помочь?"
                }
                if (lower.contains("как дела")) {
                    return "Здравствуйте! Системы Ventaxis работают в штатном режиме. Готов анализировать данные и решать ваши задачи."
                }
                return "Ventaxis AI готов к анализу. Я помогу структурировать задачу, провести аудит или предоставить развернутый технический ответ."
            } else {
                if (lower.contains("привет") || lower.contains("здравствуй")) {
                    return "Привет! Я Hexagon AI в HexShard Messenger. Готов ответить на любые вопросы и помочь в чате!"
                }
                if (lower.contains("что умеешь") || lower.contains("помощь")) {
                    return "Привет! Hexagon AI умеет мгновенно отвечать на вопросы, генерировать тексты и идеи, помогать в переписке HexShard."
                }
                return "Hexagon AI на связи! Чем могу помочь вам в HexShard прямо сейчас?"
            }
        } else {
            if (lower.contains("+999") || lower.contains("virtual number") || lower.contains("hexshard")) {
                return "In HexShard, your +999 virtual number is assigned automatically during registration to guarantee complete cryptographic anonymity."
            }
            if (isVentaxis) {
                if (lower.contains("hello") || lower.contains("hi")) {
                    return "Hello! I am Ventaxis AI, your strategic and technical intelligence assistant. How can I direct my analysis to assist you today?"
                }
                return "Ventaxis AI is ready. I can assist with analytical thinking, cryptographic architectures, and software engineering inquiries."
            } else {
                if (lower.contains("hello") || lower.contains("hi")) {
                    return "Hi there! I am Hexagon AI inside HexShard Messenger. Ask me anything for a rapid and helpful answer!"
                }
                return "Hexagon AI is active and ready to assist you inside HexShard. What would you like to explore?"
            }
        }
    }
}



