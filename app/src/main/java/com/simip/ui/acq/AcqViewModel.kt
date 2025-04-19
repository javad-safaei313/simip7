package com.simip.ui.acq

import android.content.Context // برای ExcelExporter و SharedPreferences (اگر اینجا استفاده شوند)
import androidx.lifecycle.*
import com.simip.data.model.DeviceStatus
import com.simip.data.model.GeoConfig
import com.simip.data.model.Measurement
import com.simip.data.model.MeasurementPacket
import com.simip.data.model.Project
import com.simip.data.model.ProjectType
import com.simip.data.repository.DeviceRepository
import com.simip.data.repository.MeasurementRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers // <-- Import اضافه شد
import kotlinx.coroutines.withContext // <-- Import اضافه شد
import java.text.SimpleDateFormat
import java.util.*
import com.simip.util.DataParser
import com.simip.util.LocationHelper
import com.simip.util.WifiHelper

class AcqViewModel(
    private val measurementRepository: MeasurementRepository,
    private val deviceRepository: DeviceRepository,
    private val locationHelper: LocationHelper,
    private val wifiHelper: WifiHelper,
    private val applicationContext: Context // مثال
    // private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // --- StateFlow ها ---
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _deviceStatus = MutableStateFlow<DeviceStatus?>(null)
    val deviceStatus: StateFlow<DeviceStatus?> = _deviceStatus.asStateFlow()

    private val _measurementData = MutableStateFlow<Measurement?>(null) // آخرین داده خام معتبر
    val measurementData: StateFlow<Measurement?> = _measurementData.asStateFlow()

    private val _calculatedRoh = MutableStateFlow<String>("-")
    val calculatedRoh: StateFlow<String> = _calculatedRoh.asStateFlow()

    private val _calculatedIp = MutableStateFlow<String>("-")
    val calculatedIp: StateFlow<String> = _calculatedIp.asStateFlow()

    private val _spValue = MutableStateFlow<String>("-")
    val spValue: StateFlow<String> = _spValue.asStateFlow()

    private val _currentValue = MutableStateFlow<String>("-")
    val currentValue: StateFlow<String> = _currentValue.asStateFlow()

    private val _contactResistance = MutableStateFlow<String>("-")
    val contactResistance: StateFlow<String> = _contactResistance.asStateFlow()

    private val _batteryVoltage = MutableStateFlow<String>("-")
    val batteryVoltage: StateFlow<String> = _batteryVoltage.asStateFlow()

    private val _temperature = MutableStateFlow<String>("-")
    val temperature: StateFlow<String> = _temperature.asStateFlow()

    private val _gpsLocation = MutableStateFlow<String>("GPS: -")
    val gpsLocation: StateFlow<String> = _gpsLocation.asStateFlow()

    private val _saveStatus = MutableStateFlow<String?>(null)
    val saveStatus: StateFlow<String?> = _saveStatus.asStateFlow()

    private val _isMeasuring = MutableStateFlow(false)
    val isMeasuring: StateFlow<Boolean> = _isMeasuring.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject

    private var connectionJob: Job? = null
    private var measurementJob: Job? = null
    private var statusJob: Job? = null // Job برای دریافت وضعیت


    // --- اتصال و دریافت داده ---

    fun connectToDevice(ip: String, port: Int) {
        // قطع اتصال قبلی
        disconnectDeviceInternally() // از تابع داخلی استفاده می‌کنیم که Job ها رو cancel کنه

        connectionJob = viewModelScope.launch {
            _connectionStatus.value = "Connecting..."
            try {
                // --- اصلاح شد: اجرای اتصال در ترد IO ---
                withContext(Dispatchers.IO) {
                    deviceRepository.connect(ip, port) // تابع suspend ریپازیتوری
                }
                // ------------------------------------
                _connectionStatus.value = "Connected"
                // شروع دریافت داده و وضعیت فقط پس از اتصال موفق
                startReceivingData()
                startFetchingDeviceStatus()
            } catch (e: Exception) {
                _connectionStatus.value = "Connection Failed: ${e.message}"
                e.printStackTrace()
                disconnectDeviceInternally() // در صورت خطا هم Job ها رو cancel کن
            }
        }
    }

    private fun startReceivingData() {
        measurementJob?.cancel() // کنسل کردن Job قبلی اگر وجود داشت
        measurementJob = viewModelScope.launch {
            deviceRepository.getDataStream()
                .catch { e ->
                    println("Error in data stream: ${e.message}")
                    // ارسال خطا به UI از طریق StateFlow یا SharedFlow
                    _saveStatus.value = "Data stream error: ${e.message}" // مثال
                    _isMeasuring.value = false
                    disconnectDeviceInternally() // قطع اتصال در صورت خطا در stream
                }
                .collect { rawDataPacket ->
                    // _isMeasuring.value = true // شاید بهتر باشه فقط موقع parse مقدار دهی بشه
                    val measurement = parseRawData(rawDataPacket)
                    if (measurement != null) {
                        updateUiState(measurement)
                        _measurementData.value = measurement // ذخیره آخرین داده معتبر
                    } else {
                        println("Failed to parse measurement data packet.")
                    }
                    // _isMeasuring.value = false // وضعیت اندازه‌گیری رو جای دیگه مدیریت کن
                }
        }
    }

    private fun startFetchingDeviceStatus() {
        statusJob?.cancel() // کنسل کردن Job قبلی اگر وجود داشت
        statusJob = viewModelScope.launch {
            while (_connectionStatus.value == "Connected") { // فقط تا زمانی که متصل هستیم ادامه بده
                try {
                    // اجرای درخواست وضعیت در ترد IO
                    val status = withContext(Dispatchers.IO){
                        deviceRepository.getStatus() // تابع suspend ریپازیتوری
                    }
                    _deviceStatus.value = status
                    _batteryVoltage.value = status?.batteryVoltage?.toString() ?: "-"
                    _temperature.value = status?.temperature?.toString() ?: "-"
                } catch (e: Exception) {
                    println("Error fetching device status: ${e.message}")
                    // می‌توانید وضعیت را نامشخص کنید یا خطا نمایش دهید
                    _deviceStatus.value = null // مثال
                    // اگر خطا مکرر بود، شاید بهتر باشه اتصال قطع بشه
                    // disconnectDeviceInternally()
                    // break // خروج از حلقه
                }
                delay(5000) // انتظار ۵ ثانیه
            }
        }
    }

    // تابع disconnect برای استفاده از بیرون
    fun disconnectDevice() {
        disconnectDeviceInternally() // تابع داخلی رو صدا بزن
    }

    // تابع داخلی برای مدیریت قطع اتصال و کنسل کردن Job ها
    private fun disconnectDeviceInternally() {
        connectionJob?.cancel()
        measurementJob?.cancel()
        statusJob?.cancel()
        // اجرای عملیات disconnect در ترد IO
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){
                    deviceRepository.disconnect() // تابع suspend ریپازیتوری
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _connectionStatus.value = "Disconnected"
                _isMeasuring.value = false
                _deviceStatus.value = null
                clearLastMeasurementForUI() // ریست کردن UI
            }
        }
    }

    // --- ذخیره سازی ---
    fun saveCurrentMeasurement() {
        val measurementToSave = _measurementData.value
        val project = _currentProject.value

        if (measurementToSave != null && project != null) {
            val finalMeasurement = measurementToSave.copy(
                projectName = project.name,
                projectType = project.type // نوع ProjectType از آبجکت Project
            )
            viewModelScope.launch {
                try {
                    val rowId = measurementRepository.insertMeasurement(finalMeasurement) // تابع suspend ریپازیتوری
                    _saveStatus.value = "Measurement Saved (ID: $rowId)"
                    delay(3000)
                    _saveStatus.value = null
                } catch (e: Exception) {
                    _saveStatus.value = "Error saving measurement: ${e.message}"
                    e.printStackTrace()
                }
            }
        } else if (project == null) {
            _saveStatus.value = "Error: No project selected."
        } else {
            _saveStatus.value = "Error: No valid measurement data to save."
        }
    }

    // --- توابع کمکی ---
    private fun parseRawData(packet: ByteArray): Measurement? {
        // TODO: پیاده‌سازی کامل منطق parse بر اساس پروتکل دستگاه
        try {
            val parsedData = DataParser.parseMeasurementPacket(packet) // فرض می‌کنیم این تابع MeasurementPacket برمی‌گردونه
            val location = locationHelper.getCurrentLocation() // فرض می‌کنیم این تابع Location? برمی‌گردونه

            return Measurement(
                // dbId توسط Room تولید می‌شود
                projectName = null, // این بعدا توسط saveCurrentMeasurement پر می‌شود
                projectType = null, // این بعدا توسط saveCurrentMeasurement پر می‌شود
                soundingProfileId = parsedData.soundingProfileId,
                gpsLongitude = location?.longitude?.toString(),
                gpsLatitude = location?.latitude?.toString(),
                gpsAltitude = location?.altitude?.toString(),
                measurementDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                measurementTime = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                // ... بقیه فیلدها از parsedData ...
                // مثال:
                meas_potential_mv = parsedData.potentialMv.toString(),
                meas_current_ma = parsedData.currentMa.toString(),
                calc_roh_omm = calculateRoh(parsedData.potentialMv, parsedData.currentMa),
                // ... IP Decay ...
                ip_decay_1 = parsedData.ipDecay.getOrNull(0)?.toString(),
                ip_decay_2 = parsedData.ipDecay.getOrNull(1)?.toString(),
                // ...
                ip_decay_20 = parsedData.ipDecay.getOrNull(19)?.toString()
            )
        } catch (e: Exception) {
            println("Error parsing data packet: ${e.message}")
            return null
        }
    }

    private fun updateUiState(measurement: Measurement) {
        _calculatedRoh.value = measurement.calc_roh_omm ?: "-"
        _calculatedIp.value = measurement.calc_ip_mvv ?: "-"
        _spValue.value = measurement.meas_sp_mv ?: "-"
        _currentValue.value = measurement.meas_current_ma ?: "-"
        _contactResistance.value = measurement.device_contact_kohm ?: "-"
        _gpsLocation.value = "GPS: ${measurement.gpsLatitude ?: "-"}, ${measurement.gpsLongitude ?: "-"}"
        // وضعیت باتری و دما از _deviceStatus خوانده می‌شود
    }

    private fun clearLastMeasurementForUI() {
        _measurementData.value = null
        _calculatedRoh.value = "-"
        _calculatedIp.value = "-"
        _spValue.value = "-"
        _currentValue.value = "-"
        _contactResistance.value = "-"
        // _batteryVoltage.value = "-" // این از statusJob آپدیت می‌شود
        // _temperature.value = "-" // این از statusJob آپدیت می‌شود
        _gpsLocation.value = "GPS: -"
    }

    // برای تنظیم پروژه فعلی از بیرون (مثلا MainActivity یا Fragment)
    fun updateCurrentProject(project: Project?) {
        _currentProject.value = project
    }

    private fun calculateRoh(potential: Double?, current: Double?): String {
        // TODO: پیاده‌سازی محاسبه دقیق Roh با فاکتور هندسی (K)
        return if (potential != null && current != null && current != 0.0) {
            val kFactor = 1.0 // **مقدار K باید از GeoConfig خوانده شود**
            val roh = kFactor * (potential / current)
            String.format(Locale.US, "%.2f", roh)
        } else { "-" }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectDeviceInternally() // اطمینان از قطع اتصال
        println("AcqViewModel cleared")
    }
}