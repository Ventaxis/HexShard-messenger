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

class AiPipelineTest {

    @Test
    fun testAiPersonaResolution() {
        assertEquals(AiPersona.VENTAXIS, AiPersona.fromId("VENTAXIS"))
        assertEquals(AiPersona.VENTAXIS, AiPersona.fromId("ventaxis"))
        assertEquals(AiPersona.HEXAGON, AiPersona.fromId("HEXAGON"))
        assertEquals(AiPersona.HEXAGON, AiPersona.fromId("hexagon"))
        // Fail-safe default
        assertEquals(AiPersona.VENTAXIS, AiPersona.fromId("unknown_model"))
        assertEquals(AiPersona.VENTAXIS, AiPersona.fromId(null))
    }

    @Test
    fun testAiModelOptionMapping() {
        val ventaxis = AiModelOption.fromPersonaId("VENTAXIS")
        assertEquals("Ventaxis AI", ventaxis.displayName)
        assertEquals("VENTAXIS", ventaxis.personaId)
        assertEquals(AiPersona.VENTAXIS, ventaxis.persona)

        val hexagon = AiModelOption.fromPersonaId("HEXAGON")
        assertEquals("Hexagon AI", hexagon.displayName)
        assertEquals("HEXAGON", hexagon.personaId)
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
}
