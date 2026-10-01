package com.example.network

import com.example.data.AiPersona

/**
 * Supported AI Personas for HexShard Messenger.
 * Server Edge Function strictly owns model mapping and system prompts.
 * User interface reflects only Ventaxis AI and Hexagon AI.
 */
enum class AiModelOption(
    val persona: AiPersona,
    val shortName: String,
    val badge: String,
    val description: String
) {
    HEXAGON(
        persona = AiPersona.HEXAGON,
        shortName = "HexShard",
        badge = "FAST",
        description = "Fast assistant"
    ),
    VENTAXIS(
        persona = AiPersona.VENTAXIS,
        shortName = "Ventaxis",
        badge = "PRO",
        description = "Smart assistant"
    );

    val personaId: String get() = persona.id
    val displayName: String get() = persona.displayName

    companion object {
        val DEFAULT = HEXAGON

        fun fromPersonaId(id: String?): AiModelOption {
            val p = AiPersona.fromId(id)
            return if (p == AiPersona.HEXAGON) HEXAGON else VENTAXIS
        }

        fun fromPersonaIdOrNull(id: String?): AiModelOption? {
            val p = AiPersona.fromIdOrNull(id) ?: return null
            return if (p == AiPersona.HEXAGON) HEXAGON else VENTAXIS
        }
    }
}
