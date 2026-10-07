package com.example.data.repository

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.data.database.ChatDao
import com.example.data.database.ChatEntity
import com.example.data.database.MessageEntity
import com.example.network.supabase.RealtimeConnectionState
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
    private val realtimeQueue = kotlinx.coroutines.channels.Channel<JSONObject>(kotlinx.coroutines.channels.Channel.UNLIMITED)

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

        // Sequential message worker processing realtime messages in strict arrival order
        syncScope.launch {
            for (record in realtimeQueue) {
                try {
                    processSingleMessageRecord(record, currentUserId)
                } catch (e: Throwable) {
                    Timber.w("Error processing queued realtime message: ${e.message}")
                }
            }
        }

        // 3. Connect Supabase Realtime WebSocket with explicit state machine
        if (context != null) {
            realtimeManager?.disconnect()
            realtimeManager = com.example.network.supabase.SupabaseRealtimeManager(
                context = context,
                onMessageRecordReceived = { record ->
                    realtimeQueue.trySend(record)
                },
                onConnectionStateChanged = { state ->
                    Timber.d("Realtime state update in SyncManager: $state")
                },
                onChannelJoined = {
                    Timber.i("Supabase Realtime channel joined, triggering cursor-based catch-up sync")
                    syncScope.launch { performCatchUpSync(currentUserId) }
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
            // 1. Authoritative cloud-first sync of conversations from Supabase
            conversationRepository.syncConversationsFromServer(currentUserId)
            // 2. Fetch messages by cursor
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
        val ctx = context ?: return@withContext
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() }
            ?: return@withContext
        if (anonKey.isBlank()) return@withContext

        var currentCursor = SecurePrefsManager.getSyncCursor(ctx, currentUserId)
        val filter = "or=(recipient_id.eq.$currentUserId,sender_id.eq.$currentUserId)"

        // Pagination loop to fetch all messages until fewer than 50 are returned
        while (true) {
            val url = if (currentCursor > 0L) {
                "$baseUrl/rest/v1/messages?$filter&sequence=gt.$currentCursor&order=sequence.asc&limit=50"
            } else {
                "$baseUrl/rest/v1/messages?$filter&order=sequence.asc,created_at.asc&limit=50"
            }

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
            } ?: break

            val body = resp.body?.string() ?: "[]"
            if (!resp.isSuccessful) break

            val arr = JSONArray(body)
            if (arr.length() == 0) break

            var batchHighestSeq = currentCursor
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val seq = obj.optLong("sequence", 0L)
                processSingleMessageRecord(obj, currentUserId)
                if (seq > batchHighestSeq) {
                    batchHighestSeq = seq
                }
            }

            if (batchHighestSeq > currentCursor) {
                currentCursor = batchHighestSeq
                SecurePrefsManager.setSyncCursor(ctx, currentUserId, currentCursor)
            }

            if (arr.length() < 50) break
        }
    }

    private suspend fun processSingleMessageRecord(obj: JSONObject, currentUserId: String) = withContext(Dispatchers.IO) {
        val ctx = context
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = ctx?.let { SupabaseConfig.getAnonKey(it) } ?: ""
        val token = ctx?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() } ?: ""

        val docId = obj.optString("id")
        val sender = obj.optString("sender_id")
        val recipient = obj.optString("recipient_id")
        val sequence = obj.optLong("sequence", 0L)
        if (sender.isBlank()) return@withContext

        val isSelfMessage = (sender == currentUserId && (recipient == currentUserId || recipient == "self" || recipient.isBlank()))
        val isOutboundFromAnotherDevice = (sender == currentUserId && recipient != currentUserId && recipient.isNotBlank())
        val isMe = sender == currentUserId

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

        val peerId = when {
            isSelfMessage -> "self"
            isOutboundFromAnotherDevice -> recipient
            else -> sender
        }

        val conversationId = obj.optString("conversation_id").ifBlank {
            if (isSelfMessage) "self_$currentUserId" else conversationRepository.computeConversationId(currentUserId, peerId)
        }

        // Fast-path duplicate check
        if (chatDao.getMessageByIdempotencyKey(idempotencyKey, currentUserId) != null) return@withContext
        if (chatDao.getMessageByServerId(docId, currentUserId) != null) return@withContext

        val signatureStr = obj.optString("signature").takeIf { it.isNotBlank() }

        // Decrypt and verify message using CryptoRepository outside the database mutex
        val decryptedResult = cryptoRepository.decryptInboundMessage(
            senderId = if (isSelfMessage) currentUserId else sender,
            recipientId = currentUserId,
            conversationId = conversationId,
            idempotencyKey = idempotencyKey,
            base64Payload = payloadString,
            base64Signature = signatureStr
        )

        val isDecryptionError = decryptedResult.plainText.startsWith("[E2EE Error") || 
                               decryptedResult.plainText.startsWith("[Decryption Error")

        var resolvedText = if (isDecryptionError) "[Encrypted message - decryption failed]" else decryptedResult.plainText
        var resolvedAudioUrl: String? = null
        var resolvedDuration: Int? = null
        var resolvedType = type

        if (!isDecryptionError && decryptedResult.plainText.startsWith("{") && decryptedResult.plainText.endsWith("}")) {
            try {
                val json = JSONObject(decryptedResult.plainText)
                if (json.has("audioUrl") || json.has("type")) {
                    resolvedText = json.optString("text", resolvedText)
                    resolvedAudioUrl = json.optString("audioUrl").takeIf { it.isNotBlank() }
                    if (json.has("duration")) resolvedDuration = json.optInt("duration")
                    resolvedType = json.optString("type", type)
                }
            } catch (_: Exception) {}
        }

        val messageStatus = when {
            isDecryptionError || !decryptedResult.isSignatureValid -> "integrity_error"
            isMe -> "sent"
            else -> "delivered"
        }

        // Synchronize local database insert under syncMutex
        syncMutex.withLock {
            if (chatDao.getMessageByIdempotencyKey(idempotencyKey, currentUserId) != null) return@withContext
            if (chatDao.getMessageByServerId(docId, currentUserId) != null) return@withContext

            var chat = if (isSelfMessage) {
                chatDao.getSavedMessagesChat(currentUserId)
            } else {
                chatDao.getChatByConversationId(conversationId, currentUserId) ?: chatDao.getChatByRecipientId(peerId, currentUserId)
            }

            if (chat == null) {
                val newChat = if (isSelfMessage) {
                    ChatEntity(
                        accountId = currentUserId,
                        name = "Saved Messages",
                        ava = "🔖",
                        status = "",
                        preview = if (isDecryptionError) "⚠️ Corrupted message" else resolvedText,
                        time = timeStr,
                        recipientId = "self",
                        conversationId = "self_$currentUserId",
                        conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name
                    )
                } else {
                    ChatEntity(
                        accountId = currentUserId,
                        name = peerId,
                        ava = peerId.take(2).uppercase(),
                        status = "offline",
                        preview = if (isDecryptionError) "⚠️ Corrupted message" else resolvedText,
                        time = timeStr,
                        recipientId = peerId,
                        conversationId = conversationId,
                        conversationType = com.example.data.ConversationType.DIRECT.name
                    )
                }
                val newChatId = chatDao.insertChat(newChat).toInt()
                chat = chatDao.getChatById(newChatId, currentUserId)
            }
            val resolvedChatId = chat?.id ?: 1

            val msg = MessageEntity(
                accountId = currentUserId,
                chatId = resolvedChatId,
                sender = sender,
                text = resolvedText,
                time = timeStr,
                timestamp = System.currentTimeMillis(),
                isMe = isMe,
                isAttachment = resolvedType != "text",
                type = resolvedType,
                audioUrl = resolvedAudioUrl,
                duration = resolvedDuration,
                status = messageStatus,
                idempotencyKey = idempotencyKey,
                conversationId = conversationId,
                serverMessageId = docId,
                signatureValid = !isDecryptionError && decryptedResult.isSignatureValid,
                encryptionVersion = 2
            )
            val rowId = chatDao.insertMessageIfNotExists(msg)
            if (rowId > 0) {
                val previewText = when (resolvedType) {
                    "voice" -> "🎤 Voice message"
                    "image" -> "📷 Photo"
                    "location" -> "📍 Location"
                    else -> if (isDecryptionError) "⚠️ Corrupted message" else resolvedText
                }
                chatDao.updateChatPreview(resolvedChatId, previewText, timeStr, System.currentTimeMillis(), currentUserId)
                if (token.isNotBlank() && !isMe && !isDecryptionError && decryptedResult.isSignatureValid) {
                    markDeliveredOnRemote(docId, baseUrl, anonKey, token)
                }
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
