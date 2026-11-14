package com.example.network

import com.example.data.SecurePrefsManager
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Developer-mode bridge: the phrase lives only in memory on this device and is
 * verified server-side on every call (never stored in the APK or prefs).
 */
object DevModeClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun action(
        context: android.content.Context,
        action: String,
        phrase: String,
        params: JSONObject = JSONObject()
    ): JSONObject? = withContext(Dispatchers.IO) {
        val token = SecurePrefsManager.getSupabaseAccessToken(context)
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        if (token.isBlank() || baseUrl.isBlank()) return@withContext null
        val body = JSONObject().put("action", action).put("phrase", phrase).put("params", params)
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/dev-mode")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext JSONObject().put("__status", resp.code).put("__error", text)
                }
                if (text.isBlank()) null else JSONObject(text)
            }
        } catch (e: Exception) {
            JSONObject().put("__status", -1).put("__error", e.message ?: "network error")
        }
    }
}
