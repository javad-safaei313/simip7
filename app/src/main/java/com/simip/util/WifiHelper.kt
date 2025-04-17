package com.simip.util

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
/**
 * Helper class for managing Wi-Fi connections, specifically finding and connecting
 * to a network based on a partial SSID match.
 *
 * NOTE: Wi-Fi handling, especially scanning and programmatic connection, has significant
 * limitations and API changes in modern Android versions (10+). This implementation
 * uses older APIs for broader compatibility but might require adjustments and alternative
 * approaches (like NetworkRequest API) for reliable operation on newer devices.
 * Requires Manifest permissions: ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION.
 */
class WifiHelper(
    private val context: Context,
    private val wifiManager: WifiManager,
    private val targetSsidPattern: String // Case-insensitive pattern (e.g., "kia")
) {
    private val TAG = "WifiHelper"

    // Enum to represent internal state or exposed state via Flow
    enum class WifiState {
        IDLE, SCANNING, CONNECTING, CONNECTED_TO_TARGET, CONNECTION_FAILED, ERROR
    }

    // Flow to observe Wi-Fi state changes (simplified example)
    // A more robust implementation might use ConnectivityManager callbacks
    val wifiStateFlow: StateFlow<WifiState> = callbackFlow {
        // This flow is basic, a real implementation would register receivers
        // or network callbacks to provide more accurate state updates.
        trySend(WifiState.IDLE) // Initial state
        awaitClose { /* Unregister receivers/callbacks here */ }
    }.stateIn(CoroutineScope(Dispatchers.Default + Job()), SharingStarted.WhileSubscribed(5000), WifiState.IDLE)


    /**
     * Attempts to find the target Wi-Fi network based on the SSID pattern and connect to it.
     * Handles enabling Wi-Fi if disabled and performs scanning.
     * Uses deprecated APIs for connecting on pre-Android 10 devices.
     * Uses NetworkRequest API for Android 10+ (requires user interaction usually).
     *
     * @param maxRetries Max number of scan/connection attempts.
     * @param retryDelay Delay between retries.
     * @return The ScanResult of the connected network, or null if connection failed after retries.
     */
    suspend fun findAndConnectToTargetWifi(maxRetries: Int, retryDelay: Duration): ScanResult? {
        if (!hasWifiPermissions()) {
            Log.e(TAG, "Missing necessary Wi-Fi permissions.")
            return null
        }

        // 1. Ensure Wi-Fi is enabled
        if (!wifiManager.isWifiEnabled) {
            Log.i(TAG, "Wi-Fi is disabled, attempting to enable...")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("deprecation")
                if (!wifiManager.setWifiEnabled(true)) {
                    Log.e(TAG, "Failed to enable Wi-Fi (pre-Q).")
                    return null
                }
            } else {
                // On Android 10+, apps cannot enable Wi-Fi directly.
                // Need to prompt user via Settings panel or assume user enables it.
                Log.w(TAG, "Cannot enable Wi-Fi programmatically on Android 10+. Please enable manually.")
                // Wait a bit for user to potentially enable it
                delay(5000)
                if (!wifiManager.isWifiEnabled) {
                    Log.e(TAG, "Wi-Fi remains disabled.")
                    return null
                }
            }
            // Wait a bit for Wi-Fi to stabilize after enabling
            delay(3000)
        }

        var targetNetwork: ScanResult? = null
        for (attempt in 1..maxRetries) {
            Log.d(TAG, "Connection attempt $attempt/$maxRetries...")

            // 2. Scan for networks
            val scanResults = scanForWifiNetworks()
            if (scanResults == null) {
                Log.w(TAG, "Wi-Fi scan failed or timed out on attempt $attempt.")
                delay(retryDelay)
                continue // Retry scan
            }
            if (scanResults.isEmpty()) {
                Log.w(TAG, "Wi-Fi scan returned empty results on attempt $attempt.")
                delay(retryDelay)
                continue
            }

            // 3. Find target network by SSID pattern (case-insensitive)
            targetNetwork = scanResults.firstOrNull {
                it.SSID?.contains(targetSsidPattern, ignoreCase = true) == true
            }

            if (targetNetwork != null) {
                Log.i(TAG, "Target network found: ${targetNetwork.SSID} (RSSI: ${targetNetwork.level})")

                // 4. Attempt connection
                if (connectToNetwork(targetNetwork)) {
                    Log.i(TAG, "Connection attempt initiated for ${targetNetwork.SSID}.")
                    // 5. Verify Connection Status
                    if (verifyConnection(targetNetwork.SSID, 15.seconds)) { // Increased timeout for connection verification
                        Log.i(TAG, "Successfully connected to ${targetNetwork.SSID}")
                        return targetNetwork // Success
                    } else {
                        Log.w(TAG, "Failed to verify connection to ${targetNetwork.SSID} within timeout.")
                        // Optionally remove/forget network config here if added manually
                        targetNetwork = null // Reset for retry
                    }
                } else {
                    Log.w(TAG, "Connection initiation failed for ${targetNetwork.SSID}.")
                    targetNetwork = null // Reset for retry
                }
            } else {
                Log.w(TAG, "Target network with pattern '$targetSsidPattern' not found in scan results.")
            }

            // Wait before next attempt if not successful
            if (targetNetwork == null && attempt < maxRetries) {
                Log.d(TAG, "Waiting ${retryDelay.inWholeMilliseconds}ms before next attempt...")
                delay(retryDelay)
            }
        }

        Log.e(TAG, "Failed to connect to target network after $maxRetries attempts.")
        return null // Failed after all retries
    }

    /**
     * Performs a Wi-Fi scan and waits for results.
     * Handles necessary permissions and API level differences.
     * @return List of ScanResult, or null if scan failed/timed out.
     */
    private suspend fun scanForWifiNetworks(): List<ScanResult>? = withContext(Dispatchers.IO) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // Location needed for scan >= Marshmallow
            Log.e(TAG, "ACCESS_FINE_LOCATION permission missing for Wi-Fi scan.")
            // Consider requesting permission here or informing the user.
            return@withContext null
        }

        val scanSuccess = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                } else {
                    // On older versions, assume success if receiver is called. Check results manually.
                    true // Need to check wifiManager.scanResults later
                }
                Log.d(TAG, "Scan results available: $success")
                context.unregisterReceiver(this) // Unregister immediately
                scanSuccess.complete(success)
            }
        }

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(receiver, intentFilter)

        Log.d(TAG, "Starting Wi-Fi scan...")
        @Suppress("deprecation")
        val started = wifiManager.startScan()
        if (!started) {
            Log.e(TAG, "wifiManager.startScan() returned false.")
            try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) {}
            return@withContext null // Scan could not be initiated
        }

        // Wait for scan results broadcast with timeout
        try {
            withTimeout(15000L) { // 15-second timeout for scan results
                scanSuccess.await()
            }
            // Short delay after broadcast before accessing results
            delay(500)
            // Permissions checked at start, but suppress lint warning here if needed
            @Suppress("MissingPermission")
            return@withContext wifiManager.scanResults ?: emptyList()
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Wi-Fi scan timed out waiting for results.")
            try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) {}
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Error during Wi-Fi scan: ${e.message}", e)
            try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) {}
            return@withContext null
        }
    }

    /**
     * Attempts to connect to the specified network.
     * Uses appropriate API based on Android version.
     * @param network The ScanResult of the target network.
     * @return True if connection attempt was initiated, false otherwise.
     */
    private suspend fun connectToNetwork(network: ScanResult): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting to connect using appropriate API for SSID: ${network.SSID}")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // --- Pre-Android 10 Method (Deprecated) ---
            @Suppress("deprecation", "MissingPermission")
            try {
                // Check existing configurations

                val existingConfigs = wifiManager.configuredNetworks ?: emptyList()
                var netId = existingConfigs.firstOrNull { it.SSID == "\"${network.SSID}\"" }?.networkId ?: -1

                if (netId == -1) {
                    // Create new configuration (assuming open network as per spec common case)
                    // WARNING: Handling secured networks (WPA/WEP) requires more complex config.
                    val conf = WifiConfiguration()
                    conf.SSID = "\"${network.SSID}\"" // SSID needs to be quoted
                    // Assuming open network (no password) based on typical device hotspots
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    // Optional: set BSSID if known and needed conf.BSSID = network.BSSID

                    Log.d(TAG, "Adding new network configuration for ${conf.SSID}")
                    netId = wifiManager.addNetwork(conf)
                    if (netId == -1) {
                        Log.e(TAG, "Failed to add network configuration.")
                        return@withContext false
                    }
                    // Save configuration may or may not be needed depending on device behavior
                    // wifiManager.saveConfiguration()
                } else {
                    Log.d(TAG, "Found existing network configuration (ID: $netId) for ${network.SSID}")
                    // Ensure it's enabled before connecting
                }

                // Disconnect from current network before connecting to new one
                wifiManager.disconnect()
                delay(500) // Allow time for disconnection

                // Enable and connect
                val enabled = wifiManager.enableNetwork(netId, true)
                if (!enabled) {
                    Log.e(TAG, "Failed to enable network ID: $netId")
                    // Maybe remove the config if we added it? wifiManager.removeNetwork(netId)
                    return@withContext false
                }
                val reconnected = wifiManager.reconnect()
                if (!reconnected) {
                    Log.w(TAG, "wifiManager.reconnect() failed for network ID: $netId")
                    // Fallback, try reassociate
                    if (!wifiManager.reassociate()) {
                        Log.w(TAG, "wifiManager.reassociate() also failed.")
                        // Consider this a failure? Maybe connection happens anyway.
                    }
                }
                Log.d(TAG, "enableNetwork/reconnect initiated for network ID: $netId")
                return@withContext true // Initiation successful

            }  catch (e: SecurityException) { // اضافه کردن catch برای اطمینان بیشتر
                Log.e(TAG, "SecurityException accessing configuredNetworks (pre-Q): ${e.message}", e)
                return@withContext false
            }catch (e: Exception) {
                Log.e(TAG, "Error connecting (pre-Q): ${e.message}", e)
                return@withContext false
            }

        } else {
            // --- Android 10 (Q) and above Method (NetworkRequest API) ---
            Log.d(TAG, "Using NetworkRequest API for Android Q+")
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(network.SSID) // No quotes needed here
            // .setBssid(MacAddress.fromString(network.BSSID)) // Optional: Specify BSSID
            // Add password if required (WPA2/WPA3 etc.)
            // Assuming open network:
            // specifierBuilder.setWpa2Passphrase("password")

            val networkSpecifier = try {
                specifierBuilder.build()
            } catch (e: Exception) {
                Log.e(TAG, "Error building WifiNetworkSpecifier: ${e.message}", e)
                return@withContext false
            }


            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                //.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Suggest it's peer-to-peer
                .setNetworkSpecifier(networkSpecifier)
                .build()

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                // Store the callback to unregister later if needed, but API handles timeout etc.
            }

            try {
                // This request prompts the user with a system dialog to confirm connection.
                // The app doesn't get direct control; the system handles the connection.
                connectivityManager.requestNetwork(networkRequest, networkCallback, 15000) // 15 sec timeout for user action/connection
                Log.i(TAG, "NetworkRequest sent. Connection depends on system/user.")
                // We assume initiation success here. Verification happens separately.
                return@withContext true
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException requesting network: ${e.message}. Check CHANGE_WIFI_STATE permission.", e)
                return@withContext false
            } catch (e: Exception) {
                Log.e(TAG, "Exception requesting network: ${e.message}", e)
                return@withContext false
            }
        }
    }


    /**
     * Verifies if the device is currently connected to the expected Wi-Fi network.
     * @param targetSsid The SSID of the network to check against.
     * @param timeout Timeout duration to wait for connection confirmation.
     * @return True if connected to the target SSID within the timeout, false otherwise.
     */
    private suspend fun verifyConnection(targetSsid: String, timeout: Duration): Boolean = withContext(Dispatchers.IO) sendContext@{
        val startTime = System.currentTimeMillis()
        var isConnectedToTarget = false
        while (System.currentTimeMillis() - startTime < timeout.inWholeMilliseconds) {
            val currentSsid = getCurrentSsid()
            Log.d(TAG, "Verifying connection... Current SSID: '$currentSsid', Target: '$targetSsid'")
            if (currentSsid != null && currentSsid.equals(targetSsid, ignoreCase = true)) {
                // Double check network capabilities if possible
                if (isConnectedToInternetOrPeer()) { // Check if network is actually usable
                    isConnectedToTarget = true
                    break
                } else {
                    Log.d(TAG, "Connected to SSID $currentSsid but network not usable yet.")
                }
            }
            delay(1000) // Check every second
        }
        return@sendContext isConnectedToTarget
    }


    /**
     * Gets the SSID of the currently connected Wi-Fi network.
     * Handles permissions and potential null values.
     * @return SSID string (without quotes), or null if not connected or error.
     */
    private fun getCurrentSsid(): String? {
        if (!hasWifiPermissions()) return null
        if (!wifiManager.isWifiEnabled) return null

        val connectionInfo = try { wifiManager.connectionInfo } catch (e: Exception) { null }
        // Suppress warnings about deprecated API for compatibility
        @Suppress("deprecation", "DEPRECATION")
        var ssid = connectionInfo?.ssid

        if (ssid == null || ssid == WifiManager.UNKNOWN_SSID || ssid == "<unknown ssid>") { // Check for unknown SSID values
            return null
        }

        // Remove surrounding quotes if present
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return ssid
    }

    /**
     * Checks if the device is connected to a network that provides INTERNET capability
     * or is likely a peer-to-peer Wi-Fi Direct connection.
     */
    private fun isConnectedToInternetOrPeer(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P) ||
                            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) // Heuristic: If it's not VPN, assume usable P2P or internet
        } else {
            // Older versions: Check connection info (less reliable)
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return activeNetworkInfo != null && activeNetworkInfo.isConnected && activeNetworkInfo.type == ConnectivityManager.TYPE_WIFI
        }
    }

    /**
     * Checks if the necessary Wi-Fi permissions are granted.
     */
    private fun hasWifiPermissions(): Boolean {
        val fineLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed before M for basic Wi-Fi state
        }
        // CHANGE_WIFI_STATE needed for connect/disconnect on Q+ via NetworkRequest/Suggestions
        val changeWifiStatePermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED

        // ACCESS_WIFI_STATE is generally needed
        val accessWifiStatePermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED

        if (!accessWifiStatePermission) Log.e(TAG,"Permission missing: ACCESS_WIFI_STATE")
        if (!changeWifiStatePermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Log.e(TAG,"Permission missing: CHANGE_WIFI_STATE (needed for Q+ connection)")
        // Location only needed for scanning
        // if (!fineLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Log.w(TAG,"Permission missing: ACCESS_FINE_LOCATION (needed for scanning on M+)")

        // Require Access & Change state. Location is only strictly needed for scanning.
        return accessWifiStatePermission && (changeWifiStatePermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        // Let's simplify: require all for robustness, though scanning might work without location on some devices/versions
        // return accessWifiStatePermission && changeWifiStatePermission && fineLocationPermission
    }
}