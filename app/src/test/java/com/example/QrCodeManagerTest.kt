package com.example

import com.example.util.QrCodeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrCodeManagerTest {

    @Test
    fun testBuildAndParseProfileToken() {
        val token = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0"
        val uri = QrCodeManager.buildProfileUri(token)

        assertEquals("hexshard://profile/v2/$token", uri)

        val parsedToken = QrCodeManager.parseProfileToken(uri)
        assertNotNull(parsedToken)
        assertEquals(token, parsedToken)
    }

    @Test
    fun testParseInvalidProfileUris() {
        assertNull(QrCodeManager.parseProfileToken(null))
        assertNull(QrCodeManager.parseProfileToken(""))
        assertNull(QrCodeManager.parseProfileToken("https://google.com"))
        // Legacy v1 rejected
        assertNull(QrCodeManager.parseProfileToken("hexshard://profile/v1?id=123&key=abc"))
        // Invalid host
        assertNull(QrCodeManager.parseProfileToken("hexshard://other/v2/a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0"))
        // Token too short (< 32 chars)
        assertNull(QrCodeManager.parseProfileToken("hexshard://profile/v2/short_token"))
        // Token with invalid characters
        assertNull(QrCodeManager.parseProfileToken("hexshard://profile/v2/a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0!@#$"))
    }

    @Test
    fun testGenerateAndDecodeQrBitmap() {
        val token = "token_abc123def456ghi789jkl012mno345pqr"
        val payload = QrCodeManager.buildProfileUri(token)
        val bitmap = QrCodeManager.generateQrBitmap(payload, 256)
        assertNotNull("Bitmap generation should succeed", bitmap)
        assertEquals(256, bitmap?.width)
        assertEquals(256, bitmap?.height)

        val decoded = bitmap?.let { QrCodeManager.decodeQrFromBitmap(it) }
        assertNotNull("QR decoding should successfully recover text", decoded)
        assertEquals(payload, decoded)

        val parsedToken = QrCodeManager.parseProfileToken(decoded)
        assertNotNull(parsedToken)
        assertEquals(token, parsedToken)
    }

    @Test
    fun testTokenContractAndUriResolution() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        com.example.data.SecurePrefsManager.saveProfile(
            context = context,
            userId = "test-user-id-123",
            username = "alice",
            phone = "+1 555-0199"
        )
        com.example.data.SecurePrefsManager.getPrefs(context).edit()
            .putString("private_virtual_number", "88889999")
            .apply()

        val repo = com.example.data.repository.UserRepository(context)
        // Never-fail contract: without server credentials or while offline, returns secure local v2 token
        val token = repo.createProfileShareToken(7)
        assertNotNull(token)
        assertTrue(token.length >= 32)

        // Valid v2 URI format parsing test
        val mockServerToken = "mock_server_token_1234567890abcdef1234567890"
        val uri = QrCodeManager.buildProfileUri(mockServerToken)
        val parsedToken = QrCodeManager.parseProfileToken(uri)
        assertEquals(mockServerToken, parsedToken)
    }
}
