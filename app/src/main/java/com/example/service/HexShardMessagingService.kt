package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.network.supabase.SupabaseConfig
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

class HexShardMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        
        // Store FCM registration token in EncryptedSharedPreferences
        try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(this)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            val encryptedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                this,
                "hexshard_fcm_secure",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            encryptedPrefs.edit().putString("fcm_token", token).apply()
        } catch (e: Exception) {
            Timber.w(e, "Failed to cache FCM token in EncryptedSharedPreferences")
        }
        
        // Save FCM registration token to Supabase devices table for background wakeup
        val userId = com.example.data.SecurePrefsManager.getUserId(applicationContext)
        val accessToken = com.example.data.SecurePrefsManager.getSupabaseAccessToken(applicationContext)
        if (userId.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val baseUrl = SupabaseConfig.getBaseUrl()
                    val anonKey = SupabaseConfig.getAnonKey()
                    val jsonMedia = "application/json; charset=utf-8".toMediaType()
                    val deviceId = com.example.data.SecurePrefsManager.getDeviceId(applicationContext)
                    val payload = JSONObject().apply {
                        put("account_id", userId)
                        put("device_id", deviceId)
                        put("fcm_token", token)
                        put("last_seen_at", java.time.Instant.now().toString())
                    }
                    val req = Request.Builder()
                        .url("$baseUrl/rest/v1/devices")
                        .header("apikey", anonKey)
                        .header("Authorization", if (accessToken.isNotBlank()) "Bearer $accessToken" else "Bearer $anonKey")
                        .header("Prefer", "resolution=merge-duplicates")
                        .post(payload.toString().toRequestBody(jsonMedia))
                        .build()
                    OkHttpClient().newCall(req).execute().close()
                } catch (e: Exception) {
                    Timber.d("FCM token sync to Supabase skipped/deferred: ${e.message}")
                }
            }
        }
    }

    companion object {
        fun clearFcmTokenOnLogout(context: Context) {
            try {
                val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encryptedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                    context,
                    "hexshard_fcm_secure",
                    masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                encryptedPrefs.edit().clear().apply()
            } catch (e: Exception) {
                Timber.w(e, "Failed to clean up FCM token on logout")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Privacy-first: We prioritize data-only push without plaintext message content
        val data = remoteMessage.data
        val senderId = data["senderId"] ?: "A contact"
        val messageType = data["type"] ?: "message"
        val conversationId = data["conversationId"] ?: ""

        val displayTitle = "HexShard"
        val displayBody = when (messageType) {
            "voice" -> "New encrypted voice message"
            "image" -> "New encrypted photo"
            "video" -> "New encrypted video"
            else -> "New encrypted message received"
        }

        showSecureNotification(displayTitle, displayBody, conversationId)
    }

    private fun showSecureNotification(title: String, messageBody: String, conversationId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("conversationId", conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = "hexshard_encrypted_messages"
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // Hide contents on secure lockscreens
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Encrypted Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Secure end-to-end encrypted message notifications"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // On Android 13+ (API 33), check POST_NOTIFICATIONS before posting
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Timber.w("POST_NOTIFICATIONS permission not granted; skipping notification display")
                return
            }
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
