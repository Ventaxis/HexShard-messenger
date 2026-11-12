package com.example.data.repository

import android.content.Context
import com.example.crypto.E2ECryptoManager
import com.example.data.SecurePrefsManager
import com.example.data.database.ChatDao
import com.example.data.database.MessageEntity
import com.example.data.database.OutboxEntity
import com.example.data.database.isAiConversation
import com.example.data.database.isSelfConversation
import com.example.network.SupabaseHttp
import com.example.network.supabase.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.Base64

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
                    // Server expects the per-conversation message sequence, not wall-clock millis
                    val maxSeq = chatDao.getMaxSequenceForConversation(chat.conversationId, accountId) ?: 0L
                    val payload = JSONObject().apply {
                        put("p_conversation_id", chat.conversationId)
                        put("p_sequence", maxSeq)
                    }
                    val req = SupabaseHttp.jsonRequest(
                        "$baseUrl/rest/v1/rpc/mark_conversation_read",
                        token,
                        anonKey,
                        "POST",
                        payload.toString()
                    ).build()
                    SupabaseHttp.client.newCall(req).execute().close()
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

        val originalText = msg.text
        // Fail-closed: edits are not durable-outboxed, so without a session token the
        // local change would silently diverge from the server (and other devices)
        // forever. Reject the edit instead of letting the histories diverge.
        val ctx = context
        val sessionToken = ctx?.let { SecurePrefsManager.getSupabaseAccessToken(ctx) }.orEmpty()
        if (ctx == null || sessionToken.isBlank() || serverId.isBlank()) {
            Timber.w("editMessage rejected: offline or unsynced message cannot be edited consistently")
            return@withContext false
        }
        val token = sessionToken
        // Local cache optimistic update
        chatDao.editMessage(messageId, newText, System.currentTimeMillis(), accountId)

        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        if (token.isNotBlank() && serverId.isNotBlank()) {
            try {
                val chat = chatDao.getChatById(msg.chatId, accountId)
                val peerRecipient = chat?.recipientId?.takeIf { it.isNotBlank() } ?: msg.sender
                val isSaved = chat?.conversationType == com.example.data.ConversationType.SAVED_MESSAGES.name || chat?.conversationId?.startsWith("self_") == true || peerRecipient == "self"
                val isAi = chat?.conversationType == com.example.data.ConversationType.AI_ASSISTANT.name || chat?.conversationId?.startsWith("ai_") == true || isAiConversation(peerRecipient, conversationId = msg.conversationId)
                val isSelfOrAi = isSaved || isAi || isSelfConversation(peerRecipient, msg.conversationId)

                val encrypted = cryptoRepository.encryptOutboundMessage(
                    currentUserId = accountId,
                    recipientId = if (isSelfOrAi) accountId else peerRecipient,
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
                val req = SupabaseHttp.jsonRequest(
                    "$baseUrl/rest/v1/rpc/edit_message",
                    token,
                    anonKey,
                    "POST",
                    payload.toString()
                ).build()
                val resp = SupabaseHttp.client.newCall(req).execute()
                val ok = resp.isSuccessful
                val bodyStr = resp.body?.string().orEmpty()
                resp.close()

                // Fail-closed: unparseable body or missing success flag counts as failure
                val rpcSuccess = ok && (bodyStr.isBlank() || try {
                    JSONObject(bodyStr).optBoolean("success", false)
                } catch (_: Exception) { false })

                if (!rpcSuccess) {
                    // Roll back optimistic edit on server rejection
                    chatDao.editMessage(messageId, originalText, msg.timestamp, accountId)
                    return@withContext false
                }
                return@withContext true
            } catch (e: Exception) {
                Timber.w(e, "editMessage remote RPC failed, rolling back local cache")
                chatDao.editMessage(messageId, originalText, msg.timestamp, accountId)
                return@withContext false
            }
        }
        true
    }

    suspend fun deleteMessage(messageId: Int, accountId: String = getCurrentAccountId()): Boolean = withContext(Dispatchers.IO) {
        if (accountId.isBlank()) return@withContext false
        val msg = chatDao.getMessageById(messageId, accountId) ?: return@withContext false
        val serverId = msg.serverMessageId.ifBlank { msg.idempotencyKey }

        val ctx = context
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = ctx?.let { SupabaseConfig.getAnonKey(it) } ?: ""
        val token = ctx?.let { SecurePrefsManager.getSupabaseAccessToken(it) } ?: ""

        var remoteSuccess = false
        if (token.isNotBlank() && serverId.isNotBlank() && anonKey.isNotBlank()) {
            try {
                val payload = JSONObject().apply {
                    put("p_message_id", serverId)
                }
                val req = SupabaseHttp.jsonRequest(
                    "$baseUrl/rest/v1/rpc/delete_message",
                    token,
                    anonKey,
                    "POST",
                    payload.toString()
                ).build()
                val resp = SupabaseHttp.client.newCall(req).execute()
                val ok = resp.isSuccessful
                val bodyStr = resp.body?.string().orEmpty()
                resp.close()

                // Fail-closed: unparseable body or missing success flag counts as failure
                remoteSuccess = ok && (bodyStr.isBlank() || try {
                    JSONObject(bodyStr).optBoolean("success", false)
                } catch (_: Exception) { false })
            } catch (e: Exception) {
                Timber.w(e, "deleteMessage remote RPC failed")
                remoteSuccess = false
            }
        } else {
            // Local-only unsent message
            remoteSuccess = true
        }

        if (remoteSuccess) {
            chatDao.deleteMessage(messageId, accountId)
            true
        } else {
            Timber.w("Refusing to delete local message without verified server deletion")
            false
        }
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
            val req = SupabaseHttp.jsonRequest(
                "$baseUrl/rest/v1/rpc/send_ai_message",
                token,
                anonKey,
                "POST",
                payload.toString()
            ).build()
            val resp = SupabaseHttp.client.newCall(req).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            Timber.e(e, "sendAiTranscript RPC network failure")
            false
        }
    }

    /**
     * Single construction point for outgoing MessageEntity rows: the waiting-key
     * path and the encrypted-send path build the same entity and differ only in
     * status/iv/encryptionVersion/serverMessageId (see call sites).
     */
    private fun buildOutgoingMessage(
        accountId: String,
        chatId: Int,
        sender: String,
        isMe: Boolean,
        text: String,
        timeStr: String,
        isAttachment: Boolean,
        type: String,
        audioUrl: String?,
        duration: Int?,
        idempotencyKey: String,
        conversationId: String,
        personaId: String?,
        status: String,
        iv: String,
        encryptionVersion: Int,
        serverMessageId: String
    ): MessageEntity = MessageEntity(
        accountId = accountId,
        chatId = chatId,
        sender = if (isMe) accountId else sender,
        text = text,
        time = timeStr,
        timestamp = System.currentTimeMillis(),
        isMe = isMe,
        isAttachment = isAttachment,
        type = type,
        audioUrl = audioUrl,
        duration = duration,
        status = status,
        iv = iv,
        encryptionVersion = encryptionVersion,
        idempotencyKey = idempotencyKey,
        conversationId = conversationId,
        serverMessageId = serverMessageId,
        signatureValid = true,
        personaId = personaId
    )

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
            val conversationId = conversationRepository.getChatById(chatId, currentUserId)
                ?.conversationId
                ?.takeIf { it.isNotBlank() }
                ?: conversationRepository.computeConversationId(currentUserId, recipientId)

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
                val message = buildOutgoingMessage(
                    accountId = currentUserId,
                    chatId = chatId,
                    sender = sender,
                    isMe = isMe,
                    text = text,
                    timeStr = timeStr,
                    isAttachment = isAttachment,
                    type = type,
                    audioUrl = audioUrl,
                    duration = duration,
                    idempotencyKey = idempotencyKey,
                    conversationId = conversationId,
                    personaId = resolvedPersona,
                    status = "waiting_key",
                    iv = "",
                    encryptionVersion = 2,
                    serverMessageId = idempotencyKey
                )
                val messageId: Long
                val outboxRowId: Long
                if (isMe && idempotencyKey.isNotEmpty()) {
                    val outboxEntry = OutboxEntity(
                        accountId = currentUserId,
                        localMessageId = 0,
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
                    val pair = chatDao.insertMessageAndOutbox(message, outboxEntry)
                    messageId = pair.first
                    outboxRowId = pair.second
                } else {
                    messageId = chatDao.insertMessage(message)
                    outboxRowId = 0L
                }
                conversationRepository.updateChatPreview(chatId, text, timeStr, message.timestamp)

                if (isMe && idempotencyKey.isNotEmpty()) {
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
                                applySendResult(
                                    sentRes,
                                    idempotencyKey,
                                    messageId.toInt(),
                                    currentUserId,
                                    outboxRowId,
                                    outboxFailureStatus = "failed"
                                )
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
            // AI messages don't travel through sendRemoteMessage — their transcript is
            // persisted by the dedicated AI flow, so they must never sit in "sending".
            val initialStatus = when {
                !isRemoteAuthed -> "pending"
                isAiChat -> "delivered"
                else -> "sending"
            }

            val message = buildOutgoingMessage(
                accountId = currentUserId,
                chatId = chatId,
                sender = sender,
                isMe = isMe,
                text = text,
                timeStr = timeStr,
                isAttachment = isAttachment,
                type = type,
                audioUrl = audioUrl,
                duration = duration,
                idempotencyKey = idempotencyKey,
                conversationId = conversationId,
                personaId = resolvedPersona,
                status = initialStatus,
                iv = encryptedPayload.iv,
                encryptionVersion = 3,
                // Server message id stays blank until the server confirms; callers fall
                // back to idempotencyKey where a client key is acceptable.
                serverMessageId = ""
            )
            val messageId: Long
            val outboxRowId: Long
            // AI prompts are persisted server-side by sendAiTranscript's dedicated flow —
            // a generic outbox entry would be flushed later WITHOUT personaId and
            // corrupt the AI conversation. AI chats never enter the generic outbox.
            val needsGenericOutbox = isMe && idempotencyKey.isNotEmpty() && !isAiChat
            if (needsGenericOutbox) {
                val outboxEntry = OutboxEntity(
                    accountId = currentUserId,
                    localMessageId = 0,
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
                val pair = chatDao.insertMessageAndOutbox(message, outboxEntry)
                messageId = pair.first
                outboxRowId = pair.second
            } else {
                messageId = chatDao.insertMessage(message)
                outboxRowId = 0L
            }
            conversationRepository.updateChatPreview(chatId, text, timeStr, message.timestamp)

            // ALL user-initiated messages are enqueued into durable outbox (direct, saved, and AI user prompts)
            if (isMe && idempotencyKey.isNotEmpty()) {
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
                            applySendResult(
                                sendResult,
                                idempotencyKey,
                                messageId.toInt(),
                                currentUserId,
                                outboxRowId
                            )
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
            // Cancellation must propagate: swallowing it here turns coroutine cancellation
            // into a fake "-1 = error" and makes the caller retry an already-persisted row.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "MessageRepository.sendMessage failed")
            -1L
        } finally {
            messageWriteLimiter.release()
        }
    }

    suspend fun retryMessage(msgId: Int, chatId: Int, text: String) = withContext(Dispatchers.IO) {
        val currentUserId = context?.let { SecurePrefsManager.getUserId(it) } ?: return@withContext
        val msg = chatDao.getMessageById(msgId, currentUserId) ?: return@withContext

        // Look up the outbox entry REGARDLESS of status: after 5 failed attempts the
        // entry is 'failed' and excluded from getPendingOutboxEntries — without this
        // the user's retry button would strand the message forever.
        val existingOutbox = chatDao.getOutboxByIdempotencyKey(msg.idempotencyKey, currentUserId)
            ?: chatDao.getAllOutboxEntries(currentUserId).find { it.localMessageId == msgId }
        // Never send a blank payload: WAITING_FOR_PEER_KEY entries carry no ciphertext yet
        if (existingOutbox == null || existingOutbox.payload.isBlank()) {
            // Nothing sendable (e.g. still waiting for the peer's public key)
            updateMessageStatus(msgId, "pending", currentUserId)
            return@withContext
        }

        updateMessageStatus(msgId, "sending", currentUserId)

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
            applySendResult(sendRes, existingOutbox.idempotencyKey, msgId, currentUserId)
        }
    }

    /**
     * Shared send-result bookkeeping for the durable outbox: on success the outbox
     * row is deleted and the message is marked "sent"; on failure the outbox row
     * records the attempt and the message moves to "pending"/"error" depending on
     * retryability. [outboxFailureStatus] differs per call site ("failed" for the
     * waiting-key resolution path, "error" for the generic outbox) and is preserved
     * as-is. Call sites without a durable outbox row (retryMessage) pass
     * [outboxRowId] < 0: nothing is written to the outbox and the message always
     * lands on "error" since there is no retry vehicle behind it.
     */
    private suspend fun applySendResult(
        result: RemoteMessageSendResult,
        idempotencyKey: String,
        messageId: Int,
        accountId: String,
        outboxRowId: Long = -1L,
        outboxFailureStatus: String = "error"
    ) {
        when (result) {
            is RemoteMessageSendResult.Success -> {
                chatDao.updateMessageServerInfoByIdempotencyKey(
                    idempotencyKey = idempotencyKey,
                    serverId = result.serverMessageId,
                    sequence = result.sequence,
                    status = "sent",
                    accountId = accountId
                )
                chatDao.deleteOutboxByIdempotencyKey(idempotencyKey, accountId)
                updateMessageStatus(messageId, "sent", accountId)
            }
            is RemoteMessageSendResult.Failure -> {
                if (outboxRowId >= 0) {
                    chatDao.updateOutboxAttempt(
                        outboxRowId,
                        if (result.isRetryable) "pending" else outboxFailureStatus,
                        System.currentTimeMillis(),
                        result.errorMessage,
                        accountId
                    )
                }
                updateMessageStatus(messageId, if (result.isRetryable && outboxRowId >= 0) "pending" else "error", accountId)
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
                put("p_encryption_version", 3)
            }

            val rpcReq = SupabaseHttp.jsonRequest(
                "$baseUrl/rest/v1/rpc/send_message_idempotent",
                accessToken,
                anonKey,
                "POST",
                payload.toString()
            ).build()

            var rpcResp = SupabaseHttp.client.newCall(rpcReq).execute()
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
                            val retryResp = SupabaseHttp.client.newCall(retryReq).execute()
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

            val json = try { JSONObject(respBody) } catch (_: Exception) { null }
            val serverId = json?.optString("id", "")?.trim().orEmpty()
            val sequence = json?.let {
                if (it.has("sequence")) it.optLong("sequence", -1L)
                else it.optLong("server_sequence", -1L)
            } ?: -1L
            if (json == null || serverId.isBlank() || sequence < 0L) {
                Timber.e("send_message_idempotent returned an unconfirmed result: $respBody")
                return@withContext RemoteMessageSendResult.Failure(
                    "INVALID_SERVER_RESPONSE",
                    "Server did not confirm the message ID and sequence",
                    isRetryable = true
                )
            }

            RemoteMessageSendResult.Success(
                serverId,
                sequence,
                json.optString("status", "sent"),
                json.optBoolean("is_duplicate", false)
            )
        } catch (e: Exception) {
            Timber.e(e, "send_message_idempotent RPC network failure")
            RemoteMessageSendResult.Failure("NETWORK_ERROR", e.message ?: "Network error", isRetryable = true)
        }
    }
}
