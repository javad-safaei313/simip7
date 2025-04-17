package com.simip.ui.dlist

import com.github.mikephil.charting.charts.ScatterChart
import java.util.Locale
import com.github.mikephil.charting.data.ScatterDataSet
import kotlinx.coroutines.flow.distinctUntilChanged // <--- این خط را اضافه کنید
import kotlinx.coroutines.flow.collectLatest
import android.graphics.Color // Using Color class directly for chart
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels // Share ViewModel with Activity/other fragments
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.* // Entry, LineData, ScatterData, CombinedData
import com.github.mikephil.charting.formatter.ValueFormatter
import com.simip.R
import com.simip.data.model.Measurement // Needed for Adapter type hint? No, adapter handles it.
import com.simip.databinding.FragmentDlistBinding // Import generated ViewBinding class
import com.simip.ui.main.MainViewModel // Access MainViewModel for project state if needed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.pow

/**
 * Fragment for the Data List (DList) tab.
 * Displays a list of measurements, allows filtering by sounding/profile,
 * and shows a Roh/IP graph. Interacts with DListViewModel.
 */
class DListFragment : Fragment() {

    private val TAG = "DListFragment"
    private var _binding: FragmentDlistBinding? = null
    private val binding get() = _binding!!

    // Use activityViewModels to easily access the same instance across fragments/activity
    private val viewModel: DListViewModel by activityViewModels()
    // private val mainViewModel: MainViewModel by activityViewModels() // Access if needed e.g. for project state directly

    private lateinit var measurementAdapter: MeasurementAdapter
    private var spinnerAdapter: ArrayAdapter<String>? = null
    private var currentSpinnerData: List<Pair<Int, String>> = emptyList() // Store data used in spinner

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "onCreateView")
        _binding = FragmentDlistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        setupRecyclerView()
        setupSpinner()
        setupRohIpChart()
        observeViewModel()

        // Trigger initial data load or UI update based on ViewModel state
        // ViewModel might already have data if loaded by MainActivity when project opened
        // Let observers handle UI updates based on current ViewModel state.
    }

    override fun onResume() {
        super.onResume()
        // Refresh data or UI if needed when tab becomes visible again
        // ViewModel should hold the state, observers will update UI.
        // Force a refresh? Or rely on MainActivity calling loadDataForProject?
        // Let's assume MainActivity handles calling loadDataForProject appropriately.
        Log.d(TAG, "onResume")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        binding.recyclerViewDataList.adapter = null // Clean up adapter reference
        binding.chartRohIp.data = null // Clear chart data
        _binding = null // Avoid memory leaks
    }

    // --- Setup Functions ---

    private fun setupRecyclerView() {
        Log.d(TAG, "Setting up RecyclerView")
        measurementAdapter = MeasurementAdapter(
            // Pass a lambda for item click if needed for future features (e.g., showing details)
            onItemClick = { measurement ->
                Log.d(TAG, "Measurement item clicked: ID=${measurement.dbId}")
                // Handle item click action if necessary
            }
        )
        binding.recyclerViewDataList.apply {
            adapter = measurementAdapter
            layoutManager = LinearLayoutManager(requireContext())
            // Add ItemDecoration for spacing or dividers if desired
        }
    }

    private fun setupSpinner() {
        Log.d(TAG, "Setting up Spinner")
        spinnerAdapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item)
        spinnerAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSoundingProfile.adapter = spinnerAdapter

        binding.spinnerSoundingProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < currentSpinnerData.size) {
                    val selectedPair = currentSpinnerData[position]
                    viewModel.selectSoundingProfile(selectedPair.first) // Pass the ID to ViewModel
                    Log.d(TAG,"Spinner item selected: Pos=$position, ID=${selectedPair.first}, Name=${selectedPair.second}")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupRohIpChart() {
        Log.d(TAG, "Setting up Roh/IP Chart")
        binding.chartRohIp.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT) // Match fragment background

            // Configure Legend
            legend.isEnabled = true
            legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
            legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
            legend.orientation = Legend.LegendOrientation.VERTICAL
            legend.setDrawInside(false) // Draw legend outside chart area
            legend.textColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_axis_color)

            // Configure X Axis (Distance / AB/2) - Logarithmic
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(true)
            xAxis.textColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_axis_color)
            xAxis.axisLineColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_axis_color)
            xAxis.valueFormatter = LogarithmicAxisFormatter() // Custom formatter for log scale
            // Granularity etc. might need adjustment based on data range

            // Configure Left Y Axis (Roh) - Logarithmic
            axisLeft.setDrawGridLines(true)
            axisLeft.textColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_roh_color) // Red color
            axisLeft.axisLineColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_roh_color)
            axisLeft.valueFormatter = LogarithmicAxisFormatter() // Custom formatter for log scale
            // axisLeft.axisMinimum = ... // Set min/max based on data or fixed range? Auto for now.

            // Configure Right Y Axis (IP) - Linear
            axisRight.isEnabled = true
            axisRight.setDrawGridLines(false) // Avoid grid clash with left axis
            axisRight.textColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_ip_color) // Blue color
            axisRight.axisLineColor = ContextCompat.getColor(requireContext(), R.color.simip_graph_ip_color)
            axisRight.axisMinimum = 0f // IP usually starts from 0
            // axisRight.valueFormatter = ... // Default linear formatter is likely okay

            // Initial empty data
            data = CombinedData() // Use CombinedData for dual axis
            invalidate()
        }
    }

    // --- ViewModel Observation ---

    private fun observeViewModel() {
        Log.d(TAG, "Setting up ViewModel observers")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Observe loading state
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.progressBarDList.isVisible = isLoading
                        binding.hsvDataListContainer.alpha = if (isLoading) 0.5f else 1.0f // Dim UI while loading
                        binding.chartRohIp.alpha = if (isLoading) 0.5f else 1.0f
                    }
                }

                // Observe spinner data
                launch {
                    viewModel.soundingProfileIds.collect { ids ->
                        Log.d(TAG, "Updating spinner data with ${ids.size} items.")
                        currentSpinnerData = ids
                        spinnerAdapter?.clear()
                        spinnerAdapter?.addAll(ids.map { it.second }) // Add only display names
                        spinnerAdapter?.notifyDataSetChanged()
                        // Restore selection after data update
                        updateSpinnerSelection(viewModel.selectedSoundingProfileId.value)
                    }
                }

                // Observe selected spinner ID (to ensure spinner UI reflects state)
                launch {
                    viewModel.selectedSoundingProfileId.collect { selectedId ->
                        updateSpinnerSelection(selectedId)
                    }
                }


                // Observe measurement list for RecyclerView
                launch {
                             // Using distinctUntilChanged might prevent unnecessary list updates if the list object itself doesn't change
                    viewModel.measurementList.collectLatest { list ->
                        Log.d(TAG, "Submitting ${list.size} items to RecyclerView adapter.")
                        measurementAdapter.submitList(list)
                        // Scroll to top when list changes?
                        if (list.isNotEmpty()) { // اضافه کردن بررسی برای جلوگیری از اسکرول لیست خالی
                            binding.recyclerViewDataList.scrollToPosition(0)
                        }

                    }
                }

                // Observe graph data
                launch {
                    viewModel.graphData.collect { data ->
                        Log.d(TAG, "Updating graph with ${data.size} points.")
                        updateRohIpChartData(data)
                    }
                }
            }
        }
    }

    // --- UI Update Functions ---

    private fun updateSpinnerSelection(selectedId: Int) {
        val position = currentSpinnerData.indexOfFirst { it.first == selectedId }.coerceAtLeast(0)
        if (binding.spinnerSoundingProfile.selectedItemPosition != position) {
            Log.d(TAG,"Setting spinner selection to position $position for ID $selectedId")
            binding.spinnerSoundingProfile.setSelection(position, false) // Set selection without triggering listener again
        }
    }


    private fun updateRohIpChartData(graphPoints: List<Triple<Float, Float?, Float?>>) {
        if (!isAdded || _binding == null || context == null) return // Ensure fragment is attached and context available

        val rohEntries = mutableListOf<Entry>()
        val ipEntries = mutableListOf<Entry>()

        graphPoints.forEach { point ->
            val xVal = point.first // Distance or AB/2
            val rohVal = point.second
            val ipVal = point.third

            // Use log10(x) for plotting on logarithmic X axis
            val xPlotVal = log10(xVal)

            // Prepare Roh entry (use log10(roh) for logarithmic Y axis)
            rohVal?.let {
                if (it > 0) { // Can only plot positive values on log scale
                    rohEntries.add(Entry(xPlotVal, log10(it)))
                }
            }

            // Prepare IP entry (plot directly on linear Y axis)
            ipVal?.let {
                ipEntries.add(Entry(xPlotVal, it))
            }
        }

        // Create datasets
        val rohDataSet = ScatterDataSet(rohEntries, "Roh (Ωm)").apply {

            setScatterShape(ScatterChart.ScatterShape.CIRCLE)
            color = ContextCompat.getColor(requireContext(), R.color.simip_graph_roh_color)
            scatterShapeSize = resources.getDimension(R.dimen.graph_point_radius) * 1.5f // Slightly larger points
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT // Link to left (Roh) axis
        }

        val ipDataSet = LineDataSet(ipEntries, "IP (mV/V)").apply {
            color = ContextCompat.getColor(requireContext(), R.color.simip_graph_ip_color)
            lineWidth = resources.getDimension(R.dimen.graph_line_thickness)
            setDrawValues(false)
            setDrawCircles(true)
            setCircleColor(this.color)
            circleRadius = resources.getDimension(R.dimen.graph_point_radius) / 2f
            setDrawCircleHole(false)
            mode = LineDataSet.Mode.LINEAR // Or CUBIC_BEZIER
            axisDependency = YAxis.AxisDependency.RIGHT // Link to right (IP) axis
            setDrawHighlightIndicators(false)
        }

        // Create CombinedData
        val combinedData = CombinedData()
        combinedData.setData(ScatterData(rohDataSet)) // Add Roh scatter plot
        combinedData.setData(LineData(ipDataSet))     // Add IP line plot

        // Update chart
        binding.chartRohIp.data = combinedData

        // Adjust X axis limits based on plotted data
        if (graphPoints.isNotEmpty()) {
            val minX = graphPoints.first().first
            val maxX = graphPoints.last().first
            // Add some padding to min/max for log scale
            binding.chartRohIp.xAxis.axisMinimum = log10(minX * 0.8f)
            binding.chartRohIp.xAxis.axisMaximum = log10(maxX * 1.2f)
        } else {
            // Reset axes if no data
            binding.chartRohIp.xAxis.resetAxisMinimum()
            binding.chartRohIp.xAxis.resetAxisMaximum()
        }

        // Adjust Y axis limits (optional, MPAndroidChart does auto-scaling)
        // binding.chartRohIp.axisLeft.axisMinimum = ...
        // binding.chartRohIp.axisRight.axisMinimum = 0f // Already set in setup

        // Refresh chart
        binding.chartRohIp.invalidate()
        binding.chartRohIp.animateX(500) // Optional animation
    }

    // --- Custom Value Formatter for Logarithmic Axes ---
    // This formatter converts the internal log10 values back to original scale for display
    inner class LogarithmicAxisFormatter : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String {
            // Convert log10(value) back to value for display
            val originalValue = 10f.pow(value)
            // Format based on magnitude for better readability
            return when {
                originalValue < 1 -> String.format(Locale.US, "%.2f", originalValue)
                originalValue < 10 -> String.format(Locale.US, "%.1f", originalValue)
                else -> String.format(Locale.US, "%.0f", originalValue)
            }
        }
    }


    // --- Companion Object ---
    companion object {
        /**
         * Use this factory method to create a new instance of this fragment.
         */
        @JvmStatic
        fun newInstance() = DListFragment()
    }
}