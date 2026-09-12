package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.network.supabase.AuthState
import com.example.network.supabase.SessionManager
import com.example.network.supabase.SupabaseAuthService
import com.example.network.supabase.VirtualNumberConfirmationResult
import com.example.network.supabase.VirtualNumberReservationResult
import com.example.network.supabase.VirtualNumberService
import kotlinx.coroutines.runBlocking
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
class AuthServiceTest {

    @Test
    fun testUsernameValidation() {
        assertNotNull(SupabaseAuthService.validateUsername("ab")) // too short (<3)
        assertNotNull(SupabaseAuthService.validateUsername("user name with spaces")) // spaces invalid
        assertNotNull(SupabaseAuthService.validateUsername("invalid!char$")) // special chars invalid
        assertNull(SupabaseAuthService.validateUsername("valid_user_123")) // valid
        assertNull(SupabaseAuthService.validateUsername("@valid_user")) // leading @ stripped
    }

    @Test
    fun testPasswordValidation() {
        assertNotNull(SupabaseAuthService.validatePassword("short")) // <8 chars
        assertNull(SupabaseAuthService.validatePassword("secure_password_123")) // >=8 chars
    }

    @Test
    fun testFailClosedSessionCheckWithNoTokens() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SessionManager.clearSession(context)
        val state = SessionManager.checkSession(context)
        assertEquals(AuthState.UNAUTHENTICATED, state)
        assertNull(SessionManager.currentSession.value)
    }

    @Test
    fun testFailClosedVirtualNumberReservationWithoutAuth() = runBlocking {
        val res = VirtualNumberService.reserveCandidateNumber("", "")
        assertTrue(res is VirtualNumberReservationResult.Error)
        assertEquals("Authentication is required to reserve a number", (res as VirtualNumberReservationResult.Error).message)
    }

    @Test
    fun testFailClosedVirtualNumberConfirmationInvalidLength() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val res = VirtualNumberService.confirmVirtualNumberDetailed("user123", "token123", "12345", context)
        assertTrue(res is VirtualNumberConfirmationResult.Error)
        assertEquals("Number must contain exactly 8 digits", (res as VirtualNumberConfirmationResult.Error).message)
    }
}
