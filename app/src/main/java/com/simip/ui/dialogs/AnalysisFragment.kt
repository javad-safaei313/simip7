package com.simip.ui.analysis

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.simip.databinding.FragmentAnalysisBinding // Import generated ViewBinding class

/**
 * Fragment for the Analysis tab.
 * Currently displays a placeholder message.
 */
class AnalysisFragment : Fragment() {

    private val TAG = "AnalysisFragment"
    private var _binding: FragmentAnalysisBinding? = null
    private val binding get() = _binding!!

    // No ViewModel needed for this simple version

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "onCreateView")
        _binding = FragmentAnalysisBinding.inflate(inflater, container, false)
        // No specific view setup needed here as the layout is static
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")
        // Add any specific logic here if needed in the future
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView")
        _binding = null // Clean up binding
    }

    // --- Companion Object ---
    companion object {
        /**
         * Use this factory method to create a new instance of this fragment.
         */
        @JvmStatic
        fun newInstance() = AnalysisFragment()
    }
}