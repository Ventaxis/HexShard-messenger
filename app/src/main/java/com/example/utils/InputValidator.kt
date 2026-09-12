package com.example.utils

object InputValidator {
    private const val MAX_MESSAGE_LENGTH = 4096
    private const val MAX_USERNAME_LENGTH = 30
    private const val MIN_USERNAME_LENGTH = 3
    private const val MAX_PHONE_LENGTH = 15

    fun isValidMessage(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.length > MAX_MESSAGE_LENGTH) return false
        
        // Prevent null bytes and control characters (common injection vector)
        if (text.any { it.code < 32 && it != '\n' && it != '\t' && it != '\r' }) return false
        
        return true
    }

    fun isValidUsername(username: String): Boolean {
        // Reserved names
        val reservedNames = setOf(
            "admin", "root", "system", "guest", "nobody", "operator",
            "hexshard", "support", "noreply", "postmaster", "webmaster",
            "administrator", "moderator", "bot", "api", "test", "debug"
        )
        
        if (username.lowercase() in reservedNames) return false
        
        // Length checks
        if (username.length < MIN_USERNAME_LENGTH || username.length > MAX_USERNAME_LENGTH) return false
        
        // Only ASCII alphanumeric + underscore
        if (!username.matches(Regex("^[a-zA-Z0-9_]+$"))) return false
        
        // Don't start with number or underscore
        if (username[0].isDigit() || username[0] == '_') return false
        
        // No consecutive underscores
        if (username.contains("__")) return false
        
        // No homoglyphs (only ASCII, no Cyrillic/Greek lookalikes)
        if (username.any { it.code > 127 }) return false
        
        return true
    }

    fun isValidPhoneNumber(phone: String): Boolean {
        // E.164 format strictly: +[1-9]{1-3}[0-9]{1,14}
        return phone.matches(Regex("^\\+[1-9]\\d{1,14}$"))
    }

    fun sanitizeString(input: String): String {
        return input
            .replace("\u0000", "")  // Null bytes
            .replace("\u001b", "")  // Escape sequences
            .replace("\u001a", "")  // End of file
            .trim()
    }

    fun isValidChatName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.length > 100) return false
        if (name.any { it.code < 32 }) return false
        return true
    }
}
