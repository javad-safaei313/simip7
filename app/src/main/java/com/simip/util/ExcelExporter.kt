
package com.simip.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.simip.data.model.Measurement
import com.simip.data.model.ProjectType
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility class to export Measurement data for a project to an Excel (.xlsx) file
 * in the device's Downloads directory.
 * Uses Apache POI library.
 *
 * Requires Apache POI dependencies in build.gradle.
 * Requires WRITE_EXTERNAL_STORAGE permission for Android versions below 10,
 * or uses MediaStore API for Android 10+.
 */
class ExcelExporter(private val context: Context) {

    private val TAG = "ExcelExporter"

    /**
     * Exports the provided list of measurements to an Excel file.
     * The file name will be based on the project name.
     *
     * @param projectName The name of the project, used for the filename.
     * @param projectType The type of the project, used to determine columns.
     * @param measurements The list of Measurement objects to export.
     * @return True if the export was successful, false otherwise.
     */
    fun exportToExcel(projectName: String, projectType: String, measurements: List<Measurement>): Boolean {
        if (measurements.isEmpty()) {
            Log.w(TAG, "No measurements provided for export.")
            return false
        }

        val workbook: Workbook = XSSFWorkbook() // Create new HSSFWorkbook for .xls or XSSFWorkbook for .xlsx
        val sheet: Sheet = workbook.createSheet(projectName) // Use project name as sheet name

        // Determine header row based on project type
        val headerColumns = createHeaderColumns(projectType)
        createHeaderRow(sheet, headerColumns)

        // Populate data rows
        populateDataRows(sheet, measurements, headerColumns, projectType)

        // Auto-size columns for better readability
        headerColumns.indices.forEach { sheet.autoSizeColumn(it) }

        // Write the workbook to a file
        return writeWorkbookToFile(workbook, projectName)
    }

    /**
     * Defines the header column names based on the project type.
     */
    private fun createHeaderColumns(projectType: String): List<String> {
        val commonPrefix = listOf(
            "Record#", "SoundingID", "Date", "Time", "Longitude", "Latitude", "Altitude(m)",
            "Battery(V)", "Temp(°C)", "Contact(kΩ)", "Stack", "Time(s)", "SP(mV)", "I(mA)", "dV(mV)"
        )
        val commonSuffix = listOf(
            "Roh(Ωm)", "IP(mV/V)",
            "IP1", "IP2", "IP3", "IP4", "IP5", "IP6", "IP7", "IP8", "IP9", "IP10",
            "IP11", "IP12", "IP13", "IP14", "IP15", "IP16", "IP17", "IP18", "IP19", "IP20"
        )

        val geometryColumns = when (ProjectType.fromKey(projectType)) {
            ProjectType.SHOLOM -> listOf("AB/2(m)", "MN/2(m)", "X0(m)")
            ProjectType.DP_DP -> listOf("TX0(m)", "TX1(m)", "RX0(m)", "RX1(m)", "Distance(m)", "N")
            ProjectType.P_DP -> listOf("TX0(m)", "TX1(m)", "RX0(m)", "RX1(m)", "Distance(m)", "N") // Same as DPDP for export simplicity
            ProjectType.P_P -> listOf("TX0(m)", "TX1(m)", "RX0(m)", "RX1(m)", "Distance(m)", "N") // Same as DPDP for export simplicity
            else -> emptyList() // Should not happen
        }

        return commonPrefix + geometryColumns + commonSuffix
    }

    /**
     * Creates the header row in the Excel sheet.
     */
    private fun createHeaderRow(sheet: Sheet, columns: List<String>) {
        val headerRow = sheet.createRow(0)
        val headerCellStyle = sheet.workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            setFont(sheet.workbook.createFont().apply { bold = true })
            wrapText = true
        }

        columns.forEachIndexed { index, title ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(title)
            cell.cellStyle = headerCellStyle
        }
    }

    /**
     * Populates the data rows in the sheet based on the measurements.
     */
    private fun populateDataRows(sheet: Sheet, measurements: List<Measurement>, columns: List<String>, projectType: String) {
        val dateCellStyle = sheet.workbook.createCellStyle().apply {
            dataFormat = sheet.workbook.creationHelper.createDataFormat().getFormat("yyyy-mm-dd")
            alignment = HorizontalAlignment.CENTER
        }
        val timeCellStyle = sheet.workbook.createCellStyle().apply {
            dataFormat = sheet.workbook.creationHelper.createDataFormat().getFormat("hh:mm:ss")
            alignment = HorizontalAlignment.CENTER
        }
        val numberCellStyle = sheet.workbook.createCellStyle().apply {
            alignment = HorizontalAlignment.RIGHT
        } // Add specific formats if needed (e.g., "0.000")

        measurements.forEachIndexed { index, measurement ->
            val row = sheet.createRow(index + 1) // Start from row 1 (after header)
            var cellIndex = 0

            fun createCell(value: Any?, style: CellStyle? = numberCellStyle): Cell {
                val cell = row.createCell(cellIndex++)
                when (value) {
                    is String -> cell.setCellValue(value)
                    is Number -> cell.setCellValue(value.toDouble()) // Store all numbers as double
                    is Date -> cell.setCellValue(value) // Requires specific cell style for format
                    is Boolean -> cell.setCellValue(value)
                    null -> cell.setBlank()
                    else -> cell.setCellValue(value.toString())
                }
                cell.cellStyle = style ?: numberCellStyle // Apply style
                return cell
            }

            // Common Prefix Columns
            createCell(index + 1) // Record#
            createCell(measurement.soundingProfileId)
            createCell(parseDate(measurement.measurementDate), dateCellStyle) // Date
            createCell(parseTime(measurement.measurementTime), timeCellStyle) // Time
            createCell(measurement.gpsLongitude)
            createCell(measurement.gpsLatitude)
            createCell(measurement.gpsAltitude)
            createCell(measurement.device_battery_volt?.toDouble())
            createCell(measurement.device_temperature_c?.toDouble())
            createCell(measurement.device_contact_kohm?.toDouble())
            createCell(measurement.setting_stack)
            createCell(measurement.setting_time_s.toDouble())
            createCell(measurement.meas_sp_mv?.toDouble())
            createCell(measurement.meas_current_ma.toDouble())
            createCell(measurement.meas_potential_mv.toDouble())

            // Geometry Columns (adapt based on project type)
            when (ProjectType.fromKey(projectType)) {
                ProjectType.SHOLOM -> {
                    createCell(measurement.geom_ab_2)
                    createCell(measurement.geom_mn_2)
                    createCell(measurement.geom_x0)
                }
                ProjectType.DP_DP, ProjectType.P_DP, ProjectType.P_P -> {
                    // Handle potential nulls for infinity representation
                    createCell(measurement.geom_tx0 ?: Double.NaN) // Represent -inf as NaN or specific string? NaN for numeric consistency.
                    createCell(measurement.geom_tx1)
                    createCell(measurement.geom_rx0)
                    createCell(measurement.geom_rx1 ?: Double.NaN) // Represent +inf as NaN
                    createCell(measurement.geom_distance)
                    createCell(measurement.geom_n)
                }
                else -> { /* Skip geometry columns for unknown type */ }
            }

            // Common Suffix Columns
            createCell(measurement.calc_roh_omm)
            createCell(measurement.calc_ip_mvv.toDouble())
            createCell(measurement.ip_decay_1.toDouble())
            createCell(measurement.ip_decay_2.toDouble())
            createCell(measurement.ip_decay_3.toDouble())
            createCell(measurement.ip_decay_4.toDouble())
            createCell(measurement.ip_decay_5.toDouble())
            createCell(measurement.ip_decay_6.toDouble())
            createCell(measurement.ip_decay_7.toDouble())
            createCell(measurement.ip_decay_8.toDouble())
            createCell(measurement.ip_decay_9.toDouble())
            createCell(measurement.ip_decay_10.toDouble())
            createCell(measurement.ip_decay_11.toDouble())
            createCell(measurement.ip_decay_12.toDouble())
            createCell(measurement.ip_decay_13.toDouble())
            createCell(measurement.ip_decay_14.toDouble())
            createCell(measurement.ip_decay_15.toDouble())
            createCell(measurement.ip_decay_16.toDouble())
            createCell(measurement.ip_decay_17.toDouble())
            createCell(measurement.ip_decay_18.toDouble())
            createCell(measurement.ip_decay_19.toDouble())
            createCell(measurement.ip_decay_20.toDouble())
        }
    }

    /**
     * Writes the generated workbook to a file in the public Downloads directory.
     * Uses MediaStore for Android 10+ for better compatibility with Scoped Storage.
     */
    private fun writeWorkbookToFile(workbook: Workbook, baseFileName: String): Boolean {
        val fileName = "${baseFileName}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}${Constants.EXPORT_FILE_EXTENSION}"
        var outputStream: OutputStream? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore API for Android 10 and above
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    outputStream = resolver.openOutputStream(uri)
                } else {
                    Log.e(TAG, "MediaStore failed to create file entry.")
                    return false
                }
            } else {
                // Use legacy external storage access for Android 9 and below
                // Requires WRITE_EXTERNAL_STORAGE permission
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val file = File(downloadsDir, fileName)
                outputStream = FileOutputStream(file)
            }

            if (outputStream == null) {
                Log.e(TAG, "Failed to get output stream for the file.")
                return false
            }

            Log.d(TAG, "Writing workbook to file: $fileName")
            workbook.write(outputStream)
            Log.i(TAG, "Excel file exported successfully to Downloads folder.")
            return true

        } catch (e: IOException) {
            Log.e(TAG, "IOException writing Excel file: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error writing Excel file: ${e.message}", e)
            return false
        } finally {
            try {
                outputStream?.flush()
                outputStream?.close()
                workbook.close() // Close the workbook to release resources
            } catch (e: IOException) {
                Log.e(TAG, "Error closing output stream or workbook: ${e.message}", e)
            }
        }
    }

    // Helper function to parse date string (YYYY-MM-DD) into Date object for Excel cell
    private fun parseDate(dateString: String): Date? {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateString)
        } catch (e: Exception) {
            null
        }
    }

    // Helper function to parse time string (HH:MM:SS) into Date object for Excel cell
    // Note: Excel stores time as a fraction of a day. We might need a different approach
    // if just storing time is desired without a date component, or store as text.
    // Let's store as Date for now, Excel should handle it.
    private fun parseTime(timeString: String): Date? {
        return try {
            // Create a dummy date part, as Excel needs a full date for time formatting usually
            val fullDateTimeString = "1900-01-01 $timeString" // Using a base date
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(fullDateTimeString)
        } catch (e: Exception) {
            // Fallback: store as text if parsing fails? Or return null.
            null
        }
    }
}