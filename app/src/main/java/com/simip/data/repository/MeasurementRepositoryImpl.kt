package com.simip.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.simip.data.db.MeasurementDao
import com.simip.data.model.Measurement
import com.simip.data.model.Project
import com.simip.data.model.ProjectType // Import the enum
import com.simip.util.Constants
import com.simip.util.ExcelExporter // Assume ExcelExporter utility exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Implementation of MeasurementRepository.
 * Uses MeasurementDao for database operations and SharedPreferences for last project state.
 * Also coordinates data export using ExcelExporter.
 */
class MeasurementRepositoryImpl(
    private val measurementDao: MeasurementDao,
    private val context: Context // Needed for SharedPreferences and potentially File operations
) : MeasurementRepository {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("SimipAppPrefs", Context.MODE_PRIVATE)

    private val excelExporter = ExcelExporter(context) // Instantiate the exporter utility

    private val TAG = "MeasurementRepoImpl"

    /**
     * Saves a measurement after calculating its soundingProfileId.
     */
    override suspend fun saveMeasurement(measurement: Measurement): Long = withContext(Dispatchers.IO) {
        try {
            // 1. Get the last measurement for this project to determine the next soundingProfileId
            val lastMeasurement = measurementDao.getLastMeasurementForProject(measurement.projectName, measurement.projectType)

            val nextSoundingProfileId: Int
            if (lastMeasurement == null) {
                // First record for this project
                nextSoundingProfileId = 1
            } else {
                // Calculate based on the rules in the spec (Item 2, clarifications)
                val currentProjectType = ProjectType.fromKey(measurement.projectType)
                nextSoundingProfileId = when (currentProjectType) {
                    ProjectType.SHOLOM -> {
                        // If new AB/2 < previous AB/2 -> increment ID
                        if (measurement.geom_ab_2 != null && lastMeasurement.geom_ab_2 != null &&
                            measurement.geom_ab_2 < lastMeasurement.geom_ab_2) {
                            lastMeasurement.soundingProfileId + 1
                        } else {
                            lastMeasurement.soundingProfileId
                        }
                    }
                    ProjectType.DP_DP, ProjectType.P_DP, ProjectType.P_P -> {
                        // If new n == 1 -> increment ID
                        if (measurement.geom_n == 1) {
                            lastMeasurement.soundingProfileId + 1
                        } else {
                            lastMeasurement.soundingProfileId
                        }
                    }
                    null -> { // Should not happen if projectType is validated
                        Log.e(TAG, "Unknown project type '${measurement.projectType}' during sounding ID calculation.")
                        lastMeasurement.soundingProfileId // Keep previous ID as fallback
                    }
                }
            }

            // 2. Create a new Measurement object with the calculated ID
            val measurementToSave = measurement.copy(soundingProfileId = nextSoundingProfileId)

            // 3. Insert into database
            Log.d(TAG, "Saving measurement for project '${measurement.projectName}', sounding ID $nextSoundingProfileId")
            measurementDao.insertMeasurement(measurementToSave)

        } catch (e: Exception) {
            Log.e(TAG, "Error saving measurement: ${e.message}", e)
            -1L // Return -1 indicating failure
        }
    }

    override suspend fun getAllMeasurements(projectName: String, projectType: String): List<Measurement> = withContext(Dispatchers.IO) {
        try {
            measurementDao.getAllMeasurementsForProject(projectName, projectType)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all measurements for project '$projectName': ${e.message}", e)
            emptyList() // Return empty list on error
        }
    }

    override suspend fun getMeasurementsBySoundingProfile(projectName: String, projectType: String, soundingProfileId: Int): List<Measurement> = withContext(Dispatchers.IO) {
        try {
            measurementDao.getMeasurementsForProjectAndSounding(projectName, projectType, soundingProfileId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting measurements for project '$projectName', sounding $soundingProfileId: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getSoundingProfileIds(projectName: String, projectType: String): List<Int> = withContext(Dispatchers.IO) {
        try {
            measurementDao.getDistinctSoundingProfileIds(projectName, projectType)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sounding profile IDs for project '$projectName': ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getExistingProjectNames(): List<String> = withContext(Dispatchers.IO) {
        try {
            measurementDao.getDistinctProjectNames()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting existing project names: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getProjectTypeByName(projectName: String): String? = withContext(Dispatchers.IO) {
        try {
            measurementDao.getProjectTypeByName(projectName)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting project type for name '$projectName': ${e.message}", e)
            null
        }
    }

    override suspend fun exportProjectData(projectName: String, projectType: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch all data for the project
            val measurements = measurementDao.getAllMeasurementsForProject(projectName, projectType)
            if (measurements.isEmpty()) {
                Log.w(TAG, "No data found to export for project '$projectName'.")
                return@withContext false // Or maybe true, as "exporting nothing" succeeded? Let's say false.
            }

            // 2. Use ExcelExporter utility to create the file
            Log.d(TAG, "Starting export for project '$projectName' (${measurements.size} records)...")
            val success = excelExporter.exportToExcel(projectName, projectType, measurements)
            if (success) {
                Log.d(TAG, "Export successful for project '$projectName'.")
            } else {
                Log.e(TAG, "Export failed for project '$projectName'.")
            }
            success
        } catch (e: IOException) {
            Log.e(TAG, "IO Error exporting project data '$projectName': ${e.message}", e)
            false
        }
        catch (e: Exception) {
            Log.e(TAG, "Error exporting project data '$projectName': ${e.message}", e)
            false
        }
    }

    override suspend fun getLastOpenedProject(): Project? = withContext(Dispatchers.IO) {
        val name = sharedPreferences.getString(Constants.PREF_KEY_LAST_PROJECT_NAME, null)
        val typeKey = sharedPreferences.getString(Constants.PREF_KEY_LAST_PROJECT_TYPE, null)
        val type = typeKey?.let { ProjectType.fromKey(it) }

        if (name != null && type != null) {
            Project(name, type)
        } else {
            null
        }
    }

    override suspend fun saveLastOpenedProject(project: Project) = withContext(Dispatchers.IO) {
        sharedPreferences.edit()
            .putString(Constants.PREF_KEY_LAST_PROJECT_NAME, project.name)
            .putString(Constants.PREF_KEY_LAST_PROJECT_TYPE, project.type.key)
            .apply() // Use apply() for asynchronous saving
    }
}