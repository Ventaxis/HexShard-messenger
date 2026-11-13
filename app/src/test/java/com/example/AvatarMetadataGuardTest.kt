package com.example

import android.content.Context
import com.example.network.supabase.SupabaseConfig
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the "error 520" avatar outage: a legacy build wrote the whole
 * base64 data-URI avatar into auth user_metadata, inflating every JWT until the
 * ingress rejected uploads with a bare 4xx/5xx. ProfileRepository must never
 * write avatar references into auth metadata unless they are real storage URLs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AvatarMetadataGuardTest {
    private lateinit var server: MockWebServer

    private fun tinyJpeg(): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
        0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01,
        0x00, 0x00, 0xFF.toByte(), 0xD9.toByte()
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        mockkObject(SupabaseConfig)
        every { SupabaseConfig.getBaseUrl() } returns server.url("/").toString().removeSuffix("/")
        every { SupabaseConfig.getAnonKey(any()) } returns "public-test-key"
    }

    @After
    fun tearDown() {
        unmockkObject(SupabaseConfig)
        server.shutdown()
    }

    @Test
    fun successfulUploadNeverWritesNonStorageUrlsIntoAuthMetadata() = runBlocking {
        // Only the storage upload is enqueued: the repository under test resolves
        // its public URL against the mock base URL (http://localhost), which is not
        // a https storage URL, so the guarded auth-metadata PUT must not fire.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"Key\":\"avatars/x\"}"))

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val repository = com.example.data.repository.ProfileRepository(context)

        repository.uploadAvatar(
            userId = "11111111-1111-1111-1111-111111111111",
            token = "test-token",
            imageBytes = tinyJpeg()
        )

        assertEquals(1, server.requestCount)
        assertEquals(
            "/storage/v1/object/avatars/11111111-1111-1111-1111-111111111111",
            server.takeRequest().path?.substringBefore("/avatar_")
        )
    }
}
