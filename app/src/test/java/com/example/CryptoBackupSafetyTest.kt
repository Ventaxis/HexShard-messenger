package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.crypto.CryptoBackupManager
import com.example.crypto.E2ECryptoManager
import com.example.crypto.HardwareKeyNonExportableException
import com.example.data.SecurePrefsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CryptoBackupSafetyTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecurePrefsManager.saveSupabaseTokens(context, "test_valid_access_token", "test_valid_refresh_token")
    }

    @Test
    fun testBackupFailsSafelyWithoutDestructiveKeyReplacement() = runBlocking {
        val testAccountId = "test-user-vault-preserve"

        // Ensure no exportable software keys exist initially
        assertNull(E2ECryptoManager.getExportableEcdhPrivateKey(testAccountId))
        assertNull(E2ECryptoManager.getExportableSigningPrivateKey(testAccountId))

        // Attempting to create backup when exportable keys are absent
        // must fail-closed with HardwareKeyNonExportableException
        // and must NEVER replace or fabricate keys in memory!
        val result = CryptoBackupManager.createAndUploadBackup(
            context = context,
            accountId = testAccountId,
            passphrase = "test-secure-passphrase-123".toCharArray()
        )

        assertTrue("Backup attempt without exportable keys must report failure", result.isFailure)
        val exception = result.exceptionOrNull()
        assertNotNull(exception)
        assertTrue(
            "Exception must be HardwareKeyNonExportableException, got: ${exception?.javaClass?.simpleName}",
            exception is HardwareKeyNonExportableException
        )

        // Verify no software keys were generated or cached as side-effects
        assertNull(
            "Active exportable ECDH key must remain null without mutating active state",
            E2ECryptoManager.getExportableEcdhPrivateKey(testAccountId)
        )
        assertNull(
            "Active exportable Signing key must remain null without mutating active state",
            E2ECryptoManager.getExportableSigningPrivateKey(testAccountId)
        )
    }

    @Test
    fun testKeypairCorrespondenceValidation() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))

        val pair1 = kpg.generateKeyPair()
        val pair2 = kpg.generateKeyPair()

        // Valid matching pair
        assertTrue(
            "Matching keypair must pass correspondence verification",
            CryptoBackupManager.verifyKeyPairCorrespondence(pair1.private, pair1.public)
        )

        // Mismatched pair (private key 1 with public key 2)
        assertFalse(
            "Mismatched keypair must be rejected by correspondence check",
            CryptoBackupManager.verifyKeyPairCorrespondence(pair1.private, pair2.public)
        )
    }
}
