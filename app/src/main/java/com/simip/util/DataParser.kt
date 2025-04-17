package com.simip.util

import android.util.Log
import com.simip.data.model.DeviceStatus
import com.simip.data.model.MeasurementPacket

/**
 * Utility object for parsing string responses received from the device.
 * Implements logic based on keywords (Ver, State, ResConf, ResStar, BussyM, Data).
 */
object DataParser {

    private const val TAG = "DataParser"

    // Define keywords
    private const val KEYWORD_VERSION = "Ver"
    private const val KEYWORD_STATE = "State," // Include comma for better matching
    private const val KEYWORD_RES_CONF = "ResConf,"
    private const val KEYWORD_RES_STAR = "ResStar" // No comma expected after this one
    private const val KEYWORD_BUSSY = "BussyM" // BussyMSxx, BussyMCxx, BussyMVxx
    private const val KEYWORD_DATA = "Data,"

    private val ALL_KEYWORDS = listOf(
        KEYWORD_VERSION, KEYWORD_STATE, KEYWORD_RES_CONF,
        KEYWORD_RES_STAR, KEYWORD_BUSSY, KEYWORD_DATA
    )

    // Enum to represent the type of parsed response
    sealed class ParseResult {
        data class Success<T>(val data: T) : ParseResult()
        data class Error(val message: String, val originalInput: String? = null) : ParseResult()
        object Ignore : ParseResult() // For messages to be ignored (e.g., only keyword found but invalid structure)
        object Incomplete : ParseResult() // Could be used if a partial message is detected (advanced)
    }

    /**
     * Parses a raw string received from the device.
     * Finds the first valid keyword and attempts to parse the data following it.
     * Ignores data before the keyword.
     *
     * @param rawData The raw string received from the socket.
     * @return A ParseResult indicating success (with parsed data), error, or ignore.
     */
    fun parseResponse(rawData: String?): ParseResult {
        if (rawData.isNullOrBlank()) {
            return ParseResult.Error("Received null or blank data")
        }

        Log.d(TAG, "Parsing raw data: '$rawData'")

        var keywordFound: String? = null
        var keywordIndex = -1

        // Find the first occurrence of any known keyword
        for (keyword in ALL_KEYWORDS) {
            val index = rawData.indexOf(keyword)
            if (index != -1) {
                // If we found a keyword earlier in the string, keep that one
                if (keywordIndex == -1 || index < keywordIndex) {
                    keywordIndex = index
                    keywordFound = keyword
                }
            }
        }

        if (keywordFound == null || keywordIndex == -1) {
            Log.w(TAG, "No valid keyword found in data: '$rawData'")
            return ParseResult.Error("No keyword found", rawData)
        }

        // Extract the relevant part of the string starting from the keyword
        val relevantData = rawData.substring(keywordIndex)
        Log.d(TAG, "Found keyword '$keywordFound' at index $keywordIndex. Relevant data: '$relevantData'")

        return when (keywordFound) {
            KEYWORD_VERSION -> parseVersion(relevantData)
            KEYWORD_STATE -> parseDeviceStatus(relevantData)
            KEYWORD_RES_CONF -> parseConfigResponse(relevantData)
            KEYWORD_RES_STAR -> ParseResult.Success(KEYWORD_RES_STAR) // Just acknowledge the keyword
            KEYWORD_BUSSY -> parseBussyMessage(relevantData)
            KEYWORD_DATA -> parseMeasurementPacket(relevantData)
            else -> {
                // Should not happen if keywordFound is from ALL_KEYWORDS
                Log.e(TAG, "Internal error: Keyword '$keywordFound' matched but not handled.")
                ParseResult.Error("Internal parser error", relevantData)
            }
        }
    }

    // --- Specific Parser Functions ---

    private fun parseVersion(data: String): ParseResult {
        // Expected format: VerX.Y... (allow for extra chars after version)
        if (!data.startsWith(KEYWORD_VERSION)) return ParseResult.Error("Data does not start with $KEYWORD_VERSION", data)
        // Extract version string right after "Ver"
        val version = data.substring(KEYWORD_VERSION.length).trim().takeWhile { it.isDigit() || it == '.' }
        if (version.isNotEmpty() && version.matches(Regex("\\d+\\.\\d+"))) {
            Log.d(TAG, "Parsed Version: $version")
            return ParseResult.Success(version)
        } else {
            Log.w(TAG, "Could not parse valid version from: '$data'")
            // If we found "Ver" but no valid X.Y after it, treat as error or ignore? Let's say Error.
            return ParseResult.Error("Invalid version format", data)
        }
    }

    private fun parseDeviceStatus(data: String): ParseResult {
        // Expected format: State,Fe,SetAmper,stack,Time,no,VolttageBat,temperature,measureMNvolt
        if (!data.startsWith(KEYWORD_STATE)) return ParseResult.Error("Data does not start with $KEYWORD_STATE", data)
        val parts = data.substring(KEYWORD_STATE.length).split(',')
        if (parts.size < 8) { // Need at least 8 parts after "State,"
            Log.w(TAG, "Incorrect number of parts for State: ${parts.size} in '$data'")
            return ParseResult.Error("Incorrect State format (parts count)", data)
        }
        try {
            val status = DeviceStatus(
                state = parts.getOrNull(0)?.trim(), // Actually the 'Fe' value comes first after State,
                fe = parts.getOrNull(1)?.trim(),    // Then SetAmper etc. Adjusting indices:
                setAmper = parts.getOrNull(2)?.trim()?.toIntOrNull(),
                stack = parts.getOrNull(3)?.trim()?.toIntOrNull(),
                time = parts.getOrNull(4)?.trim()?.toFloatOrNull(),
                no = parts.getOrNull(5)?.trim(),
                voltageBat = parts.getOrNull(6)?.trim()?.toFloatOrNull(),
                temperature = parts.getOrNull(7)?.trim()?.toFloatOrNull(),
                measureMNvolt = parts.getOrNull(8)?.trim()?.toFloatOrNull() // Last part
            )
            // Let's fix the state/fe mapping according to the expected format string itself:
            // State, Fe, SetAmper, stack, Time, no, VolttageBat, temperature, measureMNvolt
            // The keyword is "State,", so parts[0] is Fe, parts[1] is SetAmper etc.
            // The actual *state* value isn't explicitly in this string per the doc format.
            // Let's assume the device *might* send something like "State=Ready,Fe,..." or the parser
            // needs context. Re-reading spec 6.2: "State,Fe,..."
            // This implies the first element *is* the state value itself. Let's adjust.
            val correctedStatus = DeviceStatus(
                state = parts.getOrNull(0)?.trim(), // This is the 'State' value
                fe = parts.getOrNull(1)?.trim(),    // This is 'Fe'
                setAmper = parts.getOrNull(2)?.trim()?.toIntOrNull(),
                stack = parts.getOrNull(3)?.trim()?.toIntOrNull(),
                time = parts.getOrNull(4)?.trim()?.toFloatOrNull(),
                no = parts.getOrNull(5)?.trim(),
                voltageBat = parts.getOrNull(6)?.trim()?.toFloatOrNull(),
                temperature = parts.getOrNull(7)?.trim()?.toFloatOrNull(),
                measureMNvolt = parts.getOrNull(8)?.trim()?.toFloatOrNull()
            )

            Log.d(TAG, "Parsed DeviceStatus: $correctedStatus")
            return ParseResult.Success(correctedStatus)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DeviceStatus: ${e.message}", e)
            return ParseResult.Error("Exception during State parsing", data)
        }
    }


    private fun parseConfigResponse(data: String): ParseResult {
        // Expected format: ResConf,XXX,stack,Time
        if (!data.startsWith(KEYWORD_RES_CONF)) return ParseResult.Error("Data does not start with $KEYWORD_RES_CONF", data)
        val parts = data.substring(KEYWORD_RES_CONF.length).split(',')
        if (parts.size < 3) {
            Log.w(TAG, "Incorrect number of parts for ResConf: ${parts.size} in '$data'")
            return ParseResult.Error("Incorrect ResConf format", data)
        }
        try {
            // Return a simple map or tuple for the confirmed values
            val confirmedConfig = mapOf(
                "SetAmper" to parts[0].trim().toIntOrNull(),
                "Stack" to parts[1].trim().toIntOrNull(),
                "Time" to parts[2].trim().toFloatOrNull()
            )
            // Check if all parts parsed correctly
            if (confirmedConfig.values.any { it == null }) {
                Log.w(TAG, "Failed to parse numeric values in ResConf: '$data'")
                return ParseResult.Error("Invalid numeric values in ResConf", data)
            }
            Log.d(TAG, "Parsed ResConf: $confirmedConfig")
            return ParseResult.Success(confirmedConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing ResConf: ${e.message}", e)
            return ParseResult.Error("Exception during ResConf parsing", data)
        }
    }

    private fun parseBussyMessage(data: String): ParseResult {
        // Expected format: BussyMSxx, BussyMCxx, BussyMVxx
        if (!data.startsWith(KEYWORD_BUSSY)) return ParseResult.Error("Data does not start with $KEYWORD_BUSSY", data)
        // Format: BussyM[StageCode][RepeatXX] where StageCode is S, C, or V
        if (data.length < KEYWORD_BUSSY.length + 3) { // Need at least S/C/V and two digits
            Log.w(TAG, "Incorrect length for Bussy message: '$data'")
            return ParseResult.Error("Incorrect Bussy format (length)", data)
        }
        try {
            val stageCodeChar = data[KEYWORD_BUSSY.length] // S, C, or V
            val repeatStr = data.substring(KEYWORD_BUSSY.length + 1)
            val repeat = repeatStr.toIntOrNull()

            val stage = when(stageCodeChar) {
                'S' -> 0 // MS = Measure SP? (Guessing)
                'C' -> 1 // MC = Measure Current?
                'V' -> 2 // MV = Measure Voltage/Decay?
                else -> {
                    Log.w(TAG, "Invalid stage code in Bussy message: '$stageCodeChar' in '$data'")
                    return ParseResult.Error("Invalid stage code in Bussy", data)
                }
            }

            if (repeat == null) {
                Log.w(TAG, "Invalid repeat number in Bussy message: '$repeatStr' in '$data'")
                return ParseResult.Error("Invalid repeat number in Bussy", data)
            }

            val progressInfo = Pair(stage, repeat) // Pair of (Stage Int, Repeat Int)
            Log.d(TAG, "Parsed Bussy: Stage=$stage, Repeat=$repeat")
            return ParseResult.Success(progressInfo) // Return the pair

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Bussy message: ${e.message}", e)
            return ParseResult.Error("Exception during Bussy parsing", data)
        }
    }

    private fun parseMeasurementPacket(data: String): ParseResult {
        // Expected: Data,Id,SetAmper,Stack,Time,not,VolttageBat,temprature,Contact,MeasureAmper,MeasureSP,deltav,Ip1,...,Ip20
        if (!data.startsWith(KEYWORD_DATA)) return ParseResult.Error("Data does not start with $KEYWORD_DATA", data)
        val parts = data.substring(KEYWORD_DATA.length).split(',')

        // Expecting 12 fixed fields + 20 IP decay values = 32 parts
        val expectedParts = 12 + MeasurementPacket.EXPECTED_IP_DECAY_POINTS
        if (parts.size < expectedParts) { // Allow for potentially more parts? Strict check for now.
            Log.w(TAG, "Incorrect number of parts for Data: ${parts.size} (expected $expectedParts) in '$data'")
            return ParseResult.Error("Incorrect Data format (parts count)", data)
        }

        try {
            val ipValues = mutableListOf<Float>()
            for (i in 0 until MeasurementPacket.EXPECTED_IP_DECAY_POINTS) {
                val ipVal = parts.getOrNull(11 + i)?.trim()?.toFloatOrNull()
                if (ipVal == null) {
                    Log.w(TAG, "Invalid or missing IP value at index ${i+1} in Data packet: '$data'")
                    // Decide handling: error out, or fill with 0/NaN? Let's error out for robustness.
                    return ParseResult.Error("Invalid IP value at index ${i+1}", data)
                }
                ipValues.add(ipVal)
            }

            // Check basic fields parsing before creating the object
            val id = parts[0].trim().toLongOrNull()
            val setAmper = parts[1].trim().toIntOrNull()
            val stack = parts[2].trim().toIntOrNull()
            val time = parts[3].trim().toFloatOrNull()
            val voltageBat = parts[5].trim().toFloatOrNull()
            val temperature = parts[6].trim().toFloatOrNull()
            val contact = parts[7].trim().toFloatOrNull()
            val measureAmper = parts[8].trim().toFloatOrNull()
            val measureSP = parts[9].trim().toFloatOrNull()
            val deltaV = parts[10].trim().toFloatOrNull()

            if (listOf(id, setAmper, stack, time, voltageBat, temperature, contact, measureAmper, measureSP, deltaV).any { it == null }) {
                Log.w(TAG, "Failed to parse one or more required numeric fields in Data packet: '$data'")
                return ParseResult.Error("Invalid numeric field in Data packet", data)
            }


            val packet = MeasurementPacket(
                id = id!!,
                setAmper = setAmper!!,
                stack = stack!!,
                time = time!!,
                not = parts[4].trim(),
                voltageBat = voltageBat!!,
                temperature = temperature!!,
                contact = contact!!,
                measureAmper = measureAmper!!,
                measureSP = measureSP!!,
                deltaV = deltaV!!,
                ipDecayWindowValues = ipValues
            )
            Log.d(TAG, "Parsed MeasurementPacket: Id=${packet.id}")
            return ParseResult.Success(packet)
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Number format error parsing Data packet: ${e.message}", e)
            return ParseResult.Error("Number format error in Data packet", data)
        } catch (e: IndexOutOfBoundsException) {
            Log.e(TAG, "Index out of bounds error parsing Data packet: ${e.message}", e)
            return ParseResult.Error("Index out of bounds in Data packet", data)
        } catch (e: Exception) {
            Log.e(TAG, "Generic error parsing Data packet: ${e.message}", e)
            return ParseResult.Error("Exception during Data parsing", data)
        }
    }
}