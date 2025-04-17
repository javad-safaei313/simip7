package com.simip.ui.dlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.simip.data.model.Measurement
import com.simip.data.model.Project
import com.simip.data.model.ProjectType
import com.simip.data.repository.MeasurementRepository
import com.simip.util.GeometryCalculator // Needed for calculating plot X values for non-sholom
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow

/**
 * ViewModel for the Data List (DList) Fragment.
 * Handles fetching measurement data for the current project, filtering by sounding/profile ID,
 * and preparing data for the RecyclerView and the Roh/IP graph.
 */
class DListViewModel(
    private val application: Application,
    private val measurementRepository: MeasurementRepository
) : AndroidViewModel(application) {

    private val TAG = "DListViewModel"
    companion object { // اگر خطا داد اضافه شود

        private const val ALL_SOUNDINGS_ID = -1 // Special ID to represent "All" soundings
        }
    // --- State Exposure for UI ---

    // List of available Sounding/Profile IDs for the Spinner (including "All")
    private val _soundingProfileIds = MutableStateFlow<List<Pair<Int, String>>>(emptyList()) // Pair(ID, Display Name)
    val soundingProfileIds: StateFlow<List<Pair<Int, String>>> = _soundingProfileIds.asStateFlow()

    // The currently selected Sounding/Profile ID from the Spinner
    private val _selectedSoundingProfileId = MutableStateFlow(ALL_SOUNDINGS_ID)
    val selectedSoundingProfileId: StateFlow<Int> = _selectedSoundingProfileId.asStateFlow()

    // The filtered list of Measurement data to be displayed in the RecyclerView
    private val _measurementList = MutableStateFlow<List<Measurement>>(emptyList())
    val measurementList: StateFlow<List<Measurement>> = _measurementList.asStateFlow()

    // Data prepared for the Roh/IP graph (List of points: Triple(xValue, rohValue, ipValue))
    // Using nullable Floats for Roh/IP to handle potential NaN or missing values.
    private val _graphData = MutableStateFlow<List<Triple<Float, Float?, Float?>>>(emptyList())
    val graphData: StateFlow<List<Triple<Float, Float?, Float?>>> = _graphData.asStateFlow()

    // Indicates if data is currently being loaded
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // --- Internal State ---
    private var currentProject: Project? = null
    private var allProjectMeasurements: List<Measurement> = emptyList() // Cache all data for the project

    // Coroutine exception handler
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine Exception: ${throwable.message}", throwable)
        _isLoading.value = false
        // Maybe show an error message via a shared flow if needed
    }

    init {
        Log.d(TAG, "Initializing DListViewModel")
        // Observe changes in the selected sounding ID to refilter data and update graph
        viewModelScope.launch(coroutineExceptionHandler) {
            _selectedSoundingProfileId.collectLatest { selectedId ->
                filterAndPrepareData(selectedId)
            }
        }
    }

    /**
     * Called by MainActivity/Fragment when the current project changes or when the DList tab is selected.
     * Fetches all data for the project and updates the UI state.
     */
    fun loadDataForProject(project: Project?) {
        if (project == null) {
            Log.w(TAG, "loadDataForProject called with null project.")
            resetState()
            return
        }
        // Avoid reloading if project hasn't changed and data is already loaded
        if (currentProject == project && allProjectMeasurements.isNotEmpty()) {
            Log.d(TAG, "Data for project ${project.name} already loaded.")
            // Ensure initial filtering happens if selection changed while tab was hidden
            filterAndPrepareData(_selectedSoundingProfileId.value)
            return
        }

        Log.i(TAG, "Loading data for project: ${project.name} (${project.type.key})")
        currentProject = project
        _isLoading.value = true
        viewModelScope.launch(coroutineExceptionHandler) {
            allProjectMeasurements = measurementRepository.getAllMeasurements(project.name, project.type.key)
            val distinctIds = measurementRepository.getSoundingProfileIds(project.name, project.type.key)

            // Prepare list for Spinner: Add "All" option first
            val spinnerList = mutableListOf<Pair<Int, String>>()
            spinnerList.add(Pair(ALL_SOUNDINGS_ID, "All")) // Use "All" or localized string
            spinnerList.addAll(distinctIds.map { Pair(it, it.toString()) }) // Add actual IDs
            _soundingProfileIds.value = spinnerList

            // Reset selection to "All" when loading new project data
            // Or restore previous selection if desired? Let's reset to "All".
            _selectedSoundingProfileId.value = ALL_SOUNDINGS_ID // This will trigger filtering via collectLatest

            _isLoading.value = false
            Log.d(TAG, "Loaded ${allProjectMeasurements.size} measurements and ${distinctIds.size} profiles for project ${project.name}.")
        }
    }

    /**
     * Called when the user selects a different Sounding/Profile ID from the Spinner.
     */
    fun selectSoundingProfile(selectedId: Int) {
        if (_selectedSoundingProfileId.value != selectedId) {
            Log.d(TAG, "Sounding profile selected: $selectedId")
            _selectedSoundingProfileId.value = selectedId
            // Data filtering and graph update will be triggered by the collector
        }
    }

    /**
     * Filters the cached `allProjectMeasurements` based on the selected ID
     * and prepares data for the RecyclerView and the Graph.
     */
    private fun filterAndPrepareData(selectedId: Int) {
        if (currentProject == null) return // No project selected

        _isLoading.value = true // Indicate processing
        Log.d(TAG,"Filtering data for sounding ID: $selectedId")

        val filteredList = if (selectedId == ALL_SOUNDINGS_ID) {
            allProjectMeasurements
        } else {
            allProjectMeasurements.filter { it.soundingProfileId == selectedId }
        }
        _measurementList.value = filteredList

        // Prepare data for the graph based on the filtered list
        prepareGraphData(filteredList, currentProject!!.type)
        _isLoading.value = false
    }

    /**
     * Prepares the data points for the Roh/IP graph based on the filtered measurements.
     * Handles calculation of X-axis values and averaging for duplicate X values.
     */
    private fun prepareGraphData(data: List<Measurement>, projectType: ProjectType) {
        if (data.isEmpty()) {
            _graphData.value = emptyList()
            return
        }

        val points = mutableMapOf<Float, MutableList<Pair<Float?, Float?>>>() // Map<xValue, List<Pair(Roh, IP)>>

        data.forEach { m ->
            // Determine X-axis value based on project type
            val xValue: Float? = when (projectType) {
                ProjectType.SHOLOM -> m.geom_ab_2?.toFloat()
                ProjectType.DP_DP, ProjectType.P_DP, ProjectType.P_P -> {
                    if (m.geom_n != null && m.geom_distance != null) {
                        (m.geom_n * m.geom_distance).toFloat()
                    } else null
                }
                // else -> null // Should not happen with known types
            }

            if (xValue != null && xValue > 0) { // Only plot valid positive X values
                val rohValue = m.calc_roh_omm.takeIf { !it.isNaN() }?.toFloat()
                val ipValue = m.calc_ip_mvv.takeIf { !it.isNaN() }

                val valuesList = points.getOrPut(xValue) { mutableListOf() }
                valuesList.add(Pair(rohValue, ipValue))
            }
        }

        // Average Roh and IP for points with the same X value and sort by X
        val averagedGraphData = points.map { (x, valueList) ->
            val validRohs = valueList.mapNotNull { it.first }
            val validIps = valueList.mapNotNull { it.second }
            val avgRoh = if (validRohs.isNotEmpty()) validRohs.average().toFloat() else null
            val avgIp = if (validIps.isNotEmpty()) validIps.average().toFloat() else null
            Triple(x, avgRoh, avgIp)
        }.sortedBy { it.first } // Sort points by X value

        Log.d(TAG,"Prepared ${averagedGraphData.size} points for the graph.")
        _graphData.value = averagedGraphData
    }


    /**
     * Resets the state when no project is selected or an error occurs.
     */
    private fun resetState() {
        currentProject = null
        allProjectMeasurements = emptyList()
        _soundingProfileIds.value = emptyList()
        _selectedSoundingProfileId.value = ALL_SOUNDINGS_ID
        _measurementList.value = emptyList()
        _graphData.value = emptyList()
        _isLoading.value = false
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "DListViewModel onCleared")
    }
}

// --- ViewModel Factory ---
class DListViewModelFactory(
    private val application: Application,
    private val measurementRepository: MeasurementRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DListViewModel::class.java)) {
            return DListViewModel(application, measurementRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}