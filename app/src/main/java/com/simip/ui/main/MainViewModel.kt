package com.simip.ui.main

import androidx.lifecycle.*
import com.simip.data.model.Project
import com.simip.data.model.ProjectType
import com.simip.data.repository.MeasurementRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(
    private val measurementRepository: MeasurementRepository,
    private val savedStateHandle: SavedStateHandle // یا هر روش دیگری برای ذخیره وضعیت
) : ViewModel() {

    // --- مدیریت لیست پروژه‌ها ---
    private val _projectList = MutableStateFlow<List<Project>>(emptyList())
    val projectList: StateFlow<List<Project>> = _projectList.asStateFlow()

    // --- مدیریت وضعیت لودینگ ---
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // --- مدیریت پیام‌های خطا ---
    private val _errorMessages = MutableSharedFlow<String>() // SharedFlow برای event ها
    val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    // --- پروژه انتخاب شده فعلی ---
    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    init {
        loadProjects() // لود اولیه پروژه‌ها
        loadLastOpenedProject() // لود آخرین پروژه باز شده
    }

    fun loadProjects() {
        viewModelScope.launch {
            _isLoading.value = true
            measurementRepository.getDistinctProjectNamesFlow()
                .flatMapConcat { names -> // برای هر نام، نوع پروژه رو جداگانه می‌گیریم
                    if (names.isEmpty()) {
                        flowOf(emptyList()) // اگه نامی نیست، لیست خالی برگردون
                    } else {
                        // برای هر نام، Flow مربوط به نوعش رو می‌گیریم
                        val projectFlows = names.map { name ->
                            measurementRepository.getProjectTypeByNameFlow(name)
                                .map { type -> Project(name, type ?: ProjectType.UNKNOWN) } // اگه نوع null بود، Unknown بذار
                        }
                        // تمام Flow های Project رو با هم ترکیب می‌کنیم
                        combine(projectFlows) { projectsArray -> projectsArray.toList() }
                    }
                }
                .catch { e ->
                    _errorMessages.emit("Error loading projects: ${e.message}")
                    emit(emptyList()) // در صورت خطا لیست خالی
                }
                .onCompletion { _isLoading.value = false } // در انتها لودینگ رو false کن
                .collect { projects ->
                    _projectList.value = projects
                }
        }
    }

    fun setCurrentProject(project: Project?) {
        _currentProject.value = project
        // ذخیره پروژه فعلی به عنوان آخرین پروژه باز شده
        if (project != null) {
            viewModelScope.launch {
                try {
                    measurementRepository.saveLastOpenedProject(project)
                } catch (e: Exception) {
                    _errorMessages.emit("Error saving last project: ${e.message}")
                }
            }
        }
    }

    private fun loadLastOpenedProject() {
        viewModelScope.launch {
            try {
                val lastProject = measurementRepository.getLastOpenedProject()
                if (lastProject != null && _projectList.value.any { it.name == lastProject.name }) {
                    // فقط اگه آخرین پروژه هنوز در لیست وجود داره، انتخابش کن
                    _currentProject.value = lastProject
                } else if (_projectList.value.isNotEmpty()) {
                    // در غیر این صورت، اولین پروژه لیست رو انتخاب کن (اگه لیستی هست)
                    _currentProject.value = _projectList.value.first()
                    if (lastProject != null) { // اگه پروژه آخری وجود داشت ولی در لیست نبود، ذخیره کن
                        measurementRepository.saveLastOpenedProject(_projectList.value.first())
                    }
                } else {
                    _currentProject.value = null // اگه هیچ پروژه‌ای نیست
                }
            } catch (e: Exception) {
                _errorMessages.emit("Error loading last project: ${e.message}")
                _currentProject.value = _projectList.value.firstOrNull() // fallback
            }
        }
    }

    // تابع برای ساخت پروژه جدید (مثال)
    fun createNewProject(projectName: String, projectType: ProjectType) {
        viewModelScope.launch {
            // اینجا می‌تونید چک کنید که آیا پروژه با این نام وجود داره یا نه
            // فعلا فرض می‌کنیم وجود نداره و مستقیم می‌سازیم (البته پروژه با insert ساخته می‌شه)
            val newProject = Project(projectName, projectType)
            // شاید لازم باشه پروژه فعلی رو آپدیت کنی و لیست رو رفرش کنی
            setCurrentProject(newProject) // پروژه جدید رو انتخاب کن
            loadProjects() // لیست پروژه‌ها رو دوباره لود کن
        }
    }

    // تابع برای اکسپورت پروژه فعلی (مثال)
    fun exportCurrentProject() {
        val project = _currentProject.value
        if (project == null) {
            viewModelScope.launch { _errorMessages.emit("No project selected for export.") }
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val success = measurementRepository.exportProjectData(project.name, project.type)
                if (!success) {
                    _errorMessages.emit("Failed to export project data.")
                } else {
                    _errorMessages.emit("Project exported successfully.") // یا یه پیام بهتر
                }
            } catch (e: Exception) {
                _errorMessages.emit("Error exporting project: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}