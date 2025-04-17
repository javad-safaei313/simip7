package com.simip.data.repository

import com.simip.data.model.DeviceStatus
import com.simip.data.model.GeoConfig
import com.simip.data.model.MeasurementPacket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for interacting with the geophysical measurement device.
 * Handles connection management (Wi-Fi, TCP), command sending, and response parsing.
 * Provides Flows for observing connection status, device status, measurement progress,
 * and measurement results.
 */
interface DeviceRepository {
    val deviceVersion: StateFlow<String?> // <--- این خط را اضافه کنید

    /**
     * A Flow providing the SSID of the currently connected Wi-Fi network (if connected via Wi-Fi helper).
     * Emits null if not connected or SSID is unknown.
     */
    val connectedSsid: StateFlow<String?>

    /**
     * A Flow representing the current connection state to the device.
     * Emits ConnectionState enum values.
     */
    val connectionState: StateFlow<ConnectionState> // Use StateFlow for current state

    /**
     * A Flow providing the latest real-time status from the device (from 'Gets' command).
     * Emits DeviceStatus objects. Useful for the status bar.
     * Should emit DeviceStatus.DISCONNECTED or DeviceStatus.ERROR when appropriate.
     */
    val deviceStatus: StateFlow<DeviceStatus> // Use StateFlow for current state

    /**
     * A Flow providing updates during the measurement process.
     * Emits MeasurementProgress objects containing stage, repeat, and calculated percentage.
     * Emits only when a measurement is active (triggered by 'startMeasurement').
     */
    val measurementProgress: SharedFlow<MeasurementProgress> // Use SharedFlow for events

    /**
     * A Flow providing the complete measurement data packet when a measurement finishes.
     * Emits MeasurementPacket objects. Emits only when a full "Data,..." response is received.
     */
    val measurementResult: SharedFlow<MeasurementPacket> // Use SharedFlow for events

    /**
     * Initiates the process of connecting to the device.
     * Handles Wi-Fi scanning/connection and TCP socket establishment.
     * Updates the connectionState Flow accordingly.
     * This function might start a long-running process managed within the repository.
     */
    suspend fun connectToDevice()

    /**
     * Disconnects from the device, closing the TCP socket and potentially stopping Wi-Fi search.
     * Updates the connectionState Flow.
     */
    suspend fun disconnectFromDevice()

    /**
     * Sends configuration parameters (Current, Time, Stack) to the device.
     * Uses the 'SetConfig' command.
     * @param currentMa Current in milliamperes (e.g., 500).
     * @param timeSec Measurement time in seconds (e.g., 2.0f).
     * @param stack Number of stacks/repeats (e.g., 4).
     * @return True if the configuration was acknowledged successfully (ResConf received), false otherwise.
     */
    suspend fun sendConfiguration(currentMa: Int, timeSec: Float, stack: Int): Boolean

    /**
     * Sends the command to start the measurement process.
     * Uses the 'Star' command.
     * Starts the internal process to listen for 'BussyM' and 'Data' responses,
     * updating measurementProgress and measurementResult Flows.
     * @return True if the start command was acknowledged successfully (ResStar received), false otherwise.
     */
    suspend fun startMeasurement(): Boolean

    /**
     * Called when new geometry data is available or should be acknowledged by the repository,
     * although this might be handled directly by the AcqViewModel based on user input.
     * Include if the repository needs awareness of the current geometry for any internal logic.
     * (Optional - can be removed if ViewModel manages geometry state entirely)
     *
     * @param geoConfig The current geometry configuration.
     */
    // suspend fun updateCurrentGeometry(geoConfig: GeoConfig) // Keep commented unless needed


    // --- Helper structures ---

    /**
     * Represents the different states of connection to the device.
     */
    enum class ConnectionState {
        DISCONNECTED,
        SEARCHING_WIFI, // Searching for specific Wi-Fi SSID
        CONNECTING_WIFI, // Found SSID, attempting to connect
        WIFI_CONNECTED, // Connected to Wi-Fi, trying TCP
        CONNECTING_TCP, // Attempting TCP socket connection
        VERIFYING_DEVICE, // TCP connected, sending 'Vers'
        CONNECTED,        // Verified device ('Ver' received), polling 'Gets'
        CONNECTION_ERROR, // Any error during the process (Wi-Fi, TCP, Verification)
        DEVICE_ERROR      // Communication lost after successful connection (e.g., Gets timeout)
    }

    /**
     * Represents the progress of an ongoing measurement.
     * @param stage The current stage code (0 for S, 1 for C, 2 for V from BussyM).
     * @param repeat The current repeat number (xx from BussyM).
     * @param totalStack The total number of stacks configured for this measurement.
     * @param progressPercent Calculated progress percentage (0-100).
     */
    data class MeasurementProgress(
        val stage: Int,
        val repeat: Int,
        val totalStack: Int, // Needed to calculate percentage accurately
        val progressPercent: Int // Calculated as (stage + 3 * repeat) * 100 / (3 * totalStack) ? Verify formula
        // Formula check: If repeat is 0-indexed (0 to Stack-1), then:
        // progress = (stage + 3 * repeat) * 100 / (3 * totalStack) -- seems correct for 0-indexed repeat
        // If repeat is 1-indexed (1 to Stack):
        // progress = (stage + 3 * (repeat-1)) * 100 / (3 * totalStack)
        // Let's assume repeat 'xx' from BussyM is 0-indexed based on typical progress loops.
    )

}