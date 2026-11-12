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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
            val uid = getCurrentUserId()
            if (uid.isNotBlank() && context != null) {
                com.example.crypto.CryptoBackupManager.loadCachedKeysIfAvailable(context, uid)
            }
            E2ECryptoManager.generateKeyPairIfNeeded(uid)
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

    fun registerVerifiedPeerKey(userId: String, publicKeyBase64: String): Boolean =
        userRepository.registerVerifiedPeerKey(userId, publicKeyBase64)

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

    open suspend fun getOrCreateDirectConversation(peerId: String, title: String = ""): Result<ChatEntity> =
        conversationRepository.getOrCreateDirectConversation(peerId, title)

    open suspend fun getOrCreateSavedMessagesConversation(): Result<ChatEntity> =
        conversationRepository.getOrCreateSavedMessagesConversation()

    open suspend fun getOrCreateAiConversation(): Result<ChatEntity> =
        conversationRepository.getOrCreateAiConversation()

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
        val conversationId = computeConversationId(getCurrentUserId(), recipientId)
        return attachmentRepository.uploadVoiceFile(file, conversationId, idempotencyKey, recipientId)
    }

    suspend fun uploadVoiceMessageBytes(data: ByteArray, chatId: Int, idempotencyKey: String = "", recipientId: String): String? {
        val conversationId = computeConversationId(getCurrentUserId(), recipientId)
        val ctx = context ?: return null
        val temp = File.createTempFile("fwd_voice_", ".ogg", ctx.cacheDir)
        try {
            temp.writeBytes(data)
            return attachmentRepository.uploadVoiceFile(temp, conversationId, idempotencyKey, recipientId)
        } finally {
            temp.delete()
        }
    }

    suspend fun uploadMediaMessage(
        context: Context,
        uri: Uri,
        chatId: Int,
        type: String,
        idempotencyKey: String,
        recipientId: String
    ): String? {
        val conversationId = getChatById(chatId)?.conversationId?.takeIf { it.isNotBlank() }
            ?: computeConversationId(getCurrentUserId(), recipientId)
        return attachmentRepository.uploadAttachment(
            inputUri = uri,
            conversationId = conversationId,
            fileType = type,
            idempotencyKey = idempotencyKey,
            recipientId = recipientId
        )
    }

    suspend fun downloadMediaMessage(url: String, partnerId: String, idempotencyKey: String = ""): ByteArray? =
        attachmentRepository.downloadAttachmentBytes(url, partnerId, idempotencyKey)

    suspend fun uploadAttachmentData(
        data: ByteArray,
        chatId: Int,
        type: String,
        idempotencyKey: String,
        recipientId: String
    ): String? {
        val conversationId = getChatById(chatId)?.conversationId?.takeIf { it.isNotBlank() }
            ?: computeConversationId(getCurrentUserId(), recipientId)
        return attachmentRepository.uploadAttachmentData(data, conversationId, type, idempotencyKey, recipientId)
    }

    suspend fun downloadVoiceMessage(url: String, partnerId: String, idempotencyKey: String = ""): ByteArray? {
        if (url.startsWith("file://")) {
            return try {
                File(java.net.URI(url)).readBytes()
            } catch (e: Exception) {
                null
            }
        }
        return attachmentRepository.downloadAttachmentBytes(url, partnerId, idempotencyKey)
    }

    suspend fun retryMessage(msgId: Int, chatId: Int, text: String) {
        messageRepository.retryMessage(msgId, chatId, text)
    }

    fun refreshAccount() {
        val uid = getCurrentUserId()
        if (uid.isNotBlank() && context != null) {
            com.example.crypto.CryptoBackupManager.loadCachedKeysIfAvailable(context, uid)
        }
        conversationRepository.refreshAccount()
        if (uid.isNotBlank()) {
            syncManager.stopRealtimeSync()
            syncManager.startRealtimeSync()
        } else {
            syncManager.stopRealtimeSync()
        }
    }

    suspend fun insertOutbox(entry: OutboxEntity): Long = chatDao.insertOutbox(entry)

    suspend fun sendAiTranscript(
        conversationId: String,
        userClientId: String,
        userText: String,
        replyClientId: String,
        replyText: String,
        personaId: String,
        timeStr: String
    ): Boolean = messageRepository.sendAiTranscript(
        conversationId = conversationId,
        userClientId = userClientId,
        userText = userText,
        replyClientId = replyClientId,
        replyText = replyText,
        personaId = personaId,
        timeStr = timeStr
    )

    open suspend fun populateInitialDataIfNeeded() =
        conversationRepository.populateInitialDataIfNeeded()

    open suspend fun populateInitialDataIfNeeded(accountId: String) =
        conversationRepository.populateInitialDataIfNeeded(accountId)

    suspend fun resolveUser(identifier: String): Pair<String, String>? =
        userRepository.resolveUser(identifier)

    suspend fun searchUsers(query: String): List<UserRepository.UserSearchResult> =
        userRepository.searchUsers(query)

    suspend fun fetchPeerProfile(peerIdOrUsername: String): UserRepository.PeerProfileInfo? =
        userRepository.fetchPeerProfile(peerIdOrUsername)

    suspend fun searchChats(query: String): List<ChatEntity> =
        chatDao.searchChats(query, getCurrentUserId())

    suspend fun searchMessages(query: String): List<MessageEntity> =
        chatDao.searchMessages(query, getCurrentUserId())

    suspend fun createProfileShareToken(expiresInDays: Int = 7): String? =
        userRepository.createProfileShareToken(expiresInDays)

    suspend fun resolveProfileShareToken(token: String): com.example.util.ResolvedProfile? =
        userRepository.resolveProfileShareToken(token)

    suspend fun revokeProfileShareTokens(): Boolean =
        userRepository.revokeProfileShareTokens()

    suspend fun logout(context: Context) {
        stopRealtimeSync()
        userRepository.clearCache()
        com.example.network.supabase.SessionManager.clearSessionHard(context)
        refreshAccount()
    }

    suspend fun deleteAccount(context: Context): com.example.network.supabase.DeleteAccountResult {
        stopRealtimeSync()
        val currentAccount = getCurrentUserId()
        val result = com.example.network.supabase.SupabaseAuthService.deleteAccount(context)
        if (result is com.example.network.supabase.DeleteAccountResult.Success) {
            userRepository.clearCache()
            if (currentAccount.isNotBlank()) {
                E2ECryptoManager.deleteKeys(currentAccount)
                com.example.data.PeerKeyTrustStore.clearForAccount(context, currentAccount)
                chatDao.clearAllChatsForAccount(currentAccount)
                chatDao.clearAllMessagesForAccount(currentAccount)
                chatDao.clearAllOutboxForAccount(currentAccount)
                com.example.data.repository.AttachmentRepository.clearCacheForAccount(context, currentAccount)
            }
            refreshAccount()
            return result
        }
        Timber.w("Server account deletion failed; preserving local encryption keys and messages.")
        startRealtimeSync()
        return result
    }

    /**
     * Server-authorized conversation deletion helpers (v1.7.0). Each calls its
     * SECURITY DEFINER RPC (membership-checked server-side) and mirrors the result
     * locally. Markers let the sync catch-up skip everything up to the deletion point.
     */
    private suspend fun callConversationRpc(rpc: String, conversationId: String, token: String, baseUrl: String, anonKey: String): Long? =
        withContext(Dispatchers.IO) {
            try {
                val req = com.example.network.SupabaseHttp.jsonRequest(
                    "$baseUrl/rest/v1/rpc/$rpc", token, anonKey, "POST",
                    org.json.JSONObject().put("p_conversation_id", conversationId).toString()
                ).build()
                val resp = com.example.network.SupabaseHttp.client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                val ok = resp.isSuccessful
                resp.close()
                if (!ok) {
                    Timber.w("Conversation RPC $rpc failed: $body")
                    return@withContext null
                }
                org.json.JSONObject(body).optLong("$rpc", body.toLongOrNull() ?: 0L)
            } catch (e: Exception) {
                Timber.w(e, "Conversation RPC $rpc exception")
                null
            }
        }

    suspend fun deleteConversationForMe(chat: ChatEntity, context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val accountId = getCurrentUserId()
            val token = com.example.data.SecurePrefsManager.getSupabaseAccessToken(context)
            val baseUrl = com.example.network.supabase.SupabaseConfig.getBaseUrl()
            val anonKey = com.example.network.supabase.SupabaseConfig.getAnonKey(context)
            val marker = callConversationRpc(
                "delete_conversation_for_me", chat.conversationId.ifBlank { "self_$accountId" },
                token, baseUrl, anonKey
            ) ?: return@withContext false
            if (accountId.isNotBlank()) {
                com.example.data.SecurePrefsManager.setConversationDeleteMarker(context, accountId, chat.conversationId, marker)
                chatDao.deleteMessagesByChat(chat.id, accountId)
                chatDao.deleteChatById(chat.id, accountId)
            }
            refreshAccount()
            true
        }

    suspend fun deleteConversationForBoth(chat: ChatEntity, context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val accountId = getCurrentUserId()
            val token = com.example.data.SecurePrefsManager.getSupabaseAccessToken(context)
            val baseUrl = com.example.network.supabase.SupabaseConfig.getBaseUrl()
            val anonKey = com.example.network.supabase.SupabaseConfig.getAnonKey(context)
            callConversationRpc(
                "delete_conversation_for_both", chat.conversationId.ifBlank { chat.recipientId },
                token, baseUrl, anonKey
            ) ?: return@withContext false
            if (accountId.isNotBlank()) {
                chatDao.deleteMessagesByChat(chat.id, accountId)
                chatDao.deleteChatById(chat.id, accountId)
            }
            refreshAccount()
            true
        }

    suspend fun clearConversationMessages(chat: ChatEntity, context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val accountId = getCurrentUserId()
            val token = com.example.data.SecurePrefsManager.getSupabaseAccessToken(context)
            val baseUrl = com.example.network.supabase.SupabaseConfig.getBaseUrl()
            val anonKey = com.example.network.supabase.SupabaseConfig.getAnonKey(context)
            val marker = callConversationRpc(
                "clear_conversation_messages", chat.conversationId.ifBlank { chat.recipientId },
                token, baseUrl, anonKey
            ) ?: return@withContext false
            if (accountId.isNotBlank()) {
                com.example.data.SecurePrefsManager.setConversationDeleteMarker(context, accountId, chat.conversationId, marker)
                chatDao.deleteMessagesByChat(chat.id, accountId)
            }
            true
        }
}
