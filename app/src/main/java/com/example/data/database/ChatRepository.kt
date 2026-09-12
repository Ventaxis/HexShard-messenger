package com.example.data.database

import android.content.Context
import android.net.Uri
import com.example.crypto.E2ECryptoManager
import com.example.data.SecurePrefsManager
import com.example.data.repository.AttachmentRepository
import com.example.data.repository.ConversationRepository
import com.example.data.repository.CryptoRepository
import com.example.data.repository.MessageRepository
import com.example.data.repository.SyncManager
import com.example.data.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.io.File
import java.security.PublicKey

open class ChatRepository(
    private val chatDao: ChatDao,
    private val context: Context? = null
) {
    val userRepository = UserRepository(context)
    val cryptoRepository = CryptoRepository(userRepository)
    val attachmentRepository = AttachmentRepository(context, userRepository)
    val conversationRepository = ConversationRepository(chatDao, context)
    val messageRepository = MessageRepository(chatDao, cryptoRepository, conversationRepository, context)
    val syncManager = SyncManager(chatDao, cryptoRepository, conversationRepository, messageRepository, context)

    val allChats: Flow<List<ChatEntity>> = conversationRepository.allChats

    init {
        try {
            E2ECryptoManager.generateKeyPairIfNeeded()
            syncManager.startRealtimeSync()
        } catch (e: Throwable) {
            Timber.e(e, "ChatRepository initialization: crypto or sync error")
        }
    }

    fun getCurrentUserId(): String {
        return context?.let { SecurePrefsManager.getUserId(it) } ?: ""
    }

    fun computeConversationId(myUid: String, recipientId: String): String =
        conversationRepository.computeConversationId(myUid, recipientId)

    fun startRealtimeSync() {
        syncManager.startRealtimeSync()
    }

    fun stopRealtimeSync() {
        syncManager.stopRealtimeSync()
    }

    fun getMessagesForChat(chatId: Int): Flow<List<MessageEntity>> =
        messageRepository.getMessagesForChat(chatId)

    open suspend fun getChatById(chatId: Int): ChatEntity? =
        conversationRepository.getChatById(chatId)

    open suspend fun getChatByConversationId(conversationId: String): ChatEntity? =
        conversationRepository.getChatByConversationId(conversationId)

    open suspend fun getChatByRecipientId(recipientId: String): ChatEntity? =
        conversationRepository.getChatByRecipientId(recipientId)

    open suspend fun insertChat(chat: ChatEntity): Long =
        conversationRepository.insertChat(chat)

    open suspend fun insertChats(chats: List<ChatEntity>) =
        conversationRepository.insertChats(chats)

    open suspend fun toggleChatPin(chatId: Int) =
        conversationRepository.toggleChatPin(chatId)

    open suspend fun deleteChat(chatId: Int) =
        conversationRepository.deleteChat(chatId)

    open suspend fun updateChatPreview(chatId: Int, preview: String, time: String, timestamp: Long) =
        conversationRepository.updateChatPreview(chatId, preview, time, timestamp)

    open suspend fun getMessageById(messageId: Int): MessageEntity? =
        messageRepository.getMessageById(messageId)

    open suspend fun getMessageByServerId(serverId: String): MessageEntity? =
        messageRepository.getMessageByServerId(serverId)

    open suspend fun getMessageByIdempotencyKey(key: String): MessageEntity? =
        messageRepository.getMessageByIdempotencyKey(key)

    open suspend fun insertMessage(message: MessageEntity): Long =
        messageRepository.insertMessage(message)

    open suspend fun insertMessageIfNotExists(message: MessageEntity): Long =
        messageRepository.insertMessageIfNotExists(message)

    open suspend fun updateMessageStatus(messageId: Int, status: String) =
        messageRepository.updateMessageStatus(messageId, status)

    open suspend fun updateMessageStatusByServerId(serverId: String, status: String) =
        messageRepository.updateMessageStatusByServerId(serverId, status)

    open suspend fun markAllAsRead(chatId: Int) =
        messageRepository.markAllAsRead(chatId)

    open suspend fun editMessage(messageId: Int, newText: String) =
        messageRepository.editMessage(messageId, newText)

    open suspend fun deleteMessage(messageId: Int) =
        messageRepository.deleteMessage(messageId)

    open suspend fun sendMessage(
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
    ): Long = messageRepository.sendMessage(
        chatId = chatId,
        sender = sender,
        text = text,
        isMe = isMe,
        isAttachment = isAttachment,
        timeStr = timeStr,
        status = status,
        type = type,
        audioUrl = audioUrl,
        duration = duration,
        recipientId = recipientId,
        idempotencyKey = idempotencyKey,
        personaId = personaId
    )

    suspend fun uploadVoiceMessage(file: File, chatId: Int, idempotencyKey: String = "", recipientId: String): String? {
        val isSelfOrAi = isSelfConversation(recipientId = recipientId, id = chatId) || isAiConversation(recipientId = recipientId)
        val conversationId = computeConversationId(getCurrentUserId(), recipientId)

        val result = attachmentRepository.uploadVoiceFile(file, conversationId, idempotencyKey, recipientId)
        if (result != null) return result
        return if (isSelfOrAi) file.toURI().toString() else null
    }

    suspend fun uploadMediaMessage(
        context: Context,
        uri: Uri,
        chatId: Int,
        type: String,
        idempotencyKey: String,
        recipientId: String
    ): String? {
        val isSelfOrAi = isSelfConversation(recipientId = recipientId, id = chatId) || isAiConversation(recipientId = recipientId)
        val conversationId = computeConversationId(getCurrentUserId(), recipientId)

        val result = attachmentRepository.uploadAttachment(
            inputUri = uri,
            conversationId = conversationId,
            fileType = type,
            idempotencyKey = idempotencyKey,
            recipientId = recipientId
        )
        if (result != null) return result
        return if (isSelfOrAi) uri.toString() else null
    }

    suspend fun downloadMediaMessage(url: String, partnerId: String): ByteArray? =
        attachmentRepository.downloadAttachmentBytes(url, partnerId)

    suspend fun downloadVoiceMessage(url: String, partnerId: String): ByteArray? {
        if (url.startsWith("file://")) {
            return try {
                File(java.net.URI(url)).readBytes()
            } catch (e: Exception) {
                null
            }
        }
        return attachmentRepository.downloadAttachmentBytes(url, partnerId)
    }

    suspend fun retryMessage(msgId: Int, chatId: Int, text: String) {
        messageRepository.retryMessage(msgId, chatId, text)
    }

    fun refreshAccount() {
        conversationRepository.refreshAccount()
    }

    open suspend fun populateInitialDataIfNeeded() =
        conversationRepository.populateInitialDataIfNeeded()

    open suspend fun populateInitialDataIfNeeded(accountId: String) =
        conversationRepository.populateInitialDataIfNeeded(accountId)

    suspend fun resolveUser(identifier: String): Pair<String, String>? =
        userRepository.resolveUser(identifier)

    suspend fun logout(context: Context) {
        stopRealtimeSync()
        com.example.network.supabase.SessionManager.clearSession(context)
        SecurePrefsManager.clear(context)
        refreshAccount()
    }

    suspend fun deleteAccount(context: Context): Boolean {
        stopRealtimeSync()
        val result = com.example.network.supabase.SupabaseAuthService.deleteAccount(context)
        refreshAccount()
        return result
    }
}
