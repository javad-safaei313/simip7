package com.simip.util // <-- یا پکیج صحیح شما

import android.content.Context
import android.os.Environment
import com.simip.data.model.Measurement // <-- Import Measurement
import com.simip.data.model.ProjectType // <-- Import ProjectType Enum
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ExcelExporter(private val context: Context) {

    /**
     * اکسپورت لیست اندازه‌گیری‌ها به فایل اکسل در پوشه Downloads.
     *
     * @param measurements لیست داده‌های Measurement برای اکسپورت.
     * @param projectName نام پروژه (برای نام شیت و فایل).
     * @param projectTypeName نام نوع پروژه (String) برای استفاده در نام فایل.
     * @return Boolean نشان‌دهنده موفقیت یا عدم موفقیت عملیات.
     */
    fun exportToExcel(measurements: List<Measurement>, projectName: String, projectTypeName: String): Boolean {
        if (measurements.isEmpty()) {
            println("ExcelExporter: No measurements to export for project: $projectName")
            return false // داده‌ای برای اکسپورت وجود ندارد
        }

        println("ExcelExporter: Starting export for project: $projectName, type: $projectTypeName, measurements count: ${measurements.size}")

        val workbook: Workbook = XSSFWorkbook()
        // نام شیت را کمی خواناتر می‌کنیم (حذف کاراکترهای نامعتبر احتمالی)
        val safeSheetName = projectName.replace(Regex("[\\\\/*?\\[\\]:]"), "_").take(30) // محدودیت طول نام شیت اکسل
        val sheet: Sheet = workbook.createSheet(safeSheetName)

        // --- ایجاد ردیف هدر ---
        val headerRow: Row = sheet.createRow(0)
        // لیست دقیق هدرها مطابق با فیلدهای Measurement
        val headers = listOf(
            "dbId", "projectName", "projectType", "soundingProfileId", "gpsLongitude", "gpsLatitude", "gpsAltitude", "measurementDate", "measurementTime",
            "geom_ab_2", "geom_mn_2", "geom_x0", "geom_tx0", "geom_tx1", "geom_rx0", "geom_rx1", "geom_distance", "geom_n",
            "device_battery_volt", "device_temperature_c", "device_contact_kohm", "setting_stack", "setting_time_s",
            "meas_sp_mv", "meas_current_ma", "meas_potential_mv", "calc_roh_omm", "calc_ip_mvv",
            "ip_decay_1", "ip_decay_2", "ip_decay_3", "ip_decay_4", "ip_decay_5", "ip_decay_6", "ip_decay_7", "ip_decay_8", "ip_decay_9", "ip_decay_10",
            "ip_decay_11", "ip_decay_12", "ip_decay_13", "ip_decay_14", "ip_decay_15", "ip_decay_16", "ip_decay_17", "ip_decay_18", "ip_decay_19", "ip_decay_20"
        )

        // استایل برای هدر
        val headerStyle: CellStyle = workbook.createCellStyle()
        val headerFont: Font = workbook.createFont().apply { bold = true }
        headerStyle.setFont(headerFont)

        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).apply {
                setCellValue(header)
                cellStyle = headerStyle
            }
        }

        // --- پر کردن ردیف‌های داده ---
        measurements.forEachIndexed { dataIndex, measurement ->
            val dataRow: Row = sheet.createRow(dataIndex + 1) // شروع از ردیف 1

            // Helper function برای تنظیم مقدار سلول با مدیریت null و نوع
            fun setCellValueSafely(cellIndex: Int, value: Any?) {
                val cell = dataRow.createCell(cellIndex)
                try {
                    when (value) {
                        is String? -> cell.setCellValue(value ?: "") // String یا null
                        is Number? -> cell.setCellValue(value?.toDouble() ?: Double.NaN) // تبدیل Int?, Long?, Double? به Double (NaN اگر null بود)
                        is ProjectType? -> cell.setCellValue(value?.name ?: "") // گرفتن نام Enum
                        is Date? -> { // اگر نوع Date داشتید (که ندارید فعلا)
                            val dateStyle = workbook.createCellStyle()
                            dateStyle.dataFormat = workbook.creationHelper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss")
                            cell.setCellValue(value ?: Date(0))
                            cell.cellStyle = dateStyle
                        }
                        else -> cell.setCellValue(value?.toString() ?: "") // fallback به toString
                    }
                } catch (e: Exception) {
                    println("ExcelExporter: Error setting cell value for row ${dataIndex + 1}, col $cellIndex: ${e.message}")
                    cell.setCellValue("ERROR") // یا مقدار پیش‌فرض دیگر
                }
            }

            // تنظیم مقادیر برای هر سلول با استفاده از helper
            setCellValueSafely(0, measurement.dbId)
            setCellValueSafely(1, measurement.projectName)
            setCellValueSafely(2, measurement.projectType) // Helper .name را می‌گیرد
            setCellValueSafely(3, measurement.soundingProfileId)
            setCellValueSafely(4, measurement.gpsLongitude)
            setCellValueSafely(5, measurement.gpsLatitude)
            setCellValueSafely(6, measurement.gpsAltitude)
            setCellValueSafely(7, measurement.measurementDate)
            setCellValueSafely(8, measurement.measurementTime)
            setCellValueSafely(9, measurement.geom_ab_2)
            setCellValueSafely(10, measurement.geom_mn_2)
            setCellValueSafely(11, measurement.geom_x0)
            setCellValueSafely(12, measurement.geom_tx0)
            setCellValueSafely(13, measurement.geom_tx1)
            setCellValueSafely(14, measurement.geom_rx0)
            setCellValueSafely(15, measurement.geom_rx1)
            setCellValueSafely(16, measurement.geom_distance)
            setCellValueSafely(17, measurement.geom_n)
            setCellValueSafely(18, measurement.device_battery_volt)
            setCellValueSafely(19, measurement.device_temperature_c)
            setCellValueSafely(20, measurement.device_contact_kohm)
            setCellValueSafely(21, measurement.setting_stack) // Int? است
            setCellValueSafely(22, measurement.setting_time_s) // Int? است
            setCellValueSafely(23, measurement.meas_sp_mv)
            setCellValueSafely(24, measurement.meas_current_ma)
            setCellValueSafely(25, measurement.meas_potential_mv)
            setCellValueSafely(26, measurement.calc_roh_omm)
            setCellValueSafely(27, measurement.calc_ip_mvv)
            // IP Decay values
            for (i in 1..20) {
                val decayValue = when (i) {
                    1 -> measurement.ip_decay_1; 2 -> measurement.ip_decay_2; 3 -> measurement.ip_decay_3; 4 -> measurement.ip_decay_4;
                    5 -> measurement.ip_decay_5; 6 -> measurement.ip_decay_6; 7 -> measurement.ip_decay_7; 8 -> measurement.ip_decay_8;
                    9 -> measurement.ip_decay_9; 10 -> measurement.ip_decay_10; 11 -> measurement.ip_decay_11; 12 -> measurement.ip_decay_12;
                    13 -> measurement.ip_decay_13; 14 -> measurement.ip_decay_14; 15 -> measurement.ip_decay_15; 16 -> measurement.ip_decay_16;
                    17 -> measurement.ip_decay_17; 18 -> measurement.ip_decay_18; 19 -> measurement.ip_decay_19; 20 -> measurement.ip_decay_20;
                    else -> null
                }
                setCellValueSafely(27 + i, decayValue) // ستون 28 تا 47
            }
        }

        // --- ذخیره فایل ---
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val excelFileName = "${projectName}_${projectTypeName}_${timeStamp}.xlsx" // استفاده از نام نوع پروژه

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) {
                println("ExcelExporter: Failed to create Downloads directory.")
                workbook.close() // بستن workbook قبل از خروج
                return false
            }
        }
        val file = File(downloadsDir, excelFileName)

        return try {
            FileOutputStream(file).use { fileOut -> workbook.write(fileOut) }
            workbook.close()
            println("ExcelExporter: Excel file exported successfully to: ${file.absolutePath}")
            true
        } catch (e: IOException) {
            e.printStackTrace()
            println("ExcelExporter: Error exporting Excel file: ${e.message}")
            workbook.close() // بستن workbook در صورت خطا
            false
        } catch (e: Exception) { // گرفتن خطاهای دیگر احتمالی
            e.printStackTrace()
            println("ExcelExporter: Unexpected error during export: ${e.message}")
            workbook.close()
            false
        }
    }
}