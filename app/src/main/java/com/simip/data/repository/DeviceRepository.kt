package com.simip.simip7.repository

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

// --- Data Classes for States and Responses ---

/**
 * Represents the possible states of the connection to the geophysical device.
 */
sealed class ConnectionState {
    /** Connection has not been initiated or has been explicitly disconnected. */
    object Disconnected : ConnectionState()

    /** Connection attempt is in progress. */
    object Connecting : ConnectionState()

    /** Connection is established. Optionally holds device information like version. */
    data class Connected(val deviceInfo: String? = null) : ConnectionState()

    /** A connection attempt or an established connection failed. */
    data class Failed(val error: Throwable) : ConnectionState() // Use Throwable for more info
}

/**
 * Represents the status data received from the 'Gets' command.
 * Fields match the order specified in the PDF (section 2 and 8).
 * All fields are nullable initially until parsing confirms their presence and validity.
 * Using Double for flexibility with potential floating point values, adjust if needed.
 */
data class DeviceStatus(
    val state: String?, // e.g., "Idle", "Busy", "Error" - Define constants or Enum if possible
    val fe: String?, // Meaning of 'Fe'? Needs clarification from domain expert. Assuming String.
    val setAmper: Double?, // Current setting (mA)
    val stack: Int?, // Stack setting
    val time: Int?, // Time setting (s)
    val no: Int?, // Sequence number? Needs clarification.
    val voltageBat: Double?, // Battery voltage (V)
    val temperature: Double?, // Temperature (°C)
    val measureMNvolt: Double? // Last measured voltage (mV)? Needs clarification.
)

/**
 * Represents a single measurement data record received from the 'Data' command.
 * Fields match the order specified in the PDF (section 6.3.4 and table in 7).
 */
data class MeasurementData(
    val id: Int, // Measurement sequence number (Id)
    val setAmper: Double, // Current used (mA) - From settings or measured? Assuming from settings
    val stack: Int, // Stack used
    val time: Int, // Time used (s)
    // val notUsed: String?, // Placeholder if 'not' field exists in Data command
    val voltageBat: Double, // Battery voltage (V) at time of measurement
    val temperature: Double, // Temperature (°C) at time of measurement
    val contactResistance: Double, // Contact resistance (kΩ)
    val measuredAmper: Double, // Actual measured current (mA)
    val measuredSP: Double, // Self-Potential (mV)
    val measuredPotential: Double, // Primary Potential (deltaV) (mV) - Ensure unit is correct (mV or V)
    val ipDecay: List<Double?> // List of 20 IP decay values (mV/V or raw mV?) - Needs clarification on unit
    // Nullable Doubles in the list allow for potential parsing errors or missing values for specific windows
)

/**
 * Represents the configuration to be sent with 'SetConfig'.
 */
data class SimipConfig(
    val current: Int, // mA (e.g., 80-800)
    val stack: Int,   // e.g., 2, 4, 6, 8
    val time: Int     // s (e.g., 2, 4, 6, 8)
)

/**
 * Represents a successful response from SetConfig or Star command (can be simple Unit).
 * Could be expanded if these responses contain useful data.
 */
typealias ConfigResponse = Unit // Alias for successful SetConfig confirmation (ResConf)
typealias StartResponse = Unit  // Alias for successful Star confirmation (ResStar)


// --- Repository Interface Definition ---

interface DeviceRepository {

    /**
     * Attempts to establish a connection with the device at the specified host and port.
     * Sends an initial 'Vers' command upon successful socket connection to verify communication.
     * Updates [connectionState] flow accordingly.
     *
     * @param host The IP address or hostname of the device.
     * @param port The TCP port number for the connection.
     * @return Result<Unit> indicating success or failure (with exception).
     */
    suspend fun connect(host: String, port: Int): Result<Unit>

    /**
     * Closes the connection to the device and releases associated resources.
     * Updates [connectionState] to [ConnectionState.Disconnected].
     */
    suspend fun disconnect()

    /**
     * Provides a StateFlow representing the current connection state.
     * Observers can react to connection changes (Connecting, Connected, Failed, Disconnected).
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Sends the 'Gets' command to the device to request its current status.
     * Attempts to parse the response into a [DeviceStatus] object.
     * Updates the [latestDeviceStatus] flow with the result.
     *
     * @return Result<DeviceStatus> containing the parsed status on success, or failure details.
     */
    suspend fun requestDeviceStatus(): Result<DeviceStatus>

    /**
     * Provides a StateFlow holding the latest successfully parsed [DeviceStatus].
     * Useful for displaying real-time device status in the UI.
     * Holds the last successful result or an initial/error state.
     */
    val latestDeviceStatus: StateFlow<Result<DeviceStatus>> // Holds last known good status or error

    /**
     * Applies the given configuration and starts the measurement process on the device.
     * Sends 'SetConfig,current,stack,time\n\r' command.
     * Waits for 'ResConf' response and validates it.
     * If successful, sends 'Star\n\r' command.
     * Waits for 'ResStar' response and validates it.
     *
     * @param config The [SimipConfig] object containing measurement parameters.
     * @return Result<Unit> indicating success (both commands acknowledged) or failure.
     */
    suspend fun applyConfigAndStartMeasurement(config: SimipConfig): Result<Unit>

    /**
     * Sends the 'Data\n\r' command to request the next available measurement record.
     * Attempts to parse the response string "Data,Id,..." into a [MeasurementData] object.
     * Emits the parsed result (or failure) to the [measurementDataFlow].
     * Note: This function primarily triggers the data emission on the flow.
     *       The ViewModel should primarily collect the flow rather than relying solely on the return value.
     *
     * @return Result<MeasurementData> containing the parsed data for the *single* requested record, or failure details.
     */
    suspend fun requestSingleMeasurement(): Result<MeasurementData>

    /**
     * Provides a SharedFlow that emits [MeasurementData] results as they are received and parsed
     * in response to polling or requests (e.g., triggered by [requestSingleMeasurement] or an internal loop).
     * Use SharedFlow because measurement data points are events and we don't want to lose any.
     * Emits Result<MeasurementData> to include parsing errors.
     */
    val measurementDataFlow: SharedFlow<Result<MeasurementData>>

    // Note: stopMeasurement() is removed as the protocol doesn't specify an explicit stop command.
    // Stopping is likely handled by the ViewModel stopping the polling of requestSingleMeasurement
    // or by the device automatically stopping after the configured stack/time, or on disconnect.
}