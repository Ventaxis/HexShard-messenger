package com.example.data.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "chats",
    indices = [
        Index("recipientId"),
        Index("conversationId"),
        Index("accountId"),
        Index("conversationType"),
        Index(value = ["accountId", "id"]),
        Index(value = ["accountId", "conversationId"]),
        Index(value = ["accountId", "conversationType"])
    ]
)
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val accountId: String = "", // Account ownership for strict local data isolation
    val name: String,
    val ava: String,
    val status: String, // "online" or "offline"
    val preview: String,
    val time: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isGroup: Boolean = false,
    val isPinned: Boolean = false,
    val unreadCount: Int = 0,
    val recipientId: String = "", // Recipient's UID or phone
    val conversationId: String = "", // Server-side deterministic conversation ID
    val conversationType: String = com.example.data.ConversationType.DIRECT.name
)

fun isAiConversation(recipientId: String?, name: String? = null, conversationId: String? = null): Boolean {
    val r = recipientId ?: ""
    val c = conversationId ?: ""
    return r == "ai_assistant" || r.startsWith("ai_") || c.startsWith("ai_")
}

fun isSelfConversation(recipientId: String?, conversationId: String? = null, id: Int? = null): Boolean {
    val r = recipientId ?: ""
    val c = conversationId ?: ""
    return r == "self" || r == "me" || c.startsWith("self_")
}

fun ChatEntity.getConversationType(): com.example.data.ConversationType {
    return when {
        conversationType == com.example.data.ConversationType.SAVED_MESSAGES.name || conversationId.startsWith("self_") || recipientId == "self" ->
            com.example.data.ConversationType.SAVED_MESSAGES
        conversationType == com.example.data.ConversationType.AI_ASSISTANT.name || conversationId.startsWith("ai_") || recipientId == "ai_assistant" || recipientId.startsWith("ai_") ->
            com.example.data.ConversationType.AI_ASSISTANT
        conversationType == com.example.data.ConversationType.GROUP.name || isGroup || conversationId.startsWith("group_") ->
            com.example.data.ConversationType.GROUP
        else ->
            com.example.data.ConversationType.DIRECT
    }
}

val ChatEntity.isSavedMessages: Boolean
    get() = getConversationType() == com.example.data.ConversationType.SAVED_MESSAGES

val ChatEntity.isAiAssistant: Boolean
    get() = getConversationType() == com.example.data.ConversationType.AI_ASSISTANT


@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("chatId"),
        Index("idempotencyKey"),
        Index("conversationId"),
        Index("serverMessageId"),
        Index("accountId"),
        Index("personaId"),
        Index(value = ["accountId", "id"]),
        Index(value = ["accountId", "chatId", "timestamp"]),
        Index(value = ["accountId", "conversationId"]),
        Index(value = ["accountId", "serverMessageId"]),
        Index(value = ["accountId", "idempotencyKey"]),
        Index(value = ["accountId", "personaId", "timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val accountId: String = "", // Account ownership for strict local data isolation
    val chatId: Int,
    val sender: String,
    val text: String,
    val time: String,
    val timestamp: Long,
    val isMe: Boolean,
    val isAttachment: Boolean = false,
    val type: String = "text", // "text", "voice", "attachment", "image", "video"
    val audioUrl: String? = null,
    val duration: Int? = null,
    val status: String = "sent", // "sending", "sent", "delivered", "read", "error", "tampered"
    val reactions: String = "", // e.g. "❤️:3,😂:1"
    val iv: String = "", // Store IV separately for each encrypted message
    val encryptionVersion: Int = 2,
    val idempotencyKey: String = "", // For reliable deduplication
    val conversationId: String = "", // Stable conversation UUID / deterministic ID
    val serverMessageId: String = "", // Server message UUID
    val signatureValid: Boolean = true, // ECDSA digital signature verification status
    val personaId: String? = null // "VENTAXIS", "HEXAGON", or null for peer-to-peer chats
)

@Entity(
    tableName = "outbox",
    indices = [
        Index("accountId"),
        Index("idempotencyKey", unique = true),
        Index("status"),
        Index("createdAt"),
        Index(value = ["accountId", "status", "createdAt"]),
        Index(value = ["accountId", "idempotencyKey"])
    ]
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String = "",
    val localMessageId: Int = 0,
    val chatId: Int = 0,
    val conversationId: String = "",
    val idempotencyKey: String = "",
    val senderId: String = "",
    val recipientId: String = "",
    val payload: String = "",
    val signature: String = "",
    val type: String = "text",
    val timeStr: String = "",
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0L,
    val status: String = "pending", // "pending", "sending", "failed"
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats WHERE accountId = :accountId ORDER BY isPinned DESC, timestamp DESC")
    fun getAllChatsForAccount(accountId: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE (conversationType = 'SAVED_MESSAGES' OR conversationId = 'self_' || :accountId OR recipientId = 'self') AND accountId = :accountId LIMIT 1")
    suspend fun getSavedMessagesChat(accountId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE (conversationType = 'AI_ASSISTANT' OR conversationId = 'ai_' || :accountId OR recipientId = 'ai_assistant') AND accountId = :accountId LIMIT 1")
    suspend fun getAiAssistantChat(accountId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :id AND accountId = :accountId LIMIT 1")
    suspend fun getChatById(id: Int, accountId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE conversationId = :conversationId AND accountId = :accountId LIMIT 1")
    suspend fun getChatByConversationId(conversationId: String, accountId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE recipientId = :recipientId AND accountId = :accountId LIMIT 1")
    suspend fun getChatByRecipientId(recipientId: String, accountId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE conversationType = :conversationType AND accountId = :accountId LIMIT 1")
    suspend fun getChatByConversationType(conversationType: String, accountId: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    @Query("DELETE FROM chats WHERE id = :id AND accountId = :accountId")
    suspend fun deleteChatById(id: Int, accountId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("UPDATE chats SET isPinned = NOT isPinned WHERE id = :chatId AND accountId = :accountId")
    suspend fun toggleChatPin(chatId: Int, accountId: String)

    @Query("UPDATE chats SET preview = :preview, time = :time, timestamp = :timestamp WHERE id = :chatId AND accountId = :accountId")
    suspend fun updateChatPreview(chatId: Int, preview: String, time: String, timestamp: Long, accountId: String)

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND accountId = :accountId ORDER BY timestamp ASC")
    fun getMessagesForChatForAccount(chatId: Int, accountId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND accountId = :accountId AND personaId = :personaId ORDER BY timestamp ASC")
    fun getMessagesForAiPersona(chatId: Int, accountId: String, personaId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE personaId IS NOT NULL AND accountId = :accountId ORDER BY timestamp ASC")
    suspend fun getAllAiMessagesForAccount(accountId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfNotExists(message: MessageEntity): Long

    @Query("UPDATE messages SET status = :status WHERE id = :messageId AND accountId = :accountId")
    suspend fun updateMessageStatus(messageId: Int, status: String, accountId: String)

    @Query("UPDATE messages SET status = :status WHERE serverMessageId = :serverId AND accountId = :accountId")
    suspend fun updateMessageStatusByServerId(serverId: String, status: String, accountId: String)

    @Query("UPDATE messages SET status = :status WHERE idempotencyKey = :idempotencyKey AND accountId = :accountId")
    suspend fun updateMessageStatusByIdempotencyKey(idempotencyKey: String, status: String, accountId: String)

    @Query("UPDATE messages SET reactions = :reactions WHERE id = :messageId AND accountId = :accountId")
    suspend fun updateMessageReactions(messageId: Int, reactions: String, accountId: String)

    @Query("UPDATE messages SET status = 'read' WHERE chatId = :chatId AND accountId = :accountId AND isMe = 0 AND status != 'read'")
    suspend fun markAllAsRead(chatId: Int, accountId: String)

    @Query("SELECT * FROM messages WHERE id = :id AND accountId = :accountId LIMIT 1")
    suspend fun getMessageById(id: Int, accountId: String): MessageEntity?

    @Query("UPDATE messages SET text = :text, timestamp = :timestamp WHERE id = :id AND accountId = :accountId")
    suspend fun editMessage(id: Int, text: String, timestamp: Long, accountId: String)

    @Query("DELETE FROM messages WHERE id = :id AND accountId = :accountId")
    suspend fun deleteMessage(id: Int, accountId: String)

    @Query("SELECT * FROM messages WHERE idempotencyKey = :key AND accountId = :accountId LIMIT 1")
    suspend fun getMessageByIdempotencyKey(key: String, accountId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE serverMessageId = :serverId AND accountId = :accountId LIMIT 1")
    suspend fun getMessageByServerId(serverId: String, accountId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE serverMessageId = :serverId AND accountId = :accountId")
    suspend fun deleteMessageByServerId(serverId: String, accountId: String)

    @Query("DELETE FROM chats WHERE accountId = :accountId")
    suspend fun clearAllChatsForAccount(accountId: String)

    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun clearAllMessagesForAccount(accountId: String)

    // Durable Outbox Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(outbox: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND status != 'failed' ORDER BY createdAt ASC")
    suspend fun getPendingOutboxEntries(accountId: String): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE idempotencyKey = :idempotencyKey AND accountId = :accountId")
    suspend fun deleteOutboxByIdempotencyKey(idempotencyKey: String, accountId: String)

    @Query("DELETE FROM outbox WHERE accountId = :accountId")
    suspend fun clearAllOutboxForAccount(accountId: String)

    @Query("UPDATE messages SET serverMessageId = :serverId, status = :status WHERE idempotencyKey = :idempotencyKey AND accountId = :accountId")
    suspend fun updateMessageServerIdByIdempotencyKey(idempotencyKey: String, serverId: String, status: String, accountId: String)

    @Query("UPDATE outbox SET status = :status, attempts = attempts + 1, lastAttemptAt = :timestamp, lastError = :error WHERE id = :id AND accountId = :accountId")
    suspend fun updateOutboxAttempt(id: Long, status: String, timestamp: Long, error: String, accountId: String)

    @Query("UPDATE outbox SET status = 'pending', attempts = 0 WHERE id = :id AND accountId = :accountId")
    suspend fun retryOutboxEntry(id: Long, accountId: String)

    @Query("UPDATE outbox SET status = 'pending' WHERE status = 'sending' AND accountId = :accountId")
    suspend fun recoverStaleSendingOutbox(accountId: String): Int

    @Query("UPDATE chats SET accountId = :targetAccountId WHERE accountId = ''")
    suspend fun backfillLegacyChatsAccountId(targetAccountId: String): Int

    @Query("UPDATE messages SET accountId = :targetAccountId WHERE accountId = ''")
    suspend fun backfillLegacyMessagesAccountId(targetAccountId: String): Int

    @Query("UPDATE outbox SET accountId = :targetAccountId WHERE accountId = ''")
    suspend fun backfillLegacyOutboxAccountId(targetAccountId: String): Int
}

@Database(entities = [ChatEntity::class, MessageEntity::class, OutboxEntity::class], version = 14, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN iv TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE messages ADD COLUMN encryptionVersion INTEGER NOT NULL DEFAULT 1")
            }
        }
        
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN recipientId TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN idempotencyKey TEXT NOT NULL DEFAULT ''")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_idempotencyKey ON messages(idempotencyKey)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Table schema alignments
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN conversationId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE messages ADD COLUMN conversationId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE messages ADD COLUMN serverMessageId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE messages ADD COLUMN signatureValid INTEGER NOT NULL DEFAULT 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages(conversationId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_serverMessageId ON messages(serverMessageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chats_recipientId ON chats(recipientId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chats_conversationId ON chats(conversationId)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN accountId TEXT NOT NULL DEFAULT ''")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chats_accountId ON chats(accountId)")
                database.execSQL("ALTER TABLE messages ADD COLUMN accountId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE messages ADD COLUMN personaId TEXT DEFAULT NULL")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_accountId ON messages(accountId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_personaId ON messages(personaId)")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        accountId TEXT NOT NULL DEFAULT '',
                        localMessageId INTEGER NOT NULL DEFAULT 0,
                        chatId INTEGER NOT NULL DEFAULT 0,
                        conversationId TEXT NOT NULL DEFAULT '',
                        idempotencyKey TEXT NOT NULL DEFAULT '',
                        senderId TEXT NOT NULL DEFAULT '',
                        recipientId TEXT NOT NULL DEFAULT '',
                        payload TEXT NOT NULL DEFAULT '',
                        signature TEXT NOT NULL DEFAULT '',
                        type TEXT NOT NULL DEFAULT 'text',
                        timeStr TEXT NOT NULL DEFAULT '',
                        attempts INTEGER NOT NULL DEFAULT 0,
                        lastAttemptAt INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'pending',
                        lastError TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_accountId ON outbox(accountId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_outbox_idempotencyKey ON outbox(idempotencyKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_status ON outbox(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_createdAt ON outbox(createdAt)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN conversationType TEXT NOT NULL DEFAULT 'DIRECT'")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chats_conversationType ON chats(conversationType)")
                database.execSQL("UPDATE chats SET conversationType = 'SAVED_MESSAGES' WHERE recipientId = 'self' OR conversationId LIKE 'self_%' OR id = 1")
                database.execSQL("UPDATE chats SET conversationType = 'AI_ASSISTANT' WHERE recipientId LIKE 'ai_%' OR conversationId LIKE 'ai_%' OR id = 2")
                database.execSQL("UPDATE chats SET conversationType = 'GROUP' WHERE isGroup = 1")
                database.execSQL("UPDATE messages SET personaId = 'VENTAXIS' WHERE personaId IS NULL AND (conversationId LIKE '%ventaxis%' OR sender LIKE '%ventaxis%')")
                database.execSQL("UPDATE messages SET personaId = 'HEXAGON' WHERE personaId IS NULL AND (conversationId LIKE '%hexagon%' OR sender LIKE '%hexagon%')")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE chats SET conversationId = 'self_' || accountId WHERE conversationType = 'SAVED_MESSAGES' AND (conversationId = '' OR conversationId = 'self' OR conversationId = 'saved_messages') AND accountId != ''")
                database.execSQL("UPDATE chats SET conversationId = 'ai_' || accountId WHERE conversationType = 'AI_ASSISTANT' AND (conversationId = '' OR conversationId = 'ai_hexshard' OR conversationId = 'ai_assistant') AND accountId != ''")
                database.execSQL("UPDATE messages SET conversationId = (SELECT conversationId FROM chats WHERE chats.id = messages.chatId) WHERE (conversationId = '' OR conversationId = 'self' OR conversationId = 'ai_hexshard') AND EXISTS (SELECT 1 FROM chats WHERE chats.id = messages.chatId)")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chats_accountId_id ON chats(accountId, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chats_accountId_conversationId ON chats(accountId, conversationId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_chats_accountId_conversationType ON chats(accountId, conversationType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_accountId_id ON messages(accountId, id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_accountId_chatId_timestamp ON messages(accountId, chatId, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_accountId_conversationId ON messages(accountId, conversationId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_accountId_serverMessageId ON messages(accountId, serverMessageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_accountId_idempotencyKey ON messages(accountId, idempotencyKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_accountId_personaId_timestamp ON messages(accountId, personaId, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_accountId_status_createdAt ON outbox(accountId, status, createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_accountId_idempotencyKey ON outbox(accountId, idempotencyKey)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val factory = SQLCipherUtils.createSupportFactory(context)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hexshard_messenger_database"
                )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
