package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SecurePrefsManager
import com.example.data.repository.SyncPolicy
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConversationDeletionTest {
    @Test
    fun `AI sent by owner is not classified as Saved Messages`() {
        val record = JSONObject()
            .put("sender_id", "owner")
            .put("recipient_id", "owner")
            .put("conversation_id", "ai_owner")
            .put("persona_id", "hexagon")
        assertTrue(SyncPolicy.isAiConversation(record))
        assertFalse(SyncPolicy.isSavedMessages(record, "owner"))
    }

    @Test
    fun `old messages cannot recreate a deleted AI conversation`() {
        assertTrue(SyncPolicy.isBeforeDeletion(4, 4))
        assertFalse(SyncPolicy.isBeforeDeletion(5, 4))
    }

    @Test
    fun `empty AI chat remains deleted after restart`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val conversationId = "ai_owner"
        assertFalse(SecurePrefsManager.isConversationDeleted(context, "owner", conversationId))
        SecurePrefsManager.setConversationDeleteMarker(context, "owner", conversationId, 0)
        assertTrue(SecurePrefsManager.isConversationDeleted(context, "owner", conversationId))
        SecurePrefsManager.setConversationDeleteMarker(context, "owner", conversationId, 5)
        SecurePrefsManager.setConversationDeleteMarker(context, "owner", conversationId, 3)
        assertTrue(SecurePrefsManager.getConversationDeleteMarker(context, "owner", conversationId) == 5L)
    }
}
