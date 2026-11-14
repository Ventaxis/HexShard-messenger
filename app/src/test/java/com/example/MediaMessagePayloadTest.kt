package com.example

import com.example.util.MediaMessagePayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for "photos turn into a broken attachment card": media senders
 * pass isAttachment=false, so the old payload gate never encrypted the media
 * URL into the envelope and receivers had no audioUrl to render. The encoder
 * must always carry text, audioUrl and type for media messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaMessagePayloadTest {
    @Test
    fun `encode carries text, audioUrl and type`() {
        val json = JSONObject(MediaMessagePayload.encode("Фото", "https://x/y.bin", "image", null))
        assertEquals("Фото", json.getString("text"))
        assertEquals("https://x/y.bin", json.getString("audioUrl"))
        assertEquals("image", json.getString("type"))
        assertFalse(json.has("duration"))
    }

    @Test
    fun `encode includes duration when present`() {
        val json = JSONObject(MediaMessagePayload.encode("Voice", "https://x/v.bin", "voice", 42))
        assertEquals(42, json.getInt("duration"))
    }

    @Test
    fun `voice envelope keeps type voice`() {
        val json = JSONObject(MediaMessagePayload.encode("Голосовое сообщение", "u", "voice", 7))
        assertEquals("voice", json.getString("type"))
    }

    @Test
    fun `encoded envelope is parseable JSON object`() {
        val encoded = MediaMessagePayload.encode("Photo", "u", "video", null)
        assertTrue(encoded.startsWith("{"))
        assertTrue(encoded.endsWith("}"))
        JSONObject(encoded)
    }
}
