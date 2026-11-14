package com.example.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SupabaseConfig
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * FCM glue: data-only pings (no message content ever reaches Firebase).
 * Token is uploaded to the account's devices row; a missed upload is retried
 * on the next token refresh / app start.
 */
object PushManager {
    private val client = OkHttpClient()

    const val CHANNEL_MESSAGE = "hexshard_messages"
    const val CHANNEL_CALL = "hexshard_calls"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGE, "Сообщения", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Звонки", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    suspend fun uploadToken(context: Context, token: String) {
        val uid = SecurePrefsManager.getUserId(context)
        val accessToken = SecurePrefsManager.getSupabaseAccessToken(context)
        if (uid.isBlank() || accessToken.isBlank()) {
            SecurePrefsManager.getPrefs(context).edit().putString("fcm_token_pending", token).apply()
            return
        }
        SecurePrefsManager.getPrefs(context).edit().remove("fcm_token_pending").apply()
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val body = JSONObject().put("fcm_token", token).toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/devices?on_conflict=account_id")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Prefer", "resolution=merge-duplicates")
            .patch(body)
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) Timber.w("Push token upload: HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Push token upload failed")
        }
    }

    /** Fire-and-forget content-free ping to the recipient via send-push. */
    fun sendPing(context: Context, recipientUid: String, kind: String, fromName: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val token = SecurePrefsManager.getSupabaseAccessToken(context)
            val anonKey = SupabaseConfig.getAnonKey(context)
            val baseUrl = SupabaseConfig.getBaseUrl()
            if (token.isBlank() || baseUrl.isBlank()) return@launch
            val payload = JSONObject()
                .put("recipient", recipientUid)
                .put("kind", kind)
                .put("from", fromName)
            val request = Request.Builder()
                .url("$baseUrl/functions/v1/send-push")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) Timber.w("Push ping ($kind): HTTP ${resp.code}")
                }
            } catch (e: Exception) {
                Timber.w(e, "Push ping failed")
            }
        }
    }

    fun showLocalNotification(context: Context, channelId: String, title: String, text: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}

class HexShardMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { PushManager.uploadToken(applicationContext, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val kind = data["kind"] ?: "message"
        val fromName = data["fromName"].orEmpty().ifBlank { "HexShard" }
        val context = applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.areNotificationsEnabled() &&
                androidx.core.app.NotificationManagerCompat.from(context)
                    .areNotificationsEnabled()
            ) {
                PushManager.ensureChannels(context)
            }
        }
        if (kind == "call") {
            PushManager.showLocalNotification(
                context, PushManager.CHANNEL_CALL,
                if (com.example.ui.LocalizationManager.currentLanguage.value == com.example.ui.AppLanguage.RUSSIAN)
                    "Входящий звонок" else "Incoming call",
                fromName
            )
        } else {
            PushManager.showLocalNotification(
                context, PushManager.CHANNEL_MESSAGE,
                fromName,
                if (com.example.ui.LocalizationManager.currentLanguage.value == com.example.ui.AppLanguage.RUSSIAN)
                    "Новое сообщение" else "New message"
            )
        }
    }
}
