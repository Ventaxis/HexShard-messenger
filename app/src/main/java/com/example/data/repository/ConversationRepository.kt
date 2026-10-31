package com.example.data.repository

import android.content.Context
import com.example.data.SecurePrefsManager
import com.example.data.database.ChatDao
import com.example.data.database.ChatEntity
import com.example.data.database.MessageEntity
import com.example.data.database.isAiAssistant
import com.example.data.database.isSavedMessages
import com.example.network.supabase.SupabaseConfig
import com.example.ui.AppLanguage
import com.example.ui.LocalizationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
        val id = chatDao.insertChat(chat)
        if (!chat.isSavedMessages && !chat.isAiAssistant) {
            persistConversationToCloud(chat.accountId, chat)
        }
        id
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
            try {
                chatDao.resetStaleOnlineStatuses()
            } catch (e: Exception) {
                Timber.w(e, "Could not reset stale online statuses")
            }

            // 1. One-time deterministic reconciliation of legacy records with empty accountId
            if (context != null && !SecurePrefsManager.isLegacyMigrationDone(context)) {
                chatDao.backfillLegacyChatsAccountId(targetAccountId)
                chatDao.backfillLegacyMessagesAccountId(targetAccountId)
                chatDao.backfillLegacyOutboxAccountId(targetAccountId)
                SecurePrefsManager.setLegacyMigrationDone(context, true)
            }

            val isRussian = LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN

            // 2. Ensure Saved Messages chat exists in local vault
            var savedChat = chatDao.getSavedMessagesChat(targetAccountId)
            if (savedChat == null) {
                val defaultSaved = ChatEntity(
                    accountId = targetAccountId,
                    name = if (isRussian) "Избранное" else "Saved Messages",
                    ava = if (isRussian) "ИЗ" else "SM",
                    status = "",
                    preview = if (isRussian) "Личное облачное хранилище" else "Personal cloud storage",
                    time = "",
                    timestamp = System.currentTimeMillis(),
                    isGroup = false,
                    isPinned = true,
                    unreadCount = 0,
                    recipientId = "self",
                    conversationId = "self_$targetAccountId",
                    conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name
                )
                val newSavedId = chatDao.upsertChat(defaultSaved).toInt()
                savedChat = defaultSaved.copy(id = newSavedId)

                val welcomeMsg = MessageEntity(
                    accountId = targetAccountId,
                    chatId = newSavedId,
                    sender = if (isRussian) "Избранное" else "Saved Messages",
                    text = if (isRussian) "Добро пожаловать в Избранное! Сохраняйте здесь важные заметки, медиа и ссылки." else "Welcome to Saved Messages! Store important notes, media, and links here.",
                    time = "Только что",
                    timestamp = System.currentTimeMillis(),
                    isMe = false,
                    isAttachment = false,
                    status = "delivered",
                    conversationId = "self_$targetAccountId",
                    idempotencyKey = "init_saved_msg_$targetAccountId"
                )
                chatDao.insertMessageIfNotExists(welcomeMsg)
            } else {
                val msgList = chatDao.getMessagesForChatForAccountList(savedChat.id, targetAccountId)
                if (msgList.isEmpty()) {
                    val welcomeMsg = MessageEntity(
                        accountId = targetAccountId,
                        chatId = savedChat.id,
                        sender = if (isRussian) "Избранное" else "Saved Messages",
                        text = if (isRussian) "Добро пожаловать в Избранное! Сохраняйте здесь важные заметки, медиа и ссылки." else "Welcome to Saved Messages! Store important notes, media, and links here.",
                        time = "Только что",
                        timestamp = System.currentTimeMillis(),
                        isMe = false,
                        isAttachment = false,
                        status = "delivered",
                        conversationId = savedChat.conversationId.ifBlank { "self_$targetAccountId" },
                        idempotencyKey = "init_saved_msg_$targetAccountId"
                    )
                    chatDao.insertMessageIfNotExists(welcomeMsg)
                }
            }

            // 3. Ensure AI Assistant chat exists in local vault
            var aiChat = chatDao.getAiAssistantChat(targetAccountId)
            if (aiChat == null) {
                val defaultAi = ChatEntity(
                    accountId = targetAccountId,
                    name = "Hexagon AI",
                    ava = "HX",
                    status = "",
                    preview = if (isRussian) "Нейроассистент • HexShard / Ventaxis" else "Neural Assistant • HexShard / Ventaxis",
                    time = "",
                    timestamp = System.currentTimeMillis(),
                    isGroup = false,
                    isPinned = false,
                    unreadCount = 0,
                    recipientId = "ai_hexagon",
                    conversationId = "ai_$targetAccountId",
                    conversationType = com.example.data.ConversationType.AI_ASSISTANT.name
                )
                val newAiId = chatDao.upsertChat(defaultAi).toInt()
                aiChat = defaultAi.copy(id = newAiId)

                val hexagonWelcome = MessageEntity(
                    accountId = targetAccountId,
                    chatId = newAiId,
                    sender = "Hexagon AI",
                    text = if (isRussian) "Привет! Я Hexagon AI. Задайте любой вопрос для быстрого ответа!" else "Hello! I am Hexagon AI. Ask me anything for a quick answer!",
                    time = "Только что",
                    timestamp = System.currentTimeMillis() - 1000L,
                    isMe = false,
                    isAttachment = false,
                    status = "delivered",
                    conversationId = "ai_$targetAccountId",
                    personaId = "hexagon",
                    idempotencyKey = "init_ai_welcome_hexagon_$targetAccountId"
                )
                val ventaxisWelcome = MessageEntity(
                    accountId = targetAccountId,
                    chatId = newAiId,
                    sender = "Ventaxis AI",
                    text = if (isRussian) "Привет! Я Ventaxis AI. Чем могу помочь вам сегодня?" else "Hello! I am Ventaxis AI. How can I help you today?",
                    time = "Только что",
                    timestamp = System.currentTimeMillis(),
                    isMe = false,
                    isAttachment = false,
                    status = "delivered",
                    conversationId = "ai_$targetAccountId",
                    personaId = "ventaxis",
                    idempotencyKey = "init_ai_welcome_ventaxis_$targetAccountId"
                )
                chatDao.insertMessageIfNotExists(hexagonWelcome)
                chatDao.insertMessageIfNotExists(ventaxisWelcome)
            } else {
                val aiMessages = chatDao.getMessagesForChatForAccountList(aiChat.id, targetAccountId)
                val hasHexagon = aiMessages.any { it.personaId == "hexagon" || it.sender.contains("Hexagon", ignoreCase = true) }
                val hasVentaxis = aiMessages.any { it.personaId == "ventaxis" || it.sender.contains("Ventaxis", ignoreCase = true) }
                if (!hasHexagon) {
                    val hexagonWelcome = MessageEntity(
                        accountId = targetAccountId,
                        chatId = aiChat.id,
                        sender = "Hexagon AI",
                        text = if (isRussian) "Привет! Я Hexagon AI. Задайте любой вопрос для быстрого ответа!" else "Hello! I am Hexagon AI. Ask me anything for a quick answer!",
                        time = "Только что",
                        timestamp = System.currentTimeMillis() - 1000L,
                        isMe = false,
                        isAttachment = false,
                        status = "delivered",
                        conversationId = aiChat.conversationId.ifBlank { "ai_$targetAccountId" },
                        personaId = "hexagon",
                        idempotencyKey = "init_ai_welcome_hexagon_$targetAccountId"
                    )
                    chatDao.insertMessageIfNotExists(hexagonWelcome)
                }
                if (!hasVentaxis) {
                    val ventaxisWelcome = MessageEntity(
                        accountId = targetAccountId,
                        chatId = aiChat.id,
                        sender = "Ventaxis AI",
                        text = if (isRussian) "Привет! Я Ventaxis AI. Чем могу помочь вам сегодня?" else "Hello! I am Ventaxis AI. How can I help you today?",
                        time = "Только что",
                        timestamp = System.currentTimeMillis(),
                        isMe = false,
                        isAttachment = false,
                        status = "delivered",
                        conversationId = aiChat.conversationId.ifBlank { "ai_$targetAccountId" },
                        personaId = "ventaxis",
                        idempotencyKey = "init_ai_welcome_ventaxis_$targetAccountId"
                    )
                    chatDao.insertMessageIfNotExists(ventaxisWelcome)
                }
            }

            // 4. Synchronize with Supabase Cloud asynchronously if access token is available
            val ctx = context
            val token = ctx?.let { SecurePrefsManager.getSupabaseAccessToken(it) }?.takeIf { it.isNotBlank() }

            if (token != null) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        getOrCreateSavedMessagesConversation()
                    } catch (e: Exception) {
                        Timber.w(e, "Saved messages server sync deferred")
                    }
                    try {
                        getOrCreateAiConversation()
                    } catch (e: Exception) {
                        Timber.w(e, "AI conversation server sync deferred")
                    }
                    try {
                        syncConversationsFromServer(targetAccountId)
                    } catch (e: Exception) {
                        Timber.w(e, "Conversations list server sync deferred: ${e.message}")
                    }
                }
            }

            // 5. Offline cache deduplication: merge duplicate rows if any exist in local vault
            val allSaved = chatDao.getAllSavedMessagesChats(targetAccountId)
            if (allSaved.size > 1) {
                val masterSaved = allSaved.first()
                for (dup in allSaved.drop(1)) {
                    chatDao.reassignMessagesChatId(dup.id, masterSaved.id, targetAccountId)
                    chatDao.deleteChatById(dup.id, targetAccountId)
                }
            }

            val allAi = chatDao.getAllAiChats(targetAccountId)
            if (allAi.size > 1) {
                val primaryAi = allAi.first()
                for (dup in allAi.drop(1)) {
                    chatDao.reassignMessagesChatId(dup.id, primaryAi.id, targetAccountId)
                    chatDao.deleteChatById(dup.id, targetAccountId)
                }
            }

            val allAccountChats = chatDao.getAllChatsListForAccount(targetAccountId)
            val peerGroups = allAccountChats.filter { !it.isSavedMessages && !it.isAiAssistant }
                .groupBy { it.conversationId.ifBlank { it.recipientId } }
            peerGroups.forEach { (convKey, group) ->
                if (convKey.isNotBlank() && group.size > 1) {
                    val masterPeer = group.first()
                    for (dup in group.drop(1)) {
                        chatDao.reassignMessagesChatId(dup.id, masterPeer.id, targetAccountId)
                        chatDao.deleteChatById(dup.id, targetAccountId)
                    }
                }
            }
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Canonical server-authoritative conversation synchronization using get_my_conversations RPC.
     * Restores direct, Saved Messages, and AI conversations, along with membership status.
     */
    suspend fun syncConversationsFromServer(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        val ctx = context ?: return@withContext
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() } ?: return@withContext

        try {
            var rpcUrl = "$baseUrl/rest/v1/rpc/get_my_conversations"
            var req = Request.Builder()
                .url(rpcUrl)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var resp = try { httpClient.newCall(req).execute() } catch (e: Exception) { null }
            var body = resp?.body?.string().orEmpty()
            var isSuccess = resp?.isSuccessful == true
            resp?.close()

            // Graceful fallback to sync_conversations_for_user if get_my_conversations is unavailable
            if (!isSuccess) {
                rpcUrl = "$baseUrl/rest/v1/rpc/sync_conversations_for_user"
                req = Request.Builder()
                    .url(rpcUrl)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                resp = try { httpClient.newCall(req).execute() } catch (e: Exception) { null }
                body = resp?.body?.string().orEmpty()
                isSuccess = resp?.isSuccessful == true
                resp?.close()
            }

            if (!isSuccess) {
                Timber.w("Server conversations RPC returned error: $body")
                return@withContext
            }

            if (body.isNotBlank()) {
                val array = try { JSONArray(body) } catch (_: Exception) { JSONArray() }
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val convId = obj.optString("id")
                    if (convId.isBlank()) continue

                    val convType = obj.optString("type", "DIRECT").uppercase()
                    val title = obj.optString("title", "")
                    val isPinned = obj.optBoolean("is_pinned", false)
                    val membersArr = obj.optJSONArray("members")
                    val lastMsgObj = obj.optJSONObject("last_message")

                    var resolvedRecipientId = ""
                    var resolvedName = title
                    var resolvedAva = ""

                    when (convType) {
                        "SAVED_MESSAGES" -> {
                            resolvedRecipientId = "self"
                            val isRussian = LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN
                            resolvedName = if (title.isNotBlank()) title else if (isRussian) "Избранное" else "Saved Messages"
                            resolvedAva = if (isRussian) "ИЗ" else "SM"
                        }
                        "AI" -> {
                            resolvedRecipientId = "ai_hexagon"
                            resolvedName = if (title.isNotBlank()) title else "HexShard AI"
                            resolvedAva = "HX"
                        }
                        else -> {
                            // DIRECT / GROUP
                            if (membersArr != null) {
                                for (mIdx in 0 until membersArr.length()) {
                                    val mObj = membersArr.getJSONObject(mIdx)
                                    val mAccId = mObj.optString("account_id")
                                    if (mAccId != userId && mAccId.isNotBlank()) {
                                        resolvedRecipientId = mAccId
                                        val mDisplayName = mObj.optString("display_name", "")
                                        val mUsername = mObj.optString("username", "")
                                        resolvedName = when {
                                            title.isNotBlank() -> title
                                            mDisplayName.isNotBlank() -> mDisplayName
                                            mUsername.isNotBlank() -> mUsername
                                            else -> "User"
                                        }
                                        val rawAva = mObj.optString("avatar_url", "")
                                        resolvedAva = if (rawAva.isNotBlank() && !rawAva.startsWith("http")) {
                                            "$baseUrl/storage/v1/object/public/avatars/$rawAva"
                                        } else rawAva
                                        break
                                    }
                                }
                            }
                        }
                    }

                    if (resolvedRecipientId.isNotBlank()) {
                        val existing = chatDao.getChatByConversationId(convId, userId)
                            ?: chatDao.getChatByRecipientId(resolvedRecipientId, userId)

                        val previewText = when {
                            lastMsgObj != null -> {
                                val encVer = lastMsgObj.optInt("encryption_version", 2)
                                val rawText = lastMsgObj.optString("text", "")
                                if (encVer == 0) rawText else "Зашифрованное сообщение"
                            }
                            else -> existing?.preview.orEmpty()
                        }
                        val timeStr = lastMsgObj?.optString("time_str") ?: existing?.time.orEmpty()

                        val chatToUpsert = if (existing != null) {
                            existing.copy(
                                conversationId = convId,
                                name = if (resolvedName.isNotBlank()) resolvedName else existing.name,
                                ava = if (resolvedAva.isNotBlank()) resolvedAva else existing.ava,
                                isPinned = isPinned,
                                conversationType = convType,
                                preview = if (previewText.isNotBlank()) previewText else existing.preview,
                                time = if (timeStr.isNotBlank()) timeStr else existing.time
                            )
                        } else {
                            ChatEntity(
                                accountId = userId,
                                name = resolvedName.ifBlank { "Chat" },
                                ava = resolvedAva.ifBlank { resolvedName.take(2).uppercase() },
                                status = "offline",
                                preview = previewText,
                                time = timeStr,
                                timestamp = System.currentTimeMillis(),
                                isGroup = convType == "GROUP",
                                isPinned = isPinned,
                                unreadCount = 0,
                                recipientId = resolvedRecipientId,
                                conversationId = convId,
                                conversationType = convType
                            )
                        }
                        chatDao.upsertChat(chatToUpsert)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "syncConversationsFromServer error: ${e.message}")
        }
    }

    suspend fun getOrCreateDirectConversation(peerId: String, title: String = ""): Result<ChatEntity> = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentAccountId()
        if (currentUserId.isBlank()) return@withContext Result.failure(IllegalStateException("User is not authenticated"))
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("Context unavailable"))
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("Access token missing"))

        try {
            val rpcUrl = "$baseUrl/rest/v1/rpc/get_or_create_direct_conversation"
            val payload = JSONObject().apply {
                put("p_peer_id", peerId)
                if (title.isNotBlank()) put("p_title", title)
            }
            val req = Request.Builder()
                .url(rpcUrl)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            val isSuccess = resp.isSuccessful
            val code = resp.code
            resp.close()

            if (isSuccess && body.isNotBlank()) {
                val obj = JSONObject(body)
                val convId = obj.getString("id")
                val recipientId = obj.optString("recipient_id", peerId)
                val resolvedName = obj.optString("recipient_display_name", title.ifBlank { "User" })
                val rawAva = obj.optString("recipient_avatar_url", "")
                val resolvedAva = if (rawAva.isNotBlank() && !rawAva.startsWith("http")) {
                    "$baseUrl/storage/v1/object/public/avatars/$rawAva"
                } else rawAva

                val existing = chatDao.getChatByConversationId(convId, currentUserId)
                    ?: chatDao.getChatByRecipientId(recipientId, currentUserId)

                val chatToUpsert = if (existing != null) {
                    existing.copy(
                        conversationId = convId,
                        recipientId = recipientId,
                        name = if (resolvedName.isNotBlank()) resolvedName else existing.name,
                        ava = if (resolvedAva.isNotBlank()) resolvedAva else existing.ava
                    )
                } else {
                    ChatEntity(
                        accountId = currentUserId,
                        name = resolvedName,
                        ava = if (resolvedAva.isNotBlank()) resolvedAva else resolvedName.take(2).uppercase(),
                        status = "offline",
                        preview = "",
                        time = "",
                        timestamp = System.currentTimeMillis(),
                        isGroup = false,
                        isPinned = false,
                        unreadCount = 0,
                        recipientId = recipientId,
                        conversationId = convId,
                        conversationType = com.example.data.ConversationType.DIRECT.name
                    )
                }
                val insertedId = chatDao.upsertChat(chatToUpsert).toInt()
                val finalChat = if (insertedId > 0) chatToUpsert.copy(id = insertedId) else chatDao.getChatByConversationId(convId, currentUserId) ?: chatToUpsert
                Result.success(finalChat)
            } else {
                Timber.w("Server get_or_create_direct_conversation returned HTTP $code: $body; falling back to deterministic local conversation")
                createDeterministicLocalDirectChat(currentUserId, peerId, title)
            }
        } catch (e: Exception) {
            Timber.e(e, "getOrCreateDirectConversation error; falling back to deterministic local conversation")
            createDeterministicLocalDirectChat(currentUserId, peerId, title)
        }
    }

    private suspend fun createDeterministicLocalDirectChat(
        currentUserId: String,
        peerId: String,
        title: String
    ): Result<ChatEntity> {
        val convId = computeConversationId(currentUserId, peerId)
        val resolvedName = title.ifBlank { peerId }
        val resolvedAva = resolvedName.take(2).uppercase()

        val existing = chatDao.getChatByConversationId(convId, currentUserId)
            ?: chatDao.getChatByRecipientId(peerId, currentUserId)

        val chatToUpsert = if (existing != null) {
            existing.copy(
                conversationId = convId,
                recipientId = peerId,
                name = if (resolvedName.isNotBlank()) resolvedName else existing.name,
                ava = if (existing.ava.isNotBlank()) existing.ava else resolvedAva
            )
        } else {
            ChatEntity(
                accountId = currentUserId,
                name = resolvedName,
                ava = resolvedAva,
                status = "offline",
                preview = "E2E encryption channel opened",
                time = "",
                timestamp = System.currentTimeMillis(),
                isGroup = false,
                isPinned = false,
                unreadCount = 0,
                recipientId = peerId,
                conversationId = convId,
                conversationType = com.example.data.ConversationType.DIRECT.name
            )
        }
        val insertedId = chatDao.upsertChat(chatToUpsert).toInt()
        val finalChat = if (insertedId > 0) chatToUpsert.copy(id = insertedId) else chatDao.getChatByConversationId(convId, currentUserId) ?: chatToUpsert
        persistConversationToCloud(currentUserId, finalChat)
        return Result.success(finalChat)
    }

    suspend fun getOrCreateSavedMessagesConversation(): Result<ChatEntity> = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentAccountId()
        if (currentUserId.isBlank()) return@withContext Result.failure(IllegalStateException("User is not authenticated"))
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("Context unavailable"))
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() }

        val isRussian = LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN
        val savedName = if (isRussian) "Избранное" else "Saved Messages"
        val savedAva = if (isRussian) "ИЗ" else "SM"

        if (token != null) {
            try {
                val rpcUrl = "$baseUrl/rest/v1/rpc/get_or_create_saved_messages_conversation"
                val req = Request.Builder()
                    .url(rpcUrl)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                val isSuccess = resp.isSuccessful
                resp.close()

                if (isSuccess && body.isNotBlank()) {
                    val obj = JSONObject(body)
                    val convId = obj.getString("id")

                    val existing = chatDao.getChatByConversationId(convId, currentUserId)
                        ?: chatDao.getSavedMessagesChat(currentUserId)

                    val chatToUpsert = if (existing != null) {
                        existing.copy(
                            conversationId = convId,
                            recipientId = "self",
                            name = savedName,
                            ava = savedAva,
                            isPinned = true,
                            conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name
                        )
                    } else {
                        ChatEntity(
                            accountId = currentUserId,
                            name = savedName,
                            ava = savedAva,
                            status = "",
                            preview = if (isRussian) "Личное облачное хранилище" else "Personal cloud storage",
                            time = "",
                            timestamp = System.currentTimeMillis(),
                            isGroup = false,
                            isPinned = true,
                            unreadCount = 0,
                            recipientId = "self",
                            conversationId = convId,
                            conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name
                        )
                    }
                    val insertedId = chatDao.upsertChat(chatToUpsert).toInt()
                    val finalChat = if (insertedId > 0) chatToUpsert.copy(id = insertedId) else chatDao.getChatByConversationId(convId, currentUserId) ?: chatToUpsert
                    return@withContext Result.success(finalChat)
                }
            } catch (e: Exception) {
                Timber.w(e, "getOrCreateSavedMessagesConversation remote call deferred")
            }
        }

        // Local fallback
        val convId = "self_$currentUserId"
        val existing = chatDao.getChatByConversationId(convId, currentUserId)
            ?: chatDao.getSavedMessagesChat(currentUserId)
        val chatToUpsert = if (existing != null) {
            existing.copy(
                conversationId = convId,
                recipientId = "self",
                name = savedName,
                ava = savedAva,
                isPinned = true,
                conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name
            )
        } else {
            ChatEntity(
                accountId = currentUserId,
                name = savedName,
                ava = savedAva,
                status = "",
                preview = if (isRussian) "Личное облачное хранилище" else "Personal cloud storage",
                time = "",
                timestamp = System.currentTimeMillis(),
                isGroup = false,
                isPinned = true,
                unreadCount = 0,
                recipientId = "self",
                conversationId = convId,
                conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name
            )
        }
        val insertedId = chatDao.upsertChat(chatToUpsert).toInt()
        val finalChat = if (insertedId > 0) chatToUpsert.copy(id = insertedId) else chatDao.getChatByConversationId(convId, currentUserId) ?: chatToUpsert
        Result.success(finalChat)
    }

    suspend fun getOrCreateAiConversation(): Result<ChatEntity> = withContext(Dispatchers.IO) {
        val currentUserId = getCurrentAccountId()
        if (currentUserId.isBlank()) return@withContext Result.failure(IllegalStateException("User is not authenticated"))
        val ctx = context ?: return@withContext Result.failure(IllegalStateException("Context unavailable"))
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() }

        val isRussian = LocalizationManager.currentLanguage.value == AppLanguage.RUSSIAN
        val aiName = "Hexagon AI"
        val aiAva = "HX"
        val aiPreview = if (isRussian) "Нейроассистент • HexShard / Ventaxis" else "Neural Assistant • HexShard / Ventaxis"

        if (token != null) {
            try {
                val rpcUrl = "$baseUrl/rest/v1/rpc/get_or_create_ai_conversation"
                val req = Request.Builder()
                    .url(rpcUrl)
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                val isSuccess = resp.isSuccessful
                resp.close()

                if (isSuccess && body.isNotBlank()) {
                    val obj = JSONObject(body)
                    val convId = obj.getString("id")

                    val existing = chatDao.getChatByConversationId(convId, currentUserId)
                        ?: chatDao.getAiAssistantChat(currentUserId)

                    val chatToUpsert = if (existing != null) {
                        existing.copy(
                            conversationId = convId,
                            recipientId = "ai_hexagon",
                            name = aiName,
                            ava = aiAva,
                            preview = existing.preview.ifBlank { aiPreview },
                            conversationType = com.example.data.ConversationType.AI_ASSISTANT.name
                        )
                    } else {
                        ChatEntity(
                            accountId = currentUserId,
                            name = aiName,
                            ava = aiAva,
                            status = "",
                            preview = aiPreview,
                            time = "",
                            timestamp = System.currentTimeMillis(),
                            isGroup = false,
                            isPinned = false,
                            unreadCount = 0,
                            recipientId = "ai_hexagon",
                            conversationId = convId,
                            conversationType = com.example.data.ConversationType.AI_ASSISTANT.name
                        )
                    }
                    val insertedId = chatDao.upsertChat(chatToUpsert).toInt()
                    val finalChat = if (insertedId > 0) chatToUpsert.copy(id = insertedId) else chatDao.getChatByConversationId(convId, currentUserId) ?: chatToUpsert
                    return@withContext Result.success(finalChat)
                }
            } catch (e: Exception) {
                Timber.w(e, "getOrCreateAiConversation remote call deferred")
            }
        }

        // Local fallback
        val convId = "ai_$currentUserId"
        val existing = chatDao.getChatByConversationId(convId, currentUserId)
            ?: chatDao.getAiAssistantChat(currentUserId)
        val chatToUpsert = if (existing != null) {
            existing.copy(
                conversationId = convId,
                recipientId = "ai_hexagon",
                name = aiName,
                ava = aiAva,
                preview = existing.preview.ifBlank { aiPreview },
                conversationType = com.example.data.ConversationType.AI_ASSISTANT.name
            )
        } else {
            ChatEntity(
                accountId = currentUserId,
                name = aiName,
                ava = aiAva,
                status = "",
                preview = aiPreview,
                time = "",
                timestamp = System.currentTimeMillis(),
                isGroup = false,
                isPinned = false,
                unreadCount = 0,
                recipientId = "ai_hexagon",
                conversationId = convId,
                conversationType = com.example.data.ConversationType.AI_ASSISTANT.name
            )
        }
        val insertedId = chatDao.upsertChat(chatToUpsert).toInt()
        val finalChat = if (insertedId > 0) chatToUpsert.copy(id = insertedId) else chatDao.getChatByConversationId(convId, currentUserId) ?: chatToUpsert
        Result.success(finalChat)
    }

    suspend fun createOrGetConversationRemote(
        type: String,
        recipientId: String,
        title: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val ctx = context ?: return@withContext null
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() }
            ?: return@withContext null

        try {
            val rpcUrl = "$baseUrl/rest/v1/rpc/create_or_get_conversation"
            val payload = JSONObject().apply {
                put("p_type", type)
                put("p_recipient_id", recipientId)
                if (title.isNotBlank()) put("p_title", title)
            }
            val req = Request.Builder()
                .url(rpcUrl)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            val isSuccess = resp.isSuccessful
            resp.close()

            if (isSuccess && body.isNotBlank()) {
                val clean = body.trim().removeSurrounding("\"")
                clean.ifBlank { null }
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "createOrGetConversationRemote error")
            null
        }
    }

    suspend fun persistConversationToCloud(userId: String, chat: ChatEntity) = withContext(Dispatchers.IO) {
        if (userId.isBlank() || chat.isSavedMessages || chat.isAiAssistant) return@withContext
        val returnedId = createOrGetConversationRemote(chat.conversationType, chat.recipientId, chat.name)
        if (returnedId != null) {
            if (returnedId != chat.conversationId) {
                chatDao.updateChat(chat.copy(conversationId = returnedId))
            }
        } else {
            Timber.w("Could not immediately persist conversation to cloud; queueing in durable outbox")
            val payload = JSONObject().apply {
                put("p_type", chat.conversationType)
                put("p_recipient_id", chat.recipientId)
                if (chat.name.isNotBlank()) put("p_title", chat.name)
            }
            chatDao.insertOutbox(
                com.example.data.database.OutboxEntity(
                    accountId = userId,
                    localMessageId = 0,
                    chatId = chat.id,
                    conversationId = chat.conversationId,
                    idempotencyKey = "create_conv_${chat.conversationId}_$userId",
                    senderId = userId,
                    recipientId = chat.recipientId,
                    payload = payload.toString(),
                    signature = "",
                    type = "create_conversation",
                    timeStr = chat.time,
                    attempts = 0
                )
            )
        }
    }
}
