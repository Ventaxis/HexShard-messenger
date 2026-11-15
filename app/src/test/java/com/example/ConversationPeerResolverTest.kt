package com.example

import com.example.util.ConversationPeerResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attachment/outbox crypto must key off the peer UID parsed from the direct
 * conversation id — chats created by older builds may store a username in
 * recipientId, which silently broke media key derivation.
 */
class ConversationPeerResolverTest {
    private val me = "11111111-2222-3333-4444-555555555555"
    private val peer = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test
    fun `direct conversation id yields the peer uid`() {
        val convId = "direct_${me}_$peer"
        assertEquals(peer, ConversationPeerResolver.resolvePeerUid(convId, me, "stale_username"))
        // Symmetric: the other side resolves to me.
        assertEquals(me, ConversationPeerResolver.resolvePeerUid("direct_${peer}_$me", peer, "x"))
    }

    @Test
    fun `self and ai conversations resolve to markers`() {
        assertEquals("self", ConversationPeerResolver.resolvePeerUid("self_$me", me, "self"))
        assertEquals("ai_assistant", ConversationPeerResolver.resolvePeerUid("ai_$me", me, "ai_assistant"))
    }

    @Test
    fun `non-uuid conversation id falls back to recipient`() {
        assertEquals(
            "oopp",
            ConversationPeerResolver.resolvePeerUid("direct_broken-id", me, "oopp")
        )
    }

    @Test
    fun `uuid detection is strict`() {
        assertTrue(ConversationPeerResolver.isUuid("a1b2c3d4-e5f6-4a1b-8c2d-9e0f1a2b3c4d"))
        assertFalse(ConversationPeerResolver.isUuid("oopp"))
        assertFalse(ConversationPeerResolver.isUuid(""))
        assertFalse(ConversationPeerResolver.isUuid("direct_a1b2c3d4-e5f6-4a1b-8c2d-9e0f1a2b3c4d"))
    }
}
