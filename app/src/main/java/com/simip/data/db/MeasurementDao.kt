package com.simip.data.db

import androidx.room.*
import com.simip.data.model.Measurement
import com.simip.data.model.ProjectType // <-- Import ProjectType
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMeasurement(measurement: Measurement): Long

    @Query("SELECT * FROM measurements WHERE projectName = :projectName")
    fun getAllMeasurementsForProjectFlow(projectName: String): Flow<List<Measurement>>

    @Query("SELECT * FROM measurements WHERE projectName = :projectName AND soundingProfileId = :soundingProfileId")
    fun getMeasurementsForProjectAndSoundingFlow(projectName: String, soundingProfileId: Int): Flow<List<Measurement>>

    @Query("SELECT * FROM measurements WHERE projectName = :projectName ORDER BY dbId DESC LIMIT 1")
    fun getLastMeasurementForProjectFlow(projectName: String): Flow<Measurement?>

    @Delete
    fun deleteMeasurement(measurement: Measurement): Int

    @Update
    fun updateMeasurement(measurement: Measurement): Int

    @Query("SELECT DISTINCT soundingProfileId FROM measurements WHERE projectName = :projectName ORDER BY soundingProfileId ASC")
    fun getDistinctSoundingProfileIdsFlow(projectName: String): Flow<List<Int>>

    @Query("SELECT DISTINCT projectName FROM measurements ORDER BY projectName ASC")
    fun getDistinctProjectNamesFlow(): Flow<List<String>>

    // **تغییر:** به لطف TypeConverter، این متد می‌تواند مستقیماً Flow<ProjectType?> برگرداند
    @Query("SELECT projectType FROM measurements WHERE projectName = :projectName LIMIT 1")
    fun getProjectTypeByNameFlow(projectName: String): Flow<ProjectType?> // <-- نوع بازگشتی ProjectType شد

    // **(اختیاری ولی مفید):** یک تابع غیر Flow برای گرفتن لیست اندازه‌گیری‌ها اضافه می‌کنیم
    // که Repository بتواند در exportProjectData (داخل withContext) از آن استفاده کند.
    @Query("SELECT * FROM measurements WHERE projectName = :projectName")
    fun getAllMeasurementsForProjectList(projectName: String): List<Measurement> // <-- بدون Flow، بدون suspend
}