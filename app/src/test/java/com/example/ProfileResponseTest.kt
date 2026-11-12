package com.example

import com.example.data.repository.ProfileRepository
import org.json.JSONArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class ProfileResponseTest {
    @Test
    fun `http error object is reported instead of parsed as array`() {
        try {
            ProfileRepository.responseArrayOrThrow("{\"message\":\"Profile write rejected\"}", 403)
            fail("Expected an HTTP failure")
        } catch (e: IllegalStateException) {
            assertEquals("Profile write rejected", e.message)
        }
    }

    @Test
    fun `http error string never reaches JSONArray parser`() {
        try {
            ProfileRepository.responseArrayOrThrow("permission denied", 403)
            fail("Expected an HTTP failure")
        } catch (e: IllegalStateException) {
            assertEquals("permission denied", e.message)
        }
    }

    @Test
    fun `successful representation array remains supported`() {
        val result: JSONArray = ProfileRepository.responseArrayOrThrow("[{\"id\":\"owner\"}]", 200)
        assertEquals("owner", result.getJSONObject(0).getString("id"))
        assertTrue(ProfileRepository.responseArrayOrThrow("", 200).length() == 0)
    }
}
