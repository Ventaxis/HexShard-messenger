package com.example.util

import org.json.JSONObject

/**
 * Canonical encrypted-envelope JSON for messages that carry a media URL
 * (image / video / voice). The URL and type must travel INSIDE the encrypted
 * payload: the server row stores only the bare type flag, and the receiver
 * parses audioUrl/type/duration from the decrypted JSON
 * (see SyncManager.resolveMessageContent).
 */
object MediaMessagePayload {
    fun encode(text: String, audioUrl: String, type: String, duration: Int?): String =
        JSONObject().apply {
            put("text", text)
            put("audioUrl", audioUrl)
            put("type", type)
            if (duration != null) put("duration", duration)
        }.toString()
}
