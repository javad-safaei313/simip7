package com.simip.data.model

/**
 * Represents the parsed data from a complete measurement cycle, received via the
 * "Data,Id,SetAmper,Stack,Time,not,VolttageBat,temprature,Contact,MeasureAmper,MeasureSP,deltav,Ip1,...,Ip20"
 * message from the device.
 *
 * This class holds the raw data received. Calculations like Roh and average IP
 * will be performed based on this data before saving to the Measurement entity.
 */
data class MeasurementPacket(
    val id: Long,                   // شناسه اندازه‌گیری (فرض می‌کنیم عددی است)
    val setAmper: Int,              // جریان تنظیم شده (mA) - from SetConfig, confirmed in packet
    val stack: Int,                 // تعداد تکرار تنظیم شده - from SetConfig, confirmed in packet
    val time: Float,                // زمان اندازه‌گیری تنظیم شده (s) - from SetConfig, confirmed in packet
    val not: String,                // پارامتر رزرو شده/نامشخص
    val voltageBat: Float,          // ولتاژ باتری (V) در زمان اندازه‌گیری
    val temperature: Float,         // دما (°C) در زمان اندازه‌گیری
    val contact: Float,             // مقاومت اتصالات (kΩ)
    val measureAmper: Float,        // جریان واقعی اندازه‌گیری شده (mA)
    val measureSP: Float,           // پتانسیل خودزا (mV)
    val deltaV: Float,              // پتانسیل اصلی (dV) (mV)
    val ipDecayWindowValues: List<Float> // لیست مقادیر Ip1 تا Ip20 (mV/V)
) {
    init {
        // Ensure the IP decay list always contains exactly 20 values, even if parsing had issues
        // This check might be better placed in the parsing logic itself.
        // require(ipDecayWindowValues.size == 20) { "MeasurementPacket must contain exactly 20 IP decay values." }
    }

    /**
     * Calculates the average IP value from the decay curve data.
     * @return The average IP in mV/V, or 0f if the list is somehow empty.
     */
    fun calculateAverageIp(): Float {
        return if (ipDecayWindowValues.isNotEmpty()) {
            ipDecayWindowValues.average().toFloat()
        } else {
            0f
        }
    }

    companion object {
        const val EXPECTED_IP_DECAY_POINTS = 20
    }
}