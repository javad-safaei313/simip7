package com.simip.data.repository

import kotlinx.coroutines.flow.MutableStateFlow // <-- اطمینان از وجود این import
import kotlinx.coroutines.flow.StateFlow      // <-- اطمینان از وجود این import
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.simip.data.model.DeviceStatus
import com.simip.data.model.MeasurementPacket
import com.simip.data.network.TcpClient
import com.simip.util.Constants
import com.simip.util.DataParser
import com.simip.util.WifiHelper // Assume this helper exists for Wi-Fi operations
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Implementation of DeviceRepository.
 * Manages Wi-Fi connection, TCP communication, parsing, and state flows.
 */
class DeviceRepositoryImpl(
    private val context: Context,
    private val externalScope: CoroutineScope // Scope from ViewModel or Application for long-running tasks
) : DeviceRepository {

    private val TAG = "DeviceRepoImpl"

    // --- Dependencies ---
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wifiHelper = WifiHelper(context, wifiManager, Constants.WIFI_SSID_PATTERN)
    private val tcpClient = TcpClient()
    private val dataParser = DataParser // Object

    // --- State Flows ---
    private val _connectionState = MutableStateFlow(DeviceRepository.ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceRepository.ConnectionState> = _connectionState.asStateFlow()
    // اضافه کردن MutableStateFlow های لازم
    private val _deviceVersion = MutableStateFlow<String?>(null)
    private val _connectedSsid = MutableStateFlow<String?>(null)

    // پیاده‌سازی پراپرتی‌های انتزاعی رابط با override
    override val deviceVersion: StateFlow<String?> = _deviceVersion.asStateFlow() // <--- پیاده‌سازی deviceVersion
    override val connectedSsid: StateFlow<String?> = _connectedSsid.asStateFlow() // <--- پیاده‌سازی connectedSsid
    private val _deviceStatus = MutableStateFlow(DeviceStatus.DISCONNECTED)
    override val deviceStatus: StateFlow<DeviceStatus> = _deviceStatus.asStateFlow()

    // Use MutableSharedFlow for events that shouldn't be replayed to new collectors or lost if no collector
    private val _measurementProgress = MutableSharedFlow<DeviceRepository.MeasurementProgress>(replay = 0) // No replay
    override val measurementProgress: SharedFlow<DeviceRepository.MeasurementProgress> = _measurementProgress.asSharedFlow()

    private val _measurementResult = MutableSharedFlow<MeasurementPacket>(replay = 0) // No replay
    override val measurementResult: SharedFlow<MeasurementPacket> = _measurementResult.asSharedFlow()

    // --- Internal State ---
    private var deviceCommunicationJob: Job? = null // Job for handling TCP reading and polling
    private var connectJob: Job? = null // Job for the main connection sequence
    @Volatile private var isPollingGets = false
    @Volatile private var isAwaitingSpecificResponse = false // Flag to pause Gets polling
    @Volatile private var isMeasurementActive = false
    @Volatile private var currentConfiguredStack = Constants.DEFAULT_STACK // Store stack for progress calculation

    // --- Public Functions ---

    override suspend fun connectToDevice() {
        // Prevent multiple concurrent connection attempts
        if (connectJob?.isActive == true || _connectionState.value != DeviceRepository.ConnectionState.DISCONNECTED) {
            Log.w(TAG, "Connection process already active or not in disconnected state.")
            return
        }

        connectJob = externalScope.launch(CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Unhandled exception in connection coroutine: ${throwable.message}", throwable)
            setConnectionState(DeviceRepository.ConnectionState.CONNECTION_ERROR)
            externalScope.launch { // یا می‌توانید از CoroutineScope(Dispatchers.IO) استفاده کنید اگر عملیات IO دارد
                cleanupJobsAndState()
            }

        }) {
            Log.i(TAG, "Starting connection process...")
            var wifiConnected = false
            var tcpConnected = false
            var versionVerified = false

            try {
                // 1. Wi-Fi Handling
                setConnectionState(DeviceRepository.ConnectionState.SEARCHING_WIFI)
                ensureActive() // Check if coroutine was cancelled
                val wifiTargetNetwork = wifiHelper.findAndConnectToTargetWifi(Constants.MAX_CONNECTION_RETRIES, Constants.RETRY_DELAY_MS.milliseconds)

                if (wifiTargetNetwork != null) {
                    Log.i(TAG, "Successfully connected to Wi-Fi: ${wifiTargetNetwork.SSID}")
                    setConnectionState(DeviceRepository.ConnectionState.WIFI_CONNECTED)
                    wifiConnected = true
                } else {
                    Log.e(TAG, "Failed to connect to target Wi-Fi.")
                    throw IOException("Wi-Fi connection failed")
                }

                // 2. TCP Connection
                ensureActive()
                setConnectionState(DeviceRepository.ConnectionState.CONNECTING_TCP)
                if (tcpClient.connect()) {
                    Log.i(TAG, "TCP socket connected.")
                    tcpConnected = true
                } else {
                    Log.e(TAG, "Failed to connect TCP socket.")
                    throw IOException("TCP connection failed")
                }

                // 3. Verify Device Version
                ensureActive()
                setConnectionState(DeviceRepository.ConnectionState.VERIFYING_DEVICE)
                val version = verifyDeviceVersion()
                if (version != null) {
                    Log.i(TAG, "Device version verified: $version")
                    versionVerified = true
                } else {
                    Log.e(TAG, "Failed to verify device version.")
                    throw IOException("Device version verification failed")
                }

                // 4. Connection Successful - Start Communication Loop
                ensureActive()
                setConnectionState(DeviceRepository.ConnectionState.CONNECTED)
                startDeviceCommunicationLoop() // Start listening and polling

            } catch (e: CancellationException) {
                Log.w(TAG, "Connection process cancelled.")
                setConnectionState(DeviceRepository.ConnectionState.DISCONNECTED)
                cleanupJobsAndState() // Clean up on cancellation
            } catch (e: IOException) {
                Log.e(TAG, "Connection process failed: ${e.message}")
                setConnectionState(DeviceRepository.ConnectionState.CONNECTION_ERROR)
                cleanupJobsAndState()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during connection: ${e.message}", e)
                setConnectionState(DeviceRepository.ConnectionState.CONNECTION_ERROR)
                cleanupJobsAndState()
            }
        }
    }


    override suspend fun disconnectFromDevice() {
        Log.i(TAG, "Disconnecting from device...")
        connectJob?.cancelAndJoin() // Cancel any ongoing connection attempt
        deviceCommunicationJob?.cancelAndJoin() // Stop the communication loop
        cleanupJobsAndState()
        // Optionally disconnect from Wi-Fi if managed exclusively by the app
        // wifiHelper.disconnectFromWifi()
        Log.i(TAG, "Disconnect process complete.")
    }

    override suspend fun sendConfiguration(currentMa: Int, timeSec: Float, stack: Int): Boolean {
        if (_connectionState.value != DeviceRepository.ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send config, not connected.")
            return false
        }

        return executeCommandAndWaitForResponse(
            command = "${Constants.CMD_SET_CONFIG},${formatCurrent(currentMa)},$stack,${timeSec}",
            expectedResponsePrefix = Constants.RESPONSE_CONFIG_ACK_PREFIX,
            timeout = Constants.READ_TIMEOUT_MS.toLong()
        ) { response ->
            // Optional: Validate response content matches sent values
            when (val parsed = dataParser.parseResponse(response)) {
                is DataParser.ParseResult.Success<*> -> {
                    if (parsed.data is Map<*,*>) {
                        val map = parsed.data as Map<String, Number?>
                        val ackCurrent = map["SetAmper"]?.toInt()
                        val ackStack = map["Stack"]?.toInt()
                        val ackTime = map["Time"]?.toFloat()
                        if (ackCurrent == currentMa && ackStack == stack && ackTime == timeSec) {
                            currentConfiguredStack = stack // Store for progress calculation
                            Log.d(TAG,"SetConfig acknowledged with matching values.")
                            true
                        } else {
                            Log.w(TAG,"SetConfig acknowledged, but values mismatch! Resp: $map")
                            false // Treat mismatch as failure? Yes.
                        }
                    } else {
                        Log.w(TAG, "SetConfig acknowledged, but response format unexpected: ${parsed.data}")
                        false
                    }
                }
                is DataParser.ParseResult.Error -> {
                    Log.w(TAG,"Error parsing SetConfig response: ${parsed.message}")
                    false
                }
                else -> {
                    Log.w(TAG,"Unexpected parse result for SetConfig response: $parsed")
                    false
                }
            }
        }
    }

    override suspend fun startMeasurement(): Boolean {
        if (_connectionState.value != DeviceRepository.ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot start measurement, not connected.")
            return false
        }
        if (isMeasurementActive) {
            Log.w(TAG, "Measurement is already active.")
            return false // Or maybe return true if already active? Let's say false.
        }

        val success = executeCommandAndWaitForResponse(
            command = Constants.CMD_START_MEASUREMENT,
            expectedResponsePrefix = Constants.RESPONSE_START_ACK,
            timeout = Constants.READ_TIMEOUT_MS.toLong() // Short timeout for ResStar
        ) { response -> response.startsWith(Constants.RESPONSE_START_ACK) } // Simple check for ResStar

        if (success) {
            Log.i(TAG, "Measurement started successfully (ResStar received).")
            isMeasurementActive = true
            // Start requesting data periodically (handled in communication loop)
            // Reset progress flow? Ensure it starts fresh.
            // _measurementProgress.tryEmit(...) // Maybe emit a starting state?
        } else {
            Log.w(TAG, "Failed to start measurement (No ResStar or error).")
        }
        return success
    }

    // --- Internal Helper Functions ---

    private suspend fun verifyDeviceVersion(): String? {
        if (!tcpClient.isCurrentlyConnected()) return null
        Log.d(TAG, "Sending Vers command...")
        if (tcpClient.sendCommand(Constants.CMD_GET_VERSION)) {
            // Wait for response, expect something like "VerX.Y"
            repeat(Constants.MAX_CONNECTION_RETRIES) { attempt ->
                val response = tcpClient.readLineWithTimeout(Constants.READ_TIMEOUT_MS.toLong())
                if (response != null) {
                    Log.d(TAG, "Received response for Vers: $response")
                    val parsed = dataParser.parseResponse(response)
                    if (parsed is DataParser.ParseResult.Success<*> && parsed.data is String) {
                        // Check if it starts with Ver prefix for extra safety
                        if (parsed.data.startsWith(Constants.RESPONSE_VERSION_PREFIX)) {
                            return parsed.data // Return the full version string
                        } else {
                            Log.w(TAG, "Parsed version string does not start with '${Constants.RESPONSE_VERSION_PREFIX}': ${parsed.data}")
                        }
                    } else {
                        Log.w(TAG, "Failed to parse version from response: $response, result: $parsed")
                    }
                } else {
                    Log.w(TAG, "No response received for Vers (attempt ${attempt + 1})")
                    delay(Constants.RETRY_DELAY_MS) // Wait before retrying read
                }
                if (!tcpClient.isCurrentlyConnected()) { // Stop retrying if connection lost
                    Log.e(TAG, "Connection lost while waiting for Vers response.")
                    return null
                }
            }
        } else {
            Log.e(TAG, "Failed to send Vers command.")
        }
        return null // Failed to verify
    }

    /**
     * Starts the main loop for reading data from TCP and polling status.
     * This job runs as long as the connection is intended to be active.
     */
    private suspend fun startDeviceCommunicationLoop() {
        if (deviceCommunicationJob?.isActive == true) {
            Log.w(TAG, "Communication loop already running.")
            return
        }
        deviceCommunicationJob = externalScope.launch(Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    Log.e(TAG, "Error in communication loop: ${throwable.message}", throwable)
                    setConnectionState(DeviceRepository.ConnectionState.DEVICE_ERROR) // Or CONNECTION_ERROR?

                    // حالا فراخوانی از اینجا مجاز است چون داخل launch هستیم
                    externalScope.launch {
                        cleanupJobsAndState()
                    }
                }) {

            Log.i(TAG, "Starting device communication loop...")
            var consecutiveGetsFailures = 0
            var lastGetsTime = 0L
            var lastDataRequestTime = 0L

            // Combined loop for reading incoming data and sending periodic commands
            while (isActive && tcpClient.isCurrentlyConnected()) {
                val currentTime = System.currentTimeMillis()

                // --- Handle Incoming Data ---
                // Use a short non-blocking read attempt (or check reader.ready())
                // Let's stick to readLineWithTimeout with a short timeout for simplicity here.
                // Note: This might interfere with specific response waiting logic.
                val response = try {
                    // Only read if not expecting a specific response from sendCommandAndWait
                    if (!isAwaitingSpecificResponse) {
                        // Use a shorter timeout here to avoid blocking the loop for too long
                        tcpClient.readLineWithTimeout(50)
                    } else {
                        null // Don't read if another part of the code is waiting for a specific line
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "IOException while reading in loop: ${e.message}. Closing connection.")
                    throw e // Let the exception handler deal with it
                }

                if (response != null && !isAwaitingSpecificResponse) {
                    Log.d(TAG, "Loop received: $response")
                    handleIncomingData(response)
                    consecutiveGetsFailures = 0 // Reset failure count on any successful read
                }

                // --- Handle Periodic Commands ---
                if (!isAwaitingSpecificResponse) { // Don't send Gets/Data if waiting for ResConf/ResStar etc.
                    // Send 'Gets' periodically
                    if (isPollingGets && (currentTime - lastGetsTime > Constants.POLLING_INTERVAL_MS)) {
                        Log.v(TAG, "Sending Gets poll...")
                        if (tcpClient.sendCommand(Constants.CMD_GET_STATUS)) {
                            lastGetsTime = currentTime
                            // Response handled by the reading part of the loop
                        } else {
                            Log.e(TAG, "Failed to send Gets command. Connection likely lost.")
                            setConnectionState(DeviceRepository.ConnectionState.DEVICE_ERROR)
                            break // Exit loop
                        }
                    }

                    // Send 'Data' command periodically if measurement is active
                    if (isMeasurementActive && (currentTime - lastDataRequestTime > Constants.DATA_REQUEST_INTERVAL_MS)) {
                        Log.v(TAG, "Sending Data request...")
                        if (tcpClient.sendCommand(Constants.CMD_REQUEST_DATA)) {
                            lastDataRequestTime = currentTime
                            // Response handled by the reading part of the loop
                        } else {
                            Log.e(TAG, "Failed to send Data request command. Connection likely lost.")
                            setConnectionState(DeviceRepository.ConnectionState.DEVICE_ERROR)
                            break // Exit loop
                        }
                    }
                }

                // Check for Gets timeout (if polling is active and no response received for a while)
                if (isPollingGets && response == null && !isAwaitingSpecificResponse) {
                    // If polling is active, we expect responses. If readLine times out repeatedly,
                    // it might indicate a problem. This logic needs refinement.
                    // Let's rely on the readLineWithTimeout within the polling logic instead.

                    // Simpler check: If sendCommand fails above, we exit.
                    // If readLine keeps returning null without IOException, TCP might be stuck.
                    // Let's implement the 10 consecutive Gets failure logic based on *expected* responses.
                    // This requires modifying the polling to wait for the 'State' response.
                    // This makes the combined loop much more complex.

                    // Alternative: Keep Gets polling simple send-only here.
                    // Rely on handleIncomingData receiving 'State' responses. If none received
                    // for N seconds while polling is active, declare error.

                    // Let's stick to the explicit Gets polling with response waiting for now.
                    if (isPollingGets && (currentTime - lastGetsTime > Constants.POLLING_INTERVAL_MS * 2)) { // If poll sent but no relevant response handled recently
                        // Send Gets and wait for response explicitly here
                        val status = pollDeviceStatus()
                        if (status != null) {
                            _deviceStatus.value = status
                            consecutiveGetsFailures = 0
                            lastGetsTime = currentTime // Update time on successful poll
                        } else {
                            consecutiveGetsFailures++
                            Log.w(TAG, "Gets poll failed or timed out ($consecutiveGetsFailures/${Constants.MAX_GETS_FAILURES})")
                            if (consecutiveGetsFailures >= Constants.MAX_GETS_FAILURES) {
                                Log.e(TAG, "Max Gets poll failures reached. Assuming device error.")
                                setConnectionState(DeviceRepository.ConnectionState.DEVICE_ERROR)
                                break // Exit loop
                            }
                        }
                    }

                } else if (response == null && !isAwaitingSpecificResponse) {
                    // Read timed out, but maybe okay if no polling/measurement active
                    delay(50) // Small delay to prevent busy loop if idle
                }

            } // End while loop

            Log.i(TAG, "Device communication loop finished.")
            // Ensure state reflects disconnection if loop exited cleanly but disconnected
            if (isActive && !tcpClient.isCurrentlyConnected() && _connectionState.value != DeviceRepository.ConnectionState.DEVICE_ERROR) {
                setConnectionState(DeviceRepository.ConnectionState.CONNECTION_ERROR) // Or DISCONNECTED?
            }
        } // End launch
        // Start polling after a short delay, assuming connection is stable
        externalScope.launch {
            delay(500) // Give time for connection to stabilize
            isPollingGets = true // Enable polling
        }
    }

    /**
     * Handles parsing and reacting to messages received in the communication loop.
     */
    private suspend fun handleIncomingData(rawData: String) {
        when (val result = dataParser.parseResponse(rawData)) {
            is DataParser.ParseResult.Success<*> -> {
                when (result.data) {
                    is DeviceStatus -> {
                        Log.v(TAG, "Parsed DeviceStatus: ${result.data}")
                        _deviceStatus.value = result.data
                        // Reset Gets failure count here? Yes, if a valid status is received.
                        // Logic moved to explicit pollDeviceStatus()
                    }
                    is MeasurementPacket -> {
                        Log.i(TAG, "Parsed MeasurementPacket: ID=${result.data.id}")
                        _measurementResult.emit(result.data)
                        isMeasurementActive = false // Measurement completes upon receiving Data packet
                        // Stop requesting 'Data' command
                        isPollingGets = true // Resume polling 'Gets'
                    }
                    is Pair<*, *> -> { // Assuming Pair<Int, Int> for BussyM
                        if (result.data.first is Int && result.data.second is Int) {
                            val stage = result.data.first as Int
                            val repeat = result.data.second as Int
                            Log.d(TAG, "Parsed Measurement Progress: Stage=$stage, Repeat=$repeat")
                            val progressPercent = calculateProgress(stage, repeat, currentConfiguredStack)
                            _measurementProgress.emit(
                                DeviceRepository.MeasurementProgress(stage, repeat, currentConfiguredStack, progressPercent)
                            )
                        }
                    }
                    // Handle other success types if needed (e.g., version string if received unsolicited)
                    else -> Log.d(TAG, "Received unhandled successful parse result: ${result.data}")
                }
            }
            is DataParser.ParseResult.Error -> {
                Log.w(TAG, "Parser error: ${result.message} for data: '${result.originalInput}'")
                // Decide if this constitutes a device error or just ignores the line
            }
            DataParser.ParseResult.Ignore -> {
                Log.d(TAG, "Parser ignored data line.")
            }
            DataParser.ParseResult.Incomplete -> {
                Log.d(TAG, "Parser reported incomplete data.")
            }
        }
    }

    /**
     * Sends Gets command and waits for a valid DeviceStatus response.
     * Used for explicit polling check.
     */
    private suspend fun pollDeviceStatus(): DeviceStatus? {
        if (!tcpClient.isCurrentlyConnected()) return null

        isAwaitingSpecificResponse = true // Pause general reading
        try {
            if (tcpClient.sendCommand(Constants.CMD_GET_STATUS)) {
                // Wait for a response that parses to DeviceStatus
                val status = readUntilParsed<DeviceStatus>(Constants.READ_TIMEOUT_MS.toLong())
                if (status == null) {
                    Log.w(TAG, "Did not receive valid DeviceStatus after Gets command.")
                }
                return status
            } else {
                Log.e(TAG, "Failed to send Gets command for polling.")
                return null
            }
        } finally {
            isAwaitingSpecificResponse = false // Resume general reading
        }
    }


    /**
     * Helper function to send a command, then read lines until a specific condition is met
     * or timeout occurs. Sets isAwaitingSpecificResponse flag.
     * @param command The command to send.
     * @param expectedResponsePrefix Optional prefix to quickly check if the response might be the one we want.
     * @param timeout Total timeout for waiting for the response.
     * @param validation Lambda function to validate if the received line is the expected response.
     * @return True if the validated response was received, false otherwise (timeout, error, validation failed).
     */
    private suspend fun executeCommandAndWaitForResponse(
        command: String,
        expectedResponsePrefix: String?,
        timeout: Long,
        validation: (String) -> Boolean
    ): Boolean {
        if (!tcpClient.isCurrentlyConnected()) return false
        isAwaitingSpecificResponse = true // Pause the main loop's reader/poller
        var success = false
        try {
            Log.d(TAG, "Executing command: $command, waiting for response (timeout ${timeout}ms)")
            if (!tcpClient.sendCommand(command)) {
                Log.e(TAG, "Failed to send command '$command'.")
                return false // Exit early if send fails
            }

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeout) {
                // Check connection before attempting read
                if (!tcpClient.isCurrentlyConnected()) {
                    Log.e(TAG,"Connection lost while waiting for response to '$command'.")
                    break // Exit loop if disconnected
                }

                val remainingTime = timeout - (System.currentTimeMillis() - startTime)
                if (remainingTime <= 0) break // Exit if timeout exceeded before read attempt

                val response = tcpClient.readLineWithTimeout(remainingTime)

                if (response != null) {
                    Log.d(TAG, "Response received while waiting: $response")
                    // Basic check using prefix if provided
                    if (expectedResponsePrefix == null || response.startsWith(expectedResponsePrefix)) {
                        // Full validation
                        if (validation(response)) {
                            Log.d(TAG,"Command '$command' successfully acknowledged.")
                            success = true
                            break // Expected response received
                        } else {
                            Log.w(TAG,"Response received but failed validation for '$command': $response")
                            // Continue waiting for the correct response until timeout
                        }
                    } else {
                        Log.d(TAG,"Ignoring unrelated response while waiting for '$expectedResponsePrefix': $response")
                        // Continue waiting
                    }
                } else {
                    // readLineWithTimeout returned null (timeout or error during read)
                    Log.w(TAG,"readLine timed out or returned null while waiting for response to '$command'.")
                    // The outer loop condition will handle the overall timeout.
                    // If readLine timed out, check connection again.
                    if (!tcpClient.isCurrentlyConnected()) {
                        Log.e(TAG,"Connection lost after read timeout while waiting for response to '$command'.")
                        break
                    }
                    // Break here as readLine already waited for remainingTime
                    break
                }
                // Small delay to prevent busy-waiting if continuously receiving unrelated messages
                delay(50)
            }

            if (!success) {
                Log.w(TAG, "Timeout or error occurred while waiting for response to command '$command'.")
            }
            return success

        } catch (e: Exception) {
            Log.e(TAG, "Exception during executeCommandAndWaitForResponse for '$command': ${e.message}", e)
            return false
        }
        finally {
            isAwaitingSpecificResponse = false // IMPORTANT: Resume the main loop reader/poller
            Log.v(TAG,"Resuming general communication loop.")
        }
    }

    /**
     * Reads lines from TcpClient until a line successfully parses into the target type T, or timeout.
     */
    private suspend inline fun <reified T> readUntilParsed(timeout: Long): T? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            if (!tcpClient.isCurrentlyConnected()) break
            val remainingTime = timeout - (System.currentTimeMillis() - startTime)
            if (remainingTime <= 0) break
            val response = tcpClient.readLineWithTimeout(remainingTime)
            if (response != null) {
                when (val parsed = dataParser.parseResponse(response)) {
                    is DataParser.ParseResult.Success<*> -> {
                        if (parsed.data is T) {
                            return parsed.data // Found the target type
                        }
                    }
                    // Ignore errors or other types for this specific wait
                    else -> {}
                }
            } else {
                // readLine timed out or returned null
                break
            }
        }
        return null // Timeout or disconnected
    }


    private fun formatCurrent(currentMa: Int): String {
        // Format to 3 digits with leading zeros (e.g., 80 -> "080", 500 -> "500")
        return currentMa.toString().padStart(3, '0')
    }

    private fun calculateProgress(stage: Int, repeat: Int, totalStack: Int): Int {
        if (totalStack <= 0) return 0
        // Assuming 'repeat' from BussyM is 0-indexed (0 to stack-1)
        // Formula: (stage + 3 * repeat) / (3 * totalStack)
        val numerator = (stage + 3 * repeat).toDouble()
        val denominator = (3 * totalStack).toDouble()
        if (denominator == 0.0) return 0
        val progress = (numerator / denominator * 100).toInt()
        return progress.coerceIn(0, 100) // Ensure progress is between 0 and 100
    }


    private suspend fun cleanupJobsAndState() {
        Log.d(TAG, "Cleaning up jobs and state...")
        // Cancel jobs first
        connectJob?.cancel()
        deviceCommunicationJob?.cancel()
        connectJob = null
        deviceCommunicationJob = null

        // Reset state variables
        isPollingGets = false
        isAwaitingSpecificResponse = false
        isMeasurementActive = false

        // Reset flows to initial state
        setConnectionState(DeviceRepository.ConnectionState.DISCONNECTED) // Set state *after* cancelling jobs
        _deviceStatus.value = DeviceStatus.DISCONNECTED // Reset status
        // Reset version/ssid?
        // _deviceVersion.value = null // Handled by ConnectionState change implicitly?
        // _connectedSsid.value = null // Handled by WifiHelper or ConnectionState change

        // Close TCP connection
        tcpClient.disconnect()

        Log.d(TAG, "Cleanup complete.")
    }

    // Helper to safely update connection state flow
    private fun setConnectionState(newState: DeviceRepository.ConnectionState) {
        if (_connectionState.value != newState) {
            _connectionState.value = newState
            Log.i(TAG, "Connection state changed to: $newState")
            // Reset device status if disconnected or error
            if (newState == DeviceRepository.ConnectionState.DISCONNECTED ||
                newState == DeviceRepository.ConnectionState.CONNECTION_ERROR ||
                newState == DeviceRepository.ConnectionState.DEVICE_ERROR) {
                _deviceStatus.value = DeviceStatus.DISCONNECTED // Reset status model too
                isPollingGets = false // Ensure polling stops
                isMeasurementActive = false
            }
        }
    }

    // Need to initialize WifiHelper listener if it provides connection status updates via Flow
    init {
        externalScope.launch {
            wifiHelper.wifiStateFlow.collect { state ->
                // Update _connectionState based on WifiHelper state changes if needed
                // e.g., when scanning, connecting, or connected to the target network.
                // This requires WifiHelper to expose its state.
                Log.d(TAG,"Received WifiHelper State: $state") // Placeholder
                // Example: If WifiHelper reports connection lost, trigger disconnect.
                // if (state == WifiHelper.WifiState.DISCONNECTED_FROM_TARGET) {
                //     if (_connectionState.value != DeviceRepository.ConnectionState.DISCONNECTED) {
                //         Log.w(TAG, "Detected Wi-Fi disconnection from target. Initiating full disconnect.")
                //         disconnectFromDevice()
                //     }
                // }
            }
        }
    }

}