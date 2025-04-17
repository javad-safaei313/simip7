package com.simip.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * Helper class to get the current device location (Latitude, Longitude, Altitude)
 * using the Fused Location Provider API.
 *
 * Requires Google Play Services Location library:
 * `implementation 'com.google.android.gms:play-services-location:...'`
 *
 * Requires Manifest permissions: ACCESS_FINE_LOCATION and/or ACCESS_COARSE_LOCATION.
 * Permissions must be requested at runtime.
 */
class LocationHelper(private val context: Context) {

    private val TAG = "LocationHelper"
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _lastKnownLocation = MutableStateFlow<Location?>(null)
    val lastKnownLocation: StateFlow<Location?> = _lastKnownLocation.asStateFlow()

    init {
        // Optionally fetch last known location immediately if permissions are granted
        if (hasLocationPermission()) {
            fetchLastKnownLocation()
        }
    }

    /**
     * Checks if the required location permissions (Fine or Coarse) are granted.
     * @return True if either ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION is granted.
     */
    fun hasLocationPermission(): Boolean {
        return (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * Fetches the last known location from the provider.
     * This is fast but might be null or outdated.
     * Updates the lastKnownLocation StateFlow.
     */
    private fun fetchLastKnownLocation() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted, cannot fetch last known location.")
            _lastKnownLocation.value = null
            return
        }
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        Log.d(TAG, "Fetched last known location: Lat=${location.latitude}, Lon=${location.longitude}")
                        _lastKnownLocation.value = location
                    } else {
                        Log.w(TAG, "Last known location is null.")
                        _lastKnownLocation.value = null
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get last known location: ${e.message}", e)
                    _lastKnownLocation.value = null
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException fetching last location: ${e.message}. Check permission again.", e)
            _lastKnownLocation.value = null
        }
    }


    /**
     * Requests a single fresh location update with a specified accuracy and timeout.
     * This is more accurate than last known location but takes longer and consumes more battery.
     *
     * @param priority The desired accuracy/power trade-off (e.g., Priority.PRIORITY_HIGH_ACCURACY).
     * @param timeout Duration to wait for a location update.
     * @return The Location object if successful within the timeout, null otherwise.
     */
    suspend fun getCurrentLocation(
        priority: Int = Priority.PRIORITY_HIGH_ACCURACY, // High accuracy for geo-referencing measurements
        timeout: kotlin.time.Duration = 10.seconds // Timeout for getting a fix
    ): Location? {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted. Cannot request current location.")
            return null
        }

        val locationRequest = LocationRequest.Builder(priority, timeout.inWholeMilliseconds / 2) // Interval slightly less than timeout
            .setWaitForAccurateLocation(true) // Crucial for getting a good fix
            .setMinUpdateIntervalMillis(1000) // Minimum interval if multiple updates occur
            .setMaxUpdates(1) // Request only one update
            .build()

        val deferredLocation = CompletableDeferred<Location?>()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                Log.d(TAG,"Received location update: Lat=${location?.latitude}, Lon=${location?.longitude}, Alt=${location?.altitude}, Acc=${location?.accuracy}")
                // We only requested one update, so remove the callback immediately
                fusedLocationClient.removeLocationUpdates(this)
                deferredLocation.complete(location)
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location is not available currently on the device.")
                    // If location becomes unavailable after request started, we might need to time out.
                    // The timeout mechanism below handles this.
                }
            }
        }

        try {
            Log.d(TAG,"Requesting single location update with priority $priority and timeout ${timeout.inWholeSeconds}s...")
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

            // Wait for the callback to complete or timeout
            return  withTimeout(timeout) {
                   deferredLocation.await()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting location updates: ${e.message}", e)
            fusedLocationClient.removeLocationUpdates(locationCallback) // Clean up callback on error
            return null
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Timeout waiting for location update after ${timeout.inWholeSeconds} seconds.")
            fusedLocationClient.removeLocationUpdates(locationCallback) // Clean up callback on timeout
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location updates: ${e.message}", e)
            fusedLocationClient.removeLocationUpdates(locationCallback) // Clean up callback on error
            return null
        }
    }


    /**
     * Provides a Flow of location updates.
     * Useful if continuous location updates are needed (e.g., tracking).
     * Make sure to cancel the collection of this flow when updates are no longer needed
     * to save battery.
     *
     * @param intervalMillis Desired interval between updates.
     * @param priority Desired accuracy/power trade-off.
     * @return A Flow emitting Location objects. The flow completes if permissions are lost or an error occurs.
     */
    fun locationUpdatesFlow(
        intervalMillis: Long = 5000L, // e.g., update every 5 seconds
        priority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY
    ): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted. Cannot start location updates flow.")
            close(SecurityException("Location permission not granted.")) // Close the flow with an error
            return@callbackFlow
        }

        val locationRequest = LocationRequest.Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2) // Allow faster updates if available
            .setWaitForAccurateLocation(false) // Usually false for continuous updates
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let {
                    Log.v(TAG, "Flow emitting location: Lat=${it.latitude}, Lon=${it.longitude}")
                    trySend(it).isSuccess // Offer the location to the flow collector
                }
            }
            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Location became unavailable during flow updates.")
                }
            }
        }

        Log.d(TAG, "Starting location updates flow...")
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG,"SecurityException starting location flow: ${e.message}")
            close(e) // Close the flow with the error
        } catch (e: Exception) {
            Log.e(TAG,"Exception starting location flow: ${e.message}")
            close(e) // Close the flow with the error
        }


        // awaitClose is crucial for cleanup when the flow collector cancels
        awaitClose {
            Log.d(TAG, "Stopping location updates flow.")
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

}