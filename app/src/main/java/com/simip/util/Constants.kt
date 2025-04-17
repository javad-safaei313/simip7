package com.simip.util

object Constants {

    // --- Network Configuration ---
    const val DEVICE_HOST_ADDRESS = "192.168.4.1"
    const val DEVICE_TCP_PORT = 8888
    const val WIFI_SSID_PATTERN = "kia" // Case-insensitive check will be needed
    const val CONNECTION_TIMEOUT_MS = 5000 // Initial connection timeout
    const val READ_TIMEOUT_MS = 2000       // Default read timeout
    const val LONG_READ_TIMEOUT_MS = 7000 // Longer timeout for potentially slow operations like measurement completion
    const val POLLING_INTERVAL_MS = 2000L  // Interval for 'Gets' command polling (2 seconds)
    const val DATA_REQUEST_INTERVAL_MS = 5000L // Interval for 'Data' command request during measurement (5 seconds)
    const val COMMAND_TERMINATOR = "\n\r"
    const val MAX_CONNECTION_RETRIES = 10
    const val RETRY_DELAY_MS = 2000L
    const val MAX_GETS_FAILURES = 10 // Consecutive failures before declaring device error

    // --- Device Commands ---
    const val CMD_GET_VERSION = "Vers"
    const val CMD_GET_STATUS = "Gets"
    const val CMD_SET_CONFIG = "SetConfig" // Followed by ,XXX,Stack,Time
    const val CMD_START_MEASUREMENT = "Star"
    const val CMD_REQUEST_DATA = "Data" // Command to request data during measurement progress

    // --- Device Response Keywords ---
    // (Should match DataParser keywords for consistency)
    const val RESPONSE_VERSION_PREFIX = "Ver" // e.g., Ver1.0
    const val RESPONSE_STATE_PREFIX = "State," // e.g., State,0,500,...
    const val RESPONSE_CONFIG_ACK_PREFIX = "ResConf," // e.g., ResConf,500,4,2
    const val RESPONSE_START_ACK = "ResStar"
    const val RESPONSE_BUSY_PREFIX = "BussyM" // e.g., BussyMS00
    const val RESPONSE_DATA_PREFIX = "Data," // e.g., Data,123,500,...

    // --- Project Types (Keys used in Spinners, DB, etc.) ---
    // Redundant with ProjectType enum keys, but can be useful here for reference or non-enum usage
    const val PROJECT_TYPE_SHOLOM = "sholom"
    const val PROJECT_TYPE_DPDP = "DP-DP"
    const val PROJECT_TYPE_PDP = "P-DP"
    const val PROJECT_TYPE_PP = "P-P"

    // --- UI & Settings Related ---
    const val DEFAULT_X0 = 0.0
    const val DEFAULT_DISTANCE = 10.0
    const val DEFAULT_N = 1
    const val MIN_N = 1
    const val DEFAULT_CURRENT_MA = 80 // Min value for SeekBar
    const val MAX_CURRENT_MA = 800 // Max value for SeekBar
    const val DEFAULT_TIME_S: Float = 2f // Default selection for Time spinner
    const val DEFAULT_STACK: Int = 2 // Default selection for Stack spinner
    val TIME_OPTIONS_S = listOf(2f, 4f, 6f, 8f) // Options for Time spinner
    val STACK_OPTIONS = listOf(2, 4, 6, 8)      // Options for Stack spinner

    // --- Database ---
    const val DATABASE_NAME = "simip_database"

    // --- File Export ---
    const val EXPORT_FILE_EXTENSION = ".xlsx"
    // Folder name in Downloads might vary based on Android version access (Scoped Storage)
    // Using standard Downloads directory. WRITE_EXTERNAL_STORAGE needed for older APIs.
    // For newer APIs (>= Q), MediaStore API is preferred.

    // --- Preferences Keys (Example, if using SharedPreferences) ---
    const val PREF_KEY_LAST_PROJECT_NAME = "last_project_name"
    const val PREF_KEY_LAST_PROJECT_TYPE = "last_project_type"
    const val PREF_KEY_LANGUAGE = "app_language" // e.g., "en", "fa"

    // --- Logging Tag ---
    const val APP_LOG_TAG = "SIMIP_APP"

    // --- Default Values for UI Formatting ---
    const val DEFAULT_DECIMAL_FORMAT_V_MN = "%.3f" // 3 decimal places for V_MN
    const val DEFAULT_DECIMAL_FORMAT_BAT_TEMP = "%.1f" // 1 decimal place for Battery and Temp
    const val DEFAULT_TEXT_VALUE_PLACEHOLDER = "--" // Placeholder for unavailable values

}