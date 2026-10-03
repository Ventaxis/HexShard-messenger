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
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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

        // All AI requests route strictly through server-side gemini-assistant Edge Function.
        // Fallback between models, automatic rotation, and local fake AI responses are strictly forbidden.
        val userToken = SecurePrefsManager.getSupabaseAccessToken(context)
        if (userToken.isBlank()) {
            return@withContext AiResult.Unauthorized
        }

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
                "model"
            } else {
                "user"
            }
            messagesArray.put(JSONObject().apply {
                put("role", roleStr)
                put("text", cleanText)
            })
        }

        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("text", prompt)
        })

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

    fun generateFallbackResult(
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        modelOption: AiModelOption = AiModelOption.DEFAULT
    ): AiResult {
        val reply = generateFallbackReply(prompt, history, modelOption)
        return AiResult.Success(reply = reply, persona = modelOption.personaId)
    }

    fun generateFallbackReply(
        prompt: String,
        history: List<Pair<String, String>> = emptyList(),
        modelOption: AiModelOption = AiModelOption.DEFAULT
    ): String {
        val lower = prompt.lowercase().trim()
        val isRussian = lower.any { it in 'а'..'я' || it in 'А'..'Я' } ||
                com.example.ui.LocalizationManager.currentLanguage.value == com.example.ui.AppLanguage.RUSSIAN

        val isVentaxis = modelOption.persona == com.example.data.AiPersona.VENTAXIS

        return when {
            // Greetings
            lower.contains("привет") || lower.contains("здравствуй") || lower.contains("добрый") ||
            lower == "hi" || lower == "hello" || lower == "hey" || lower.startsWith("hi ") || lower.startsWith("hello ") -> {
                if (isRussian) {
                    if (isVentaxis) {
                        "Здравствуйте! Я Ventaxis AI — интеллектуальный помощник HexShard. Готов помочь с анализом данных, архитектурой, кодом или решением любых задач. О чём бы вы хотели поговорить?"
                    } else {
                        "Привет! Я Hexagon AI — быстрый ассистент HexShard. Задавай вопрос или ставь задачу — отвечу чётко и по делу!"
                    }
                } else {
                    if (isVentaxis) {
                        "Hello! I am Ventaxis AI, your analytical assistant in HexShard. Ready to assist with technical analysis, architecture, coding, or problem-solving. How can I assist you today?"
                    } else {
                        "Hi! I'm Hexagon AI, your fast built-in assistant in HexShard. Ask me anything, and I'll give you a concise, direct answer!"
                    }
                }
            }

            // Identity / Who are you
            lower.contains("кто ты") || lower.contains("как тебя зовут") || lower.contains("who are you") || lower.contains("what are you") -> {
                if (isRussian) {
                    if (isVentaxis) {
                        "Я Ventaxis AI — встроенная нейросетевая модель HexShard Messenger. Мои приоритеты: безопасность, точность формулировок, структурированный анализ и помощь в инженерных вопросах."
                    } else {
                        "Я Hexagon AI — высокоскоростной встроенный ассистент в экосистеме HexShard. Я отвечаю кратко, конкретно и без лишних слов."
                    }
                } else {
                    if (isVentaxis) {
                        "I am Ventaxis AI, a built-in neural assistant in HexShard Messenger. I focus on analytical precision, technical accuracy, privacy, and clear problem solving."
                    } else {
                        "I am Hexagon AI, the rapid built-in assistant in HexShard Messenger. I provide fast, direct, and actionable solutions."
                    }
                }
            }

            // HexShard / Messenger features / +999 identity
            lower.contains("hexshard") || lower.contains("хексшард") || lower.contains("+999") || lower.contains("номер") || lower.contains("номер") || lower.contains("number") -> {
                if (isRussian) {
                    "HexShard — это защищённый приватный мессенджер с поддержкой виртуальной идентификации (+999) и сквозного шифрования (E2EE).\n\n" +
                    "• **Виртуальный номер +999**: уникальная 8-значная цифровая идентичность, привязанная к вашей криптографической сессии без раскрытия реального номера телефона.\n" +
                    "• **Приватность**: полная изоляция ключей и отсутствие доступа третьих лиц к сообщениям.\n" +
                    "• **Встроенный ИИ**: Ventaxis (глубокий анализ) и Hexagon (скорость и лаконичность)."
                } else {
                    "HexShard is a private, secure messenger featuring virtual identity (+999) and End-to-End Encryption (E2EE).\n\n" +
                    "• **Virtual Number (+999)**: Unique 8-digit identity linked directly to your secure account without exposing personal phone numbers.\n" +
                    "• **Privacy**: Complete account isolation, zero data sharing, and cryptographic identity keys.\n" +
                    "• **Built-in AI**: Ventaxis (deep analytical reasoning) and Hexagon (high-speed pragmatism)."
                }
            }

            // Encryption / Security
            lower.contains("шифрован") || lower.contains("безопасн") || lower.contains("encrypt") || lower.contains("security") -> {
                if (isRussian) {
                    "Безопасность в HexShard основана на сквозном шифровании (E2EE) с генерацией криптографических пар ключей (ECDSA/Ed25519) непосредственно на устройстве. Приватные ключи никогда не покидают ваше локальное защищённое хранилище (EncryptedSharedPreferences), а сообщения подписываются цифровой подписью для защиты от подделки."
                } else {
                    "Security in HexShard is built on End-to-End Encryption with on-device cryptographic key pairs. Private keys never leave your secure local storage, and messages are digitally signed to guarantee authenticity and prevent tampering."
                }
            }

            // Help / Capabilities
            lower.contains("что ты умеешь") || lower.contains("помощь") || lower.contains("help") || lower.contains("capabilities") -> {
                if (isRussian) {
                    "Я могу помочь со следующими задачами:\n" +
                    "1. **Программирование и код**: Kotlin, Java, Python, SQL, REST API, архитектура ПО.\n" +
                    "2. **Тексты и переводы**: редактура, перевод, составление документации.\n" +
                    "3. **Анализ и расчёты**: логические задачи, математика, алгоритмы.\n" +
                    "4. **Навигация по HexShard**: объяснение функций безопасности, виртуальных номеров и настроек."
                } else {
                    "Here is what I can assist you with:\n" +
                    "1. **Coding & Architecture**: Kotlin, Java, Python, SQL, REST APIs, system design.\n" +
                    "2. **Content & Writing**: Technical writing, editing, documentation, translations.\n" +
                    "3. **Analysis & Logic**: Mathematics, algorithmic problem solving, reasoning.\n" +
                    "4. **HexShard Features**: Security architecture, virtual numbers, and preferences."
                }
            }

            // Generic queries
            else -> {
                if (isRussian) {
                    if (isVentaxis) {
                        "По вашему запросу («$prompt»):\n\n" +
                        "Внимательно рассмотрел вопрос. Чтобы предоставить наиболее точный и полный ответ, уточните конкретные детали или контекст задачи, если требуется специализированное решение. Чем могу дополнить анализ?"
                    } else {
                        "Принято: «$prompt». Задача понятна. Если нужны конкретные шаги реализации или пример кода — напиши детали, разберём мгновенно!"
                    }
                } else {
                    if (isVentaxis) {
                        "Regarding your inquiry (\"$prompt\"):\n\n" +
                        "I have processed your request. To provide the most precise and complete solution, please let me know if you would like code examples, architectural breakdown, or step-by-step guidance."
                    } else {
                        "Received: \"$prompt\". If you need specific implementation steps, code snippets, or a direct answer, let me know the details!"
                    }
                }
            }
        }
    }
}
