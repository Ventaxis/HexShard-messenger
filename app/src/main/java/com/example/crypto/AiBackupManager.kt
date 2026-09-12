package com.example.crypto

import android.content.Context
import com.example.data.database.ChatDao
import com.example.data.database.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages encrypted local backups for AI conversations.
 *
 * Requirements:
 * - Plaintext transcripts never leak to cloud or unencrypted files.
 * - Recovery key is derived strictly from a user-controlled passphrase via PBKDF2 (100,000 iterations).
 * - Authenticated AES-256-GCM encryption with 12-byte IV and 128-bit authentication tag.
 * - Passphrase protection: if the passphrase is lost, the backup cannot be decrypted.
 */
object AiBackupManager {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val BACKUP_FILENAME = "hexshard_ai_history.hexbak"

    data class BackupResult(
        val success: Boolean,
        val message: String,
        val messageCount: Int = 0,
        val filePath: String? = null
    )

    /**
     * Exports all AI transcripts for the given account into an encrypted local backup file.
     */
    suspend fun exportAiHistory(
        context: Context,
        chatDao: ChatDao,
        accountId: String,
        passphrase: CharArray
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            if (passphrase.isEmpty()) {
                return@withContext BackupResult(false, "Passphrase cannot be empty")
            }
            if (accountId.isBlank()) {
                return@withContext BackupResult(false, "Account ID is required for scoped export")
            }

            // Fetch all AI messages
            val aiMessages = chatDao.getAllAiMessagesForAccount(accountId)
            if (aiMessages.isEmpty()) {
                return@withContext BackupResult(false, "No AI history to export", 0)
            }

            // Serialize messages to JSON
            val root = JSONObject().apply {
                put("version", 1)
                put("accountId", accountId)
                put("exportedAt", System.currentTimeMillis())
                put("count", aiMessages.size)

                val msgArray = JSONArray()
                for (msg in aiMessages) {
                    val mObj = JSONObject().apply {
                        put("chatId", msg.chatId)
                        put("sender", msg.sender)
                        put("text", msg.text)
                        put("time", msg.time)
                        put("timestamp", msg.timestamp)
                        put("isMe", msg.isMe)
                        put("status", msg.status)
                        put("conversationId", msg.conversationId)
                        put("idempotencyKey", msg.idempotencyKey)
                        put("personaId", msg.personaId ?: "")
                    }
                    msgArray.put(mObj)
                }
                put("messages", msgArray)
            }

            val plaintextBytes = root.toString().toByteArray(StandardCharsets.UTF_8)

            // Generate cryptographic salt and IV
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

            // Derive key via PBKDF2
            val keySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val secretKeyBytes = keyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(secretKeyBytes, "AES")

            // Encrypt via AES-256-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val ciphertext = cipher.doFinal(plaintextBytes)

            // Package encrypted container
            val container = JSONObject().apply {
                put("format", "hexshard_ai_v1")
                put("salt", Base64.getEncoder().encodeToString(salt))
                put("iv", Base64.getEncoder().encodeToString(iv))
                put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
                put("timestamp", System.currentTimeMillis())
            }

            val backupFile = File(context.filesDir, BACKUP_FILENAME)
            backupFile.writeText(container.toString(), StandardCharsets.UTF_8)

            Timber.i("Exported ${aiMessages.size} AI messages to encrypted backup")
            BackupResult(
                success = true,
                message = "Successfully exported ${aiMessages.size} messages",
                messageCount = aiMessages.size,
                filePath = backupFile.absolutePath
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to export AI history")
            BackupResult(false, "Export failed: ${e.message}")
        } finally {
            java.util.Arrays.fill(passphrase, '\u0000')
        }
    }

    /**
     * Imports and decrypts AI transcripts from the local backup file into the database.
     */
    suspend fun importAiHistory(
        context: Context,
        chatDao: ChatDao,
        accountId: String,
        passphrase: CharArray
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            if (passphrase.isEmpty()) {
                return@withContext BackupResult(false, "Passphrase cannot be empty")
            }
            if (accountId.isBlank()) {
                return@withContext BackupResult(false, "Account ID is required for scoped import")
            }

            val backupFile = File(context.filesDir, BACKUP_FILENAME)
            if (!backupFile.exists()) {
                return@withContext BackupResult(false, "No backup file found at ${backupFile.name}")
            }

            val containerStr = backupFile.readText(StandardCharsets.UTF_8)
            val container = JSONObject(containerStr)

            val salt = Base64.getDecoder().decode(container.getString("salt"))
            val iv = Base64.getDecoder().decode(container.getString("iv"))
            val ciphertext = Base64.getDecoder().decode(container.getString("ciphertext"))

            // Derive key via PBKDF2
            val keySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val secretKeyBytes = keyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(secretKeyBytes, "AES")

            // Decrypt via AES-256-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plaintextBytes = try {
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                Timber.w(e, "Decryption failed (invalid passphrase or corrupted tag)")
                return@withContext BackupResult(false, "Invalid password or corrupted backup file")
            }

            val root = JSONObject(String(plaintextBytes, StandardCharsets.UTF_8))
            val msgArray = root.optJSONArray("messages") ?: JSONArray()

            // Resolve target AI chat by structured conversation type without magic numeric IDs
            var aiChat = chatDao.getAiAssistantChat(accountId)
                ?: chatDao.getChatByConversationType(com.example.data.ConversationType.AI_ASSISTANT.name, accountId)
            if (aiChat == null) {
                val newAiChat = com.example.data.database.ChatEntity(
                    accountId = accountId,
                    name = "HexShard AI",
                    ava = "AI",
                    status = "online",
                    preview = "Chat restored from encrypted backup",
                    time = "",
                    recipientId = "ai_assistant",
                    conversationId = "ai_assistant",
                    conversationType = com.example.data.ConversationType.AI_ASSISTANT.name
                )
                val newId = chatDao.insertChat(newAiChat).toInt()
                aiChat = chatDao.getChatById(newId, accountId)
            }
            val targetChatId = aiChat?.id ?: run {
                return@withContext BackupResult(false, "Failed to resolve or initialize AI conversation")
            }

            val messagesToInsert = mutableListOf<MessageEntity>()
            for (i in 0 until msgArray.length()) {
                val mObj = msgArray.getJSONObject(i)
                val idKey = mObj.optString("idempotencyKey").ifBlank { "ai_bk_${System.currentTimeMillis()}_$i" }
                val persona = mObj.optString("personaId").takeIf { it.isNotBlank() } ?: "VENTAXIS"

                messagesToInsert.add(
                    MessageEntity(
                        accountId = accountId,
                        chatId = targetChatId,
                        sender = mObj.optString("sender"),
                        text = mObj.optString("text"),
                        time = mObj.optString("time"),
                        timestamp = mObj.optLong("timestamp", System.currentTimeMillis()),
                        isMe = mObj.optBoolean("isMe", false),
                        status = mObj.optString("status", "delivered"),
                        conversationId = mObj.optString("conversationId", "ai_assistant"),
                        idempotencyKey = idKey,
                        personaId = persona
                    )
                )
            }

            chatDao.insertMessages(messagesToInsert)
            Timber.i("Restored ${messagesToInsert.size} AI messages from encrypted backup")

            BackupResult(
                success = true,
                message = "Successfully restored ${messagesToInsert.size} messages",
                messageCount = messagesToInsert.size
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to import AI history")
            BackupResult(false, "Import failed: ${e.message}")
        } finally {
            java.util.Arrays.fill(passphrase, '\u0000')
        }
    }

    fun hasLocalBackup(context: Context): Boolean {
        val file = File(context.filesDir, BACKUP_FILENAME)
        return file.exists() && file.length() > 0
    }
}
