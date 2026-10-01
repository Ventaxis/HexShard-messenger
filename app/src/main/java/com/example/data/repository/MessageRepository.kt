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
        if (accountId.isNotBlank()) chatDao.markAllAsRead(chatId, accountId)
    }

    suspend fun editMessage(messageId: Int, newText: String, accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        if (accountId.isNotBlank()) chatDao.editMessage(messageId, newText, System.currentTimeMillis(), accountId)
    }

    suspend fun deleteMessage(messageId: Int, accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext
        val msg = chatDao.getMessageById(messageId, accountId)
        chatDao.deleteMessage(messageId, accountId)

        val serverId = msg?.serverMessageId?.takeIf { it.isNotBlank() }
            ?: msg?.idempotencyKey?.takeIf { it.isNotBlank() }
            ?: messageId.toString()

        backgroundScope.launch {
            try {
                val baseUrl = SupabaseConfig.getBaseUrl()
                val anonKey = SupabaseConfig.getAnonKey(context)
                val token = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
                    ?: return@launch

                val body = JSONObject().apply {
                    put("is_deleted", true)
                    put("payload", "[DELETED]")
                }.toString().toRequestBody(JSON_MEDIA)

                val req = Request.Builder()
                    .url("$baseUrl/rest/v1/messages?or=(id.eq.$serverId,client_message_id.eq.$serverId)")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .patch(body)
                    .build()

                httpClient.newCall(req).execute().close()
            } catch (e: Exception) {
                Timber.w(e, "Error updating deleted message on Supabase")
            }
        }
    }

    /**
     * Primary entry point for sending a message.
     * Encrypts message, signs payload, saves to Room as "sending",
     * updates chat preview, and dispatches to Supabase.
     */
    suspend fun sendMessage(
        chatId: Int,
        sender: String,
        text: String,
        isMe: Boolean,
        isAttachment: Boolean = false,
        timeStr: String,
        status: String = "sending",
        type: String = "text",
        audioUrl: String? = null,
        duration: Int? = null,
        recipientId: String,
        idempotencyKey: String = "",
        personaId: String? = null
    ): Long = withContext(Dispatchers.IO) {
        messageWriteLimiter.acquire()
        try {
            val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: ""
            if (currentUserId.isBlank()) {
                Timber.w("Cannot send message: user is not authenticated")
                return@withContext -1L
            }
            val isSelfChat = isSelfConversation(recipientId = recipientId, id = chatId)
            val isAiChat = isAiConversation(recipientId = recipientId)
            val conversationId = conversationRepository.computeConversationId(currentUserId, recipientId)
            val resolvedPersona = personaId ?: if (isAiChat) {
                com.example.network.AiModelOption.DEFAULT.personaId
            } else null

            val plainTextToEncrypt = if (!audioUrl.isNullOrBlank() || type != "text") {
                JSONObject().apply {
                    put("text", text)
                    put("type", type)
                    if (!audioUrl.isNullOrBlank()) put("audioUrl", audioUrl)
                    if (duration != null) put("duration", duration)
                }.toString()
            } else {
                text
            }

            val encryptedPayload = cryptoRepository.encryptOutboundMessage(
                currentUserId = currentUserId,
                recipientId = recipientId,
                conversationId = conversationId,
                idempotencyKey = idempotencyKey,
                plainText = plainTextToEncrypt,
                isSelfOrAi = isSelfChat || isAiChat
            )

            val initialStatus = if (isSelfChat || isAiChat) "delivered" else "sending"

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

            val isRemoteAuthed = currentUserId.isNotBlank()
            if (!isAiChat && idempotencyKey.isNotEmpty() && isRemoteAuthed) {
                // 1. Enqueue in durable outbox table
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

                // 2. Attempt immediate delivery
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
                            timeStr = timeStr
                        )
                        if (sentOk) {
                            chatDao.deleteOutboxByIdempotencyKey(idempotencyKey, currentUserId)
                            updateMessageStatus(messageId.toInt(), "sent", currentUserId)
                        } else {
                            chatDao.updateOutboxAttempt(outboxRowId, "pending", System.currentTimeMillis(), "Initial delivery failed", currentUserId)
                        }
                    } catch (netEx: Exception) {
                        Timber.e(netEx, "Supabase message send network failure, will retry via outbox queue")
                        chatDao.updateOutboxAttempt(outboxRowId, "pending", System.currentTimeMillis(), netEx.message ?: "Network error", currentUserId)
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

    suspend fun sendRemoteMessage(
        idempotencyKey: String,
        conversationId: String,
        senderId: String,
        recipientId: String,
        payloadBase64: String,
        signatureBase64: String,
        type: String,
        timeStr: String
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(context)
        val accessToken = context?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }
        if (accessToken.isNullOrBlank() || anonKey.isBlank()) {
            Timber.w("Cannot send remote message: user is not authenticated or Supabase not configured")
            return@withContext false
        }

        // STRICT SERVER AUTHORITY: Call atomic send_message_idempotent RPC with schema-cache resilience.
        // No direct table insertion fallback is permitted because sequence allocation,
        // membership enforcement, and idempotency checks are server-side invariants.
        try {
            val primaryPayload = JSONObject().apply {
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
                put("p_encryption_version", 2)
            }

            val rpcReq = Request.Builder()
                .url("$baseUrl/rest/v1/rpc/send_message_idempotent")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post(primaryPayload.toString().toRequestBody(JSON_MEDIA))
                .build()

            var rpcResp = httpClient.newCall(rpcReq).execute()
            var isSuccess = rpcResp.isSuccessful
            var errorBody = if (!isSuccess) rpcResp.body?.string() ?: "" else ""
            rpcResp.close()

            // If schema cache mismatch, try non-prefixed parameter names
            if (!isSuccess && (rpcResp.code == 404 || errorBody.contains("schema cache") || errorBody.contains("Could not find the function") || errorBody.contains("PGRST202"))) {
                Timber.w("send_message_idempotent schema mismatch, trying fallback payload without p_ prefix")
                val fallbackPayload = JSONObject().apply {
                    put("conversation_id", conversationId)
                    put("client_message_id", idempotencyKey)
                    put("payload", payloadBase64)
                    put("signature", signatureBase64)
                    if (isSelfConversation(recipientId)) {
                        put("recipient_id", senderId)
                    } else if (recipientId.isNotBlank() && !isAiConversation(recipientId)) {
                        put("recipient_id", recipientId)
                    } else {
                        put("recipient_id", JSONObject.NULL)
                    }
                    put("type", type)
                    put("time_str", timeStr)
                    put("encryption_version", 2)
                }
                val fallbackReq = Request.Builder()
                    .url("$baseUrl/rest/v1/rpc/send_message_idempotent")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .post(fallbackPayload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                rpcResp = httpClient.newCall(fallbackReq).execute()
                isSuccess = rpcResp.isSuccessful
                if (!isSuccess) {
                    errorBody = rpcResp.body?.string() ?: ""
                    Timber.w("send_message_idempotent fallback RPC failed with HTTP ${rpcResp.code}: $errorBody")
                }
                rpcResp.close()
            } else if (!isSuccess) {
                Timber.w("send_message_idempotent RPC failed with HTTP ${rpcResp.code}: $errorBody")
            }

            isSuccess
        } catch (e: Exception) {
            Timber.e(e, "send_message_idempotent RPC network failure")
            false
        }
    }
}
