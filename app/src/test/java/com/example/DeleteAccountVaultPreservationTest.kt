package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SecurePrefsManager
import com.example.network.supabase.DeleteAccountResult
import com.example.network.supabase.SupabaseAuthService
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec (K02): account deletion must fail closed — when the server refuses to delete
 * the account, the local vault (account identity + tokens) is preserved for retry,
 * and the result reaches callers as a typed value instead of a bare Boolean.
 *
 * The server is faked with an OkHttp interceptor, so the test is deterministic and
 * never touches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeleteAccountVaultPreservationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fakeClient(status: Int): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain: Interceptor.Chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("fake")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun seedVault() {
        SecurePrefsManager.saveProfile(
            context,
            userId = "11111111-2222-3333-4444-555555555555",
            username = "vault_tester",
            phone = "+99912345678"
        )
        SecurePrefsManager.saveSupabaseTokens(context, "access-token", "refresh-token")
    }

    @Test
    fun `server failure returns typed Error and preserves the vault`() = runBlocking {
        seedVault()

        val result = SupabaseAuthService.deleteAccount(context, fakeClient(500))

        assertTrue("Expected DeleteAccountResult.Error, got $result", result is DeleteAccountResult.Error)
        assertEquals(
            "Account identity must survive a failed deletion for retry",
            "11111111-2222-3333-4444-555555555555",
            SecurePrefsManager.getUserId(context)
        )
        assertEquals(
            "Access token must survive a failed deletion for retry",
            "access-token",
            SecurePrefsManager.getSupabaseAccessToken(context)
        )
    }

    @Test
    fun `confirmed server deletion returns typed Success`() = runBlocking {
        seedVault()

        val result = SupabaseAuthService.deleteAccount(context, fakeClient(200))

        assertTrue("Expected DeleteAccountResult.Success, got $result", result is DeleteAccountResult.Success)
        assertEquals("11111111-2222-3333-4444-555555555555", (result as DeleteAccountResult.Success).accountId)
    }

    @Test
    fun `deletion without an authenticated account fails closed with Error`() = runBlocking {
        val result = SupabaseAuthService.deleteAccount(context, fakeClient(200))

        assertTrue("Expected DeleteAccountResult.Error, got $result", result is DeleteAccountResult.Error)
    }
}
