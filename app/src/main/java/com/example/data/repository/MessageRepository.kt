package com.example.data.repository

import android.content.Context
import com.example.crypto.E2ECryptoManager
import com.example.data.SecurePrefsManager
import com.example.data.database.ChatDao
import com.example.data.database.MessageEntity
import com.example.data.database.OutboxEntity
import com.example.data.database.isAiConversation
import com.example.data.database.isSelfConversation
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.Base64
import java.util.concurrent.TimeUnit

class MessageRepository(
    private val chatDao: ChatDao,
    private val cryptoRepository: CryptoRepository,
    private val conversationRepository: ConversationRepository,
    private val context: Context? = null
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val messageWriteLimiter = Semaphore(10)
    private val backgroundScope = CoroutineScope(Dispatchers.IO)

    fun getCurrentAccountId(): String {
        return context?.let { SecurePrefsManager.getUserId(it) } ?: ""
    }

    fun getMessagesForChat(chatId: Int, accountId: String = getCurrentAccountId()): Flow<List<MessageEntity>> =
        if (accountId.isNotBlank()) {
            chatDao.getMessagesForChatForAccount(chatId, accountId).flowOn(Dispatchers.Default)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }

    suspend fun getMessageById(messageId: Int, accountId: String = getCurrentAccountId()): MessageEntity? = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) null else chatDao.getMessageById(messageId, accountId)
    }

    suspend fun getMessageByServerId(serverId: String, accountId: String = getCurrentAccountId()): MessageEntity? = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) null else chatDao.getMessageByServerId(serverId, accountId)
    }

    suspend fun getMessageByIdempotencyKey(key: String, accountId: String = getCurrentAccountId()): MessageEntity? = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) null else chatDao.getMessageByIdempotencyKey(key, accountId)
    }

    suspend fun insertMessage(message: MessageEntity): Long = withContext(Dispatchers.IO) {
        if (message.accountId.isBlank()) -1L else chatDao.insertMessage(message)
    }

    suspend fun insertMessageIfNotExists(message: MessageEntity): Long = withContext(Dispatchers.IO) {
        if (message.accountId.isBlank()) -1L else chatDao.insertMessageIfNotExists(message)
    }

    suspend fun updateMessageStatus(messageId: Int, status: String, accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        if (accountId.isNotBlank()) chatDao.updateMessageStatus(messageId, status, accountId)
    }

    suspend fun updateMessageStatusByServerId(serverId: String, status: String, accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        if (accountId.isNotBlank()) chatDao.updateMessageStatusByServerId(serverId, status, accountId)
    }

    suspend fun markAllAsRead(chatId: Int, accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext
        val chat = chatDao.getChatById(chatId, accountId) ?: return@withContext
        chatDao.markAllAsRead(chatId, accountId)

        // Mark on server as well
        val ctx = context ?: return@withContext
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx)
        if (token.isNotBlank() && chat.conversationId.isNotBlank()) {
            backgroundScope.launch {
                try {
                    val messages = chatDao.getMessagesForChatForAccountList(chatId, accountId)
                    val maxSeq = messages.maxOfOrNull { it.timestamp } ?: 0L
                    val payload = JSONObject().apply {
                        put("p_conversation_id", chat.conversationId)
                        put("p_sequence", maxSeq)
                    }
                    val req = Request.Builder()
                        .url("$baseUrl/rest/v1/rpc/mark_conversation_read")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    httpClient.newCall(req).execute().close()
                } catch (e: Exception) {
                    Timber.w(e, "markAllAsRead server RPC failed")
                }
            }
        }
    }

    suspend fun editMessage(messageId: Int, newText: String, accountId: String = getCurrentAccountId()): Boolean = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext false
        val msg = chatDao.getMessageById(messageId, accountId) ?: return@withContext false
        val serverId = msg.serverMessageId.ifBlank { msg.idempotencyKey }

        // Local cache optimistic update
        chatDao.editMessage(messageId, newText, System.currentTimeMillis(), accountId)

        val ctx = context ?: return@withContext true
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx)
        if (token.isNotBlank() && serverId.isNotBlank()) {
            try {
                val isSelfOrAi = isSelfConversation(msg.conversationId) || isAiConversation(msg.conversationId)
                val encrypted = cryptoRepository.encryptOutboundMessage(
                    currentUserId = accountId,
                    recipientId = msg.sender,
                    conversationId = msg.conversationId,
                    idempotencyKey = msg.idempotencyKey,
                    plainText = newText,
                    isSelfOrAi = isSelfOrAi
                )
                val payload = JSONObject().apply {
                    put("p_message_id", serverId)
                    put("p_payload", encrypted.base64Payload)
                    put("p_signature", encrypted.base64Signature)
                }
                val req = Request.Builder()
                    .url("$baseUrl/rest/v1/rpc/edit_message")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                val resp = httpClient.newCall(req).execute()
                val ok = resp.isSuccessful
                resp.close()
                return@withContext ok
            } catch (e: Exception) {
                Timber.w(e, "editMessage remote RPC failed")
            }
        }
        true
    }

    suspend fun deleteMessage(messageId: Int, accountId: String = getCurrentAccountId()): Boolean = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext false
        val msg = chatDao.getMessageById(messageId, accountId) ?: return@withContext false
        val serverId = msg.serverMessageId.ifBlank { msg.idempotencyKey }

        // Local cache delete
        chatDao.deleteMessage(messageId, accountId)

        val ctx = context ?: return@withContext true
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx)
        if (token.isNotBlank() && serverId.isNotBlank()) {
            try {
                val payload = JSONObject().apply {
                    put("p_message_id", serverId)
                }
                val req = Request.Builder()
                    .url("$baseUrl/rest/v1/rpc/delete_message")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                val resp = httpClient.newCall(req).execute()
                val ok = resp.isSuccessful
                resp.close()
                return@withContext ok
            } catch (e: Exception) {
                Timber.w(e, "deleteMessage remote RPC failed")
            }
        }
        true
    }

    /**
     * Persists AI conversation history atomically to Supabase.
     */
    suspend fun sendAiTranscript(
        conversationId: String,
        userClientId: String,
        userText: String,
        replyClientId: String,
        replyText: String,
        personaId: String,
        timeStr: String
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
        if (token.isNullOrBlank() || anonKey.isBlank()) return@withContext false

        try {
            val payload = JSONObject().apply {
                put("p_conversation_id", conversationId)
                put("p_user_client_id", userClientId)
                put("p_user_text", userText)
                put("p_reply_client_id", replyClientId)
                put("p_reply_text", replyText)
                put("p_persona_id", personaId)
                put("p_time_str", timeStr)
            }
            val req = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/send_ai_message")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            val resp = httpClient.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            Timber.e(e, "sendAiTranscript RPC network failure")
            false
        }
    }

    suspend fun sendMessage(
        chatId: Int,
        sender: String,
        text: String,
        isMe: Boolean,
        isAttachment: Boolean,
        timeStr: String,
        status: String = "sending",
        recipientId: String = "",
        type: String = "text",
        audioUrl: String? = null,
        duration: Int? = null,
        idempotencyKey: String = "",
        personaId: String? = null
    ): Long = withContext(Dispatchers.IO) {
        messageWriteLimiter.acquire()
        try {
            val currentUserId = getCurrentAccountId()
            val isSelfChat = isSelfConversation(recipientId = recipientId, id = chatId)
            val isAiChat = isAiConversation(recipientId = recipientId, conversationId = personaId)
            val conversationId = conversationRepository.computeConversationId(currentUserId, recipientId)

            val resolvedPersona = when {
                personaId != null -> com.example.data.AiPersona.fromIdOrNull(personaId)?.id
                isAiChat -> "hexagon"
                else -> null
            }

            val payloadToEncrypt = if (isAttachment && audioUrl != null) {
                JSONObject().apply {
                    put("text", text)
                    put("audioUrl", audioUrl)
                    put("type", type)
                    if (duration != null) put("duration", duration)
                }.toString()
            } else {
                text
            }

            // Cryptographic envelope
            val encryptedPayload = cryptoRepository.encryptOutboundMessage(
                currentUserId = currentUserId,
                recipientId = if (isSelfChat) currentUserId else recipientId,
                conversationId = conversationId,
                idempotencyKey = idempotencyKey,
                plainText = payloadToEncrypt,
                isSelfOrAi = isSelfChat || isAiChat
            )

            val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
            val isRemoteAuthed = currentUserId.isNotBlank() && token != null
            val initialStatus = if (isRemoteAuthed) "sending" else "pending"

            val message = MessageEntity(
                accountId = currentUserId,
                chatId = chatId,
                sender = if (isMe) currentUserId else sender,
                text = text,
                time = timeStr,
                timestamp = System.currentTimeMillis(),
                isMe = isMe,
                isAttachment = isAttachment,
                type = type,
                audioUrl = audioUrl,
                duration = duration,
                status = initialStatus,
                iv = encryptedPayload.iv,
                encryptionVersion = 2,
                idempotencyKey = idempotencyKey,
                conversationId = conversationId,
                serverMessageId = idempotencyKey,
                signatureValid = true,
                personaId = resolvedPersona
            )
            val messageId = chatDao.insertMessage(message)
            conversationRepository.updateChatPreview(chatId, text, timeStr, message.timestamp)

            // ALL user-initiated messages are enqueued into durable outbox (direct, saved, and AI user prompts)
            if (isMe && idempotencyKey.isNotEmpty()) {
                val outboxEntry = OutboxEntity(
                    accountId = currentUserId,
                    localMessageId = messageId.toInt(),
                    chatId = chatId,
                    conversationId = conversationId,
                    idempotencyKey = idempotencyKey,
                    senderId = currentUserId,
                    recipientId = if (isSelfChat) currentUserId else recipientId,
                    payload = encryptedPayload.base64Payload,
                    signature = encryptedPayload.base64Signature,
                    type = type,
                    timeStr = timeStr,
                    attempts = 0,
                    status = "sending",
                    createdAt = System.currentTimeMillis()
                )
                val outboxRowId = chatDao.insertOutbox(outboxEntry)

                if (isRemoteAuthed && !isAiChat) {
                    backgroundScope.launch {
                        try {
                            val sentOk = sendRemoteMessage(
                                idempotencyKey = idempotencyKey,
                                conversationId = conversationId,
                                senderId = currentUserId,
                                recipientId = if (isSelfChat) currentUserId else recipientId,
                                payloadBase64 = encryptedPayload.base64Payload,
                                signatureBase64 = encryptedPayload.base64Signature,
                                type = type,
                                timeStr = timeStr,
                                personaId = resolvedPersona
                            )
                            if (sentOk) {
                                chatDao.deleteOutboxByIdempotencyKey(idempotencyKey, currentUserId)
                                updateMessageStatus(messageId.toInt(), "sent", currentUserId)
                            } else {
                                chatDao.updateOutboxAttempt(outboxRowId, "pending", System.currentTimeMillis(), "Server send failed", currentUserId)
                                updateMessageStatus(messageId.toInt(), "pending", currentUserId)
                            }
                        } catch (netEx: Exception) {
                            Timber.e(netEx, "Supabase message send network failure, will retry via outbox")
                            chatDao.updateOutboxAttempt(outboxRowId, "pending", System.currentTimeMillis(), netEx.message ?: "Network error", currentUserId)
                            updateMessageStatus(messageId.toInt(), "pending", currentUserId)
                        }
                    }
                }
            }

            messageId
        } catch (e: Exception) {
            Timber.e(e, "MessageRepository.sendMessage failed")
            -1L
        } finally {
            messageWriteLimiter.release()
        }
    }

    suspend fun retryMessage(msgId: Int, chatId: Int, text: String) = withContext(Dispatchers.IO) {
        val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: return@withContext
        val msg = chatDao.getMessageById(msgId, currentUserId) ?: return@withContext
        
        updateMessageStatus(msgId, "sending", currentUserId)
        val outboxList = chatDao.getPendingOutboxEntries(currentUserId)
        val existingOutbox = outboxList.find { it.idempotencyKey == msg.idempotencyKey || it.localMessageId == msgId }

        if (existingOutbox != null) {
            chatDao.retryOutboxEntry(existingOutbox.id, currentUserId)
            val sentOk = sendRemoteMessage(
                idempotencyKey = existingOutbox.idempotencyKey,
                conversationId = existingOutbox.conversationId,
                senderId = existingOutbox.senderId,
                recipientId = existingOutbox.recipientId,
                payloadBase64 = existingOutbox.payload,
                signatureBase64 = existingOutbox.signature,
                type = existingOutbox.type,
                timeStr = existingOutbox.timeStr
            )
            if (sentOk) {
                chatDao.deleteOutboxByIdempotencyKey(existingOutbox.idempotencyKey, currentUserId)
                updateMessageStatus(msgId, "sent", currentUserId)
            } else {
                updateMessageStatus(msgId, "error", currentUserId)
            }
        }
    }

    /**
     * Sends message to Supabase via canonical send_message_idempotent RPC.
     * STRICT: No fake delivery. If server call fails, returns false fail-closed.
     */
    suspend fun sendRemoteMessage(
        idempotencyKey: String,
        conversationId: String,
        senderId: String,
        recipientId: String,
        payloadBase64: String,
        signatureBase64: String,
        type: String,
        timeStr: String,
        personaId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val accessToken = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
        if (accessToken.isNullOrBlank() || anonKey.isBlank()) {
            Timber.w("Cannot send remote message: user is not authenticated or Supabase not configured")
            return@withContext false
        }

        try {
            val payload = JSONObject().apply {
                put("p_conversation_id", conversationId)
                put("p_client_message_id", idempotencyKey)
                put("p_payload", payloadBase64)
                put("p_signature", signatureBase64)
                if (isSelfConversation(recipientId)) {
                    put("p_recipient_id", senderId)
                } else if (recipientId.isNotBlank() && !isAiConversation(recipientId)) {
                    put("p_recipient_id", recipientId)
                } else {
                    put("p_recipient_id", JSONObject.NULL)
                }
                put("p_type", type)
                put("p_time_str", timeStr)
                if (personaId != null) put("p_persona_id", personaId)
                put("p_encryption_version", 2)
            }

            val rpcReq = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/send_message_idempotent")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            var rpcResp = httpClient.newCall(rpcReq).execute()
            var isSuccess = rpcResp.isSuccessful
            val initialCode = rpcResp.code
            var errorBody = if (!isSuccess) rpcResp.body?.string() ?: "" else ""
            rpcResp.close()

            // If unauthorized 401, attempt single-flight token refresh and retry
            if (!isSuccess && initialCode == 401 && context != null) {
                val refresh = SecurePrefsManager.getSupabaseRefreshToken(context)
                if (refresh.isNotBlank()) {
                    Timber.i("send_message_idempotent received 401; attempting token refresh...")
                    val refreshed = com.example.network.supabase.SessionManager.refreshSession(context, refresh)
                    if (refreshed == com.example.network.supabase.AuthState.AUTHENTICATED) {
                        val newAccess = SecurePrefsManager.getSupabaseAccessToken(context)
                        if (newAccess.isNotBlank()) {
                            val retryReq = rpcReq.newBuilder()
                                .header("Authorization", "Bearer $newAccess")
                                .build()
                            val retryResp = httpClient.newCall(retryReq).execute()
                            isSuccess = retryResp.isSuccessful
                            if (!isSuccess) {
                                errorBody = retryResp.body?.string() ?: ""
                            }
                            retryResp.close()
                        }
                    }
                }
            }

            if (!isSuccess) {
                Timber.e("send_message_idempotent failed (HTTP $initialCode): $errorBody")
                return@withContext false
            }

            true
        } catch (e: Exception) {
            Timber.e(e, "send_message_idempotent RPC network failure")
            false
        }
    }
}
