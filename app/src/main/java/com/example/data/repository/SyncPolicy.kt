package com.example.data.repository

import org.json.JSONObject

/** Conversation routing shared by catch-up sync and deletion regression tests. */
internal object SyncPolicy {
    fun isAiConversation(record: JSONObject): Boolean =
        record.optString("recipient_id").startsWith("ai_") ||
            record.optString("recipient_id") == "ai_assistant" ||
            record.optString("persona_id").let { it.isNotBlank() && it != "null" } ||
            record.optString("conversation_id").startsWith("ai_")

    fun isSavedMessages(record: JSONObject, accountId: String): Boolean =
        !isAiConversation(record) && record.optString("sender_id") == accountId &&
            record.optString("recipient_id").let { it == accountId || it == "self" || it.isBlank() }

    fun isBeforeDeletion(sequence: Long, deletedThrough: Long): Boolean =
        deletedThrough >= 0 && sequence <= deletedThrough
}
