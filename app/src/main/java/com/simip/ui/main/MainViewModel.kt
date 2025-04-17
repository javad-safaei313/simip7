package com.simip.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.simip.SimipApp // Access to application scope if needed
import com.simip.data.model.Project
import com.simip.data.model.ProjectType
import com.simip.data.repository.DeviceRepository
import com.simip.data.repository.MeasurementRepository
import com.simip.util.Constants
import com.simip.util.LocaleHelper
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel // اطمینان از وجود این
import androidx.lifecycle.viewModelScope // <-- import لازم
import kotlinx.coroutines.Dispatchers // <--- اضافه کردن این import
import kotlinx.coroutines.withContext // <--- اضافه کردن این import




/**
 * ViewModel for MainActivity.
 * Handles overall application state like device connection, current project management,
 * language selection, and menu actions. It interacts with both DeviceRepository and
 * MeasurementRepository.
 */
class MainViewModel(
    private val application: Application, // Use Application context carefully
    private val deviceRepository: DeviceRepository,
    private val measurementRepository: MeasurementRepository
    // private val applicationScope: CoroutineScope // Inject application scope if needed
) : AndroidViewModel(application) { // Use AndroidViewModel to access application context safely

    private val TAG = "MainViewModel"

    // --- State Exposure ---

    // Device Connection Status (from DeviceRepository)
    val connectionState: StateFlow<DeviceRepository.ConnectionState> = deviceRepository.connectionState

    // Device Real-time Status (for status bar, from DeviceRepository)
    val deviceStatus = deviceRepository.deviceStatus

    // Device Firmware Version (from DeviceRepository)
    val deviceVersion = deviceRepository.deviceVersion

    // Connected Wi-Fi SSID (from DeviceRepository)
    val connectedSsid = deviceRepository.connectedSsid

    // Current Project State
    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    // List of available projects for the "Open" dialog
    private val _projectList = MutableStateFlow<List<String>>(emptyList())
    val projectList: StateFlow<List<String>> = _projectList.asStateFlow()

    // Last opened project (for pre-filling New Project dialog)
    private val _lastProject = MutableStateFlow<Project?>(null)
    val lastProject: StateFlow<Project?> = _lastProject.asStateFlow()

    // State for showing messages/errors to the user (e.g., via Snackbar)
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1) // Buffer 1 for latest msg
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    // State for triggering dialogs
    enum class DialogType { NONE, NEW_PROJECT, OPEN_PROJECT, ABOUT }
    private val _dialogState = MutableStateFlow(DialogType.NONE)
    val dialogState: StateFlow<DialogType> = _dialogState.asStateFlow()

    // Coroutine exception handler for ViewModel scope
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine Exception: ${throwable.message}", throwable)
        _userMessage.tryEmit("An unexpected error occurred: ${throwable.localizedMessage}")
        // Optionally update connection state to error? Depends on where the error happened.
    }

    init {
        Log.d(TAG, "Initializing MainViewModel")
        loadInitialData()
        startDeviceConnection() // Attempt to connect on ViewModel initialization
    }

    private fun loadInitialData() {
        viewModelScope.launch(coroutineExceptionHandler) {
            // Load last opened project
            _lastProject.value = measurementRepository.getLastOpenedProject()
            Log.d(TAG, "Last project loaded: ${_lastProject.value}")
            // Open last project automatically? Or just load for 'New' dialog suggestion. Let's just load.
            // if (_lastProject.value != null) {
            //     selectProject(_lastProject.value!!)
            // }

            // Load project list for "Open" dialog
            _projectList.value = measurementRepository.getExistingProjectNames()
            Log.d(TAG, "Existing project names loaded: ${_projectList.value}")
        }
    }

    // --- Device Connection ---

    fun startDeviceConnection() {
        Log.i(TAG, "Attempting to start device connection process...")
        // Ensure previous connection attempts are handled if necessary
        // (DeviceRepository should handle internal state)
        viewModelScope.launch(coroutineExceptionHandler) {
            deviceRepository.connectToDevice()
            // Connection status is observed via deviceRepository.connectionState flow
        }
    }

    fun disconnectDevice() {
        Log.i(TAG, "Attempting to disconnect device...")
        viewModelScope.launch(coroutineExceptionHandler) {
            deviceRepository.disconnectFromDevice()
        }
    }

    // --- Project Management ---

    fun createNewProject(projectName: String, projectType: ProjectType) {
        if (projectName.isBlank()) {
            _userMessage.tryEmit("Project name cannot be empty.")
            return
        }
        // Simple validation for name characters (optional)
        if (!projectName.matches(Regex("[a-zA-Z0-9_\\- ]+"))) {
            _userMessage.tryEmit("Project name contains invalid characters.")
            return
        }

        val newProject = Project(projectName.trim(), projectType)
        Log.i(TAG, "Creating/Selecting new project: $newProject")
        // Check if project already exists? For now, just select it.
        selectProject(newProject)
        dismissDialog()

        // Add to project list if it's truly new (or refresh list)
        viewModelScope.launch(coroutineExceptionHandler) {
            _projectList.value = measurementRepository.getExistingProjectNames() // Refresh list
        }
    }

    fun openProject(projectName: String) {
        viewModelScope.launch(coroutineExceptionHandler) {
            val projectTypeKey = measurementRepository.getProjectTypeByName(projectName)
            if (projectTypeKey != null) {
                val projectType = ProjectType.fromKey(projectTypeKey)
                if (projectType != null) {
                    val projectToOpen = Project(projectName, projectType)
                    Log.i(TAG, "Opening project: $projectToOpen")
                    selectProject(projectToOpen)
                    dismissDialog()
                } else {
                    Log.e(TAG, "Project '$projectName' has unknown type '$projectTypeKey'.")
                    _userMessage.tryEmit("Cannot open project '$projectName': Unknown type.")
                }
            } else {
                Log.e(TAG, "Could not find project type for name '$projectName'.")
                _userMessage.tryEmit("Cannot open project '$projectName': Not found or type missing.")
            }
        }
    }

    private fun selectProject(project: Project) {
        _currentProject.value = project
        // Save as last opened project
        viewModelScope.launch(coroutineExceptionHandler) {
            measurementRepository.saveLastOpenedProject(project)
            _lastProject.value = project // Update last project state
        }
        // Notify other parts of the app (e.g., AcqViewModel, DListViewModel) if needed
        // This might involve shared flows or a shared repository state.
        Log.i(TAG, "Project selected: ${project.name} (${project.type})")
    }

    fun exportCurrentProject() {
        val project = _currentProject.value
        if (project == null) {
            _userMessage.tryEmit("No project is currently open to export.")
            return
        }
        if (connectionState.value == DeviceRepository.ConnectionState.CONNECTED) {
            // Optional: Ask user if they want to proceed while connected? Or just allow it.
        }

        Log.i(TAG, "Exporting project: ${project.name}")
        _userMessage.tryEmit("Starting export for project ${project.name}...") // Give feedback
        viewModelScope.launch(coroutineExceptionHandler + Dispatchers.IO) { // Use IO dispatcher for file operation
            val success = measurementRepository.exportProjectData(project.name, project.type.key)
            withContext(Dispatchers.Main) { // Switch back to main thread for UI update
                if (success) {
                    _userMessage.tryEmit("Project '${project.name}' exported successfully to Downloads.")
                } else {
                    _userMessage.tryEmit("Failed to export project '${project.name}'.")
                }
            }
        }
    }

    // --- Language Management ---

    fun changeLanguage(newLanguageCode: String) {
        val currentLanguage = LocaleHelper.getPersistedLanguage(getApplication())
        if (currentLanguage != newLanguageCode && LocaleHelper.SUPPORTED_LANGUAGES.contains(newLanguageCode)) {
            Log.i(TAG, "Changing language to: $newLanguageCode")
            LocaleHelper.setLocale(getApplication(), newLanguageCode)
            // Signal activity to recreate itself
            _userMessage.tryEmit("RECREATE_ACTIVITY_FOR_LOCALE") // Use a specific signal or event flow
        } else {
            Log.d(TAG, "Language not changed (already '$currentLanguage' or unsupported '$newLanguageCode')")
        }
    }

    // --- Dialog Management ---

    fun showNewProjectDialog() {
        _dialogState.value = DialogType.NEW_PROJECT
    }

    fun showOpenProjectDialog() {
        // Refresh project list before showing dialog
        viewModelScope.launch(coroutineExceptionHandler) {
            _projectList.value = measurementRepository.getExistingProjectNames()
            _dialogState.value = DialogType.OPEN_PROJECT
        }
    }

    fun showAboutDialog() {
        _dialogState.value = DialogType.ABOUT
    }

    fun dismissDialog() {
        _dialogState.value = DialogType.NONE
    }

    // --- Cleanup ---
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "MainViewModel onCleared")
        // Disconnect device when ViewModel is destroyed? Or manage lifecycle elsewhere?
        // Let's disconnect here to ensure cleanup if Activity is destroyed.
        disconnectDevice()
    }
}

// --- ViewModel Factory ---

/**
 * Factory for creating MainViewModel with dependencies.
 * Dependencies (Repositories) should ideally be provided via Dependency Injection (Hilt, Koin, etc.).
 * This is a manual factory implementation.
 */
class MainViewModelFactory(
    private val application: Application,
    private val deviceRepository: DeviceRepository,
    private val measurementRepository: MeasurementRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(application, deviceRepository, measurementRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}