package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SecurePrefsManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncCursorTest {

    @Test
    fun testSyncCursorPersistenceAndAdvancement() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Initial default cursor is 0
        val initialCursor = SecurePrefsManager.getSyncCursor(context, "conv_test_123")
        assertEquals(0L, initialCursor)

        // Advance cursor to seq 42
        SecurePrefsManager.setSyncCursor(context, "conv_test_123", 42L)
        assertEquals(42L, SecurePrefsManager.getSyncCursor(context, "conv_test_123"))

        // Independent conversation cursors
        assertEquals(0L, SecurePrefsManager.getSyncCursor(context, "conv_other_456"))
        SecurePrefsManager.setSyncCursor(context, "conv_other_456", 100L)
        assertEquals(100L, SecurePrefsManager.getSyncCursor(context, "conv_other_456"))
        assertEquals(42L, SecurePrefsManager.getSyncCursor(context, "conv_test_123"))
    }
}
