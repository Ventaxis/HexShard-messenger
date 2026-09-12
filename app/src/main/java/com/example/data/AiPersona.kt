package com.example.data

/**
 * First-class domain representation of AI Personas in HexShard V21.
 * Provides strict identity isolation between Ventaxis and Hexagon.
 */
enum class AiPersona(val id: String, val displayName: String) {
    VENTAXIS("VENTAXIS", "Ventaxis AI"),
    HEXAGON("HEXAGON", "Hexagon AI");

    companion object {
        val DEFAULT = VENTAXIS

        fun fromId(id: String?): AiPersona {
            if (id.isNullOrBlank()) return DEFAULT
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }
    }
}
