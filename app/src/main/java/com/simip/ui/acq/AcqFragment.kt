package com.simip.ui.acq

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels // Use activityViewModels to share with MainActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.simip.R
import com.simip.data.model.GeoConfig
import com.simip.databinding.FragmentAcqBinding // Import generated ViewBinding class
import com.simip.util.Constants
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
/**
 * Fragment for the Acquisition (Acq) tab.
 * Displays measurement settings, geometry controls, measured values, and IP decay graph.
 * Interacts with AcqViewModel.
 */
class AcqFragment : Fragment() {

    private val TAG = "AcqFragment"
    private var _binding: FragmentAcqBinding? = null
    private val binding get() = _binding!! // Non-null assertion operator

    // Use activityViewModels to share the ViewModel instance with MainActivity and other fragments if needed
    // especially for accessing the current project state easily.
    private val viewModel: AcqViewModel by activityViewModels()
    // If AcqViewModel doesn't need shared state or direct access to MainActivity's project,
    // you could use private val viewModel: AcqViewModel by viewModels() instead,
    // but sharing seems more appropriate here.

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "onCreateView")
        _binding = FragmentAcqBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        setupUIListeners()
        setupSpinners()
        setupIpDecayChart()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        binding.chartIpDecay.data?.clearValues() // Clear chart data
        _binding = null // Avoid memory leaks
    }

    // --- Setup Functions ---

    private fun setupUIListeners() {
        Log.d(TAG, "Setting up UI listeners")
        // Settings Listeners
        binding.seekBarCurrent.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.updateCurrentSetting(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnApply.setOnClickListener { viewModel.applySettings() }

        // Geometry Control Button Listeners
        binding.btnReset.setOnClickListener { viewModel.handleGeometryReset() }
        binding.btnNext.setOnClickListener { viewModel.handleGeometryNext() }
        binding.btnPrevious.setOnClickListener { viewModel.handleGeometryPrevious() }

        // Measurement Control Button Listeners
        binding.btnStart.setOnClickListener { viewModel.startMeasurement() }
        binding.btnSave.setOnClickListener { viewModel.saveMeasurement() }

        // Geometry EditText Listeners (using TextWatcher)
        // Sholom
        binding.etSholomX0.addTextChangedListener(createTextWatcher { s ->
            val value = s.toString().toDoubleOrNull() ?: Constants.DEFAULT_X0
            viewModel.updateGeometryValue { config ->
                (config as? GeoConfig.Sholom)?.copy(x0 = value) ?: config
            }
        })
        // Add watchers for AB/2 and MN/2 if they become editable later

        // Other Geometries (Distance is editable, tx0/tx1 might be)
        binding.etOtherDistance.addTextChangedListener(createTextWatcher { s ->
            val value = s.toString().toDoubleOrNull() ?: Constants.DEFAULT_DISTANCE
            viewModel.updateGeometryValue { config ->
                when(config) {
                    is GeoConfig.DipoleDipole -> config.copy(distance = value)
                    is GeoConfig.PoleDipole -> config.copy(distance = value)
                    is GeoConfig.PolePole -> config.copy(distance = value)
                    else -> config
                }
            }
        })
        binding.etOtherTX0.addTextChangedListener(createTextWatcher { s ->
            val value = s.toString().toDoubleOrNull() ?: 0.0
            viewModel.updateGeometryValue { config ->
                if (config is GeoConfig.DipoleDipole) config.copy(tx0 = value) else config
            }
        })
        binding.etOtherTX1.addTextChangedListener(createTextWatcher { s ->
            val value = s.toString().toDoubleOrNull() ?: 0.0
            viewModel.updateGeometryValue { config ->
                when(config) {
                    is GeoConfig.PoleDipole -> config.copy(tx1 = value)
                    is GeoConfig.PolePole -> config.copy(tx1 = value)
                    else -> config
                }
            }
        })
        // RX0/RX1 are usually calculated, no listeners needed unless made editable
    }

    private fun setupSpinners() {
        // Setup Time Spinner
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.time_options_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerTime.adapter = adapter
        }
        binding.spinnerTime.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedTime = parent?.getItemAtPosition(position)?.toString()?.toFloatOrNull()
                selectedTime?.let { viewModel.updateTimeSetting(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup Stack Spinner
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.stack_options_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerStack.adapter = adapter
        }
        binding.spinnerStack.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedStack = parent?.getItemAtPosition(position)?.toString()?.toIntOrNull()
                selectedStack?.let { viewModel.updateStackSetting(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }


    private fun setupIpDecayChart() {
        binding.chartIpDecay.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            legend.isEnabled = false // No legend needed for single line

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(true)
            xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_axis_color) // Use color resource
            xAxis.axisLineColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_axis_color)
            xAxis.granularity = 80f // Display ticks every 80ms

            axisRight.isEnabled = false // Disable right Y axis

            axisLeft.setDrawGridLines(true)
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_axis_color)
            axisLeft.axisLineColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_axis_color)
            axisLeft.axisMinimum = 0f // Start Y axis from 0 or auto? Let's try 0. Adjust if needed.

            // Initial empty data
            data = LineData()
            invalidate()
        }
    }

    // --- ViewModel Observation ---

    private fun observeViewModel() {
        Log.d(TAG, "Setting up ViewModel observers")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Settings
                launch { viewModel.currentSetting.collect { updateCurrentUI(it) } }
                launch { viewModel.timeSetting.collect { updateTimeUI(it) } }
                launch { viewModel.stackSetting.collect { updateStackUI(it) } }

                // Observe Geometry Configuration
                launch { viewModel.geoConfig.collect { updateGeometryUI(it) } }

                // Observe Measured Values
                launch { viewModel.measuredDV.collect { updateTextView(binding.tvMeasuredDV, it, "%.3f") } }
                launch { viewModel.measuredI.collect { updateTextView(binding.tvMeasuredI, it, "%.1f") } }
                launch { viewModel.calculatedIP.collect { updateTextView(binding.tvMeasuredIP, it, "%.1f") } }
                launch { viewModel.calculatedRoh.collect { updateTextView(binding.tvMeasuredRoh, it, "%.1f") } }

                // Observe IP Decay Data for Graph
                launch { viewModel.ipDecayData.collect { updateIpDecayChartData(it) } }

                // Observe Measurement State
                launch { viewModel.isMeasuring.collect { updateMeasurementStateUI(it) } }
                launch { viewModel.measurementProgressPercent.collect { updateProgressBar(it) } }

                // Observe Save Button State
                launch { viewModel.isSaveEnabled.collect { binding.btnSave.isEnabled = it } }

                // Observe Geometry Lock State
                launch { viewModel.isGeometryLocked.collect { updateGeometryLockUI(it) } }

            }
        }
    }

    // --- UI Update Functions ---

    private fun updateCurrentUI(current: Int) {
        binding.tvCurrentValue.text = "$current mA"
        if (binding.seekBarCurrent.progress != current) { // Avoid infinite loop from listener
            binding.seekBarCurrent.progress = current
        }
    }

    private fun updateTimeUI(time: Float) {
        val timeString = time.toInt().toString() // Get integer part for matching spinner value
        val adapter = binding.spinnerTime.adapter
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == timeString) {
                if (binding.spinnerTime.selectedItemPosition != i) {
                    binding.spinnerTime.setSelection(i)
                }
                break
            }
        }
    }

    private fun updateStackUI(stack: Int) {
        val stackString = stack.toString()
        val adapter = binding.spinnerStack.adapter
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i).toString() == stackString) {
                if (binding.spinnerStack.selectedItemPosition != i) {
                    binding.spinnerStack.setSelection(i)
                }
                break
            }
        }
    }

    private fun updateGeometryUI(geoConfig: GeoConfig) {
        Log.d(TAG,"Updating Geometry UI for type: ${geoConfig::class.java.simpleName}")
        binding.layoutGeometrySholom.isVisible = geoConfig is GeoConfig.Sholom
        binding.layoutGeometryOther.isVisible = geoConfig !is GeoConfig.Sholom && geoConfig !is GeoConfig.Uninitialized

        when (geoConfig) {
            is GeoConfig.Sholom -> {
                // Update Sholom fields if they aren't focused (to avoid cursor jump)
                if (!binding.etSholomAB2.isFocused) binding.etSholomAB2.setText(geoConfig.ab_2.format(1))
                if (!binding.etSholomMN2.isFocused) binding.etSholomMN2.setText(geoConfig.mn_2.format(2))
                if (!binding.etSholomX0.isFocused) binding.etSholomX0.setText(geoConfig.x0.format(1))
            }
            is GeoConfig.DipoleDipole -> {
                updateOtherGeometryField(binding.etOtherTX0, binding.tvOtherTX0Infinity, geoConfig.tx0?.format(1), false)
                updateOtherGeometryField(binding.etOtherTX1, null, geoConfig.tx1.format(1), true) // tx1 is editable
                updateOtherGeometryField(binding.etOtherRX0, null, geoConfig.rx0.format(1), false)
                updateOtherGeometryField(binding.etOtherRX1, binding.tvOtherRX1Infinity, geoConfig.rx1?.format(1), false)
                updateOtherGeometryField(binding.etOtherDistance, null, geoConfig.distance.format(1), true) // distance is editable
            }
            is GeoConfig.PoleDipole -> {
                updateOtherGeometryField(binding.etOtherTX0, binding.tvOtherTX0Infinity, null, false) // TX0 is -inf
                updateOtherGeometryField(binding.etOtherTX1, null, geoConfig.tx1.format(1), true) // tx1 is editable
                updateOtherGeometryField(binding.etOtherRX0, null, geoConfig.rx0.format(1), false)
                updateOtherGeometryField(binding.etOtherRX1, binding.tvOtherRX1Infinity, geoConfig.rx1?.format(1), false) // RX1 is finite
                updateOtherGeometryField(binding.etOtherDistance, null, geoConfig.distance.format(1), true) // distance is editable
            }
            is GeoConfig.PolePole -> {
                updateOtherGeometryField(binding.etOtherTX0, binding.tvOtherTX0Infinity, null, false) // TX0 is -inf
                updateOtherGeometryField(binding.etOtherTX1, null, geoConfig.tx1.format(1), true) // tx1 is editable
                updateOtherGeometryField(binding.etOtherRX0, null, geoConfig.rx0.format(1), false)
                updateOtherGeometryField(binding.etOtherRX1, binding.tvOtherRX1Infinity, null, false) // RX1 is +inf
                updateOtherGeometryField(binding.etOtherDistance, null, geoConfig.distance.format(1), true) // distance is editable
            }
            is GeoConfig.Uninitialized -> {
                // Clear fields or show placeholder? Handled by visibility changes.
            }
        }
    }

    // Helper to update geometry fields, handling infinity display
    private fun updateOtherGeometryField(editText: com.google.android.material.textfield.TextInputEditText,
                                         infinityTextView: TextView?,
                                         value: String?,
                                         isEditable: Boolean) {
        val isInfinity = (infinityTextView != null && value == null) // Value is null signifies infinity here

        editText.isVisible = !isInfinity
        editText.isEnabled = isEditable // Enable/disable based on spec
        infinityTextView?.isVisible = isInfinity
        infinityTextView?.isEnabled = false // Infinity display is never editable

        if (!isInfinity && value != null && !editText.isFocused) {
            editText.setText(value)
        }
        // Set correct hint/label based on editability? Or handle via styles.
    }


    // Helper to update TextView, handling null values
    private fun <T : Number> updateTextView(textView: TextView, value: T?, format: String) {
        textView.text = value?.let { String.format(Locale.US, format, it) } ?: Constants.DEFAULT_TEXT_VALUE_PLACEHOLDER
    }


    private fun updateMeasurementStateUI(isMeasuring: Boolean) {
        binding.btnStart.isEnabled = !isMeasuring
        // Keep Save enabled status separate (depends on newDataAvailable)
        // binding.btnSave.isEnabled = !isMeasuring && viewModel.isSaveEnabled.value // Combine checks? Viewmodel handles save state.
        binding.btnApply.isEnabled = !isMeasuring
        binding.btnReset.isEnabled = !isMeasuring
        binding.btnNext.isEnabled = !isMeasuring
        binding.btnPrevious.isEnabled = !isMeasuring
        // Lock/Unlock settings while measuring? Optional.
        binding.seekBarCurrent.isEnabled = !isMeasuring
        binding.spinnerTime.isEnabled = !isMeasuring
        binding.spinnerStack.isEnabled = !isMeasuring
    }

    private fun updateGeometryLockUI(isLocked: Boolean) {
        val enable = !isLocked
        // Sholom
        binding.etSholomAB2.isEnabled = enable
        binding.etSholomMN2.isEnabled = enable
        binding.etSholomX0.isEnabled = enable
        // Other (only enable editable fields)
        binding.etOtherTX0.isEnabled = enable && binding.etOtherTX0.isVisible // Only if not infinity
        binding.etOtherTX1.isEnabled = enable // Always editable for P-DP/P-P, calculated for DP-DP but depends on tx0
        binding.etOtherDistance.isEnabled = enable

        // Also disable geometry buttons if locked
        binding.btnReset.isEnabled = enable
        binding.btnNext.isEnabled = enable
        binding.btnPrevious.isEnabled = enable

        // Re-apply enable state for Start/Save/Apply based on measurement state if needed
        if (isLocked) { // If locking, ensure Start is disabled
            binding.btnStart.isEnabled = false
            binding.btnApply.isEnabled = false
        } else {
            // If unlocking, re-evaluate button states based on isMeasuring etc.
            updateMeasurementStateUI(viewModel.isMeasuring.value)
        }
    }

    private fun updateProgressBar(progress: Int) {
        binding.progressBar.isVisible = progress > 0 && progress < 100 && viewModel.isMeasuring.value
        binding.progressBar.progress = progress
    }

    private fun updateIpDecayChartData(dataPoints: List<Pair<Float, Float>>) {
        if (!isAdded || _binding == null) return // Check if fragment is still attached

        val entries = dataPoints.map { Entry(it.first, it.second) } // Pair(timeMs, ipValue)

        val lineDataSet: LineDataSet
        if (binding.chartIpDecay.data != null && binding.chartIpDecay.data.dataSetCount > 0) {
            lineDataSet = binding.chartIpDecay.data.getDataSetByIndex(0) as LineDataSet
            lineDataSet.values = entries
            binding.chartIpDecay.data.notifyDataChanged()
            binding.chartIpDecay.notifyDataSetChanged()
        } else {
            lineDataSet = LineDataSet(entries, "IP Decay")
            lineDataSet.color = ContextCompat.getColor(requireContext(), R.color.simip_graph_ip_decay_color)
            lineDataSet.lineWidth = resources.getDimension(R.dimen.graph_line_thickness)
            lineDataSet.setDrawValues(false) // Don't draw values on points
            lineDataSet.setDrawCircles(true)
            lineDataSet.setCircleColor(lineDataSet.color)
            lineDataSet.circleRadius = resources.getDimension(R.dimen.graph_point_radius) / 2f // Smaller circles
            lineDataSet.setDrawCircleHole(false)
            lineDataSet.setDrawHighlightIndicators(false)
            lineDataSet.mode = LineDataSet.Mode.LINEAR // Or CUBIC_BEZIER

            val lineData = LineData(lineDataSet)
            binding.chartIpDecay.data = lineData
        }

        // Update chart view
        binding.chartIpDecay.invalidate()
        binding.chartIpDecay.animateX(500) // Optional animation
    }

    // --- Utility ---

    // Simple TextWatcher factory
    private fun createTextWatcher(onTextChanged: (s: CharSequence?) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onTextChanged(s)
            }
            override fun afterTextChanged(s: Editable?) {}
        }
    }

    // Format Double to String with specified decimal places
    private fun Double?.format(digits: Int): String {
        return this?.let { String.format(Locale.US, "%.${digits}f", it) } ?: ""
    }

    // --- Companion Object ---
    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         */
        @JvmStatic
        fun newInstance() = AcqFragment()
        // Add arguments here if needed:
        // Bundle().apply {
        //     putString(ARG_PARAM1, param1)
        // }
    }
}