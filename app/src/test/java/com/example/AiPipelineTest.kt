package com.example

import com.example.data.AiPersona
import com.example.data.ConversationType
import com.example.network.AiModelOption
import com.example.network.AiResult
import com.example.ui.AiUiError
import com.example.ui.EnglishStrings
import com.example.ui.RussianStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiPipelineTest {

    @Test
    fun testAiPersonaResolution() {
        assertEquals(AiPersona.VENTAXIS, AiPersona.fromId("VENTAXIS"))
        assertEquals(AiPersona.VENTAXIS, AiPersona.fromId("ventaxis"))
        assertEquals(AiPersona.HEXAGON, AiPersona.fromId("HEXAGON"))
        assertEquals(AiPersona.HEXAGON, AiPersona.fromId("hexagon"))

        // Strict rejection of unknown/null IDs
        try {
            AiPersona.fromId("unknown_model")
            org.junit.Assert.fail("Expected IllegalArgumentException for unknown model")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unknown or unauthorized AI persona") == true)
        }

        try {
            AiPersona.fromId(null)
            org.junit.Assert.fail("Expected IllegalArgumentException for null model")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unknown or unauthorized AI persona") == true)
        }

        assertEquals(null, AiPersona.fromIdOrNull("unknown_model"))
        assertEquals(null, AiPersona.fromIdOrNull(null))
    }

    @Test
    fun testAiModelOptionMapping() {
        val ventaxis = AiModelOption.fromPersonaId("VENTAXIS")
        assertEquals("Ventaxis AI", ventaxis.displayName)
        assertEquals("ventaxis", ventaxis.personaId)
        assertEquals(AiPersona.VENTAXIS, ventaxis.persona)

        val hexagon = AiModelOption.fromPersonaId("HEXAGON")
        assertEquals("HexShard AI", hexagon.displayName)
        assertEquals("hexagon", hexagon.personaId)
        assertEquals(AiPersona.HEXAGON, hexagon.persona)
    }

    @Test
    fun testConversationTypeDistinction() {
        assertTrue(ConversationType.AI_ASSISTANT != ConversationType.DIRECT)
        assertTrue(ConversationType.AI_ASSISTANT != ConversationType.SAVED_MESSAGES)
        assertEquals("AI_ASSISTANT", ConversationType.AI_ASSISTANT.name)
    }

    @Test
    fun testAiUiErrorLocalization() {
        val err404 = AiUiError.FunctionNotFound
        assertTrue(err404.getLocalizedMessage(EnglishStrings).contains("404"))
        assertTrue(err404.getLocalizedMessage(RussianStrings).contains("404"))

        val errRate = AiUiError.RateLimited(5)
        assertTrue(errRate.getLocalizedMessage(EnglishStrings).contains("5"))
        assertTrue(errRate.getLocalizedMessage(RussianStrings).contains("5"))

        val errDeploy = AiUiError.DeploymentUnavailable
        assertNotNull(errDeploy.getLocalizedMessage(EnglishStrings))
        assertNotNull(errDeploy.getLocalizedMessage(RussianStrings))

        val errModel = AiUiError.ModelUnavailable
        assertNotNull(errModel.getLocalizedMessage(EnglishStrings))
        assertNotNull(errModel.getLocalizedMessage(RussianStrings))
    }

    @Test
    fun testPersonaSystemPromptsConfigured() {
        assertTrue(com.example.network.GeminiService.VENTAXIS_SYSTEM_PROMPT.contains("Ventaxis AI"))
        assertTrue(com.example.network.GeminiService.HEXAGON_SYSTEM_PROMPT.contains("Hexagon AI"))
        assertEquals("ventaxis", AiModelOption.VENTAXIS.personaId)
        assertEquals("hexagon", AiModelOption.HEXAGON.personaId)
    }
}
