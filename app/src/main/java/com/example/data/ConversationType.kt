package com.example.data

/**
 * Stable, unambiguous conversation types for routing, calls, and security permissions.
 * Localized display strings (e.g. "Избранное", "Saved Messages") must NEVER be used
 * for permission or routing logic.
 */
enum class ConversationType {
    DIRECT,
    GROUP,
    SAVED_MESSAGES,
    AI_ASSISTANT,
    SYSTEM
}
