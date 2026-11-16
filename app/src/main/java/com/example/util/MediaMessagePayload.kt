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

    /**
     * Plaintext that must be encrypted for an outgoing message row: media rows
     * carry the URL INSIDE the payload JSON (see class doc), plain text rows
     * are encrypted as-is. Used by both the immediate send path and the
     * WAITING_FOR_PEER_KEY flush loop so a deferred re-encryption keeps the
     * media URL — the old flush loop encrypted only the caption and the
     * receiver rendered a broken attachment card.
     */
    fun plaintextForOutgoing(text: String, audioUrl: String?, type: String, duration: Int?): String =
        if (audioUrl != null) encode(text, audioUrl, type, duration) else text

    /**
     * Whether a local message row has anything to encrypt at all. Media rows
     * are encodable even with a blank caption — the URL alone is the content.
     */
    fun hasEncodableContent(text: String, audioUrl: String?): Boolean =
        text.isNotBlank() || !audioUrl.isNullOrBlank()
}
