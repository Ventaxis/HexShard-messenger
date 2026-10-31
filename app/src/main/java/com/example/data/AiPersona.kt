package com.example.data

/**
 * First-class domain representation of AI Personas in HexShard.
 * Canonical IDs: "ventaxis" and "hexagon".
 * Legacy uppercase and beta variants are normalized automatically.
 */
enum class AiPersona(val id: String, val displayName: String) {
    VENTAXIS("ventaxis", "Ventaxis AI"),
    HEXAGON("hexagon", "HexShard AI");

    companion object {
        val DEFAULT = HEXAGON

        /**
         * Resolves persona strictly by canonical id ("ventaxis" or "hexagon").
         * Rejects unknown, empty, or loosely matched IDs with IllegalArgumentException.
         */
        fun fromId(id: String?): AiPersona {
            val resolved = fromIdOrNull(id)
            return resolved ?: throw IllegalArgumentException(
                "Unknown or unauthorized AI persona ID: '${id ?: "null"}'. Authorized personas: ventaxis, hexagon"
            )
        }

        /**
         * Safely resolves persona or returns null if unknown/invalid.
         */
        fun fromIdOrNull(id: String?): AiPersona? {
            if (id.isNullOrBlank()) return null
            val clean = id.trim().lowercase()
            return when (clean) {
                "ventaxis" -> VENTAXIS
                "hexagon" -> HEXAGON
                else -> null
            }
        }
    }
}
