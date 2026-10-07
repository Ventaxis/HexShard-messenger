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
        try {
            chatDao.resetStaleOnlineStatuses()
        } catch (e: Exception) {
            Timber.w(e, "Could not reset stale online statuses")
        }

        val targetAccountId = if (accountId.isNotBlank()) accountId else getCurrentAccountId()
        if (targetAccountId.isBlank()) {
            return@withContext
        }
        
        defaultChatsMutex.withLock {
            // 1. One-time deterministic reconciliation of legacy records with empty accountId
            if (context != null && !SecurePrefsManager.isLegacyMigrationDone(context)) {
                chatDao.backfillLegacyChatsAccountId(targetAccountId)
                chatDao.backfillLegacyMessagesAccountId(targetAccountId)
                chatDao.backfillLegacyOutboxAccountId(targetAccountId)
                SecurePrefsManager.setLegacyMigrationDone(context, true)
            }

            // Ensure stale online statuses are cleared so offline contacts aren't falsely shown as online
            chatDao.resetStaleOnlineStatuses()

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
                chatDao.updateChat(updated)
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
                chatDao.updateChat(updated)
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

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun syncConversationsFromServer(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        ensureDefaultChatsExist(userId)
        val ctx = context ?: return@withContext
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() } ?: return@withContext

        try {
            val filter = "or=(participant1.eq.$userId,participant2.eq.$userId)"
            val url = "$baseUrl/rest/v1/conversations?$filter&select=*&order=updated_at.desc"
            val req = Request.Builder()
                .url(url)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val resp = try { httpClient.newCall(req).execute() } catch (e: Exception) { null }
            val body = resp?.body?.string() ?: ""
            val isSuccess = resp?.isSuccessful == true
            resp?.close()

            if (isSuccess && body.isNotBlank()) {
                val array = try { JSONArray(body) } catch (_: Exception) { JSONArray() }
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val convId = obj.optString("id")
                    val convType = obj.optString("type", "DIRECT")
                    val p1 = obj.optString("participant1")
                    val p2 = obj.optString("participant2")
                    val peerId = if (p1 == userId) p2 else p1

                    if (peerId.isNotBlank() && peerId != "null") {
                        val existing = chatDao.getChatByConversationId(convId, userId) ?: chatDao.getChatByRecipientId(peerId, userId)
                        if (existing == null) {
                            fetchAndInsertPeerChat(baseUrl, anonKey, token, userId, peerId, convId, convType)
                        }
                    }
                }
            }

            // Sync from Supabase Auth server metadata (survives app reinstallation even without conversations table)
            try {
                val authReq = Request.Builder()
                    .url("$baseUrl/auth/v1/user")
                    .header("apikey", anonKey)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                val authResp = httpClient.newCall(authReq).execute()
                val authBody = authResp.body?.string() ?: ""
                authResp.close()
                if (authResp.isSuccessful && authBody.isNotBlank()) {
                    val userMeta = JSONObject(authBody).optJSONObject("user_metadata")
                    val convArray = userMeta?.optJSONArray("conversations")
                    if (convArray != null) {
                        for (i in 0 until convArray.length()) {
                            val cObj = convArray.getJSONObject(i)
                            val convId = cObj.optString("id")
                            val peerId = cObj.optString("peer_id")
                            val convType = cObj.optString("type", "DIRECT")
                            if (peerId.isNotBlank() && peerId != "null") {
                                val existing = chatDao.getChatByConversationId(convId, userId) ?: chatDao.getChatByRecipientId(peerId, userId)
                                if (existing == null) {
                                    fetchAndInsertPeerChat(baseUrl, anonKey, token, userId, peerId, convId, convType)
                                }
                            }
                        }
                    }
                }
            } catch (authEx: Exception) {
                Timber.w(authEx, "Non-fatal: could not sync conversations from auth user_metadata")
            }
        } catch (e: Exception) {
            Timber.w(e, "syncConversationsFromServer error: ${e.message}")
        }
    }

    private suspend fun fetchAndInsertPeerChat(
        baseUrl: String,
        anonKey: String,
        token: String,
        userId: String,
        peerId: String,
        convId: String,
        convType: String
    ) = withContext(Dispatchers.IO) {
        val pUrl = "$baseUrl/rest/v1/profiles?id=eq.$peerId&select=id,username,display_name,avatar_path,avatar_url&limit=1"
        val pReq = Request.Builder()
            .url(pUrl)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val pResp = try { httpClient.newCall(pReq).execute() } catch (_: Exception) { null }
        var peerName = "User ${peerId.take(4)}"
        var peerAva = peerName.take(2).uppercase()
        var avatarUrl = ""

        if (pResp != null && pResp.isSuccessful) {
            val pBody = pResp.body?.string() ?: ""
            pResp.close()
            val pArr = try { JSONArray(pBody) } catch (_: Exception) { JSONArray() }
            if (pArr.length() > 0) {
                val pObj = pArr.getJSONObject(0)
                val uName = pObj.optString("username", "")
                val dName = pObj.optString("display_name", "")
                peerName = if (dName.isNotBlank()) dName else if (uName.isNotBlank()) uName else peerName
                peerAva = peerName.take(2).uppercase()
                val rawAva = pObj.optString("avatar_url", pObj.optString("avatar_path", ""))
                if (rawAva.isNotBlank()) {
                    avatarUrl = if (rawAva.startsWith("http")) rawAva else "$baseUrl/storage/v1/object/public/avatars/$rawAva"
                }
            }
        } else {
            pResp?.close()
        }

        val newChat = ChatEntity(
            accountId = userId,
            name = peerName,
            ava = if (avatarUrl.isNotBlank()) avatarUrl else peerAva,
            status = "offline",
            preview = "",
            time = "",
            isGroup = false,
            recipientId = peerId,
            conversationId = convId,
            conversationType = convType
        )
        chatDao.insertChat(newChat)
    }

    suspend fun persistConversationToCloud(userId: String, chat: ChatEntity) = withContext(Dispatchers.IO) {
        if (userId.isBlank() || chat.isSavedMessages || chat.isAiAssistant) return@withContext
        val ctx = context ?: return@withContext
        val baseUrl = SupabaseConfig.getBaseUrl()
        val anonKey = SupabaseConfig.getAnonKey(ctx)
        val token = SecurePrefsManager.getSupabaseAccessToken(ctx).takeIf { it.isNotBlank() } ?: return@withContext

        try {
            // Retrieve current conversations from user_metadata
            val authReq = Request.Builder()
                .url("$baseUrl/auth/v1/user")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val authResp = httpClient.newCall(authReq).execute()
            val authBody = authResp.body?.string() ?: ""
            authResp.close()

            val existingArray = if (authResp.isSuccessful && authBody.isNotBlank()) {
                val userMeta = JSONObject(authBody).optJSONObject("user_metadata")
                userMeta?.optJSONArray("conversations") ?: JSONArray()
            } else JSONArray()

            val newArray = JSONArray()
            var exists = false
            for (i in 0 until existingArray.length()) {
                val item = existingArray.getJSONObject(i)
                if (item.optString("peer_id") == chat.recipientId || item.optString("id") == chat.conversationId) {
                    exists = true
                }
                newArray.put(item)
            }
            if (!exists && chat.recipientId.isNotBlank()) {
                newArray.put(JSONObject().apply {
                    put("id", chat.conversationId)
                    put("peer_id", chat.recipientId)
                    put("name", chat.name)
                    put("type", chat.conversationType)
                    put("created_at", System.currentTimeMillis())
                })
            }

            val updatePayload = JSONObject().apply {
                put("data", JSONObject().apply {
                    put("conversations", newArray)
                })
            }
            val updateReq = Request.Builder()
                .url("$baseUrl/auth/v1/user")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .put(updatePayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(updateReq).execute().close()
        } catch (e: Exception) {
            Timber.w(e, "Could not persist conversation to Supabase Auth metadata")
        }
    }
}
