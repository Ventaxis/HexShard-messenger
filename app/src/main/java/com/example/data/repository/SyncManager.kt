package com.example.data.repository

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.data.database.ChatDao
import com.example.data.database.ChatEntity
import com.example.data.database.MessageEntity
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

class SyncManager(
    private val chatDao: ChatDao,
    private val cryptoRepository: CryptoRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val context: Context? = null
) {
    private val syncJob = SupervisorJob()
    private val syncScope = CoroutineScope(Dispatchers.IO + syncJob)
    private var syncJobInstance: Job? = null
    private val syncMutex = Mutex()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private var realtimeManager: com.example.network.supabase.SupabaseRealtimeManager? = null

    fun startRealtimeSync() {
        val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: ""
        if (currentUserId.isBlank()) return

        // 1. Recover stale sending outbox entries on startup
        syncScope.launch {
            try {
                val recovered = chatDao.recoverStaleSendingOutbox(currentUserId)
                if (recovered > 0) {
                    Timber.i("Recovered $recovered stale outbox entries to pending on startup")
                }
            } catch (e: Exception) {
                Timber.w("Failed to recover stale outbox: ${e.message}")
            }
            // 2. Perform initial catch-up sync once upon startup
            performCatchUpSync(currentUserId)
        }

        // 3. Connect Supabase Realtime WebSocket for instant event-driven delivery
        if (context != null) {
            realtimeManager?.disconnect()
            realtimeManager = com.example.network.supabase.SupabaseRealtimeManager(
                context = context,
                onMessageRecordReceived = { record ->
                    syncScope.launch {
                        try {
                            processSingleMessageRecord(record, currentUserId)
                        } catch (e: Throwable) {
                            Timber.w("Error processing realtime message: ${e.message}")
                        }
                    }
                },
                onConnectionStateChanged = { connected ->
                    if (connected) {
                        Timber.i("Supabase Realtime connected, triggering catch-up sync")
                        syncScope.launch { performCatchUpSync(currentUserId) }
                    }
                }
            ).also { it.connect() }
        }

        // 4. Background maintenance job for durable outbox retry with exponential backoff (no tight polling)
        syncJobInstance?.cancel()
        syncJobInstance = syncScope.launch {
            while (isActive) {
                delay(60_000) // 1-minute maintenance interval strictly for offline outbox retry
                try {
                    flushOutbox(currentUserId)
                } catch (e: Throwable) {
                    Timber.d("Outbox retry check error: ${e.message}")
                }
            }
        }
    }

    /**
     * Triggered on app startup, WebSocket reconnect, push notification wakeup, or network restore.
     */
    fun triggerCatchUpSync() {
        val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: ""
        if (currentUserId.isBlank()) return
        syncScope.launch {
            performCatchUpSync(currentUserId)
        }
    }

    private suspend fun performCatchUpSync(currentUserId: String) {
        try {
            syncIncomingMessages(currentUserId)
            syncSentStatus(currentUserId)
            flushOutbox(currentUserId)
        } catch (e: Throwable) {
            Timber.d("Catch-up sync error: ${e.message}")
        }
    }

    fun triggerOutboxFlush() {
        val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: ""
        if (currentUserId.isBlank()) return
        syncScope.launch {
            try {
                flushOutbox(currentUserId)
            } catch (e: Exception) {
                Timber.w("Manual outbox flush error: ${e.message}")
            }
        }
    }

    fun stopRealtimeSync() {
        realtimeManager?.disconnect()
        realtimeManager = null
        syncJobInstance?.cancel()
        syncJobInstance = null
        syncJob.cancelChildren()
    }

    private suspend fun syncIncomingMessages(currentUserId: String) = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
            ?: return@withContext
        if (anonKey.isBlank()) return@withContext

        val url = "$baseUrl/rest/v1/messages?recipient_id=eq.$currentUserId&status=neq.read&order=sequence.asc,created_at.asc&limit=50"
        val req = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val resp = try {
            httpClient.newCall(req).execute()
        } catch (e: Exception) {
            null
        } ?: return@withContext

        val body = resp.body?.string() ?: "[]"
        if (!resp.isSuccessful) return@withContext

        val arr = JSONArray(body)
        if (arr.length() == 0) return@withContext

        syncMutex.withLock {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                processSingleMessageRecord(obj, currentUserId)
            }
        }
    }

    private suspend fun processSingleMessageRecord(obj: JSONObject, currentUserId: String) = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() } ?: ""

        val docId = obj.optString("id")
        val sender = obj.optString("sender_id")
        if (sender.isBlank() || sender == currentUserId) return@withContext

        val payloadString = obj.optString("payload")
        if (payloadString.isBlank()) return@withContext

        val isDeleted = obj.optBoolean("is_deleted", false)
        if (isDeleted) {
            chatDao.deleteMessageByServerId(docId, currentUserId)
            return@withContext
        }

        val type = obj.optString("type", "text")
        val timeStr = obj.optString("time_str", "")
        val idempotencyKey = obj.optString("client_message_id", obj.optString("idempotency_key", docId)).ifBlank { docId }
        val conversationId = obj.optString("conversation_id").ifBlank {
            conversationRepository.computeConversationId(currentUserId, sender)
        }

        if (chatDao.getMessageByIdempotencyKey(idempotencyKey, currentUserId) != null) return@withContext
        if (chatDao.getMessageByServerId(docId, currentUserId) != null) return@withContext

        val signatureStr = obj.optString("signature").takeIf { it.isNotBlank() }

        // Decrypt and verify message using CryptoRepository
        val decryptedResult = cryptoRepository.decryptInboundMessage(
            senderId = sender,
            recipientId = currentUserId,
            conversationId = conversationId,
            idempotencyKey = idempotencyKey,
            base64Payload = payloadString,
            base64Signature = signatureStr
        )

        if (decryptedResult.plainText.startsWith("[E2EE Error") || decryptedResult.plainText.startsWith("[Decryption Error")) {
            Timber.w("Unable to decrypt message $docId from $sender")
            return@withContext
        }

        var chat = chatDao.getChatByConversationId(conversationId, currentUserId) ?: chatDao.getChatByRecipientId(sender, currentUserId)
        if (chat == null) {
            val newChat = ChatEntity(
                accountId = currentUserId,
                name = sender,
                ava = sender.take(2).uppercase(),
                status = "online",
                preview = decryptedResult.plainText,
                time = timeStr,
                recipientId = sender,
                conversationId = conversationId,
                conversationType = com.example.data.ConversationType.DIRECT.name
            )
            val newChatId = chatDao.insertChat(newChat).toInt()
            chat = chatDao.getChatById(newChatId, currentUserId)
        }
        val resolvedChatId = chat?.id ?: 1

        val msg = MessageEntity(
            accountId = currentUserId,
            chatId = resolvedChatId,
            sender = sender,
            text = decryptedResult.plainText,
            time = timeStr,
            timestamp = System.currentTimeMillis(),
            isMe = false,
            isAttachment = type != "text" && type != "voice",
            type = type,
            status = "delivered",
            idempotencyKey = idempotencyKey,
            conversationId = conversationId,
            serverMessageId = docId,
            signatureValid = decryptedResult.isSignatureValid,
            encryptionVersion = 2
        )
        val rowId = chatDao.insertMessageIfNotExists(msg)
        if (rowId > 0) {
            chatDao.updateChatPreview(resolvedChatId, decryptedResult.plainText, timeStr, System.currentTimeMillis(), currentUserId)
            if (token.isNotBlank()) {
                markDeliveredOnRemote(docId, baseUrl, anonKey, token)
            }
        }
    }

    private suspend fun markDeliveredOnRemote(
        docId: String,
        baseUrl: String,
        anonKey: String,
        token: String
    ) = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("status", "delivered")
            }.toString().toRequestBody(JSON_MEDIA)

            val ackReq = Request.Builder()
                .url("$baseUrl/rest/v1/messages?id=eq.$docId")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .patch(body)
                .build()

            httpClient.newCall(ackReq).execute().close()
        } catch (e: Exception) {
            Timber.d("Failed to send delivery receipt: ${e.message}")
        }
    }

    private suspend fun syncSentStatus(currentUserId: String) = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
            ?: return@withContext

        val url = "$baseUrl/rest/v1/messages?sender_id=eq.$currentUserId&select=id,status&order=id.desc&limit=30"
        val req = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: "[]"
            if (!resp.isSuccessful) return@withContext

            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val docId = obj.optString("id")
                val status = obj.optString("status")
                if (docId.isNotBlank() && status.isNotBlank()) {
                    chatDao.updateMessageStatusByServerId(docId, status, currentUserId)
                }
            }
        } catch (e: Exception) {
            Timber.d("Status sync failed: ${e.message}")
        }
    }

    private suspend fun flushOutbox(currentUserId: String) = withContext(Dispatchers.IO) {
        val pendingEntries = try {
            chatDao.getPendingOutboxEntries(currentUserId)
        } catch (e: Exception) {
            emptyList()
        }
        if (pendingEntries.isEmpty()) return@withContext

        val now = System.currentTimeMillis()
        for (entry in pendingEntries) {
            if (entry.status == "failed") continue

            if (entry.attempts >= 5) {
                chatDao.updateOutboxAttempt(entry.id, "failed", now, "Max retry attempts reached", currentUserId)
                chatDao.updateMessageStatusByIdempotencyKey(entry.idempotencyKey, "error", currentUserId)
                continue
            }

            // Exponential backoff: 2s, 4s, 8s, 16s, 32s
            val backoffMs = (1000L * (1 shl (entry.attempts.coerceAtMost(5))))
            if (entry.lastAttemptAt > 0 && now - entry.lastAttemptAt < backoffMs) {
                continue
            }

            chatDao.updateOutboxAttempt(entry.id, "sending", now, "", currentUserId)
            val success = try {
                messageRepository.sendRemoteMessage(
                    idempotencyKey = entry.idempotencyKey,
                    conversationId = entry.conversationId,
                    senderId = entry.senderId,
                    recipientId = entry.recipientId,
                    payloadBase64 = entry.payload,
                    signatureBase64 = entry.signature,
                    type = entry.type,
                    timeStr = entry.timeStr
                )
            } catch (e: Exception) {
                Timber.w(e, "Outbox delivery failed for key ${entry.idempotencyKey}")
                false
            }

            if (success) {
                chatDao.deleteOutboxByIdempotencyKey(entry.idempotencyKey, currentUserId)
                chatDao.updateMessageStatusByIdempotencyKey(entry.idempotencyKey, "sent", currentUserId)
            } else {
                val newAttempts = entry.attempts + 1
                val newStatus = if (newAttempts >= 5) "failed" else "pending"
                chatDao.updateOutboxAttempt(entry.id, newStatus, System.currentTimeMillis(), "Delivery attempt $newAttempts failed", currentUserId)
                if (newStatus == "failed") {
                    chatDao.updateMessageStatusByIdempotencyKey(entry.idempotencyKey, "error", currentUserId)
                }
            }
        }
    }
}
