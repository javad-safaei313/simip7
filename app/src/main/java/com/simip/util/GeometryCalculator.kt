package com.simip.util

import com.simip.data.model.GeoConfig
import com.simip.data.model.ProjectType
import kotlin.math.PI

/**
 * Utility object for geometry related calculations and logic.
 * Calculates geometric factor (k) and handles state updates for non-Sholom geometries.
 */
object GeometryCalculator {

    private const val TAG = "GeometryCalculator"
    const val INFINITY_REPRESENTATION = Double.NaN // Use NaN internally for infinity

    /**
     * Calculates the geometric factor 'k' based on the current GeoConfig.
     * @param config The current geometric configuration.
     * @return The calculated geometric factor 'k' in meters (m), or Double.NaN if calculation is not possible or applicable.
     */
    fun calculateGeometricFactor(config: GeoConfig): Double {
        return when (config) {
            is GeoConfig.Sholom -> calculateKSchlumberger(config.ab_2, config.mn_2)
            is GeoConfig.DipoleDipole -> calculateKDipoleDipole(config.distance, config.n)
            is GeoConfig.PoleDipole -> calculateKPoleDipole(config.distance, config.n)
            is GeoConfig.PolePole -> calculateKPolePole(config.distance, config.n)
            is GeoConfig.Uninitialized -> Double.NaN // Cannot calculate for uninitialized state
        }
    }

    /**
     * Calculates the geometric factor for Schlumberger arrays.
     * k_sholom = PI * ( (AB/2)^2 - (MN/2)^2 ) / (2 * MN/2)
     * As per spec (section 6.3.5, point 4), using the formula exactly as provided,
     * even if alternative formulas exist. The division is (2 * MN/2), which simplifies to MN.
     * Let's re-verify the formula based on standard texts, it's usually PI * L^2 / a where L=AB/2, a=MN.
     * Or k = PI * ( (AB/2)^2 / MN - MN / 4 ) -- approx for small MN
     * Let's stick to the specified formula: PI * ( (AB/2)^2 - (MN/2)^2 ) / MN
     *
     * @param ab2 AB/2 distance in meters.
     * @param mn2 MN/2 distance in meters.
     * @return Geometric factor k in meters, or NaN if mn2 is zero.
     */
    private fun calculateKSchlumberger(ab2: Double, mn2: Double): Double {
        // The formula provided was PI * ( (AB/2)^2 - (MN/2)^2 ) / (2 * MN/2)
        // which simplifies to PI * ( (ab2*ab2) - (mn2*mn2) ) / (2 * mn2)
        if (mn2 <= 0) return Double.NaN // Prevent division by zero or invalid input
        val mn = 2 * mn2
        return PI * ( (ab2 * ab2) - (mn2 * mn2) ) / mn
        // Let's re-confirm the formula from the spec: PI * ( (AB/2)^2 - (MN/2)^2 ) / (2 * MN/2)
        // Yes, that's what was written. Implementing that directly.
        // return PI * ( (ab2 * ab2) - (mn2 * mn2) ) / (2 * mn2) // <-- Original implementation based on literal spec text
    }

    /**
     * Calculates the geometric factor for Dipole-Dipole arrays.
     * k_dpdp = PI * n * (n+1) * (n+2) * a
     * @param a Electrode spacing (distance) in meters.
     * @param n Dipole separation factor (integer).
     * @return Geometric factor k in meters.
     */
    private fun calculateKDipoleDipole(a: Double, n: Int): Double {
        if (n <= 0) return Double.NaN // n must be positive integer
        return PI * n * (n + 1) * (n + 2) * a
    }

    /**
     * Calculates the geometric factor for Pole-Dipole arrays.
     * k_pdp = 2 * PI * n * (n+1) * a
     * @param a Electrode spacing (distance) in meters.
     * @param n Dipole separation factor (integer).
     * @return Geometric factor k in meters.
     */
    private fun calculateKPoleDipole(a: Double, n: Int): Double {
        if (n <= 0) return Double.NaN // n must be positive integer
        return 2.0 * PI * n * (n + 1) * a
    }

    /**
     * Calculates the geometric factor for Pole-Pole arrays.
     * k_pp = 2 * PI * n * a
     * @param a Electrode spacing (distance) in meters.
     * @param n Dipole separation factor (integer).
     * @return Geometric factor k in meters.
     */
    private fun calculateKPolePole(a: Double, n: Int): Double {
        if (n <= 0) return Double.NaN // n must be positive integer
        return 2.0 * PI * n * a
    }

    /**
     * Calculates the Roh (Apparent Resistivity) value.
     * Roh (Ωm) = k * (dV / I)
     * @param k Geometric factor (m).
     * @param dV_mV Measured potential (deltaV) in millivolts (mV).
     * @param I_mA Measured current (MeasureAmper) in milliamperes (mA).
     * @return Apparent resistivity in Ohm-meters (Ωm), or Double.NaN if k is NaN or I_mA is zero.
     */
    fun calculateRoh(k: Double, dV_mV: Float, I_mA: Float): Double {
        if (k.isNaN() || I_mA == 0f) {
            return Double.NaN // Cannot calculate Roh
        }
        // Units mV/mA result in Ohms (Ω). Multiply by k (m) to get Ωm.
        return k * (dV_mV / I_mA)
    }


    // --- Geometry State Management for Non-Sholom ---

    /**
     * Updates the GeoConfig for the "Next" action (typically increments 'n').
     * Modifies the passed config object directly.
     * @param config The current GeoConfig object (must be DP-DP, P-DP, or P-P).
     * @return The modified GeoConfig object. Returns the original object if type is wrong or action is invalid.
     */
    fun handleNextAction(config: GeoConfig): GeoConfig {
        when (config) {
            is GeoConfig.DipoleDipole -> {
                config.n++
                config.recalculateDerivedFields()
            }
            is GeoConfig.PoleDipole -> {
                config.n++
                config.recalculateDerivedFields()
            }
            is GeoConfig.PolePole -> {
                config.n++
                config.recalculateDerivedFields()
            }
            else -> { /* No action for Sholom or Uninitialized */ }
        }
        return config // Return the (potentially modified) object
    }

    /**
     * Updates the GeoConfig for the "Previous" action (decrements 'n', minimum n=1).
     * Modifies the passed config object directly.
     * @param config The current GeoConfig object (must be DP-DP, P-DP, or P-P).
     * @return The modified GeoConfig object. Returns the original object if type is wrong or action is invalid.
     */
    fun handlePreviousAction(config: GeoConfig): GeoConfig {
        when (config) {
            is GeoConfig.DipoleDipole -> {
                if (config.n > 1) {
                    config.n--
                    config.recalculateDerivedFields()
                }
            }
            is GeoConfig.PoleDipole -> {
                if (config.n > 1) {
                    config.n--
                    config.recalculateDerivedFields()
                }
            }
            is GeoConfig.PolePole -> {
                if (config.n > 1) {
                    config.n--
                    config.recalculateDerivedFields()
                }
            }
            else -> { /* No action for Sholom or Uninitialized */ }
        }
        return config // Return the (potentially modified) object
    }

    /**
     * Resets the GeoConfig to its default state for DP-DP, P-DP, P-P types.
     * Modifies the passed config object directly if mutable, otherwise returns a new instance.
     * NOTE: Since GeoConfig data classes are technically immutable regarding 'n' etc. if declared as val,
     * this function should ideally return a *new* reset instance. Let's adjust GeoConfig fields to be 'var'.
     * (Done in GeoConfig.kt previously).
     *
     * @param config The current GeoConfig object (must be DP-DP, P-DP, or P-P).
     * @return The reset GeoConfig object. Returns the original object if type is wrong.
     */
    fun handleResetAction(config: GeoConfig): GeoConfig {
        return when (config) {
            is GeoConfig.DipoleDipole -> GeoConfig.DipoleDipole() // Return new instance with defaults
            is GeoConfig.PoleDipole -> GeoConfig.PoleDipole()   // Return new instance with defaults
            is GeoConfig.PolePole -> GeoConfig.PolePole()     // Return new instance with defaults
            // Reset for Sholom could mean going to index 0, handle if needed
            is GeoConfig.Sholom -> config // Or implement Sholom reset logic (e.g., config.currentIndex = 0)
            is GeoConfig.Uninitialized -> config // No reset for uninitialized
        }
        // Let's refine Reset based on spec 6.3.2: Reset sets specific values and recalculates.
        // We will modify the *existing* object now that fields are 'var'.
        when(config) {
            is GeoConfig.DipoleDipole -> {
                config.tx0 = 0.0
                config.distance = 10.0
                config.n = 1
                config.recalculateDerivedFields()
                config // Return modified object
            }
            is GeoConfig.PoleDipole -> {
                config.tx1 = 0.0
                config.distance = 10.0
                config.n = 1
                config.recalculateDerivedFields()
                config // Return modified object
            }
            is GeoConfig.PolePole -> {
                config.tx1 = 0.0
                config.distance = 10.0
                config.n = 1
                config.recalculateDerivedFields()
                config // Return modified object
            }
            else -> config // No reset action defined for Sholom/Uninitialized here
        }
        return config
    }

    /**
     * Parses the predefined Schlumberger geometry string array entry.
     * Example entry: "1.5,0.25"
     * @param entry The string entry from the array.
     * @return A Pair of (AB/2, MN/2) as Doubles, or null if parsing fails.
     */
    fun parseSholomGeometryEntry(entry: String): Pair<Double, Double>? {
        return try {
            val parts = entry.split(',')
            if (parts.size == 2) {
                val ab2 = parts[0].trim().toDoubleOrNull()
                val mn2 = parts[1].trim().toDoubleOrNull()
                if (ab2 != null && mn2 != null) {
                    Pair(ab2, mn2)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

}