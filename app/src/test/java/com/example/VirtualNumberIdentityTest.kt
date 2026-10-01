package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SecurePrefsManager
import com.example.network.supabase.SessionManager
import com.example.util.VirtualNumberGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualNumberIdentityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SessionManager.clearSession(context)
        SecurePrefsManager.clear(context)
    }

    @Test
    fun testVirtualNumberFormattingAndExtraction() {
        val raw = "12345678"
        val formatted = VirtualNumberGenerator.format8Digits(raw)
        assertEquals("+999 1234 5678", formatted)
        assertTrue(VirtualNumberGenerator.isVirtual(formatted))

        val extracted = VirtualNumberGenerator.extractRaw8Digits(formatted)
        assertEquals("12345678", extracted)
    }

    @Test
    fun testSetAndGetPrivateVirtualNumberAccountIsolation() {
        val accountA = "user_account_a"
        val accountB = "user_account_b"

        SecurePrefsManager.setPrivateVirtualNumber(context, "67676767", accountA)
        SecurePrefsManager.setPrivateVirtualNumber(context, "88888888", accountB)

        // Verify account A strictly gets its own number
        val numA = SecurePrefsManager.getPrivateVirtualNumber(context, accountA)
        assertEquals("+999 6767 6767", numA)
        assertEquals("67676767", SecurePrefsManager.getRawPrivateVirtualNumber(context, accountA))

        // Verify account B strictly gets its own number
        val numB = SecurePrefsManager.getPrivateVirtualNumber(context, accountB)
        assertEquals("+999 8888 8888", numB)
        assertEquals("88888888", SecurePrefsManager.getRawPrivateVirtualNumber(context, accountB))
    }

    @Test
    fun testEmptyStringDoesNotWipeExistingValidNumber() {
        val accountId = "test_user_wipe_protection"
        SecurePrefsManager.setPrivateVirtualNumber(context, "12345678", accountId)
        assertEquals("+999 1234 5678", SecurePrefsManager.getPrivateVirtualNumber(context, accountId))

        // Blank string attempt must NOT wipe
        SecurePrefsManager.setPrivateVirtualNumber(context, "", accountId)
        SecurePrefsManager.setPrivateVirtualNumber(context, "   ", accountId)
        assertEquals("+999 1234 5678", SecurePrefsManager.getPrivateVirtualNumber(context, accountId))
    }

    @Test
    fun testOnLoginSuccessPreservesCachedNumberOnServerEmptyString() = runBlocking {
        val accountId = "cached_user_123"
        SecurePrefsManager.setPrivateVirtualNumber(context, "98765432", accountId)

        // Login with empty virtualNumber (e.g. server timeout / transient network glitch)
        SessionManager.onLoginSuccess(
            context = context,
            userId = accountId,
            username = "cached_hero",
            accessToken = "token_abc",
            refreshToken = "refresh_xyz",
            virtualNumber = "",
            isTelegramVerified = false
        )

        // Cached number MUST be preserved in session and local storage
        val session = SessionManager.currentSession.value
        assertNotNull(session)
        assertEquals("+999 9876 5432", session?.virtualNumber)
        assertEquals("+999 9876 5432", SecurePrefsManager.getPrivateVirtualNumber(context, accountId))
    }

    @Test
    fun testOnLoginSuccessWithServerNumberSetsActiveNumber() = runBlocking {
        val accountId = "new_login_user"

        SessionManager.onLoginSuccess(
            context = context,
            userId = accountId,
            username = "server_user",
            accessToken = "token_1",
            refreshToken = "refresh_1",
            virtualNumber = "55554444",
            isTelegramVerified = true
        )

        val session = SessionManager.currentSession.value
        assertNotNull(session)
        assertEquals("+999 5555 4444", session?.virtualNumber)
        assertEquals("+999 5555 4444", SecurePrefsManager.getPrivateVirtualNumber(context, accountId))
    }

    @Test
    fun testLogoutClearsSessionAndActiveNumber() = runBlocking {
        val accountId = "logout_user"
        SessionManager.onLoginSuccess(
            context = context,
            userId = accountId,
            username = "logout_user",
            accessToken = "token_logout",
            refreshToken = "refresh_logout",
            virtualNumber = "77777777",
            isTelegramVerified = false
        )
        assertNotNull(SessionManager.currentSession.value)

        // Perform logout
        SessionManager.clearSession(context)

        assertNull(SessionManager.currentSession.value)
        assertEquals("", SecurePrefsManager.getPrivateVirtualNumber(context, accountId))
    }

    @Test
    fun testExplicitClearRemovesAccountRecord() {
        val accountId = "explicit_clear_user"
        SecurePrefsManager.setPrivateVirtualNumber(context, "44443333", accountId)
        assertEquals("+999 4444 3333", SecurePrefsManager.getPrivateVirtualNumber(context, accountId))

        SecurePrefsManager.clearPrivateVirtualNumber(context, accountId)
        assertEquals("", SecurePrefsManager.getPrivateVirtualNumber(context, accountId))
    }
}
