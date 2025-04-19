package com.simip.simip7.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive // Import specifically if needed, often implicit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

// Custom Exception for parsing errors
class ParsingException(message: String, cause: Throwable? = null) : Exception(message, cause)

class DeviceRepositoryImpl : DeviceRepository {

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    // --- StateFlow for Connection Status ---
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // --- StateFlow for Latest Device Status ---
    // Initialize with failure to indicate no status received yet
    private val _latestDeviceStatus = MutableStateFlow<Result<DeviceStatus>>(
        Result.failure(IOException("No status requested yet"))
    )
    override val latestDeviceStatus: StateFlow<Result<DeviceStatus>> = _latestDeviceStatus.asStateFlow()

    // --- SharedFlow for Measurement Data Events ---
    private val _measurementDataFlow = MutableSharedFlow<Result<MeasurementData>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val measurementDataFlow: SharedFlow<Result<MeasurementData>> = _measurementDataFlow.asSharedFlow()

    private val TAG = "DeviceRepoImpl" // Shortened Tag for brevity in logs
    private val CONNECT_TIMEOUT_MS = 5000 // Use const val for compile-time constants
    private val VERS_COMMAND = "Vers\n\r"     // Ensure line endings match device spec
    private val GETS_COMMAND = "Gets\n\r"
    private val STAR_COMMAND = "Star\n\r"
    private val DATA_COMMAND = "Data\n\r"

    // ====================================================================================
    // Public Interface Implementation
    // ====================================================================================

    override suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (_connectionState.value is ConnectionState.Connected) {
            Log.i(TAG, "Already connected.")
            return@withContext Result.success(Unit)
        }
        if (_connectionState.value is ConnectionState.Connecting) {
            Log.w(TAG, "Connection attempt already in progress.")
            return@withContext Result.failure(IOException("Connection attempt in progress"))
        }

        _connectionState.value = ConnectionState.Connecting
        Log.d(TAG, "Attempting connection to $host:$port")

        try {
            socket = Socket()
            socket!!.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)

            writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream(), StandardCharsets.UTF_8), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), StandardCharsets.UTF_8))
            Log.i(TAG, "Socket connected. Verifying communication with VERS command...")

            // Send VERS and wait for response using the helper
            val versResult = sendCommandAndReadResponse(VERS_COMMAND, Companion.READ_TIMEOUT_MS)

            versResult.fold(
                onSuccess = { response ->
                    if (response.startsWith("Ver")) { // Adapt based on actual response format
                        Log.i(TAG, "Connection successful and verified. Device Info: $response")
                        _connectionState.value = ConnectionState.Connected(response)
                        Result.success(Unit) // Fold returns this Result
                    } else {
                        Log.w(TAG, "Connection verified but VERS response unexpected: $response")
                        disconnectInternal()
                        val error = IOException("Invalid VERS response: $response")
                        _connectionState.value = ConnectionState.Failed(error)
                        Result.failure(error) // Fold returns this Result
                    }
                },
                onFailure = { exception ->
                    Log.e(TAG, "Failed to get VERS response after connection", exception)
                    disconnectInternal()
                    _connectionState.value = ConnectionState.Failed(exception)
                    Result.failure(exception) // Fold returns this Result
                }
            ) // End of fold - the result of fold is the result of the connect function's try block

        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Connection timed out", e)
            disconnectInternal()
            _connectionState.value = ConnectionState.Failed(e)
            Result.failure(e) // Catch block returns this Result
        } catch (e: IOException) {
            Log.e(TAG, "Connection IO error", e)
            disconnectInternal()
            _connectionState.value = ConnectionState.Failed(e)
            Result.failure(e) // Catch block returns this Result
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected connection error", e)
            disconnectInternal()
            _connectionState.value = ConnectionState.Failed(e)
            Result.failure(e) // Catch block returns this Result
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Disconnect requested.")
        disconnectInternal()
    }

    override suspend fun requestDeviceStatus(): Result<DeviceStatus> = withContext(Dispatchers.IO) {
        val result = sendCommandAndReadResponse(GETS_COMMAND, Companion.READ_TIMEOUT_MS)
        val parsedResult = result.fold(
            onSuccess = { response -> parseDeviceStatus(response) },
            onFailure = { Result.failure(it) }
        )
        // Update the state flow regardless of success/failure
        _latestDeviceStatus.value = parsedResult
        return@withContext parsedResult
    }

    override suspend fun applyConfigAndStartMeasurement(config: SimipConfig): Result<Unit> = withContext(Dispatchers.IO) {
        // 1. Send SetConfig command
        val setConfigCommand = formatSetConfigCommand(config)
        Log.d(TAG, "Sending SetConfig: $setConfigCommand")
        val confResult = sendCommandAndReadResponse(setConfigCommand, Companion.READ_TIMEOUT_MS)

        val confValidation = confResult.fold(
            onSuccess = { response -> validateResConf(response, config) },
            onFailure = { Result.failure<Unit>(it) }
        )

        if (confValidation.isFailure) {
            Log.e(TAG, "SetConfig failed or response invalid.", confValidation.exceptionOrNull())
            return@withContext Result.failure(confValidation.exceptionOrNull() ?: IOException("SetConfig failed"))
        }

        Log.i(TAG, "SetConfig successful (ResConf validated). Sending Star...")

        // 2. Send Star command
        val starResult = sendCommandAndReadResponse(STAR_COMMAND, Companion.READ_TIMEOUT_MS)
        val starValidation = starResult.fold(
            onSuccess = { response -> validateResStar(response) },
            onFailure = { Result.failure<Unit>(it) }
        )

        if (starValidation.isFailure) {
            Log.e(TAG, "Star command failed or response invalid.", starValidation.exceptionOrNull())
            return@withContext Result.failure(starValidation.exceptionOrNull() ?: IOException("Star failed"))
        }

        Log.i(TAG, "Star command successful (ResStar validated). Measurement should start.")
        return@withContext Result.success(Unit)
    }

    override suspend fun requestSingleMeasurement(): Result<MeasurementData> = withContext(Dispatchers.IO) {
        Log.v(TAG, "Requesting single measurement data...")
        val result = sendCommandAndReadResponse(DATA_COMMAND, Companion.READ_TIMEOUT_MS)
        val parsedResult = result.fold(
            onSuccess = { response -> parseMeasurementData(response) },
            onFailure = { Result.failure(it) }
        )
        // Emit the result (success or failure) to the SharedFlow
        _measurementDataFlow.tryEmit(parsedResult) // Use tryEmit for SharedFlow

        // Also return the result for immediate feedback if needed
        return@withContext parsedResult
    }

    // ====================================================================================
    // Internal Helper Functions
    // ====================================================================================

    /**
     * Sends a command, waits for a single-line response with timeout, and handles errors.
     * This is the core communication function.
     */
    private suspend fun sendCommandAndReadResponse(command: String, timeoutMs: Long): Result<String> {
        // Check connection status first
        if (socket?.isConnected != true || writer == null || reader == null) {
            val error = IOException("Not connected. Cannot send command: $command")
            // Log using the recommended way (with Throwable)
            Log.w(TAG, "Failed to send command because not connected.", error)
            // Only change state if not already failed/disconnected
            if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
                _connectionState.value = ConnectionState.Failed(error)
                // Consider if disconnect is needed here, depends on strategy
                // disconnectInternal()
            }
            // Return failure directly
            return Result.failure(error)
        }

        // Execute network operation within try-catch
        // No 'return' needed before 'try'; the try-catch block itself is an expression

          try {
            Log.d(TAG, "Sending command: $command")
            writer?.println(command) // Assumes command includes \n\r
            writer?.flush() // Ensure data is sent

            // Robust reading with timeout
            var responseLine: String? = null
            // 'jobResult' will hold the String? returned by the block, or null if timeout occurred
            val jobResult = withTimeoutOrNull(timeoutMs) {
                // Basic loop to read a single line, ignoring potential blanks (adapt if multi-line needed)
                while (isActive) { // Check coroutine status
                    val line = reader?.readLine() // Blocking call - relies on underlying socket timeout or data
                    if (line != null) {
                        responseLine = line // Store the received line
                        break // Exit loop once a line is read
                    }
                    // If readLine returns null, it usually means end-of-stream (connection closed)
                    // Throw exception to handle this case within the try-catch
                    throw IOException("End of stream reached while reading response for $command (connection closed?)")
                    // Note: Removed the busy-wait loop with delay(20) as readLine() should block until data or timeout/error.
                    // Relying on withTimeoutOrNull and socket's potential SO_TIMEOUT is standard.
                }
                 responseLine // Return the read line from the block
            }

            // Check if timeout occurred (withTimeoutOrNull returned null)
            if (jobResult == null) {
                throw SocketTimeoutException("Timeout ($timeoutMs ms) waiting for response to command: $command")
            }

            // If we reach here, timeout did not occur, and jobResult holds the response line
            responseLine = jobResult
            Log.d(TAG, "Received response: $responseLine")
               Result.success(responseLine) // Try block evaluates to Success

        } catch (e: TimeoutCancellationException) {
            // This specifically catches the timeout from withTimeoutOrNull
            Log.e(TAG, "Coroutine Timeout waiting for response to command $command", e)
              return Result.failure(SocketTimeoutException("Timeout waiting for response: ${e.message}"))
        } catch (e: SocketTimeoutException) {
            // This catches timeouts from socket operations if socket.soTimeout was set
            Log.e(TAG, "Socket operation timed out for command $command", e)
              return Result.failure(e)
        } catch (e: IOException) {
            // Catch connection closed during read or other IO errors
            Log.e(TAG, "IO Error during send/receive for command $command", e)
            // Critical error, likely disconnect
            _connectionState.value = ConnectionState.Failed(e)
            disconnectInternal()
              return Result.failure(e)
        } catch (e: Exception) {
            // Catch any other unexpected errors
            Log.e(TAG, "Unexpected error during send/receive for $command", e)
            _connectionState.value = ConnectionState.Failed(e)
            disconnectInternal()
              return   Result.failure(e)
        }
        return Result.failure(IllegalStateException("Should not be reached after try-catch expression"))
    }


    /**
     * Closes socket and streams safely, updating connection state.
     */
    private fun disconnectInternal() {
        Log.i(TAG, "Closing connection resources.")
        // Close resources in reverse order of creation, ignoring errors during close
        try { writer?.close() } catch (e: Exception) { Log.e(TAG, "Error closing writer", e) }
        try { reader?.close() } catch (e: Exception) { Log.e(TAG, "Error closing reader", e) }
        try { socket?.close() } catch (e: Exception) { Log.e(TAG, "Error closing socket", e) }

        writer = null
        reader = null
        socket = null

        // Only update state if it wasn't already Failed or Disconnected
        if (_connectionState.value !is ConnectionState.Failed && _connectionState.value !is ConnectionState.Disconnected) {
            _connectionState.value = ConnectionState.Disconnected
        }
        // Reset status to indicate disconnection
        _latestDeviceStatus.value = Result.failure(IOException("Disconnected"))
    }

    // --- Command Formatting ---
    private fun formatSetConfigCommand(config: SimipConfig): String {
        val formattedCurrent = config.current.toString().padStart(3, '0')
        return "SetConfig,$formattedCurrent,${config.stack},${config.time}\n\r" // Ensure line endings
    }

    // --- Response Parsers (with detailed error handling) ---

    private fun parseDeviceStatus(response: String): Result<DeviceStatus> {
        Log.d(TAG, "Parsing DeviceStatus: $response")
        val parts = response.split(',')
        if (parts.size < 9) {
            return Result.failure(ParsingException("Invalid 'Gets' format: Expected >=9 parts, got ${parts.size} in '$response'"))
        }
        return try {
            Result.success(
                DeviceStatus(
                    state = parts[0].ifEmpty { null },
                    fe = parts[1].ifEmpty { null },
                    setAmper = parts[2].toDoubleOrNull(),
                    stack = parts[3].toIntOrNull(),
                    time = parts[4].toIntOrNull(),
                    no = parts[5].toIntOrNull(),
                    voltageBat = parts[6].toDoubleOrNull(),
                    temperature = parts[7].toDoubleOrNull(),
                    measureMNvolt = parts[8].toDoubleOrNull()
                )
            )
        } catch (e: NumberFormatException) {
            Result.failure(ParsingException("Number format error parsing 'Gets': '$response'", e))
        } catch (e: Exception) {
            Result.failure(ParsingException("Generic error parsing 'Gets': '$response'", e))
        }
    }

    private fun parseMeasurementData(response: String): Result<MeasurementData> {
        Log.d(TAG, "Parsing MeasurementData: $response")
        if (!response.startsWith("Data,")) {
            return Result.failure(ParsingException("Invalid 'Data' prefix: '$response'"))
        }
        val parts = response.substring(5).split(',') // Remove "Data,"

        // Expecting Id + 9 fixed fields + 20 IP = 30 parts
        if (parts.size < 30) {
            return Result.failure(ParsingException("Invalid 'Data' format: Expected >=30 parts after 'Data,', got ${parts.size} in '$response'"))
        }

        try {
            val id = parts[0].toInt() // Throw if not Int
            val setAmper = parts[1].toDouble()
            val stack = parts[2].toInt()
            val time = parts[3].toInt()
            // parts[4] skipped ('not' field)
            val voltageBat = parts[5].toDouble()
            val temperature = parts[6].toDouble()
            val contactResistance = parts[7].toDouble()
            val measuredAmper = parts[8].toDouble()
            val measuredSP = parts[9].toDouble()
            val measuredPotential = parts[10].toDouble() // deltaV

            val ipDecay = mutableListOf<Double?>()
            for (i in 11 until minOf(parts.size, 31)) { // Indices 11 to 30
                // Allow nulls for individual IP values if they fail parsing
                ipDecay.add(parts[i].toDoubleOrNull())
            }
            // Ensure exactly 20 values, padding with null if response was shorter
            while (ipDecay.size < 20) {
                ipDecay.add(null)
            }

            return Result.success(
                MeasurementData(
                    id = id, setAmper = setAmper, stack = stack, time = time,
                    voltageBat = voltageBat, temperature = temperature,
                    contactResistance = contactResistance, measuredAmper = measuredAmper,
                    measuredSP = measuredSP, measuredPotential = measuredPotential,
                    ipDecay = ipDecay
                )
            )
        } catch (e: NumberFormatException) {
            Result.failure<MeasurementData>(ParsingException("Number format error parsing 'Data': '$response'", e))
        } catch (e: IndexOutOfBoundsException) {
            Result.failure(ParsingException("Index out of bounds error parsing 'Data' (likely missing fields): '$response'", e))
        } catch (e: Exception) {
            Result.failure(ParsingException("Generic error parsing 'Data': '$response'", e))
        }
        return Result.failure(IllegalStateException("Should not be reached after try-catch expression"))
    }

    // --- Response Validators ---

    private fun validateResConf(response: String, expectedConfig: SimipConfig): Result<Unit> {
        Log.d(TAG, "Validating ResConf: $response")
        if (!response.startsWith("ResConf,")) {
            return Result.failure(ParsingException("Invalid 'ResConf' prefix: '$response'"))
        }
        val parts = response.substring(8).split(',')
        if (parts.size != 3) {
            return Result.failure(ParsingException("Invalid 'ResConf' format: Expected 3 parts, got ${parts.size} in '$response'"))
        }
        try {
            val respCurrentStr = parts[0] // Keep as string for comparison with formatted expected value
            val respStack = parts[1].toInt()
            val respTime = parts[2].toInt()
            val expectedFormattedCurrentStr = expectedConfig.current.toString().padStart(3, '0')

            if (respCurrentStr == expectedFormattedCurrentStr && respStack == expectedConfig.stack && respTime == expectedConfig.time) {
                return Result.success(Unit)
            } else {
                return Result.failure(ParsingException("Mismatch in 'ResConf': Expected $expectedFormattedCurrentStr,${expectedConfig.stack},${expectedConfig.time} Got $respCurrentStr,$respStack,$respTime in '$response'"))
            }
        } catch (e: NumberFormatException) {
            return Result.failure(ParsingException("Number format error validating 'ResConf': '$response'", e))
        } catch (e: Exception) {
            return Result.failure(ParsingException("Generic error validating 'ResConf': '$response'", e))
        }
    }

    private fun validateResStar(response: String): Result<Unit> {
        Log.d(TAG, "Validating ResStar: $response")
        return if (response.trim() == "ResStar") { // Use trim() just in case of whitespace
            Result.success(Unit)
        } else {
            Result.failure(ParsingException("Invalid 'ResStar' response: Expected 'ResStar', Got '$response'"))
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS: Long = 3000L // Corrected type to Long
    }
}