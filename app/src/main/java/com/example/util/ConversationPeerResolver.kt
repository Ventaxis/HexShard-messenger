package com.example.util

/**
 * Resolves the crypto peer UID for a conversation. Attachment crypto, outbox
 * delivery and server rows must never trust chat.recipientId blindly: chats
 * created by older builds could store a username (or blank) there, which
 * silently broke peer-key derivation while message decryption (keyed by the
 * server-side sender id) kept working. The direct conversation id is
 * deterministic — "direct_<uuidA>_<uuidB>" — so the peer uid is read from it
 * whenever possible, falling back to the stored recipient only as a last resort.
 */
object ConversationPeerResolver {
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    fun isUuid(value: String): Boolean = UUID_REGEX.matches(value)

    /**
     * Returns the peer identity for [conversationId] ("direct_...", "self_...",
     * "ai_..."). Self/AI conversations return markers handled by the caller's
     * is-self checks; direct conversations yield the peer UID parsed out of the
     * conversation id, or [fallbackRecipientId] when it yields nothing usable.
     */
    fun resolvePeerUid(conversationId: String, myUid: String, fallbackRecipientId: String): String {
        if (conversationId.startsWith("self_")) return "self"
        if (conversationId.startsWith("ai_")) return "ai_assistant"
        if (conversationId.startsWith("direct_")) {
            val other = conversationId.removePrefix("direct_")
                .split('_')
                .firstOrNull { isUuid(it) && it != myUid }
            if (other != null) return other
        }
        return fallbackRecipientId
    }
}
