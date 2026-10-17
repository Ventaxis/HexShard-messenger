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

sealed class RemoteMessageSendResult {
    data class Success(
        val serverMessageId: String,
        val sequence: Long,
        val status: String,
        val isDuplicate: Boolean = false
    ) : RemoteMessageSendResult() {
        val isSuccess: Boolean = true
    }

    data class Failure(
        val errorCode: String,
        val errorMessage: String,
        val isRetryable: Boolean = true
    ) : RemoteMessageSendResult()
}

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
            val encryptedPayload = try {
                cryptoRepository.encryptOutboundMessage(
                    currentUserId = currentUserId,
                    recipientId = if (isSelfChat) currentUserId else recipientId,
                    conversationId = conversationId,
                    idempotencyKey = idempotencyKey,
                    plainText = payloadToEncrypt,
                    isSelfOrAi = isSelfChat || isAiChat
                )
            } catch (peerKeyEx: PeerKeyUnavailableException) {
                // Requirement 6: Durable Outbox entry with status WAITING_FOR_PEER_KEY
                // Plaintext is kept local in SQLCipher vault; no encryption or sending occurs until peer key is verified
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
                    status = "waiting_key",
                    iv = "",
                    encryptionVersion = 2,
                    idempotencyKey = idempotencyKey,
                    conversationId = conversationId,
                    serverMessageId = idempotencyKey,
                    signatureValid = true,
                    personaId = resolvedPersona
                )
                val messageId = chatDao.insertMessage(message)
                conversationRepository.updateChatPreview(chatId, text, timeStr, message.timestamp)

                if (isMe && idempotencyKey.isNotEmpty()) {
                    val outboxEntry = OutboxEntity(
                        accountId = currentUserId,
                        localMessageId = messageId.toInt(),
                        chatId = chatId,
                        conversationId = conversationId,
                        idempotencyKey = idempotencyKey,
                        senderId = currentUserId,
                        recipientId = recipientId,
                        payload = "", // Plaintext withheld until peer key is retrieved & verified
                        signature = "",
                        type = type,
                        timeStr = timeStr,
                        attempts = 0,
                        status = "WAITING_FOR_PEER_KEY",
                        createdAt = System.currentTimeMillis()
                    )
                    val outboxRowId = chatDao.insertOutbox(outboxEntry)

                    backgroundScope.launch {
                        try {
                            val pubKey = cryptoRepository.userRepository.getOrFetchUserPublicKey(recipientId)
                            if (pubKey != null) {
                                val enc = cryptoRepository.encryptOutboundMessage(
                                    currentUserId = currentUserId,
                                    recipientId = recipientId,
                                    conversationId = conversationId,
                                    idempotencyKey = idempotencyKey,
                                    plainText = payloadToEncrypt,
                                    isSelfOrAi = false
                                )
                                chatDao.updateOutboxPayload(outboxRowId, enc.base64Payload, enc.base64Signature, currentUserId)
                                chatDao.updateMessageStatus(messageId.toInt(), "sending", currentUserId)
                                val sentRes = sendRemoteMessage(
                                    idempotencyKey = idempotencyKey,
                                    conversationId = conversationId,
                                    senderId = currentUserId,
                                    recipientId = recipientId,
                                    payloadBase64 = enc.base64Payload,
                                    signatureBase64 = enc.base64Signature,
                                    type = type,
                                    timeStr = timeStr,
                                    personaId = resolvedPersona
                                )
                                when (sentRes) {
                                    is RemoteMessageSendResult.Success -> {
                                        chatDao.updateMessageServerInfoByIdempotencyKey(
                                            idempotencyKey = idempotencyKey,
                                            serverId = sentRes.serverMessageId,
                                            sequence = sentRes.sequence,
                                            status = "sent",
                                            accountId = currentUserId
                                        )
                                        chatDao.deleteOutboxByIdempotencyKey(idempotencyKey, currentUserId)
                                        updateMessageStatus(messageId.toInt(), "sent", currentUserId)
                                    }
                                    is RemoteMessageSendResult.Failure -> {
                                        val newStat = if (sentRes.isRetryable) "pending" else "failed"
                                        chatDao.updateOutboxAttempt(outboxRowId, newStat, System.currentTimeMillis(), sentRes.errorMessage, currentUserId)
                                        updateMessageStatus(messageId.toInt(), if (sentRes.isRetryable) "pending" else "error", currentUserId)
                                    }
                                }
                            }
                        } catch (resEx: Exception) {
                            Timber.d("Peer key deferred resolution: ${resEx.message}")
                        }
                    }
                }
                return@withContext messageId
            }

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
                            val sendResult = sendRemoteMessage(
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
                            when (sendResult) {
                                is RemoteMessageSendResult.Success -> {
                                    chatDao.deleteOutboxByIdempotencyKey(idempotencyKey, currentUserId)
                                    updateMessageStatus(messageId.toInt(), "sent", currentUserId)
                                }
                                is RemoteMessageSendResult.Failure -> {
                                    val nextStatus = if (sendResult.isRetryable) "pending" else "error"
                                    chatDao.updateOutboxAttempt(outboxRowId, nextStatus, System.currentTimeMillis(), sendResult.errorMessage, currentUserId)
                                    updateMessageStatus(messageId.toInt(), nextStatus, currentUserId)
                                }
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
            val sendRes = sendRemoteMessage(
                idempotencyKey = existingOutbox.idempotencyKey,
                conversationId = existingOutbox.conversationId,
                senderId = existingOutbox.senderId,
                recipientId = existingOutbox.recipientId,
                payloadBase64 = existingOutbox.payload,
                signatureBase64 = existingOutbox.signature,
                type = existingOutbox.type,
                timeStr = existingOutbox.timeStr
            )
            when (sendRes) {
                is RemoteMessageSendResult.Success -> {
                    chatDao.updateMessageServerInfoByIdempotencyKey(
                        idempotencyKey = existingOutbox.idempotencyKey,
                        serverId = sendRes.serverMessageId,
                        sequence = sendRes.sequence,
                        status = "sent",
                        accountId = currentUserId
                    )
                    chatDao.deleteOutboxByIdempotencyKey(existingOutbox.idempotencyKey, currentUserId)
                    updateMessageStatus(msgId, "sent", currentUserId)
                }
                is RemoteMessageSendResult.Failure -> {
                    updateMessageStatus(msgId, "error", currentUserId)
                }
            }
        }
    }

    /**
     * Sends message to Supabase via canonical send_message_idempotent RPC.
     * STRICT: No fake delivery. If server call fails, returns failure fail-closed.
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
    ): RemoteMessageSendResult = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val accessToken = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
        if (accessToken.isNullOrBlank() || anonKey.isBlank()) {
            Timber.w("Cannot send remote message: user is not authenticated or Supabase not configured")
            return@withContext RemoteMessageSendResult.Failure("NOT_AUTHENTICATED", "User is not authenticated", isRetryable = false)
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
            var respBody = rpcResp.body?.string().orEmpty()
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
                            respBody = retryResp.body?.string().orEmpty()
                            retryResp.close()
                        }
                    }
                }
            }

            if (!isSuccess) {
                Timber.e("send_message_idempotent failed (HTTP $initialCode): $respBody")
                val isRetryable = initialCode >= 500 || initialCode == 429 || initialCode == 408
                return@withContext RemoteMessageSendResult.Failure("HTTP_$initialCode", respBody.ifBlank { "HTTP $initialCode" }, isRetryable)
            }

            if (respBody.isNotBlank()) {
                val json = try { JSONObject(respBody) } catch (_: Exception) { null }
                if (json != null) {
                    val sId = json.optString("id", idempotencyKey)
                    val sSeq = json.optLong("sequence", json.optLong("server_sequence", 0L))
                    val sStat = json.optString("status", "sent")
                    val isDup = json.optBoolean("is_duplicate", false)
                    return@withContext RemoteMessageSendResult.Success(sId, sSeq, sStat, isDup)
                }
            }

            RemoteMessageSendResult.Success(idempotencyKey, 0L, "sent")
        } catch (e: Exception) {
            Timber.e(e, "send_message_idempotent RPC network failure")
            RemoteMessageSendResult.Failure("NETWORK_ERROR", e.message ?: "Network error", isRetryable = true)
        }
    }
}
