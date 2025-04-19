package com.simip.data.db // <-- پکیج صحیح

import androidx.room.TypeConverter
import com.simip.data.model.ProjectType

class Converters {
    @TypeConverter
    fun fromProjectType(value: ProjectType?): String? {
        return value?.name // ذخیره نام Enum به صورت String
    }

    @TypeConverter
    fun toProjectType(value: String?): ProjectType? {
        return value?.let {
            try {
                ProjectType.valueOf(it) // تبدیل String به Enum
            } catch (e: IllegalArgumentException) {
                null // اگر نامعتبر بود
            }
        }
    }
}