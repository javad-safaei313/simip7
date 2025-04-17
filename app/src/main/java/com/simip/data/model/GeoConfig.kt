package com.simip.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the geometric configuration for a measurement point.
 * Using a sealed class to handle different parameter sets for each project type.
 * Parcelable allows passing this state between components if needed.
 */
@Parcelize
sealed class GeoConfig : Parcelable {

    abstract val projectType: ProjectType

    /**
     * Represents the geometry configuration for Sholomberje (Schlumberger) arrays.
     * @param ab_2 Current AB/2 value (m).
     * @param mn_2 Current MN/2 value (m).
     * @param x0 Current X0 value (m, center point offset).
     * @param currentIndex The index in the sholom_geometries_array corresponding to the current AB/2 and MN/2.
     */
    @Parcelize
    data class Sholom(
        var ab_2: Double,
        var mn_2: Double,
        var x0: Double = 0.0, // Default X0 to 0 as per spec
        var currentIndex: Int = 0 // Index in the predefined array
    ) : GeoConfig() {
        override val projectType: ProjectType get() = ProjectType.SHOLOM
    }

    /**
     * Represents the geometry configuration for Dipole-Dipole arrays.
     * @param tx0 Transmitter electrode 1 position (m).
     * @param tx1 Transmitter electrode 2 position (m).
     * @param rx0 Receiver electrode 1 position (m).
     * @param rx1 Receiver electrode 2 position (m).
     * @param distance Electrode spacing 'a' (m).
     * @param n Dipole separation factor (integer, starts from 1).
     */
    @Parcelize
    data class DipoleDipole(
        var tx0: Double = 0.0,   // Default tx0 to 0 as per spec
        var tx1: Double = 10.0,  // Calculated: tx0 + distance
        var rx0: Double = 20.0,  // Calculated: tx0 + (n+1)*distance
        var rx1: Double = 30.0,  // Calculated: tx0 + (n+2)*distance
        var distance: Double = 10.0, // Default distance to 10 as per spec
        var n: Int = 1           // Default n to 1
    ) : GeoConfig() {
        override val projectType: ProjectType get() = ProjectType.DP_DP

        // Helper to recalculate derived fields based on editable fields (tx0, distance) and n
        fun recalculateDerivedFields() {
            tx1 = tx0 + distance
            rx0 = tx0 + (n + 1) * distance
            rx1 = tx0 + (n + 2) * distance
        }
    }

    /**
     * Represents the geometry configuration for Pole-Dipole arrays.
     * Note: tx0 is conceptually at infinity. We store it as null here, UI handles display.
     * @param tx0 Always null (represents -infinity).
     * @param tx1 Transmitter electrode position (m).
     * @param rx0 Receiver electrode 1 position (m).
     * @param rx1 Receiver electrode 2 position (m).
     * @param distance Electrode spacing 'a' (m).
     * @param n Dipole separation factor (integer, starts from 1).
     */
    @Parcelize
    data class PoleDipole(
        val tx0: Double? = null, // Represents -infinity, always null
        var tx1: Double = 0.0,    // Default tx1 to 0 as per spec
        var rx0: Double = 10.0,   // Calculated: tx1 + n*distance
        var rx1: Double = 20.0,   // Calculated: tx1 + (n+1)*distance
        var distance: Double = 10.0,  // Default distance to 10 as per spec
        var n: Int = 1            // Default n to 1
    ) : GeoConfig() {
        override val projectType: ProjectType get() = ProjectType.P_DP

        // Helper to recalculate derived fields based on editable fields (tx1, distance) and n
        fun recalculateDerivedFields() {
            rx0 = tx1 + n * distance
            rx1 = tx1 + (n + 1) * distance
        }
    }

    /**
     * Represents the geometry configuration for Pole-Pole arrays.
     * Note: tx0 and rx1 are conceptually at infinity. We store them as null.
     * @param tx0 Always null (represents -infinity).
     * @param tx1 Transmitter electrode position (m).
     * @param rx0 Receiver electrode position (m).
     * @param rx1 Always null (represents +infinity).
     * @param distance Electrode spacing 'a' (m).
     * @param n Dipole separation factor (integer, starts from 1).
     */
    @Parcelize
    data class PolePole(
        val tx0: Double? = null, // Represents -infinity, always null
        var tx1: Double = 0.0,   // Default tx1 to 0 as per spec
        var rx0: Double = 10.0,  // Calculated: tx1 + n*distance
        val rx1: Double? = null, // Represents +infinity, always null
        var distance: Double = 10.0, // Default distance to 10 as per spec
        var n: Int = 1           // Default n to 1
    ) : GeoConfig() {
        override val projectType: ProjectType get() = ProjectType.P_P

        // Helper to recalculate derived fields based on editable fields (tx1, distance) and n
        fun recalculateDerivedFields() {
            rx0 = tx1 + n * distance
        }
    }

    // Represents an uninitialized or invalid state, useful for initial ViewModel state
    @Parcelize
    object Uninitialized : GeoConfig() {
        override val projectType: ProjectType get() = throw IllegalStateException("Cannot get project type from Uninitialized GeoConfig")
    }
}