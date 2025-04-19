package com.simip.data.repository

import android.content.SharedPreferences
import com.simip.data.db.MeasurementDao
import com.simip.data.model.Measurement
import com.simip.data.model.Project
import com.simip.data.model.ProjectType // <-- Import ProjectType
import com.simip.util.ExcelExporter // اگر استفاده می‌کنی
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
// import javax.inject.Inject // حذف شد

class MeasurementRepositoryImpl constructor(
    private val measurementDao: MeasurementDao,
    private val sharedPreferences: SharedPreferences,
    private val excelExporter: ExcelExporter // فرض می‌کنیم این هست
) : MeasurementRepository { // <-- پیاده‌سازی اینترفیس

    // --- پیاده‌سازی توابع SELECT ---
    override fun getAllMeasurementsFlow(projectName: String): Flow<List<Measurement>> {
        return measurementDao.getAllMeasurementsForProjectFlow(projectName)
    }

    override fun getMeasurementsForProjectAndSoundingFlow(projectName: String, soundingProfileId: Int): Flow<List<Measurement>> {
        return measurementDao.getMeasurementsForProjectAndSoundingFlow(projectName, soundingProfileId)
    }

    override fun getLastMeasurementForProjectFlow(projectName: String): Flow<Measurement?> {
        return measurementDao.getLastMeasurementForProjectFlow(projectName)
    }

    override fun getDistinctSoundingProfileIdsFlow(projectName: String): Flow<List<Int>> {
        return measurementDao.getDistinctSoundingProfileIdsFlow(projectName)
    }

    override fun getDistinctProjectNamesFlow(): Flow<List<String>> {
        return measurementDao.getDistinctProjectNamesFlow()
    }

    // **تغییر:** حالا تابع DAO مستقیماً Flow<ProjectType?> برمی‌گرداند
    override fun getProjectTypeByNameFlow(projectName: String): Flow<ProjectType?> {
        return measurementDao.getProjectTypeByNameFlow(projectName)
    }

    // --- پیاده‌سازی توابع INSERT/DELETE/UPDATE ---
    override suspend fun insertMeasurement(measurement: Measurement): Long {
        return withContext(Dispatchers.IO) {
            measurementDao.insertMeasurement(measurement)
        }
    }

    override suspend fun deleteMeasurement(measurement: Measurement): Int {
        return withContext(Dispatchers.IO) {
            measurementDao.deleteMeasurement(measurement)
        }
    }

    override suspend fun updateMeasurement(measurement: Measurement): Int {
        return withContext(Dispatchers.IO) {
            measurementDao.updateMeasurement(measurement)
        }
    }

    // --- پیاده‌سازی سایر توابع ---
    override suspend fun exportProjectData(projectName: String, projectType: ProjectType): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // از تابع جدید DAO که لیست معمولی برمی‌گرداند استفاده می‌کنیم
                val measurements = measurementDao.getAllMeasurementsForProjectList(projectName)
                if (measurements.isNotEmpty()) {
                    // **تغییر:** projectType الان Enum هست، شاید ExcelExporter نیاز به نامش داشته باشه
                    excelExporter.exportToExcel(measurements, projectName, projectType.name) // <-- استفاده از projectType.name
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    override suspend fun getLastOpenedProject(): Project? {
        val name = sharedPreferences.getString("lastProjectName", null)
        val typeString = sharedPreferences.getString("lastProjectType", null) // نام Enum ذخیره شده

        return if (name != null && typeString != null) {
            try {
                val typeEnum = ProjectType.valueOf(typeString) // تبدیل String به Enum
                Project(name, typeEnum)
            } catch (e: IllegalArgumentException) { null }
        } else { null }
    }

    override suspend fun saveLastOpenedProject(project: Project) {
        sharedPreferences.edit()
            .putString("lastProjectName", project.name)
            .putString("lastProjectType", project.type.name) // ذخیره نام Enum
            .apply()
    }
}