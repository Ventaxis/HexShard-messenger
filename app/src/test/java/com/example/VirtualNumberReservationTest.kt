package com.example

import com.example.network.supabase.SupabaseConfig
import com.example.network.supabase.VirtualNumberReservationResult
import com.example.network.supabase.VirtualNumberService
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VirtualNumberReservationTest {
    private lateinit var server: MockWebServer

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
    fun explicitPreferredNumberIsNeverReplacedByServerFallback() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"raw_number":"87654321","formatted":"+999 8765 4321","expires_at":2000000000000}"""
        ))

        val result = VirtualNumberService.reserveCandidateNumber("account", "user-token", "12345678")

        assertTrue(result is VirtualNumberReservationResult.Error)
        assertEquals("12345678", server.takeRequest().body.readUtf8().let {
            org.json.JSONObject(it).getString("p_preferred")
        })
    }

    @Test
    fun missingReservationRpcNeverCreatesLocalReservation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"code":"PGRST202","message":"Could not find the function in the schema cache"}"""
        ))

        val result = VirtualNumberService.reserveCandidateNumber("account", "user-token", "12345678")

        assertTrue(result is VirtualNumberReservationResult.Error)
    }
}
