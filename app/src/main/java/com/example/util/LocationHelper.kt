package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Strict typed location payload for E2EE transmission in HexShard.
 * Enforces bounded coordinates, valid accuracy, and prevents arbitrary URL injection.
 */
data class LocationPayload(
    val version: Int = 1,
    val type: String = "location",
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean {
        if (version != 1) return false
        if (type != "location") return false
        if (latitude.isNaN() || latitude.isInfinite() || latitude < -90.0 || latitude > 90.0) return false
        if (longitude.isNaN() || longitude.isInfinite() || longitude < -180.0 || longitude > 180.0) return false
        if (accuracy.isNaN() || accuracy.isInfinite() || accuracy < 0f) return false
        if (timestamp <= 0) return false
        return true
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("version", version)
            put("type", type)
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy", accuracy.toDouble())
            put("timestamp", timestamp)
        }.toString()
    }

    /**
     * Safe Android geo URI for ACTION_VIEW intent.
     * Guaranteed to never be an arbitrary http/https address.
     */
    val geoUri: String
        get() = "geo:$latitude,$longitude?q=$latitude,$longitude(Shared+Location)"

    // Legacy property for backward compatibility
    val mapsUrl: String
        get() = geoUri

    companion object {
        fun fromJson(jsonStr: String?): LocationPayload? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(jsonStr.trim())
                if (obj.optString("type") != "location") return null
                val version = obj.optInt("version", 1)
                val lat = obj.getDouble("latitude")
                val lng = obj.getDouble("longitude")
                val acc = obj.optDouble("accuracy", 0.0).toFloat()
                val ts = obj.optLong("timestamp", System.currentTimeMillis())

                val payload = LocationPayload(
                    version = version,
                    type = "location",
                    latitude = lat,
                    longitude = lng,
                    accuracy = acc,
                    timestamp = ts
                )
                if (payload.isValid()) payload else null
            } catch (e: Exception) {
                null
            }
        }
    }
}

// Alias for seamless backward compatibility
typealias LocationData = LocationPayload

object LocationHelper {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Obtains the single current user location strictly on user interaction.
     * Never runs in the background.
     */
    suspend fun getCurrentLocation(context: Context): LocationPayload? = suspendCancellableCoroutine { continuation ->
        if (!hasLocationPermission(context)) {
            Timber.w("Location permission not granted for user-triggered share")
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val cts = CancellationTokenSource()

            continuation.invokeOnCancellation {
                cts.cancel()
            }

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        val payload = LocationPayload(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                        )
                        continuation.resume(if (payload.isValid()) payload else null)
                    } else {
                        // Fallback to last known location if immediate fix is null
                        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                            if (lastLoc != null) {
                                val payload = LocationPayload(
                                    latitude = lastLoc.latitude,
                                    longitude = lastLoc.longitude,
                                    accuracy = lastLoc.accuracy,
                                    timestamp = lastLoc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                                )
                                continuation.resume(if (payload.isValid()) payload else null)
                            } else {
                                continuation.resume(null)
                            }
                        }.addOnFailureListener {
                            continuation.resume(null)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Timber.w(e, "Failed to retrieve current location")
                    continuation.resume(null)
                }
        } catch (e: SecurityException) {
            Timber.e(e, "SecurityException while requesting location")
            continuation.resume(null)
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error retrieving location")
            continuation.resume(null)
        }
    }
}
