package com.simip.ui.dlist

import android.util.Log
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simip.R
import com.simip.data.model.Measurement
import com.simip.data.model.ProjectType
import com.simip.databinding.ItemMeasurementBinding // Import generated ViewBinding class
import com.simip.util.Constants
import java.util.Locale

class MeasurementAdapter(
    private val onItemClick: (Measurement) -> Unit // Lambda for item click callback
    // Add callback for edit request if implementing inline editing:
    // private val onEditRequest: (measurementId: Int, field: String, currentValue: String) -> Unit
) : ListAdapter<Measurement, MeasurementAdapter.MeasurementViewHolder>(MeasurementDiffCallback()) {

    private var currentProjectType: ProjectType? = null

    /**
     * Sets the current project type to correctly manage column visibility.
     * Must be called when the project changes or adapter is created.
     */
    fun setProjectType(projectType: ProjectType?) {
        val changed = currentProjectType != projectType
        currentProjectType = projectType
        if (changed) {
            // Notify adapter to rebind all visible items if project type changes,
            // as column visibility depends on it.
            notifyDataSetChanged() // Simple way, might flicker. Use payload for better updates if needed.
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeasurementViewHolder {
        val binding = ItemMeasurementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MeasurementViewHolder(binding, parent.context)
    }

    override fun onBindViewHolder(holder: MeasurementViewHolder, position: Int) {
        val measurement = getItem(position)
        holder.bind(measurement, position, currentProjectType, onItemClick)
    }

    // --- ViewHolder ---
    class MeasurementViewHolder(
        private val binding: ItemMeasurementBinding,
        private val context: Context // Needed for colors
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            measurement: Measurement,
            position: Int, // Position in the currently displayed list
            projectType: ProjectType?,
            onItemClick: (Measurement) -> Unit
        ) {
            binding.root.setOnClickListener { onItemClick(measurement) }

            // Set Row Background Color (Odd/Even based on soundingProfileId)
            val backgroundColorRes = if (measurement.soundingProfileId % 2 == 0) {
                R.color.simip_dlist_row_even_background // Use the specific even color
            } else {
                // Use default surface color (transparent might make selectableItemBackground better)
                android.R.color.transparent // Let selectableItemBackground handle odd rows
                // Or R.color.simip_dlist_row_odd_background if defined and different from surface
            }
            binding.root.setBackgroundColor(ContextCompat.getColor(context, backgroundColorRes))


            // --- Bind Common Data ---
            binding.itemTvNumber.text = (position + 1).toString() // Display number starting from 1
            // Format values using locale-safe method and placeholder
            binding.itemTvDv.text = measurement.meas_potential_mv.format(3)
            binding.itemTvI.text = measurement.meas_current_ma.format(1)
            binding.itemTvRoh.text = measurement.calc_roh_omm.format(1)
            binding.itemTvIp.text = measurement.calc_ip_mvv.format(1)
            binding.itemTvContact.text = measurement.device_contact_kohm?.format(1) ?: Constants.DEFAULT_TEXT_VALUE_PLACEHOLDER


            // --- Bind Geometry Data (Control Visibility) ---
            val isSholom = projectType == ProjectType.SHOLOM
            binding.itemTvSholomAb2.isVisible = isSholom
            binding.itemTvSholomMn2.isVisible = isSholom
            binding.itemTvSholomX0.isVisible = isSholom

            val isOther = !isSholom && projectType != null
            binding.itemTvOtherTx0.isVisible = isOther
            binding.itemTvOtherTx1.isVisible = isOther
            binding.itemTvOtherRx0.isVisible = isOther
            binding.itemTvOtherRx1.isVisible = isOther

            if (isSholom) {
                binding.itemTvSholomAb2.text = measurement.geom_ab_2?.format(1) ?: Constants.DEFAULT_TEXT_VALUE_PLACEHOLDER
                binding.itemTvSholomMn2.text = measurement.geom_mn_2?.format(2) ?: Constants.DEFAULT_TEXT_VALUE_PLACEHOLDER
                binding.itemTvSholomX0.text = measurement.geom_x0?.format(1) ?: Constants.DEFAULT_TEXT_VALUE_PLACEHOLDER
            } else if (isOther) {
                binding.itemTvOtherTx0.text = measurement.geom_tx0?.format(1) ?: context.getString(R.string.text_infinity_negative) // Handle -inf
                binding.itemTvOtherTx1.text = measurement.geom_tx1?.format(1) ?: Constants.DEFAULT_TEXT_VALUE_PLACEHOLDER
                binding.itemTvOtherRx0.text = measurement.geom_rx0?.format(1) ?: Constants.DEFAULT_TEXT_VALUE_PLACEHOLDER
                binding.itemTvOtherRx1.text = measurement.geom_rx1?.format(1) ?: context.getString(R.string.text_infinity_positive) // Handle +inf
            }

            // --- Temporary Edit Handling (TODO) ---
            setupTemporaryEditListeners(measurement)

        } // End of bind function

        private fun setupTemporaryEditListeners(measurement: Measurement) {
            // TODO: Implement temporary editing for Roh and IP
            // Example: Set onClickListener for Roh TextView
            binding.itemTvRoh.setOnClickListener {
                Log.d("AdapterEdit", "Roh clicked for ID: ${measurement.dbId}, Current: ${binding.itemTvRoh.text}")
                // 1. Get current value
                val currentValue = binding.itemTvRoh.text.toString()
                // 2. Show a small dialog (e.g., AlertDialog with EditText) to get new value
                showEditDialog("Edit Roh (Ωm)", currentValue) { newValueString ->
                    // 3. Validate the new value (must be a number)
                    val newValue = newValueString.toDoubleOrNull()
                    if (newValue != null) {
                        // 4. Update the TextView directly (temporary)
                        binding.itemTvRoh.text = newValue.format(1) // Update UI
                        // 5. Trigger graph update (How? Need callback to Fragment/ViewModel or direct data manipulation if adapter holds editable data)
                        // This requires more complex state management. Simplest is a callback.
                        Log.w("AdapterEdit","Need mechanism to trigger graph update after temporary edit.")
                        // Option: Trigger a general 'data updated' event via a callback.
                    } else {
                        // Show error if input is invalid
                        Log.w("AdapterEdit","Invalid input for Roh: $newValueString")
                    }
                }
            }

            binding.itemTvIp.setOnClickListener {
                Log.d("AdapterEdit", "IP clicked for ID: ${measurement.dbId}, Current: ${binding.itemTvIp.text}")
                val currentValue = binding.itemTvIp.text.toString()
                showEditDialog("Edit IP (mV/V)", currentValue) { newValueString ->
                    val newValue = newValueString.toFloatOrNull()
                    if (newValue != null) {
                        binding.itemTvIp.text = newValue.format(1)
                        Log.w("AdapterEdit","Need mechanism to trigger graph update after temporary edit.")
                    } else {
                        Log.w("AdapterEdit","Invalid input for IP: $newValueString")
                    }
                }
            }
        }

        // TODO: Replace with a proper styled AlertDialog or custom DialogFragment
        private fun showEditDialog(title: String, currentValue: String, onConfirm: (String) -> Unit) {
            val input = android.widget.EditText(context)
            input.setText(currentValue)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED

            androidx.appcompat.app.AlertDialog.Builder(context) // Use AppCompat for theme consistency
                .setTitle(title)
                .setView(input)
                .setPositiveButton(R.string.button_ok) { dialog, _ ->
                    onConfirm(input.text.toString())
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.button_cancel) { dialog, _ ->
                    dialog.cancel()
                }
                .show()
        }


        // Helper extension functions for formatting
        private fun Float.format(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)
        private fun Double.format(digits: Int): String = String.format(Locale.US, "%.${digits}f", this)
        private fun Float?.format(digits: Int): String? = this?.format(digits)
        private fun Double?.format(digits: Int): String? = this?.format(digits)
    } // End of ViewHolder


    // --- DiffUtil Callback ---
    class MeasurementDiffCallback : DiffUtil.ItemCallback<Measurement>() {
        override fun areItemsTheSame(oldItem: Measurement, newItem: Measurement): Boolean {
            return oldItem.dbId == newItem.dbId // Use unique ID
        }

        override fun areContentsTheSame(oldItem: Measurement, newItem: Measurement): Boolean {
            // If temporary editing modifies the item in the list, this needs to compare edited values too.
            // For now, assume list data is immutable from ViewModel perspective.
            return oldItem == newItem
        }
    }
}