package com.simip.ui.dlist

import androidx.lifecycle.*
import com.simip.data.model.Measurement
import com.simip.data.model.Project
import com.simip.data.repository.MeasurementRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class) // برای استفاده از flatMapLatest
class DListViewModel(
    private val measurementRepository: MeasurementRepository,
    private val savedStateHandle: SavedStateHandle // برای ذخیره وضعیت فیلتر
) : ViewModel() {

    // StateFlow برای نگهداری پروژه فعلی
    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    // StateFlow برای نگهداری ID پروفایل انتخاب شده (null یعنی "All")
    private val _selectedProfileId = MutableStateFlow<Int?>(null)
    val selectedProfileId: StateFlow<Int?> = _selectedProfileId.asStateFlow()

    // StateFlow برای لیست ID های پروفایل جهت نمایش در Spinner
    private val _soundingProfileIds = MutableStateFlow<List<Pair<Int?, String>>>(listOf(null to "All"))
    val soundingProfileIds: StateFlow<List<Pair<Int?, String>>> = _soundingProfileIds.asStateFlow()

    // StateFlow نهایی برای لیست Measurement ها که UI باید Observe کند
    // از flatMapLatest استفاده می‌کنیم تا هر وقت پروژه یا فیلتر ID عوض شد، کوئری جدید اجرا شود
    val measurements: StateFlow<List<Measurement>> = combine(
        _currentProject,
        _selectedProfileId
    ) { project, profileId ->
        Pair(project, profileId) // یک جفت از آخرین مقادیر پروژه و فیلتر می‌سازیم
    }.flatMapLatest { (project, profileId) -> // هر وقت این جفت تغییر کرد، Flow جدید را اجرا کن
        if (project == null) {
            flowOf(emptyList()) // اگر پروژه‌ای نیست، لیست خالی برگردان
        } else {
            // بر اساس profileId، یا همه را بگیر یا فیلتر کن
            if (profileId == null) {
                measurementRepository.getAllMeasurementsFlow(project.name)
            } else {
                measurementRepository.getMeasurementsForProjectAndSoundingFlow(project.name, profileId)
            }
        }
        // shareIn برای تبدیل Flow به StateFlow و اشتراک‌گذاری نتیجه بین Observers
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    // تابعی برای تنظیم پروژه فعلی از بیرون (مثلا از Fragment)
    fun setCurrentProject(project: Project?) {
        _currentProject.value = project
        // وقتی پروژه عوض شد، ID های پروفایل رو دوباره لود کن
        if (project != null) {
            loadSoundingProfileIds(project.name)
            // فیلتر رو ریست کن
            _selectedProfileId.value = null
        } else {
            // اگه پروژه null شد، لیست پروفایل‌ها رو هم خالی کن
            _soundingProfileIds.value = listOf(null to "All")
        }
    }

    // تابعی برای تنظیم فیلتر ID پروفایل از بیرون
    fun filterBySoundingProfile(profileId: Int?) {
        _selectedProfileId.value = profileId
    }

    // لود کردن ID های پروفایل برای Spinner
    private fun loadSoundingProfileIds(projectName: String) {
        viewModelScope.launch {
            measurementRepository.getDistinctSoundingProfileIdsFlow(projectName)
                .map { distinctIds -> // تبدیل لیست Int به لیست Pair
                    val spinnerList = mutableListOf<Pair<Int?, String>>(null to "All") // اضافه کردن گزینه "All"
                    spinnerList.addAll(distinctIds.map { id -> id to id.toString() }) // تبدیل Int ها به Pair
                    spinnerList
                }
                .catch { e ->
                    // مدیریت خطا در صورت لزوم
                    println("Error loading sounding profile IDs: ${e.message}")
                    val defaultList = mutableListOf<Pair<Int?, String>>(null as Int? to "All")
                    emit(defaultList)
                }.collect { spinnerList ->
                    _soundingProfileIds.value = spinnerList // آپدیت StateFlow
                }
        }
    }

    // (اختیاری) تابع برای حذف measurement (مثال)
    fun deleteMeasurement(measurement: Measurement) {
        viewModelScope.launch {
            try {
                measurementRepository.deleteMeasurement(measurement)
                // نیازی به آپدیت دستی لیست نیست، چون measurements یک StateFlow هست
                // و وقتی داده در دیتابیس تغییر کنه، Flow اصلی دوباره منتشر می‌شه.
            } catch (e: Exception) {
                // مدیریت خطا
            }
        }
    }
}