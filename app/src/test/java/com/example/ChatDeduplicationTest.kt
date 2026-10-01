package com.example

import com.example.data.database.ChatDao
import com.example.data.database.ChatEntity
import com.example.data.database.MessageEntity
import com.example.data.database.OutboxEntity
import com.example.data.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeChatDao : ChatDao {
    override fun getAllChatsForAccount(accountId: String): Flow<List<ChatEntity>> = flowOf(emptyList())
    override suspend fun getSavedMessagesChat(accountId: String): ChatEntity? = null
    override suspend fun getAllSavedMessagesChats(accountId: String): List<ChatEntity> = emptyList()
    override suspend fun getAiAssistantChat(accountId: String): ChatEntity? = null
    override suspend fun getVentaxisChat(accountId: String): ChatEntity? = null
    override suspend fun getHexagonChat(accountId: String): ChatEntity? = null
    override suspend fun getAllAiChats(accountId: String): List<ChatEntity> = emptyList()
    override suspend fun getAllChatsListForAccount(accountId: String): List<ChatEntity> = emptyList()
    override suspend fun getChatById(id: Int, accountId: String): ChatEntity? = null
    override suspend fun getChatByConversationId(conversationId: String, accountId: String): ChatEntity? = null
    override suspend fun getChatByRecipientId(recipientId: String, accountId: String): ChatEntity? = null
    override suspend fun getChatByConversationType(conversationType: String, accountId: String): ChatEntity? = null
    override suspend fun insertChat(chat: ChatEntity): Long = 1L
    override suspend fun insertChats(chats: List<ChatEntity>) {}
    override suspend fun deleteChatById(id: Int, accountId: String) {}
    override suspend fun reassignMessagesChatId(sourceChatId: Int, targetChatId: Int, accountId: String) {}
    override suspend fun insertMessages(messages: List<MessageEntity>) {}
    override suspend fun toggleChatPin(chatId: Int, accountId: String) {}
    override suspend fun updateChatPreview(chatId: Int, preview: String, time: String, timestamp: Long, accountId: String) {}
    override fun getMessagesForChatForAccount(chatId: Int, accountId: String): Flow<List<MessageEntity>> = flowOf(emptyList())
    override fun getMessagesForAiPersona(chatId: Int, accountId: String, personaId: String): Flow<List<MessageEntity>> = flowOf(emptyList())
    override suspend fun getAllAiMessagesForAccount(accountId: String): List<MessageEntity> = emptyList()
    override suspend fun insertMessage(message: MessageEntity): Long = 1L
    override suspend fun insertMessageIfNotExists(message: MessageEntity): Long = 1L
    override suspend fun updateMessageStatus(messageId: Int, status: String, accountId: String) {}
    override suspend fun updateMessageStatusByServerId(serverId: String, status: String, accountId: String) {}
    override suspend fun updateMessageStatusByIdempotencyKey(idempotencyKey: String, status: String, accountId: String) {}
    override suspend fun updateMessageReactions(messageId: Int, reactions: String, accountId: String) {}
    override suspend fun markAllAsRead(chatId: Int, accountId: String) {}
    override suspend fun getMessageById(id: Int, accountId: String): MessageEntity? = null
    override suspend fun editMessage(id: Int, text: String, timestamp: Long, accountId: String) {}
    override suspend fun deleteMessage(id: Int, accountId: String) {}
    override suspend fun getMessageByIdempotencyKey(key: String, accountId: String): MessageEntity? = null
    override suspend fun getMessageByServerId(serverId: String, accountId: String): MessageEntity? = null
    override suspend fun deleteMessageByServerId(serverId: String, accountId: String) {}
    override suspend fun clearAllChatsForAccount(accountId: String) {}
    override suspend fun clearAllMessagesForAccount(accountId: String) {}
    override suspend fun insertOutbox(outbox: OutboxEntity): Long = 1L
    override suspend fun getPendingOutboxEntries(accountId: String): List<OutboxEntity> = emptyList()
    override suspend fun deleteOutboxByIdempotencyKey(idempotencyKey: String, accountId: String) {}
    override suspend fun clearAllOutboxForAccount(accountId: String) {}
    override suspend fun updateMessageServerIdByIdempotencyKey(idempotencyKey: String, serverId: String, status: String, accountId: String) {}
    override suspend fun updateOutboxAttempt(id: Long, status: String, timestamp: Long, error: String, accountId: String) {}
    override suspend fun retryOutboxEntry(id: Long, accountId: String) {}
    override suspend fun recoverStaleSendingOutbox(accountId: String): Int = 0
    override suspend fun backfillLegacyChatsAccountId(targetAccountId: String): Int = 0
    override suspend fun backfillLegacyMessagesAccountId(targetAccountId: String): Int = 0
    override suspend fun backfillLegacyOutboxAccountId(targetAccountId: String): Int = 0
}

class ChatDeduplicationTest {

    private val fakeDao = FakeChatDao()
    private val repository = ConversationRepository(fakeDao)

    @Test
    fun testDeduplicateChatListLeavesSingleSavedMessagesChat() {
        val rawChats = listOf(
            ChatEntity(
                id = 1,
                name = "Saved Messages",
                ava = "SM",
                status = "online",
                preview = "Welcome",
                time = "10:00",
                isPinned = true,
                recipientId = "self",
                conversationId = "self_user123",
                conversationType = "SAVED_MESSAGES",
                accountId = "user123"
            ),
            ChatEntity(
                id = 2,
                name = "Избранное",
                ava = "ИЗ",
                status = "online",
                preview = "Saved 2",
                time = "10:05",
                isPinned = true,
                recipientId = "self",
                conversationId = "self_user123",
                conversationType = "SAVED_MESSAGES",
                accountId = "user123"
            )
        )

        val deduplicated = repository.deduplicateChatList(rawChats)
        assertEquals(1, deduplicated.size)
        assertEquals(1, deduplicated[0].id)
    }

    @Test
    fun testDeduplicateChatListLeavesSingleUnifiedAiChat() {
        val rawChats = listOf(
            ChatEntity(
                id = 10,
                name = "Hexagon AI",
                ava = "HX",
                status = "online",
                preview = "AI Neural Assistant",
                time = "12:00",
                recipientId = "ai_hexagon",
                conversationId = "ai_hexagon_user123",
                conversationType = "AI_ASSISTANT",
                accountId = "user123"
            ),
            ChatEntity(
                id = 11,
                name = "Ventaxis AI",
                ava = "VX",
                status = "online",
                preview = "AI Smart Assistant",
                time = "12:01",
                recipientId = "ai_ventaxis",
                conversationId = "ai_ventaxis_user123",
                conversationType = "AI_ASSISTANT",
                accountId = "user123"
            ),
            ChatEntity(
                id = 12,
                name = "AI Assistant",
                ava = "AI",
                status = "online",
                preview = "Old AI",
                time = "12:02",
                recipientId = "ai_assistant",
                conversationId = "ai_user123",
                conversationType = "AI_ASSISTANT",
                accountId = "user123"
            )
        )

        val deduplicated = repository.deduplicateChatList(rawChats)
        assertEquals(1, deduplicated.size)
        assertEquals(10, deduplicated[0].id)
        assertEquals("Hexagon AI", deduplicated[0].name)
    }

    @Test
    fun testDeduplicatePeerChatsWithDuplicateConversationId() {
        val rawChats = listOf(
            ChatEntity(
                id = 20,
                name = "Alice",
                ava = "AL",
                status = "online",
                preview = "Hi!",
                time = "14:00",
                recipientId = "user_alice",
                conversationId = "direct_alice_user123",
                conversationType = "DIRECT",
                accountId = "user123"
            ),
            ChatEntity(
                id = 21,
                name = "Alice",
                ava = "AL",
                status = "online",
                preview = "Hi again!",
                time = "14:05",
                recipientId = "user_alice",
                conversationId = "direct_alice_user123",
                conversationType = "DIRECT",
                accountId = "user123"
            )
        )

        val deduplicated = repository.deduplicateChatList(rawChats)
        assertEquals(1, deduplicated.size)
        assertEquals(20, deduplicated[0].id)
    }

    @Test
    fun testComputeConversationId() {
        val selfId = repository.computeConversationId("user1", "self")
        assertEquals("self_user1", selfId)

        val hexAi = repository.computeConversationId("user1", "ai_hexagon")
        assertEquals("ai_hexagon_user1", hexAi)

        // All AI assistants map to the canonical unified Hexagon AI conversation ID to prevent duplication
        val ventAi = repository.computeConversationId("user1", "ai_ventaxis")
        assertEquals("ai_hexagon_user1", ventAi)

        val direct1 = repository.computeConversationId("userA", "userB")
        val direct2 = repository.computeConversationId("userB", "userA")
        assertEquals("direct_userA_userB", direct1)
        assertEquals(direct1, direct2)
    }

    @Test
    fun testBuiltInChatsStatusIsNotOnline() {
        val savedChat = ChatEntity(
            id = 1,
            name = "Saved Messages",
            ava = "SM",
            status = "",
            preview = "Welcome",
            time = "12:00",
            recipientId = "self",
            conversationId = "self_user1",
            conversationType = com.example.data.ConversationType.SAVED_MESSAGES.name,
            accountId = "user1"
        )
        val aiChat = ChatEntity(
            id = 2,
            name = "Hexagon AI",
            ava = "HX",
            status = "",
            preview = "Assistant",
            time = "12:00",
            recipientId = "ai_hexagon",
            conversationId = "ai_hexagon_user1",
            conversationType = com.example.data.ConversationType.AI_ASSISTANT.name,
            accountId = "user1"
        )

        org.junit.Assert.assertNotEquals("online", savedChat.status)
        org.junit.Assert.assertNotEquals("online", aiChat.status)
    }
}
