package com.simip.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import kotlin.math.PI // برای محاسبه k_sholom

@Entity(tableName = "Measurements")
data class Measurement(
    @PrimaryKey(autoGenerate = true)
    var dbId: Int = 0,

    @ColumnInfo(index = true) // Index for faster project filtering
    val projectName: String,

    @ColumnInfo(index = true) // Index for faster project filtering
    val projectType: String, // 'sholom', 'DP-DP', 'P-DP', 'P-P'

    @ColumnInfo(index = true) // Index for sounding/profile filtering
    val soundingProfileId: Int, // شناسه سوند/پروفیل درون پروژه (محاسبه شده در زمان ذخیره)

    val gpsLongitude: Double?, // Can be null if GPS unavailable
    val gpsLatitude: Double?,  // Can be null if GPS unavailable
    val gpsAltitude: Double?,  // Can be null if GPS unavailable

    val measurementDate: String, // 'YYYY-MM-DD'
    val measurementTime: String, // 'HH:MM:SS'

    // Geometry Parameters - Nullable because only relevant ones are filled per projectType
    val geom_ab_2: Double?,       // Sholomberje
    val geom_mn_2: Double?,       // Sholomberje
    val geom_x0: Double?,         // Sholomberje
    val geom_tx0: Double?,        // Dipole-Dipole, Pole-Dipole, Pole-Pole (can be NULL for P-DP/P-P -> -inf)
    val geom_tx1: Double?,        // Dipole-Dipole, Pole-Dipole, Pole-Pole
    val geom_rx0: Double?,        // Dipole-Dipole, Pole-Dipole, Pole-Pole
    val geom_rx1: Double?,        // Dipole-Dipole, Pole-Dipole, Pole-Pole (can be NULL for P-P -> inf)
    val geom_distance: Double?,   // Dipole-Dipole, Pole-Dipole, Pole-Pole (a)
    val geom_n: Int?,             // Dipole-Dipole, Pole-Dipole, Pole-Pole

    // Device & Settings Parameters
    val device_battery_volt: Float?, // ولتاژ باتری (V) - from Gets or Data
    val device_temperature_c: Float?,  // دما (°C) - from Gets or Data
    val device_contact_kohm: Float?,   // مقاومت اتصال (kΩ) - from Data
    val setting_stack: Int,           // تعداد تکرار - from Data (originally from SetConfig)
    val setting_time_s: Float,        // زمان تنظیم شده (s) - from Data (originally from SetConfig)

    // Measured Values
    val meas_sp_mv: Float?,           // پتانسیل خودزا (mV) - MeasureSP from Data
    val meas_current_ma: Float,       // جریان اندازه‌گیری شده (mA) - MeasureAmper from Data
    val meas_potential_mv: Float,     // پتانسیل اصلی (dV) (mV) - deltav from Data

    // Calculated Values (Stored for convenience, especially for Export and DList)
    val calc_roh_omm: Double,         // مقاومت ویژه محاسبه شده (Ωm)
    val calc_ip_mvv: Float,           // شارژپذیری میانگین محاسبه شده (mV/V)

    // IP Decay Curve Points (mV/V)
    val ip_decay_1: Float,
    val ip_decay_2: Float,
    val ip_decay_3: Float,
    val ip_decay_4: Float,
    val ip_decay_5: Float,
    val ip_decay_6: Float,
    val ip_decay_7: Float,
    val ip_decay_8: Float,
    val ip_decay_9: Float,
    val ip_decay_10: Float,
    val ip_decay_11: Float,
    val ip_decay_12: Float,
    val ip_decay_13: Float,
    val ip_decay_14: Float,
    val ip_decay_15: Float,
    val ip_decay_16: Float,
    val ip_decay_17: Float,
    val ip_decay_18: Float,
    val ip_decay_19: Float,
    val ip_decay_20: Float
) {
    // Helper function to calculate average IP directly from the entity
    fun calculateAverageIp(): Float {
        val decayValues = listOf(ip_decay_1, ip_decay_2, ip_decay_3, ip_decay_4, ip_decay_5,
            ip_decay_6, ip_decay_7, ip_decay_8, ip_decay_9, ip_decay_10,
            ip_decay_11, ip_decay_12, ip_decay_13, ip_decay_14, ip_decay_15,
            ip_decay_16, ip_decay_17, ip_decay_18, ip_decay_19, ip_decay_20)
        return if (decayValues.isNotEmpty()) decayValues.average().toFloat() else 0f
    }

    // Helper function to calculate Roh (requires k factor calculation logic externally)
    // This calculation should ideally happen *before* creating the entity instance.
    // This is just a placeholder illustrating the formula base.
    // Roh (Ωm) = k * (dV / I)
    // dV = meas_potential_mv (mV)
    // I = meas_current_ma (mA)
    // k = Geometric factor (m) - calculated based on projectType and geom_* fields
    // Note: Units (mV/mA) cancel out, result depends only on k's unit (m).

    // Static factory method or helper outside to calculate Roh before instantiation
    // is recommended. For example:
    // companion object {
    //     fun calculateRoh(k: Double, dV_mV: Float, I_mA: Float): Double {
    //         if (I_mA == 0f) return Double.NaN // Avoid division by zero
    //         return k * (dV_mV / I_mA)
    //     }
    // }
}

// Enum for Project Types to avoid string typos
enum class ProjectType(val key: String) {
    SHOLOM("sholom"),
    DP_DP("DP-DP"),
    P_DP("P-DP"),
    P_P("P-P"),
    UNKNOWN("Unknown Type");

    companion object {
        fun fromKey(key: String): ProjectType? = values().find { it.key == key }
    }
}