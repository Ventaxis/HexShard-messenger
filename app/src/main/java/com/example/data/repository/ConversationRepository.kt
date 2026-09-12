package com.example.data.repository

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.data.database.ChatDao
import com.example.data.database.ChatEntity
import com.example.data.database.MessageEntity
import com.example.data.database.isAiAssistant
import com.example.data.database.isSavedMessages
import com.example.ui.AppLanguage
import com.example.ui.LocalizationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber

class ConversationRepository(
    private val chatDao: ChatDao,
    private val context: Context? = null
) {
    fun getCurrentAccountId(): String {
        return context?.let { SecurePrefsManager.getUserId(it) } ?: ""
    }

    private val _currentAccountId = MutableStateFlow(getCurrentAccountId())
    val currentAccountId: StateFlow<String> = _currentAccountId.asStateFlow()

    fun refreshAccount() {
        val newId = getCurrentAccountId()
        _currentAccountId.value = newId
    }

    val allChats: Flow<List<ChatEntity>> = _currentAccountId
        .flatMapLatest { accountId ->
            flow {
                if (accountId.isNotBlank()) {
                    ensureDefaultChatsExist(accountId)
                    emitAll(chatDao.getAllChatsForAccount(accountId))
                } else {
                    emit(emptyList())
                }
            }
        }
        .flowOn(Dispatchers.IO)

    fun getChatsForAccount(accountId: String = getCurrentAccountId()): Flow<List<ChatEntity>> =
        if (accountId.isNotBlank()) {
            chatDao.getAllChatsForAccount(accountId).flowOn(Dispatchers.Default)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }

    fun computeConversationId(myUid: String, recipientId: String): String {
        if (recipientId.isBlank() || recipientId == "me" || recipientId == "self" || recipientId.startsWith("self_")) {
            return "self_${myUid.ifBlank { "unknown" }}"
        }
        if (recipientId == "ai_assistant" || recipientId.startsWith("ai_")) {
            return "ai_${myUid.ifBlank { "unknown" }}"
        }
        if (recipientId.startsWith("group_")) {
            return recipientId
        }
        val uid1 = myUid.ifBlank { "unknown" }
        val uid2 = recipientId
        val (first, second) = if (uid1 < uid2) uid1 to uid2 else uid2 to uid1
        return "direct_${first}_${second}"
    }

    suspend fun getChatById(chatId: Int, accountId: String = getCurrentAccountId()): ChatEntity? = withContext(Dispatchers.IO) {
        chatDao.getChatById(chatId, accountId)
    }

    suspend fun getChatByConversationId(conversationId: String, accountId: String = getCurrentAccountId()): ChatEntity? = withContext(Dispatchers.IO) {
        chatDao.getChatByConversationId(conversationId, accountId)
    }

    suspend fun getChatByRecipientId(recipientId: String, accountId: String = getCurrentAccountId()): ChatEntity? = withContext(Dispatchers.IO) {
        chatDao.getChatByRecipientId(recipientId, accountId)
    }

    suspend fun insertChat(chat: ChatEntity): Long = withContext(Dispatchers.IO) {
        chatDao.insertChat(chat)
    }

    suspend fun insertChats(chats: List<ChatEntity>) = withContext(Dispatchers.IO) {
        chatDao.insertChats(chats)
    }

    suspend fun toggleChatPin(chatId: Int, accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        chatDao.toggleChatPin(chatId, accountId)
    }

    suspend fun deleteChat(chatId: Int, accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        val chat = chatDao.getChatById(chatId, accountId)
        if (chat != null && (chat.isSavedMessages || chat.isAiAssistant)) {
            Timber.w("Refusing to delete protected system chat: ${chat.name} (id=$chatId)")
            return@withContext
        }
        chatDao.deleteChatById(chatId, accountId)
    }

    suspend fun updateChatPreview(chatId: Int, preview: String, time: String, timestamp: Long, accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        chatDao.updateChatPreview(chatId, preview, time, timestamp, accountId)
    }

    suspend fun populateInitialDataIfNeeded(accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        ensureDefaultChatsExist(accountId)
    }

    suspend fun ensureDefaultChatsExist(accountId: String = getCurrentAccountId()) = withContext(Dispatchers.IO) {
        val targetAccountId = if (accountId.isNotBlank()) accountId else getCurrentAccountId()
        if (targetAccountId.isBlank()) {
            return@withContext
        }
        
        // 1. Backfill any legacy records with empty accountId to targetAccountId
        chatDao.backfillLegacyChatsAccountId(targetAccountId)
        chatDao.backfillLegacyMessagesAccountId(targetAccountId)
        chatDao.backfillLegacyOutboxAccountId(targetAccountId)

        val isRussian = LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN
        val savedName = if (isRussian) "Избранное" else "Saved Messages"
        val savedAva = if (isRussian) "ИЗ" else "SM"
        val welcomeText = if (isRussian) "Добро пожаловать в Избранное!" else "Welcome to Saved Messages!"

        val hexshardName = "HexShard AI"
        val hexshardAva = "HA"
        val hexshardPreview = if (isRussian) "Умный ассистент • HexShard AI" else "Smart Assistant • HexShard AI"

        val ventaxisSender = "Ventaxis AI"
        val ventaxisWelcome = if (isRussian) "Привет! Я Ventaxis AI. Чем могу помочь вам сегодня?" else "Hello! I am Ventaxis AI. How can I help you today?"

        val hexagonSender = "Hexagon AI (Beta)"
        val hexagonWelcome = if (isRussian) "Привет! Я Hexagon AI (Beta). Задайте любой вопрос для быстрого ответа!" else "Hello! I am Hexagon AI (Beta). Ask me anything for a rapid answer!"

        val timeText = if (isRussian) "Только что" else "Just now"
        val selfConvId = "self_$targetAccountId"
        val aiConvId = "ai_$targetAccountId"

        // --- 1. ENSURE SAVED MESSAGES EXISTS ---
        val existingSaved = chatDao.getSavedMessagesChat(targetAccountId)
        val savedChatId = if (existingSaved == null) {
            Timber.d("Creating default Saved Messages for account $targetAccountId")
            val newChat = ChatEntity(
                id = 0,
                name = savedName,
                ava = savedAva,
                status = "online",
                preview = welcomeText,
                time = timeText,
                isGroup = false,
                isPinned = true,
                recipientId = "self",
                conversationId = selfConvId,
                conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name,
                accountId = targetAccountId
            )
            val generatedId = chatDao.insertChat(newChat).toInt()
            chatDao.insertMessage(
                MessageEntity(
                    chatId = generatedId,
                    sender = "me",
                    text = welcomeText,
                    time = timeText,
                    timestamp = System.currentTimeMillis(),
                    isMe = true,
                    status = "delivered",
                    conversationId = selfConvId,
                    idempotencyKey = "init_saved_msg_${targetAccountId}_$generatedId",
                    accountId = targetAccountId
                )
            )
            generatedId
        } else {
            val updated = existingSaved.copy(
                conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name,
                accountId = targetAccountId,
                recipientId = "self",
                conversationId = selfConvId,
                isPinned = true
            )
            chatDao.insertChat(updated)
            val msgs = chatDao.getMessagesForChatForAccount(existingSaved.id, targetAccountId).first()
            if (msgs.isEmpty()) {
                chatDao.insertMessage(
                    MessageEntity(
                        chatId = existingSaved.id,
                        sender = "me",
                        text = welcomeText,
                        time = timeText,
                        timestamp = System.currentTimeMillis(),
                        isMe = true,
                        status = "delivered",
                        conversationId = selfConvId,
                        idempotencyKey = "init_saved_msg_${targetAccountId}_${existingSaved.id}",
                        accountId = targetAccountId
                    )
                )
            }
            existingSaved.id
        }

        // --- 2. ENSURE HEXSHARD AI EXISTS ---
        val existingAi = chatDao.getAiAssistantChat(targetAccountId)
        val aiChatId = if (existingAi == null) {
            Timber.d("Creating default HexShard AI for account $targetAccountId")
            val newChat = ChatEntity(
                id = 0,
                name = hexshardName,
                ava = hexshardAva,
                status = "online",
                preview = hexshardPreview,
                time = timeText,
                isGroup = false,
                isPinned = false,
                recipientId = "ai_assistant",
                conversationId = aiConvId,
                conversationType = com.example.data.ConversationType.AI_ASSISTANT.name,
                accountId = targetAccountId
            )
            val generatedId = chatDao.insertChat(newChat).toInt()
            val baseTime = System.currentTimeMillis()
            chatDao.insertMessages(listOf(
                MessageEntity(
                    chatId = generatedId,
                    sender = ventaxisSender,
                    text = ventaxisWelcome,
                    time = timeText,
                    timestamp = baseTime,
                    isMe = false,
                    status = "delivered",
                    conversationId = aiConvId,
                    idempotencyKey = "init_ventaxis_msg_${targetAccountId}_$generatedId",
                    personaId = "VENTAXIS",
                    accountId = targetAccountId
                ),
                MessageEntity(
                    chatId = generatedId,
                    sender = hexagonSender,
                    text = hexagonWelcome,
                    time = timeText,
                    timestamp = baseTime + 1000,
                    isMe = false,
                    status = "delivered",
                    conversationId = aiConvId,
                    idempotencyKey = "init_hexagon_msg_${targetAccountId}_$generatedId",
                    personaId = "HEXAGON",
                    accountId = targetAccountId
                )
            ))
            generatedId
        } else {
            val updated = existingAi.copy(
                name = hexshardName,
                ava = hexshardAva,
                status = "online",
                preview = hexshardPreview,
                recipientId = "ai_assistant",
                conversationId = aiConvId,
                conversationType = com.example.data.ConversationType.AI_ASSISTANT.name,
                accountId = targetAccountId
            )
            chatDao.insertChat(updated)

            val currentMessages = chatDao.getMessagesForChatForAccount(existingAi.id, targetAccountId).first()
            val hasVentaxisWelcome = currentMessages.any { 
                (it.personaId == "VENTAXIS" || it.sender.contains("Ventaxis", ignoreCase = true)) && !it.isMe
            }
            if (!hasVentaxisWelcome) {
                chatDao.insertMessage(
                    MessageEntity(
                        chatId = existingAi.id,
                        sender = ventaxisSender,
                        text = ventaxisWelcome,
                        time = timeText,
                        timestamp = System.currentTimeMillis() - 1000,
                        isMe = false,
                        status = "delivered",
                        conversationId = aiConvId,
                        idempotencyKey = "init_ventaxis_msg_${targetAccountId}_${existingAi.id}",
                        personaId = "VENTAXIS",
                        accountId = targetAccountId
                    )
                )
            }
            val hasHexagonWelcome = currentMessages.any { 
                (it.personaId == "HEXAGON" || it.sender.contains("Hexagon", ignoreCase = true)) && !it.isMe
            }
            if (!hasHexagonWelcome) {
                chatDao.insertMessage(
                    MessageEntity(
                        chatId = existingAi.id,
                        sender = hexagonSender,
                        text = hexagonWelcome,
                        time = timeText,
                        timestamp = System.currentTimeMillis(),
                        isMe = false,
                        status = "delivered",
                        conversationId = aiConvId,
                        idempotencyKey = "init_hexagon_msg_${targetAccountId}_${existingAi.id}",
                        personaId = "HEXAGON",
                        accountId = targetAccountId
                    )
                )
            }
            existingAi.id
        }

        // Clean up redundant duplicate AI chats if any
        val allChatsList = chatDao.getAllChatsForAccount(targetAccountId).first()
        val duplicateAiChats = allChatsList.filter { 
            it.id != aiChatId && (it.conversationType == com.example.data.ConversationType.AI_ASSISTANT.name || it.conversationId == aiConvId)
        }
        for (dup in duplicateAiChats) {
            chatDao.deleteChatById(dup.id, targetAccountId)
        }
    }
}
