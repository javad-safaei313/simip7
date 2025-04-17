package com.simip.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

/**
 * Handles TCP/IP communication with the geophysical device.
 * Provides methods for connecting, sending commands, and receiving responses.
 * Uses Kotlin Coroutines and Flow for asynchronous operations.
 */
class TcpClient(
    private val host: String = HOST_ADDRESS, // Default IP address
    private val port: Int = PORT             // Default Port
) {

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var isConnected: Boolean = false

    companion object {
        private const val TAG = "TcpClient"
        private const val CONNECTION_TIMEOUT_MS = 5000 // 5 seconds for initial connection
        private const val READ_TIMEOUT_MS = 2000       // 2 seconds for reading response (adjust as needed)
        private const val COMMAND_TERMINATOR = "\n\r"
        const val HOST_ADDRESS = "192.168.4.1"
        const val PORT = 8888
    }

    /**
     * Attempts to establish a TCP connection to the device.
     * Must be called from a coroutine scope (e.g., using viewModelScope).
     * @return True if connection is successful, false otherwise.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) {
            Log.d(TAG, "Already connected.")
            return@withContext true
        }
        try {
            Log.d(TAG, "Attempting to connect to $host:$port...")
            socket = Socket()
            // Set a timeout for the connection attempt itself
            socket?.connect(InetSocketAddress(host, port), CONNECTION_TIMEOUT_MS)
            // Set a timeout for read operations
            socket?.soTimeout = READ_TIMEOUT_MS

            writer = PrintWriter(OutputStreamWriter(socket!!.getOutputStream(), Charsets.UTF_8), true) // Auto-flush enabled
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream(), Charsets.UTF_8))
            isConnected = true
            Log.d(TAG, "Connection successful.")
            true
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Connection attempt timed out.", e)
            closeConnectionInternal() // Ensure resources are released on timeout
            false
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            closeConnectionInternal() // Ensure resources are released on error
            false
        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred during connection: ${e.message}", e)
            closeConnectionInternal()
            false
        }
    }

    /**
     * Sends a command string to the connected device.
     * Appends the required newline and carriage return terminator.
     * Must be called from a coroutine scope.
     * @param command The command string to send (e.g., "Vers", "Gets", "SetConfig,XXX,S,T").
     * @return True if the command was sent successfully, false otherwise (e.g., not connected).
     */
    suspend fun sendCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected || writer == null || socket?.isClosed == true || socket?.isConnected == false) {
            Log.w(TAG, "Cannot send command, not connected.")
            isConnected = false // Update connection state
            return@withContext false
        }
        try {
            val fullCommand = command + COMMAND_TERMINATOR
            Log.d(TAG, "Sending command: $fullCommand")
            writer?.print(fullCommand) // Use print, not println, as terminator is included
            writer?.flush() // Ensure data is sent immediately
            if (writer?.checkError() == true) {
                Log.e(TAG, "PrintWriter error after sending command.")
                // Consider connection lost if error occurs
                closeConnectionInternal()
                return@withContext false
            }
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error sending command: ${e.message}", e)
            closeConnectionInternal() // Assume connection lost on send error
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error sending command: ${e.message}", e)
            closeConnectionInternal()
            false
        }
    }

    /**
     * Reads a single line of response from the device.
     * This is a blocking call on the IO thread but handled within a coroutine.
     * Includes a timeout mechanism.
     * @param timeoutMillis Timeout duration for this specific read attempt.
     * @return The response string (without terminators), or null if timeout or error occurs.
     */
    suspend fun readLineWithTimeout(timeoutMillis: Long = READ_TIMEOUT_MS.toLong()): String? = withContext(Dispatchers.IO) {
        if (!isConnected || reader == null || socket?.isClosed == true || !socket!!.isConnected) {
            Log.w(TAG, "Cannot read line, not connected.")
            isConnected = false
            return@withContext null
        }
        try {
            // Use withTimeoutOrNull for the read operation
            val response = withTimeoutOrNull(timeoutMillis) {
                // Check if ready first to potentially avoid blocking if stream closed
                if (reader?.ready() == false) {
                    // Small delay to prevent busy-waiting if stream temporarily not ready
                    // but still connected. Might need adjustment.
                    delay(50)
                    if (reader?.ready() == false && !isSocketConnectedCheck()) {
                        Log.w(TAG, "Reader not ready and socket seems disconnected.")
                        throw IOException("Socket disconnected while waiting for reader.")
                    }
                }
                reader?.readLine() // This can block
            }

            if (response == null) {
                Log.w(TAG, "Read timed out after ${timeoutMillis}ms.")
                // Check connection status more definitively after timeout
                if (!isSocketConnectedCheck()) {
                    Log.e(TAG, "Socket appears disconnected after read timeout.")
                    closeConnectionInternal()
                }
            } else {
                Log.d(TAG, "Received: $response")
            }
            response // Return the response or null if timeout occurred
        } catch (e: IOException) {
            Log.e(TAG, "Error reading line: ${e.message}", e)
            closeConnectionInternal() // Assume connection lost on read error
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error reading line: ${e.message}", e)
            closeConnectionInternal()
            null
        }
    }

    /**
     * Provides a continuous Flow of lines read from the socket.
     * This is useful for scenarios where multiple unsolicited messages might be received,
     * although the primary interaction seems request-response based on the spec.
     * The flow runs on Dispatchers.IO.
     * It completes when the connection is closed or an error occurs.
     * Note: This might compete with readLineWithTimeout if used concurrently without care.
     * Recommended use: Either use readLineWithTimeout for request-response or this Flow
     * for continuous listening, managed by the repository/viewmodel.
     */
    fun listenForMessages(): Flow<String> = flow {
        if (!isConnected || reader == null) {
            Log.w(TAG, "Cannot listen, not connected.")
            return@flow // Complete the flow immediately
        }
        Log.d(TAG, "Starting to listen for messages...")
        try {
            var line: String?
            while (isActiveAndConnected()) { // Check both coroutine and connection status
                line = reader?.readLine() // Blocking call
                if (line != null) {
                    Log.d(TAG, "Flow Received: $line")
                    emit(line)
                } else {
                    // readLine returned null, typically means end of stream
                    Log.w(TAG, "readLine returned null, stream ended or connection closed.")
                    break // Exit the loop
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "IOException during listen: ${e.message}", e)
            // Flow will complete due to exception
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during listen: ${e.message}", e)
            // Flow will complete due to exception
        } finally {
            Log.d(TAG, "Stopping message listener flow.")
            // Don't close connection here automatically, let the caller manage lifecycle
            // closeConnectionInternal()
        }
    }.flowOn(Dispatchers.IO) // Ensure the flow runs on the IO dispatcher


    /**
     * Closes the TCP connection and releases resources.
     * Safe to call even if already closed.
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        closeConnectionInternal()
    }

    /**
     * Internal function to close resources. Should be called on IO Dispatcher.
     */
    private fun closeConnectionInternal() {
        if (!isConnected && socket == null) return // Already closed or never opened

        Log.d(TAG, "Closing connection...")
        isConnected = false
        try {
            // Close streams first, then socket
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing connection resources: ${e.message}", e)
        } finally {
            writer = null
            reader = null
            socket = null
            Log.d(TAG, "Connection closed.")
        }
    }

    /**
     * Checks if the socket is currently connected.
     * This performs checks beyond the simple 'isConnected' flag.
     * @return True if the socket appears connected, false otherwise.
     */
    fun isCurrentlyConnected(): Boolean {
        return isConnected && isSocketConnectedCheck()
    }

    /**
     * More thorough check of socket connection status.
     * Note: These checks might not always be definitive for TCP state.
     */
    private fun isSocketConnectedCheck(): Boolean {
        return socket != null && socket!!.isConnected && !socket!!.isClosed && !socket!!.isInputShutdown && !socket!!.isOutputShutdown
    }


    /**
     * Helper to check coroutine status and connection status for loops.
     */
    // Requires access to a CoroutineScope or knowledge if the calling coroutine is active.
    // This is better handled in the calling scope (ViewModel/Repository).
    // Placeholder concept:
    private fun isActiveAndConnected(): Boolean {
        // In a real scenario, you'd check kotlinx.coroutines.isActive here if needed,
        // but primarily rely on socket status.
        return isCurrentlyConnected()
    }

}