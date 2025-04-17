package com.simip.data.repository

import com.simip.data.model.Measurement
import com.simip.data.model.Project // Assuming Project data class exists
import kotlinx.coroutines.flow.Flow

/**
 * Interface for accessing and managing measurement data.
 * This acts as the single source of truth for measurement-related data operations
 * for the ViewModels.
 */
interface MeasurementRepository {

    /**
     * Inserts a new measurement record into the data source.
     * Handles the calculation of soundingProfileId based on the previous record.
     *
     * @param measurement The measurement data to save (excluding dbId and possibly soundingProfileId which will be set here).
     * @return The ID (dbId) of the newly inserted record, or -1 if insertion failed.
     */
    suspend fun saveMeasurement(measurement: Measurement): Long

    /**
     * Retrieves all measurement records for a given project.
     *
     * @param projectName The name of the project.
     * @param projectType The type of the project (e.g., "sholom", "DP-DP").
     * @return A list of Measurement objects for the project, ordered appropriately (e.g., by dbId).
     */
    suspend fun getAllMeasurements(projectName: String, projectType: String): List<Measurement>

    /**
     * Retrieves measurement records for a specific sounding/profile within a project.
     *
     * @param projectName The name of the project.
     * @param projectType The type of the project.
     * @param soundingProfileId The ID of the sounding/profile to retrieve.
     * @return A list of Measurement objects for the specified sounding/profile.
     */
    suspend fun getMeasurementsBySoundingProfile(projectName: String, projectType: String, soundingProfileId: Int): List<Measurement>

    /**
     * Retrieves a list of distinct sounding/profile IDs available for a project.
     *
     * @param projectName The name of the project.
     * @param projectType The type of the project.
     * @return A list of unique integer sounding/profile IDs.
     */
    suspend fun getSoundingProfileIds(projectName: String, projectType: String): List<Int>

    /**
     * Retrieves a list of distinct project names stored in the database.
     *
     * @return A list of unique project name strings.
     */
    suspend fun getExistingProjectNames(): List<String>

    /**
     * Retrieves the project type associated with a given project name.
     * Needed when opening a project by name from the dialog.
     *
     * @param projectName The name of the project.
     * @return The project type string (e.g., "sholom") or null if not found.
     */
    suspend fun getProjectTypeByName(projectName: String): String?


    /**
     * Exports all data for the given project to an Excel (.xlsx) file.
     *
     * @param projectName The name of the project.
     * @param projectType The type of the project.
     * @return True if export was successful, false otherwise. (Consider returning File path or Uri)
     */
    suspend fun exportProjectData(projectName: String, projectType: String): Boolean

    /**
     * Retrieves the last saved project details (name and type).
     * This might involve reading from SharedPreferences or another storage mechanism.
     *
     * @return A Project object representing the last project, or null if none is saved.
     */
    suspend fun getLastOpenedProject(): Project?

    /**
     * Saves the details of the currently opened project as the "last opened" project.
     *
     * @param project The Project object to save.
     */
    suspend fun saveLastOpenedProject(project: Project)

}