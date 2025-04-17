package com.simip.ui.acq

import kotlinx.coroutines.flow.StateFlow // اطمینان از وجود import
import kotlinx.coroutines.flow.asStateFlow // اطمینان از وجود import

import android.app.Application
import android.location.Location
import android.util.Log
import androidx.lifecycle.*
import com.simip.data.model.*
import com.simip.data.repository.DeviceRepository
import com.simip.data.repository.MeasurementRepository
import com.simip.util.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.simip.R
import com.google.android.gms.location.Priority

/**
 * ViewModel for the Acquisition (Acq) Fragment.
 * Handles settings, geometry, measurement control (Start/Save), data display,
 * IP decay graph, and saving measurements. Interacts with DeviceRepository,
 * MeasurementRepository, LocationHelper, and GeometryCalculator.
 */
class AcqViewModel(
    private val application: Application,
    private val deviceRepository: DeviceRepository,
    private val measurementRepository: MeasurementRepository,
    private val locationHelper: LocationHelper // Inject LocationHelper
) : AndroidViewModel(application) {

    private val TAG = "AcqViewModel"

    // --- State Exposure for UI ---

    // Settings
    private val _currentSetting = MutableStateFlow(Constants.DEFAULT_CURRENT_MA)
    val currentSetting: StateFlow<Int> = _currentSetting.asStateFlow()

    private val _timeSetting = MutableStateFlow(Constants.DEFAULT_TIME_S)
    val timeSetting: StateFlow<Float> = _timeSetting.asStateFlow()

    private val _stackSetting = MutableStateFlow(Constants.DEFAULT_STACK)
    val stackSetting: StateFlow<Int> = _stackSetting.asStateFlow()

    // Geometry
    private val _geoConfig = MutableStateFlow<GeoConfig>(GeoConfig.Uninitialized) // Start as uninitialized
    val geoConfig: StateFlow<GeoConfig> = _geoConfig.asStateFlow()
    // Predefined Sholom geometries (loaded from resources)
    private var sholomGeometries: List<Pair<Double, Double>> = emptyList()
    private val _isGeometryLocked = MutableStateFlow(false) // Lock geometry during measurement?
    val isGeometryLocked: StateFlow<Boolean> = _isGeometryLocked.asStateFlow()

    // Measurement Values Display
    private val _measuredDV = MutableStateFlow<Float?>(null)
    val measuredDV: StateFlow<Float?> = _measuredDV.asStateFlow()

    private val _measuredI = MutableStateFlow<Float?>(null)
    val measuredI: StateFlow<Float?> = _measuredI.asStateFlow()

    private val _calculatedIP = MutableStateFlow<Float?>(null) // Average IP
    val calculatedIP: StateFlow<Float?> = _calculatedIP.asStateFlow()

    private val _calculatedRoh = MutableStateFlow<Double?>(null)
    val calculatedRoh: StateFlow<Double?> = _calculatedRoh.asStateFlow()

    // IP Decay Curve Data (List of pairs: time (ms), value (mV/V))
    private val _ipDecayData = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val ipDecayData: StateFlow<List<Pair<Float, Float>>> = _ipDecayData.asStateFlow()

    // Measurement Progress
    private val _measurementProgressPercent = MutableStateFlow(0)
    val measurementProgressPercent: StateFlow<Int> = _measurementProgressPercent.asStateFlow()

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    // Save Button State
    private val _isSaveEnabled = MutableStateFlow(false)
    val isSaveEnabled: StateFlow<Boolean> = _isSaveEnabled.asStateFlow()

    // Status/Error Messages specific to Acq tab
    private val _acqMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val acqMessage: SharedFlow<String> = _acqMessage.asSharedFlow()

    // --- Internal State ---
    private var latestMeasurementPacket: MeasurementPacket? = null // Hold the last full data packet
    private var latestLocation: Location? = null // Hold the last fetched location for saving
    private var currentProject: Project? = null // Hold the currently active project from MainViewModel
    private var collectProgressJob : Job? = null // Job for collecting progress flow

    // Coroutine exception handler
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine Exception: ${throwable.message}", throwable)
        _acqMessage.tryEmit("Error: ${throwable.localizedMessage}")
        _isMeasuring.value = false // Stop measurement indicator on error
        _measurementProgressPercent.value = 0
        _isGeometryLocked.value = false // Unlock geometry
    }

    init {
        Log.d(TAG, "Initializing AcqViewModel")
        loadSholomGeometries()
        observeDeviceRepository() // Start observing flows from DeviceRepository
    }

    /**
     * Called by MainActivity/Fragment when the current project changes.
     */
    fun setCurrentProject(project: Project?) {
        if (currentProject != project) {
            Log.i(TAG, "Current project set to: ${project?.name} (${project?.type?.key})")
            currentProject = project
            resetAcquisitionState() // Reset state when project changes
            if (project != null) {
                initializeGeometry(project.type)
            } else {
                _geoConfig.value = GeoConfig.Uninitialized
            }
        }
    }

    private fun loadSholomGeometries() {
        try {
            val array = application.resources.getStringArray(R.array.sholom_geometries_array)
            sholomGeometries = array.mapNotNull { GeometryCalculator.parseSholomGeometryEntry(it) }
            Log.d(TAG, "Loaded ${sholomGeometries.size} Sholomberje geometries.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Sholomberje geometries: ${e.message}", e)
            _acqMessage.tryEmit("Error loading Sholom geometries.")
            sholomGeometries = emptyList()
        }
    }

    private fun initializeGeometry(projectType: ProjectType) {
        _geoConfig.value = when (projectType) {
            ProjectType.SHOLOM -> {
                if (sholomGeometries.isNotEmpty()) {
                    val firstGeo = sholomGeometries[0]
                    GeoConfig.Sholom(firstGeo.first, firstGeo.second, Constants.DEFAULT_X0, 0)
                } else {
                    GeoConfig.Uninitialized // Fallback if loading failed
                }
            }
            ProjectType.DP_DP -> GeoConfig.DipoleDipole() // Use defaults from data class
            ProjectType.P_DP -> GeoConfig.PoleDipole()
            ProjectType.P_P -> GeoConfig.PolePole()
        }
        Log.d(TAG,"Initialized geometry for $projectType: ${_geoConfig.value}")
    }

    private fun resetAcquisitionState() {
        Log.d(TAG,"Resetting Acquisition State")
        // Reset displayed values
        _measuredDV.value = null
        _measuredI.value = null
        _calculatedIP.value = null
        _calculatedRoh.value = null
        _ipDecayData.value = emptyList()
        _isSaveEnabled.value = false
        latestMeasurementPacket = null
        latestLocation = null
        // Reset progress and measurement state (should be handled by observing DeviceRepo flows too)
        _isMeasuring.value = false
        _measurementProgressPercent.value = 0
        _isGeometryLocked.value = false
        collectProgressJob?.cancel() // Stop collecting progress if project changes mid-measurement

        // Reset settings? Or keep them? Let's keep settings for now.
        // Reset geometry is handled by initializeGeometry
    }


    // --- Observing Device Repository ---
    private fun observeDeviceRepository() {
        // Observe measurement progress
        viewModelScope.launch(coroutineExceptionHandler) {
            // Use launch instead of assigning to collectProgressJob here, cancel it explicitly when needed
            deviceRepository.measurementProgress.collect { progress ->
                if (progress != null) {
                    _isMeasuring.value = true // Show progress bar etc.
                    _measurementProgressPercent.value = progress.progressPercent
                    _isGeometryLocked.value = true // Lock geometry during measurement
                } else {
                    // Null emission might signify completion or cancellation from repo side
                    if(_isMeasuring.value) { // Only reset if we were previously measuring
                        Log.d(TAG,"Measurement progress ended (null received).")
                        _isMeasuring.value = false
                        _measurementProgressPercent.value = 0
                        _isGeometryLocked.value = false // Unlock geometry
                    }
                }
            }
        }

        // Observe measurement results
        viewModelScope.launch(coroutineExceptionHandler) {
            deviceRepository.measurementResult.collect { packet ->
                Log.i(TAG,"Received Measurement Packet ID: ${packet.id}")
                latestMeasurementPacket = packet
                _measuredDV.value = packet.deltaV
                _measuredI.value = packet.measureAmper
                val avgIp = packet.calculateAverageIp()
                _calculatedIP.value = avgIp
                updateIpDecayGraph(packet.ipDecayWindowValues)

                // Calculate Roh
                val k = GeometryCalculator.calculateGeometricFactor(_geoConfig.value)
                if (!k.isNaN()) {
                    _calculatedRoh.value = GeometryCalculator.calculateRoh(k, packet.deltaV, packet.measureAmper)
                } else {
                    Log.w(TAG,"Could not calculate Roh, K factor is NaN for config: ${_geoConfig.value}")
                    _calculatedRoh.value = null
                    _acqMessage.tryEmit("Warning: Could not calculate Roh (Invalid Geometry?).")
                }

                // Enable Save button
                _isSaveEnabled.value = true
                _isMeasuring.value = false // Ensure measuring state is off
                _measurementProgressPercent.value = 0 // Reset progress bar
                _isGeometryLocked.value = false // Unlock geometry

                // Play sound
                _acqMessage.tryEmit("PLAY_SOUND_DING") // Signal UI to play sound

                // Fetch location *after* receiving data, preparing for Save action
                fetchLocationForSaving()
            }
        }

        // Observe connection state to enable/disable controls
        viewModelScope.launch {
            deviceRepository.connectionState.collect { state ->
                if (state != DeviceRepository.ConnectionState.CONNECTED) {
                    // If connection lost during measurement, handle it
                    if (_isMeasuring.value) {
                        Log.w(TAG, "Connection lost during measurement!")
                        _acqMessage.tryEmit("Connection lost during measurement!")
                        _isMeasuring.value = false
                        _measurementProgressPercent.value = 0
                        _isGeometryLocked.value = false // Unlock geometry
                        // Decide if Save should be disabled or allowed with potentially incomplete data? Disable for now.
                        _isSaveEnabled.value = false
                    }
                }
            }
        }
    }

    private fun fetchLocationForSaving() {
        if (!locationHelper.hasLocationPermission()) {
            _acqMessage.tryEmit("Location permission needed to save coordinates.")
            latestLocation = null // Ensure location is null if no permission
            return
        }
        viewModelScope.launch(coroutineExceptionHandler) {
            Log.d(TAG, "Fetching current location for saving...")
            // Request a high-accuracy location fix
            latestLocation = locationHelper.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY)
            if (latestLocation != null) {
                Log.i(TAG,"Location fetched: Lat=${latestLocation?.latitude}, Lon=${latestLocation?.longitude}, Alt=${latestLocation?.altitude}")
                _acqMessage.tryEmit("Location acquired.")
            } else {
                Log.w(TAG, "Failed to fetch location within timeout.")
                _acqMessage.tryEmit("Warning: Could not get location for saving.")
            }
        }
    }

    // --- Settings ---

    fun applySettings() {
        val current = _currentSetting.value
        val time = _timeSetting.value
        val stack = _stackSetting.value

        Log.d(TAG, "Applying settings: Current=$current mA, Time=$time s, Stack=$stack")
        viewModelScope.launch(coroutineExceptionHandler) {
            val success = deviceRepository.sendConfiguration(current, time, stack)
            if (success) {
                _acqMessage.tryEmit("Settings applied successfully.")
                // Optional: Update UI based on response if repo provided it
            } else {
                _acqMessage.tryEmit("Failed to apply settings.")
            }
        }
    }

    fun updateCurrentSetting(value: Int) {
        _currentSetting.value = value.coerceIn(Constants.DEFAULT_CURRENT_MA, Constants.MAX_CURRENT_MA)
    }

    fun updateTimeSetting(value: Float) {
        if (Constants.TIME_OPTIONS_S.contains(value)) {
            _timeSetting.value = value
        }
    }

    fun updateStackSetting(value: Int) {
        if (Constants.STACK_OPTIONS.contains(value)) {
            _stackSetting.value = value
        }
    }

    // --- Geometry Control ---

    fun handleGeometryNext() {
        if (_isGeometryLocked.value) return
        val currentConfig = _geoConfig.value
        if (currentConfig is GeoConfig.Sholom) {
            val nextIndex = (currentConfig.currentIndex + 1).coerceAtMost(sholomGeometries.size - 1)
            if (nextIndex != currentConfig.currentIndex) {
                val nextGeo = sholomGeometries[nextIndex]
                _geoConfig.value = currentConfig.copy(
                    ab_2 = nextGeo.first,
                    mn_2 = nextGeo.second,
                    currentIndex = nextIndex
                )
                clearMeasurementResults() // Clear old results when geometry changes
            }
        } else {
            _geoConfig.value = GeometryCalculator.handleNextAction(currentConfig)
            clearMeasurementResults()
        }
    }

    fun handleGeometryPrevious() {
        if (_isGeometryLocked.value) return
        val currentConfig = _geoConfig.value
        if (currentConfig is GeoConfig.Sholom) {
            val prevIndex = (currentConfig.currentIndex - 1).coerceAtLeast(0)
            if (prevIndex != currentConfig.currentIndex) {
                val prevGeo = sholomGeometries[prevIndex]
                _geoConfig.value = currentConfig.copy(
                    ab_2 = prevGeo.first,
                    mn_2 = prevGeo.second,
                    currentIndex = prevIndex
                )
                clearMeasurementResults()
            }
        } else {
            _geoConfig.value = GeometryCalculator.handlePreviousAction(currentConfig)
            clearMeasurementResults()
        }
    }

    fun handleGeometryReset() {
        if (_isGeometryLocked.value) return
        val currentConfig = _geoConfig.value
        if (currentConfig is GeoConfig.Sholom) {
            // Reset Sholom to first geometry point
            if (sholomGeometries.isNotEmpty()) {
                val firstGeo = sholomGeometries[0]
                _geoConfig.value = GeoConfig.Sholom(firstGeo.first, firstGeo.second, Constants.DEFAULT_X0, 0)
                clearMeasurementResults()
            }
        } else {
            _geoConfig.value = GeometryCalculator.handleResetAction(currentConfig)
            clearMeasurementResults()
        }
    }

    // Called when user manually edits a geometry field
    fun updateGeometryValue(updater: (GeoConfig) -> GeoConfig) {
        if (_isGeometryLocked.value) return
        _geoConfig.update { currentConfig ->
            val updatedConfig = updater(currentConfig)
            // Recalculate derived fields if applicable after manual update
            when (updatedConfig) {
                is GeoConfig.DipoleDipole -> updatedConfig.recalculateDerivedFields()
                is GeoConfig.PoleDipole -> updatedConfig.recalculateDerivedFields()
                is GeoConfig.PolePole -> updatedConfig.recalculateDerivedFields()
                else -> {} // No derived fields for Sholom or Uninitialized
            }
            clearMeasurementResults() // Clear results after manual geometry change
            updatedConfig // Return the fully updated config
        }
    }


    // --- Measurement Control ---

    fun startMeasurement() {
        if (_isMeasuring.value) {
            _acqMessage.tryEmit("Measurement already in progress.")
            return
        }
        if (currentProject == null) {
            _acqMessage.tryEmit("Please create or open a project first.")
            return
        }
        if (deviceRepository.connectionState.value != DeviceRepository.ConnectionState.CONNECTED) {
            _acqMessage.tryEmit("Device not connected.")
            return
        }

        Log.i(TAG, "Attempting to start measurement...")
        clearMeasurementResults() // Clear previous results before starting
        _isSaveEnabled.value = false // Disable save until new data arrives
        _isMeasuring.value = true // Indicate measurement starting (UI feedback)
        _measurementProgressPercent.value = 0 // Reset progress
        _isGeometryLocked.value = true // Lock geometry

        viewModelScope.launch(coroutineExceptionHandler) {
            val success = deviceRepository.startMeasurement()
            if (!success) {
                _acqMessage.tryEmit("Failed to start measurement.")
                _isMeasuring.value = false // Reset state if start failed
                _measurementProgressPercent.value = 0
                _isGeometryLocked.value = false // Unlock geometry
            } else {
                // Success: Measurement is running, progress/results handled by observers
                _acqMessage.tryEmit("Measurement started.")
            }
        }
    }

    fun saveMeasurement() {
        val packet = latestMeasurementPacket
        val project = currentProject
        val config = _geoConfig.value

        if (!_isSaveEnabled.value) {
            Log.w(TAG,"Save called but not enabled.")
            _acqMessage.tryEmit("No new data to save.")
            return
        }
        if (packet == null) {
            _acqMessage.tryEmit("Error: Measurement data missing.")
            return
        }
        if (project == null) {
            _acqMessage.tryEmit("Error: No active project.")
            return
        }
        if (config is GeoConfig.Uninitialized) {
            _acqMessage.tryEmit("Error: Geometry not initialized.")
            return
        }

        Log.i(TAG,"Preparing to save measurement ID: ${packet.id}")

        val measurementToSave = Measurement(
            // dbId is auto-generated
            projectName = project.name,
            projectType = project.type.key,
            soundingProfileId = 0, // This will be calculated by Repository
            gpsLongitude = latestLocation?.longitude,
            gpsLatitude = latestLocation?.latitude,
            gpsAltitude = if (latestLocation?.hasAltitude() == true) latestLocation?.altitude else null,
            measurementDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
            measurementTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),

            // Geometry - Fill based on current config
            geom_ab_2 = (config as? GeoConfig.Sholom)?.ab_2,
            geom_mn_2 = (config as? GeoConfig.Sholom)?.mn_2,
            geom_x0 = (config as? GeoConfig.Sholom)?.x0,
            geom_tx0 = (config as? GeoConfig.DipoleDipole)?.tx0 ?: (config as? GeoConfig.PoleDipole)?.tx0 ?: (config as? GeoConfig.PolePole)?.tx0, // Handles null for P-DP/P-P
            geom_tx1 = (config as? GeoConfig.DipoleDipole)?.tx1 ?: (config as? GeoConfig.PoleDipole)?.tx1 ?: (config as? GeoConfig.PolePole)?.tx1,
            geom_rx0 = (config as? GeoConfig.DipoleDipole)?.rx0 ?: (config as? GeoConfig.PoleDipole)?.rx0 ?: (config as? GeoConfig.PolePole)?.rx0,
            geom_rx1 = (config as? GeoConfig.DipoleDipole)?.rx1 ?: (config as? GeoConfig.PoleDipole)?.rx1 ?: (config as? GeoConfig.PolePole)?.rx1, // Handles null for P-P
            geom_distance = (config as? GeoConfig.DipoleDipole)?.distance ?: (config as? GeoConfig.PoleDipole)?.distance ?: (config as? GeoConfig.PolePole)?.distance,
            geom_n = (config as? GeoConfig.DipoleDipole)?.n ?: (config as? GeoConfig.PoleDipole)?.n ?: (config as? GeoConfig.PolePole)?.n,


            // Device & Settings from packet
            device_battery_volt = packet.voltageBat,
            device_temperature_c = packet.temperature,
            device_contact_kohm = packet.contact,
            setting_stack = packet.stack,
            setting_time_s = packet.time,

            // Measured Values from packet
            meas_sp_mv = packet.measureSP,
            meas_current_ma = packet.measureAmper,
            meas_potential_mv = packet.deltaV,

            // Calculated Values (Recalculate Roh for safety)
            calc_roh_omm = _calculatedRoh.value ?: Double.NaN, // Use displayed value or NaN
            calc_ip_mvv = _calculatedIP.value ?: Float.NaN, // Use displayed value or NaN

            // IP Decay from packet
            ip_decay_1 = packet.ipDecayWindowValues.getOrElse(0) { Float.NaN },
            ip_decay_2 = packet.ipDecayWindowValues.getOrElse(1) { Float.NaN },
            ip_decay_3 = packet.ipDecayWindowValues.getOrElse(2) { Float.NaN },
            ip_decay_4 = packet.ipDecayWindowValues.getOrElse(3) { Float.NaN },
            ip_decay_5 = packet.ipDecayWindowValues.getOrElse(4) { Float.NaN },
            ip_decay_6 = packet.ipDecayWindowValues.getOrElse(5) { Float.NaN },
            ip_decay_7 = packet.ipDecayWindowValues.getOrElse(6) { Float.NaN },
            ip_decay_8 = packet.ipDecayWindowValues.getOrElse(7) { Float.NaN },
            ip_decay_9 = packet.ipDecayWindowValues.getOrElse(8) { Float.NaN },
            ip_decay_10 = packet.ipDecayWindowValues.getOrElse(9) { Float.NaN },
            ip_decay_11 = packet.ipDecayWindowValues.getOrElse(10) { Float.NaN },
            ip_decay_12 = packet.ipDecayWindowValues.getOrElse(11) { Float.NaN },
            ip_decay_13 = packet.ipDecayWindowValues.getOrElse(12) { Float.NaN },
            ip_decay_14 = packet.ipDecayWindowValues.getOrElse(13) { Float.NaN },
            ip_decay_15 = packet.ipDecayWindowValues.getOrElse(14) { Float.NaN },
            ip_decay_16 = packet.ipDecayWindowValues.getOrElse(15) { Float.NaN },
            ip_decay_17 = packet.ipDecayWindowValues.getOrElse(16) { Float.NaN },
            ip_decay_18 = packet.ipDecayWindowValues.getOrElse(17) { Float.NaN },
            ip_decay_19 = packet.ipDecayWindowValues.getOrElse(18) { Float.NaN },
            ip_decay_20 = packet.ipDecayWindowValues.getOrElse(19) { Float.NaN }
        )

        viewModelScope.launch(coroutineExceptionHandler) {
            val rowId = measurementRepository.saveMeasurement(measurementToSave)
            if (rowId > -1L) {
                _acqMessage.tryEmit("Measurement saved successfully (ID: $rowId).")
                _isSaveEnabled.value = false // Disable save after successful save
                latestMeasurementPacket = null // Clear packet after saving
                // Advance geometry to the next point automatically
                handleGeometryNext()
            } else {
                _acqMessage.tryEmit("Error saving measurement to database.")
            }
        }
    }

    // --- UI Helpers ---

    private fun clearMeasurementResults() {
        _measuredDV.value = null
        _measuredI.value = null
        _calculatedIP.value = null
        _calculatedRoh.value = null
        _ipDecayData.value = emptyList()
        _isSaveEnabled.value = false
        latestMeasurementPacket = null
        // Should we clear progress too? No, progress is tied to active measurement.
    }

    private fun updateIpDecayGraph(ipValues: List<Float>) {
        val graphData = ipValues.mapIndexed { index, value ->
            // Time axis starts from 0ms, increments by 80ms for each window (Ip1 to Ip20)
            val timeMs = index * 80f // Time for Ip(index+1)
            Pair(timeMs, value)
        }
        _ipDecayData.value = graphData
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "AcqViewModel onCleared")
        collectProgressJob?.cancel() // Cancel progress collection if still active
    }
}


// --- ViewModel Factory ---
class AcqViewModelFactory(
    private val application: Application,
    private val deviceRepository: DeviceRepository,
    private val measurementRepository: MeasurementRepository,
    private val locationHelper: LocationHelper
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AcqViewModel::class.java)) {
            return AcqViewModel(application, deviceRepository, measurementRepository, locationHelper) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}