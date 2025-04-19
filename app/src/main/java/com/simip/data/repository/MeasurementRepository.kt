package com.simip.data.repository // <--- مطمئن شو پکیج درسته

import com.simip.data.model.ProjectType
import com.simip.data.model.Measurement
import com.simip.data.model.Project // اگر مدل Project داری import کن
import kotlinx.coroutines.flow.Flow

/**
 * اینترفیس برای تعریف عملیات مربوط به داده‌های اندازه‌گیری و پروژه.
 * ViewModel ها باید از این اینترفیس استفاده کنند، نه از پیاده‌سازی مستقیم.
 */
interface MeasurementRepository {

    // --- توابع خواندن (بدون suspend، با Flow) ---

    fun getAllMeasurementsFlow(projectName: String): Flow<List<Measurement>>

    fun getMeasurementsForProjectAndSoundingFlow(projectName: String, soundingProfileId: Int): Flow<List<Measurement>>

    fun getLastMeasurementForProjectFlow(projectName: String): Flow<Measurement?>

    fun getDistinctSoundingProfileIdsFlow(projectName: String): Flow<List<Int>>

    fun getDistinctProjectNamesFlow(): Flow<List<String>>

    // **تغییر:** قبلاً Flow<String?> بود، اما چون ProjectType یک Enum است،
    // بهتر است Repository مستقیماً Flow<ProjectType?> را برگرداند.
    // این نیاز به تغییر در DAO و Impl هم دارد (در ادامه می‌آید).
    fun getProjectTypeByNameFlow(projectName: String): Flow<ProjectType?> // <-- تغییر نوع بازگشتی

    // --- توابع نوشتن (باید suspend باشند) ---

    suspend fun insertMeasurement(measurement: Measurement): Long

    suspend fun deleteMeasurement(measurement: Measurement): Int

    suspend fun updateMeasurement(measurement: Measurement): Int

    // --- سایر توابع مورد نیاز Repository ---

    // **تغییر:** projectType را به نوع Enum تغییر می‌دهیم
    suspend fun exportProjectData(projectName: String, projectType: ProjectType): Boolean // <-- نوع پارامتر تغییر کرد

    suspend fun getLastOpenedProject(): Project?

    suspend fun saveLastOpenedProject(project: Project)
}