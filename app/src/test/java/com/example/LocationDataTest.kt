package com.example

import com.example.util.LocationData
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
class LocationDataTest {

    @Test
    fun testSerializationAndDeserialization() {
        val original = LocationData(
            latitude = 55.7558,
            longitude = 37.6173,
            accuracy = 12.5f
        )

        val json = original.toJson()
        assertTrue(json.contains("\"latitude\":55.7558"))
        assertTrue(json.contains("\"longitude\":37.6173"))
        assertTrue(json.contains("\"accuracy\":12.5"))

        val restored = LocationData.fromJson(json)
        assertNotNull(restored)
        assertEquals(original.latitude, restored?.latitude ?: 0.0, 0.0001)
        assertEquals(original.longitude, restored?.longitude ?: 0.0, 0.0001)
        assertEquals(original.accuracy, restored?.accuracy ?: 0f, 0.1f)
    }

    @Test
    fun testGeoUri() {
        val loc = LocationData(latitude = 37.7749, longitude = -122.4194)
        val uri = loc.geoUri
        assertEquals("geo:37.7749,-122.4194?q=37.7749,-122.4194(Shared+Location)", uri)
    }

    @Test
    fun testInvalidJsonHandling() {
        assertNull(LocationData.fromJson(""))
        assertNull(LocationData.fromJson("{invalid-json"))
        assertNull(LocationData.fromJson("{}")) // missing required fields
    }
}
