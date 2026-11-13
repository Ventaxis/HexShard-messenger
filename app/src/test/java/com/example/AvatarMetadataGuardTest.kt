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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun tinyJpeg(): ByteArray = android.util.Base64.decode(
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof" +
            "Hh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAF" +
            "AABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN/" +
            "/2Q==",
        android.util.Base64.DEFAULT
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
        // The repository under test resolves its public URL against the mock base
        // URL (http://localhost), which is not a https storage URL, so the guarded
        // auth-metadata PUT must not fire. Exactly two requests are expected:
        // the storage upload and the profiles PATCH; no /auth/v1/user write.
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"Key\":\"avatars/x\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[{\"id\":\"11111111-1111-1111-1111-111111111111\"}]"))

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val repository = com.example.data.repository.ProfileRepository(context)

        repository.uploadAvatar(
            userId = "11111111-1111-1111-1111-111111111111",
            token = "test-token",
            imageBytes = tinyJpeg()
        )

        val recordedPaths = (0 until server.requestCount)
            .map { server.takeRequest().path.orEmpty().substringBefore("?") }
        assertEquals(2, recordedPaths.size)
        assertTrue(recordedPaths[0].startsWith("/storage/v1/object/avatars/11111111-1111-1111-1111-111111111111/avatar_"))
        assertEquals("/rest/v1/profiles", recordedPaths[1])
        assertFalse("auth-metadata write must not fire for non-storage URLs",
            recordedPaths.any { it.startsWith("/auth/v1/user") })
    }
}
