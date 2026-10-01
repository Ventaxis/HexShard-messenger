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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

class ConversationRepository(
    private val chatDao: ChatDao,
    private val context: Context? = null
) {
    private val defaultChatsMutex = Mutex()

    fun getCurrentAccountId(): String {
        return context?.let { SecurePrefsManager.getUserId(it) } ?: ""
    }

    private val _currentAccountId = MutableStateFlow(getCurrentAccountId())
    val currentAccountId: StateFlow<String> = _currentAccountId.asStateFlow()

    fun refreshAccount() {
        val newId = getCurrentAccountId()
        _currentAccountId.value = newId
    }

    fun deduplicateChatList(rawList: List<ChatEntity>): List<ChatEntity> {
        val result = mutableListOf<ChatEntity>()
        var savedSeen = false
        var aiSeen = false
        val seenPeerKeys = mutableSetOf<String>()

        for (chat in rawList) {
            when {
                chat.isSavedMessages -> {
                    if (!savedSeen) {
                        savedSeen = true
                        result.add(chat)
                    }
                }
                chat.isAiAssistant || chat.recipientId == "ai_hexagon" || chat.name.contains("Hexagon", ignoreCase = true) || chat.name.contains("Ventaxis", ignoreCase = true) -> {
                    if (!aiSeen) {
                        aiSeen = true
                        result.add(chat)
                    }
                }
                else -> {
                    val key = chat.conversationId.ifBlank { chat.recipientId.ifBlank { chat.id.toString() } }
                    if (seenPeerKeys.add(key)) {
                        result.add(chat)
                    }
                }
            }
        }
        return result
    }

    val allChats: Flow<List<ChatEntity>> = _currentAccountId
        .flatMapLatest { accountId ->
            flow {
                if (accountId.isNotBlank()) {
                    ensureDefaultChatsExist(accountId)
                    emitAll(
                        chatDao.getAllChatsForAccount(accountId).map { rawList ->
                            deduplicateChatList(rawList)
                        }
                    )
                } else {
                    emit(emptyList())
                }
            }
        }
        .flowOn(Dispatchers.IO)

    fun getChatsForAccount(accountId: String = getCurrentAccountId()): Flow<List<ChatEntity>> =
        if (accountId.isNotBlank()) {
            chatDao.getAllChatsForAccount(accountId)
                .map { rawList -> deduplicateChatList(rawList) }
                .flowOn(Dispatchers.Default)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }

    fun computeConversationId(myUid: String, recipientId: String): String {
        if (recipientId.isBlank() || recipientId == "me" || recipientId == "self" || recipientId.startsWith("self_")) {
            return "self_${myUid.ifBlank { "unknown" }}"
        }
        if (recipientId == "ai_hexagon" || recipientId == "hexagon" || recipientId == "ai_ventaxis" || recipientId == "ventaxis" || recipientId == "ai_assistant" || recipientId.startsWith("ai_")) {
            return "ai_hexagon_${myUid.ifBlank { "unknown" }}"
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
        
        defaultChatsMutex.withLock {
            // 1. Backfill any legacy records with empty accountId to targetAccountId
            chatDao.backfillLegacyChatsAccountId(targetAccountId)
            chatDao.backfillLegacyMessagesAccountId(targetAccountId)
            chatDao.backfillLegacyOutboxAccountId(targetAccountId)

            val isRussian = LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN
            val savedName = if (isRussian) "Избранное" else "Saved Messages"
            val savedAva = if (isRussian) "ИЗ" else "SM"
            val welcomeText = if (isRussian) "Добро пожаловать в Избранное!" else "Welcome to Saved Messages!"

            val ventaxisName = "Ventaxis AI"
            val ventaxisSender = "Ventaxis AI"
            val ventaxisWelcome = if (isRussian) "Привет! Я Ventaxis AI. Чем могу помочь вам сегодня?" else "Hello! I am Ventaxis AI. How can I help you today?"

            val hexagonName = "Hexagon AI"
            val hexagonAva = "HX"
            val hexagonPreview = if (isRussian) "Нейроассистент • HexShard / Ventaxis" else "Neural Assistant • HexShard / Ventaxis"
            val hexagonSender = "HexShard AI"
            val hexagonWelcome = if (isRussian) "Привет! Я HexShard AI. Задайте любой вопрос для быстрого ответа!" else "Hello! I am HexShard AI. Ask me anything for a rapid answer!"
            val hexagonConvId = "ai_hexagon_$targetAccountId"

            val timeText = if (isRussian) "Только что" else "Just now"
            val selfConvId = "self_$targetAccountId"

            // --- 1. CLEAN UP & DEDUPLICATE ALL SAVED MESSAGES CHATS ---
            val allSaved = chatDao.getAllSavedMessagesChats(targetAccountId)
            val savedChatId = if (allSaved.isEmpty()) {
                Timber.d("Creating default Saved Messages for account $targetAccountId")
                val newChat = ChatEntity(
                    id = 0,
                    name = savedName,
                    ava = savedAva,
                    status = "",
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
                val masterSaved = allSaved.first()
                if (allSaved.size > 1) {
                    Timber.w("Found ${allSaved.size} duplicate Saved Messages chats! Deduplicating into master id=${masterSaved.id}")
                    for (dup in allSaved.drop(1)) {
                        chatDao.reassignMessagesChatId(dup.id, masterSaved.id, targetAccountId)
                        chatDao.deleteChatById(dup.id, targetAccountId)
                    }
                }
                val updated = masterSaved.copy(
                    name = savedName,
                    ava = savedAva,
                    status = "",
                    conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name,
                    accountId = targetAccountId,
                    recipientId = "self",
                    conversationId = selfConvId,
                    isPinned = true
                )
                chatDao.insertChat(updated)
                val msgs = chatDao.getMessagesForChatForAccount(masterSaved.id, targetAccountId).first()
                if (msgs.isEmpty()) {
                    chatDao.insertMessage(
                        MessageEntity(
                            chatId = masterSaved.id,
                            sender = "me",
                            text = welcomeText,
                            time = timeText,
                            timestamp = System.currentTimeMillis(),
                            isMe = true,
                            status = "delivered",
                            conversationId = selfConvId,
                            idempotencyKey = "init_saved_msg_${targetAccountId}_${masterSaved.id}",
                            accountId = targetAccountId
                        )
                    )
                }
                masterSaved.id
            }

            // --- 2. CLEAN UP & DEDUPLICATE ALL AI CHATS INTO A SINGLE HEXAGON AI CHAT ---
            val allAi = chatDao.getAllAiChats(targetAccountId)
            val primaryAi = allAi.find { it.recipientId == "ai_hexagon" || it.name.contains("Hexagon", ignoreCase = true) } ?: allAi.firstOrNull()

            val hexagonChatId = if (primaryAi == null) {
                Timber.d("Creating unified Hexagon AI chat for account $targetAccountId")
                val newChat = ChatEntity(
                    id = 0,
                    name = hexagonName,
                    ava = hexagonAva,
                    status = "",
                    preview = hexagonPreview,
                    time = timeText,
                    isGroup = false,
                    isPinned = false,
                    recipientId = "ai_hexagon",
                    conversationId = hexagonConvId,
                    conversationType = com.example.data.ConversationType.AI_ASSISTANT.name,
                    accountId = targetAccountId
                )
                val generatedId = chatDao.insertChat(newChat).toInt()
                chatDao.insertMessage(
                    MessageEntity(
                        chatId = generatedId,
                        sender = hexagonSender,
                        text = hexagonWelcome,
                        time = timeText,
                        timestamp = System.currentTimeMillis(),
                        isMe = false,
                        status = "delivered",
                        conversationId = hexagonConvId,
                        idempotencyKey = "init_hexagon_msg_${targetAccountId}_$generatedId",
                        personaId = "hexagon",
                        accountId = targetAccountId
                    )
                )
                chatDao.insertMessage(
                    MessageEntity(
                        chatId = generatedId,
                        sender = ventaxisSender,
                        text = ventaxisWelcome,
                        time = timeText,
                        timestamp = System.currentTimeMillis() + 10,
                        isMe = false,
                        status = "delivered",
                        conversationId = hexagonConvId,
                        idempotencyKey = "init_ventaxis_msg_${targetAccountId}_$generatedId",
                        personaId = "ventaxis",
                        accountId = targetAccountId
                    )
                )
                generatedId
            } else {
                if (allAi.size > 1) {
                    Timber.w("Found ${allAi.size} AI chats! Deduplicating into unified master id=${primaryAi.id}")
                    for (dup in allAi) {
                        if (dup.id != primaryAi.id) {
                            chatDao.reassignMessagesChatId(dup.id, primaryAi.id, targetAccountId)
                            chatDao.deleteChatById(dup.id, targetAccountId)
                        }
                    }
                }
                val updated = primaryAi.copy(
                    name = hexagonName,
                    ava = hexagonAva,
                    status = "",
                    preview = hexagonPreview,
                    recipientId = "ai_hexagon",
                    conversationId = hexagonConvId,
                    conversationType = com.example.data.ConversationType.AI_ASSISTANT.name,
                    accountId = targetAccountId
                )
                chatDao.insertChat(updated)
                val msgs = chatDao.getMessagesForChatForAccount(primaryAi.id, targetAccountId).first()
                if (msgs.none { it.personaId == "hexagon" }) {
                    chatDao.insertMessage(
                        MessageEntity(
                            chatId = primaryAi.id,
                            sender = hexagonSender,
                            text = hexagonWelcome,
                            time = timeText,
                            timestamp = System.currentTimeMillis(),
                            isMe = false,
                            status = "delivered",
                            conversationId = hexagonConvId,
                            idempotencyKey = "init_hexagon_msg_${targetAccountId}_${primaryAi.id}",
                            personaId = "hexagon",
                            accountId = targetAccountId
                        )
                    )
                }
                if (msgs.none { it.personaId == "ventaxis" }) {
                    chatDao.insertMessage(
                        MessageEntity(
                            chatId = primaryAi.id,
                            sender = ventaxisSender,
                            text = ventaxisWelcome,
                            time = timeText,
                            timestamp = System.currentTimeMillis() + 10,
                            isMe = false,
                            status = "delivered",
                            conversationId = hexagonConvId,
                            idempotencyKey = "init_ventaxis_msg_${targetAccountId}_${primaryAi.id}",
                            personaId = "ventaxis",
                            accountId = targetAccountId
                        )
                    )
                }
                primaryAi.id
            }

            // --- 3. DEDUPLICATE ANY PEER / DIRECT CHATS ---
            val allAccountChats = chatDao.getAllChatsListForAccount(targetAccountId)
            val peerGroups = allAccountChats.filter { !it.isSavedMessages && !it.isAiAssistant }
                .groupBy { it.conversationId.ifBlank { it.recipientId } }
            peerGroups.forEach { (convKey, group) ->
                if (convKey.isNotBlank() && group.size > 1) {
                    val masterPeer = group.first()
                    Timber.w("Found ${group.size} duplicate peer chats for key '$convKey'! Deduplicating into master id=${masterPeer.id}")
                    for (dup in group.drop(1)) {
                        chatDao.reassignMessagesChatId(dup.id, masterPeer.id, targetAccountId)
                        chatDao.deleteChatById(dup.id, targetAccountId)
                    }
                }
            }
        }
    }
}
