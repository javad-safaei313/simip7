package com.simip.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents the currently active project.
 * Parcelable allows passing this object between components (e.g., Activities/Fragments) if needed.
 */
@Parcelize
data class Project(
    val name: String,
    val type: ProjectType // Using the Enum defined earlier

) : Parcelable {
    // You can add helper functions here if needed, for example,
    // to generate a display string or check validity.
}