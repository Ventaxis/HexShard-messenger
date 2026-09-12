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
    VENTAXIS(
        persona = AiPersona.VENTAXIS,
        shortName = "Ventaxis",
        badge = "AI",
        description = "Smart assistant"
    ),
    HEXAGON_BETA(
        persona = AiPersona.HEXAGON,
        shortName = "Hexagon",
        badge = "Beta",
        description = "Fast beta"
    );

    val personaId: String get() = persona.id
    val displayName: String get() = persona.displayName

    companion object {
        val DEFAULT = VENTAXIS

        fun fromPersonaId(id: String?): AiModelOption {
            return when (id?.uppercase()?.trim()) {
                "HEXAGON" -> HEXAGON_BETA
                "VENTAXIS" -> VENTAXIS
                else -> DEFAULT
            }
        }
    }
}
