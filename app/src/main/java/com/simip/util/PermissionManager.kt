package com.simip.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * Helper class to manage runtime permissions using the modern Activity Result API.
 * Handles checking and requesting single or multiple permissions.
 */
class PermissionManager {

    private val TAG = "PermissionManager"

    // --- Required Permissions for Simip App ---
    // List all permissions required by the app based on the spec.
    companion object {
        val locationPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf( // Fine location is primary, Coarse is fallback but often needed together?
                Manifest.permission.ACCESS_FINE_LOCATION // Needed for precise GPS and Wi-Fi scan on some versions
                // Manifest.permission.ACCESS_COARSE_LOCATION // Usually granted if FINE is
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
                // Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val wifiPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // CHANGE_WIFI_STATE needed for NetworkRequest/Suggestions API
            arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
                // ACCESS_FINE_LOCATION is also implicitly needed for scanning/connection results on some versions
            )
        } else {
            // Pre-Q, CHANGE_WIFI_STATE was often system/signature level, but needed for enable/disable etc.
            arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        }

        val storagePermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Needed for legacy file saving in Downloads for Excel export
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            // Not needed for MediaStore access on Q+
            emptyArray<String>()
        }

        // Combine all potentially needed permissions
        val allRequiredPermissions: Array<String> = (locationPermissions + wifiPermissions + storagePermission).distinct().toTypedArray()

        // Specific groups for targeted requests
        val locationAndWifiPermissions = (locationPermissions + wifiPermissions).distinct().toTypedArray()
    }


    // --- Permission Checking ---

    /**
     * Checks if a specific permission is granted.
     * @param context Context.
     * @param permission The permission string (e.g., Manifest.permission.ACCESS_FINE_LOCATION).
     * @return True if the permission is granted, false otherwise.
     */
    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if all permissions in a list are granted.
     * @param context Context.
     * @param permissions Array of permission strings.
     * @return True if all permissions are granted, false otherwise.
     */
    fun arePermissionsGranted(context: Context, permissions: Array<String>): Boolean {
        if (permissions.isEmpty()) return true // No permissions requested means granted
        return permissions.all { isPermissionGranted(context, it) }
    }


    // --- Permission Requesting (using Activity Result API) ---

    /**
     * Registers an ActivityResultLauncher for requesting a single permission.
     * Should be called in the Activity's or Fragment's initialization phase (e.g., onCreate or field initializer).
     *
     * @param activity The ComponentActivity to register the launcher with.
     * @param onResult Callback lambda invoked with the result (Boolean: true if granted, false otherwise).
     * @return The configured ActivityResultLauncher<String>.
     */
    fun registerRequestPermissionLauncher(
        activity: ComponentActivity,
        onResult: (isGranted: Boolean) -> Unit
    ): ActivityResultLauncher<String> {
        return activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            Log.d(TAG, "Single permission request result: Granted = $isGranted")
            onResult(isGranted)
        }
    }

    /**
     * Registers an ActivityResultLauncher for requesting multiple permissions.
     * Should be called in the Activity's or Fragment's initialization phase.
     *
     * @param activity The ComponentActivity to register the launcher with.
     * @param onResult Callback lambda invoked with the result map (Map<String, Boolean> where key is permission, value is grant status).
     * @return The configured ActivityResultLauncher<Array<String>>.
     */
    fun registerRequestMultiplePermissionsLauncher(
        activity: ComponentActivity,
        onResult: (grantResults: Map<String, Boolean>) -> Unit
    ): ActivityResultLauncher<Array<String>> {
        return activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            Log.d(TAG, "Multiple permissions request result: $grantResults")
            onResult(grantResults)
        }
    }

    /**
     * Registers an ActivityResultLauncher for requesting a single permission within a Fragment.
     *
     * @param fragment The Fragment to register the launcher with.
     * @param onResult Callback lambda invoked with the result (Boolean: true if granted, false otherwise).
     * @return The configured ActivityResultLauncher<String>.
     */
    fun registerRequestPermissionLauncher(
        fragment: Fragment,
        onResult: (isGranted: Boolean) -> Unit
    ): ActivityResultLauncher<String> {
        return fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            Log.d(TAG, "Single permission request result (Fragment): Granted = $isGranted")
            onResult(isGranted)
        }
    }

    /**
     * Registers an ActivityResultLauncher for requesting multiple permissions within a Fragment.
     *
     * @param fragment The Fragment to register the launcher with.
     * @param onResult Callback lambda invoked with the result map (Map<String, Boolean>).
     * @return The configured ActivityResultLauncher<Array<String>>.
     */
    fun registerRequestMultiplePermissionsLauncher(
        fragment: Fragment,
        onResult: (grantResults: Map<String, Boolean>) -> Unit
    ): ActivityResultLauncher<Array<String>> {
        return fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            Log.d(TAG, "Multiple permissions request result (Fragment): $grantResults")
            onResult(grantResults)
        }
    }


    /**
     * Requests a single permission if it's not already granted.
     * Requires a pre-registered launcher.
     *
     * @param context Context (needed for checking).
     * @param launcher The ActivityResultLauncher<String> obtained from registration.
     * @param permission The permission to request.
     * @param showRationale Optional lambda to show rationale before requesting (if needed). Return true to proceed with request.
     */
    fun requestPermissionIfNeeded(
        context: Context,
        launcher: ActivityResultLauncher<String>,
        permission: String,
        activity: ComponentActivity? = null, // Needed for shouldShowRequestPermissionRationale
        showRationale: (() -> Boolean)? = null
    ) {
        when {
            isPermissionGranted(context, permission) -> {
                Log.d(TAG, "Permission '$permission' already granted.")
                // Optionally trigger the callback directly if needed by caller logic
                // launcher.activityResultRegistry.dispatchResult(...) // Not standard way
            }
            activity != null && showRationale != null && activity.shouldShowRequestPermissionRationale(permission) -> {
                Log.d(TAG, "Showing rationale for permission '$permission'.")
                if (showRationale()) {
                    Log.d(TAG, "Rationale accepted, launching permission request.")
                    launcher.launch(permission)
                } else {
                    Log.d(TAG, "Rationale declined or handled, not launching request now.")
                }
            }
            else -> {
                Log.d(TAG, "Requesting permission '$permission'.")
                launcher.launch(permission)
            }
        }
    }

    /**
     * Requests multiple permissions if any of them are not already granted.
     * Requires a pre-registered launcher.
     *
     * @param context Context.
     * @param launcher The ActivityResultLauncher<Array<String>> obtained from registration.
     * @param permissions Array of permissions to request.
     * @param activity ComponentActivity needed for rationale check (optional but recommended).
     * @param showRationale Optional lambda invoked if rationale is needed for *any* of the permissions. Return true to proceed.
     */
    fun requestMultiplePermissionsIfNeeded(
        context: Context,
        launcher: ActivityResultLauncher<Array<String>>,
        permissions: Array<String>,
        activity: ComponentActivity? = null, // Needed for rationale check
        showRationale: (() -> Boolean)? = null
    ) {
        val permissionsToRequest = permissions.filter { !isPermissionGranted(context, it) }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            Log.d(TAG, "All requested permissions already granted: ${permissions.joinToString()}.")
            // Optionally trigger callback? Need careful handling as launcher expects system result.
            return
        }

        val shouldShowRationale = activity != null && showRationale != null &&
                permissionsToRequest.any { activity.shouldShowRequestPermissionRationale(it) }

        if (shouldShowRationale) {
            Log.d(TAG, "Showing rationale for permissions: ${permissionsToRequest.joinToString()}.")
            if (showRationale!!()) { // Invoke the lambda
                Log.d(TAG, "Rationale accepted, launching permission request.")
                launcher.launch(permissionsToRequest)
            } else {
                Log.d(TAG, "Rationale declined or handled, not launching request now.")
            }
        } else {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}.")
            launcher.launch(permissionsToRequest)
        }
    }

}