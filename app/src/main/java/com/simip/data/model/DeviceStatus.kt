package com.simip.data.model

/**
 * Represents the status information received from the device via the 'Gets' command.
 * Expected response format: State,Fe,SetAmper,stack,Time,no,VolttageBat,temperature,measureMNvolt
 *
 * Note: Nullable types are used for robustness in case parsing fails for a specific field,
 * or if the device sends unexpected data. Default values might be considered too.
 */
data class DeviceStatus(
    val state: String?,          // Raw state string from the device (e.g., "Ready", "Busy") - Might need more parsing
    val fe: String?,             // Unknown purpose based on spec ("Fe") - kept as String
    val setAmper: Int?,         // Configured current (mA)
    val stack: Int?,            // Configured stack/repeat count
    val time: Float?,           // Configured time (s)
    val no: String?,             // Unknown purpose based on spec ("no") - kept as String
    val voltageBat: Float?,     // Battery voltage (V) - Requires conversion in UI display format
    val temperature: Float?,    // Temperature (°C) - Requires conversion in UI display format
    val measureMNvolt: Float?   // Instantaneous MN voltage (mV) - Requires conversion in UI display format
) {
    companion object {
        /**
         * Represents an initial or disconnected status.
         */
        val DISCONNECTED = DeviceStatus(
            state = null,
            fe = null,
            setAmper = null,
            stack = null,
            time = null,
            no = null,
            voltageBat = null,
            temperature = null,
            measureMNvolt = null
        )

        /**
         * Represents a status indicating an error or invalid data.
         */
        val ERROR = DeviceStatus(
            state = "Error", // Or some specific error indicator
            fe = null,
            setAmper = null,
            stack = null,
            time = null,
            no = null,
            voltageBat = null,
            temperature = null,
            measureMNvolt = null
        )
    }
}