package com.simip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.simip.data.model.Measurement

@Dao
interface MeasurementDao {

    /**
     * Inserts a new measurement record into the database.
     * If a conflict occurs (which shouldn't happen with auto-generated keys), it replaces the old record.
     * @param measurement The Measurement object to insert.
     * @return The row ID of the newly inserted measurement.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeasurement(measurement: Measurement): Long

    /**
     * Retrieves all measurement records for a specific project, ordered by insertion order (dbId).
     * @param projectName The name of the project.
     * @param projectType The type of the project.
     * @return A list of Measurement objects for the project.
     */
  //  @Query("SELECT * FROM Measurements WHERE projectName = :projectName AND projectType = :projectType ORDER BY dbId ASC")
//suspend fun getAllMeasurementsForProject(projectName: String, projectType: String): List<Measurement>

    @Query("SELECT * FROM measurements WHERE projectName = :projectName ORDER BY dbId ASC")
    // موقتاً نوع بازگشتی به List تغییر کرد و suspend اضافه شد
    suspend fun getAllMeasurementsForProject(projectName: String): List<Measurement>


    /**
     * Retrieves measurement records for a specific project and sounding/profile ID, ordered by insertion order.
     * @param projectName The name of the project.
     * @param projectType The type of the project.
     * @param soundingProfileId The specific sounding/profile ID to filter by.
     * @return A list of Measurement objects for the specified sounding/profile within the project.
     */
    @Query("SELECT * FROM Measurements WHERE projectName = :projectName AND projectType = :projectType AND soundingProfileId = :soundingProfileId ORDER BY dbId ASC")
    suspend fun getMeasurementsForProjectAndSounding(projectName: String, projectType: String, soundingProfileId: Int): List<Measurement>

    /**
     * Retrieves the most recently inserted measurement record for a specific project.
     * This is used to determine the soundingProfileId for the next measurement.
     * @param projectName The name of the project.
     * @param projectType The type of the project.
     * @return The last Measurement object inserted for the project, or null if the project has no measurements yet.
     */
    @Query("SELECT * FROM Measurements WHERE projectName = :projectName AND projectType = :projectType ORDER BY dbId DESC LIMIT 1")
    suspend fun getLastMeasurementForProject(projectName: String, projectType: String): Measurement?

    /**
     * Retrieves a distinct list of sounding/profile IDs used within a specific project.
     * Used to populate the Spinner in the DList tab.
     * @param projectName The name of the project.
     * @param projectType The type of the project.
     * @return A list of unique integer sounding/profile IDs, ordered numerically.
     */
    @Query("SELECT DISTINCT soundingProfileId FROM Measurements WHERE projectName = :projectName AND projectType = :projectType ORDER BY soundingProfileId ASC")
    suspend fun getDistinctSoundingProfileIds(projectName: String, projectType: String): List<Int>

    /**
     * Retrieves a distinct list of all project names present in the database.
     * Used to populate the list in the "Open Project" dialog. Returns pairs of name and type.
     * Consider returning a simple data class or Pair<String, String> if needed later.
     * For simplicity now, just returning distinct names. A separate query might be needed for types.
     * Let's refine this: Return distinct project names only. We might need another query or logic
     * to fetch the type associated with the selected name if multiple projects share a name
     * but have different types (though the spec implies a project is defined by name AND type).
     * Assuming project name uniquely identifies a project for the Open dialog based on typical usage.
     * @return A list of unique project names, ordered alphabetically.
     */
    @Query("SELECT DISTINCT projectName FROM Measurements ORDER BY projectName ASC")
    suspend fun getDistinctProjectNames(): List<String>


    /**
     * Retrieves the project type associated with a given project name.
     * Assumes a project name corresponds to only one type. If not, this needs adjustment.
     * @param projectName The name of the project.
     * @return The project type string, or null if the project name doesn't exist.
     */
    @Query("SELECT projectType FROM Measurements WHERE projectName = :projectName LIMIT 1")
    suspend fun getProjectTypeByName(projectName: String): String?


    // Optional: Delete operations (uncomment if needed)
    /*
    @Query("DELETE FROM Measurements WHERE projectName = :projectName AND projectType = :projectType")
    suspend fun deleteMeasurementsForProject(projectName: String, projectType: String)

    @Query("DELETE FROM Measurements")
    suspend fun deleteAllMeasurements()
    */

}