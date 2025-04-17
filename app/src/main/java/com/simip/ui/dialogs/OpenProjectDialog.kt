package com.simip.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels // Access MainViewModel shared by Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simip.R
import com.simip.databinding.DialogOpenProjectBinding // Import generated ViewBinding class
import com.simip.ui.main.MainViewModel
import kotlinx.coroutines.launch

/**
 * DialogFragment for opening an existing project.
 * Displays a list of project names in a RecyclerView.
 * Communicates the selected project name back via OpenProjectDialogListener.
 */
class OpenProjectDialog : DialogFragment() {

    companion object {
        const val TAG = "OpenProjectDialog"
    }

    private var _binding: DialogOpenProjectBinding? = null
    private val binding get() = _binding!!

    // Listener to communicate back
    private var listener: OpenProjectDialogListener? = null

    // Access the shared MainViewModel
    private val mainViewModel: MainViewModel by activityViewModels()

    private lateinit var projectAdapter: ProjectAdapter

    // Interface for communication
    interface OpenProjectDialogListener {
        fun onOpenProjectSelected(projectName: String)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        try {
            listener = context as OpenProjectDialogListener
        } catch (e: ClassCastException) {
            throw ClassCastException("$context must implement OpenProjectDialogListener")
        }
    }

    /* Using onCreateView for custom layout */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogOpenProjectBinding.inflate(inflater, container, false)
        setupRecyclerView()
        setupViews()
        observeViewModel()
        return binding.root
    }


    /* Alternative: Using MaterialAlertDialogBuilder */
    /*
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogOpenProjectBinding.inflate(LayoutInflater.from(context))

        setupRecyclerView()
        setupViews()
        observeViewModel() // Observe ViewModel to populate list

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_open_project)
            .setView(binding.root)
            // No Positive button needed, selection is via list click
            .setNegativeButton(R.string.button_cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }
    */

    private fun setupRecyclerView() {
        projectAdapter = ProjectAdapter { projectName ->
            // Callback from adapter when an item is clicked
            Log.d(TAG,"Project selected: $projectName")
            listener?.onOpenProjectSelected(projectName)
            dismiss() // Close dialog after selection
        }
        binding.recyclerViewOpenProject.apply {
            adapter = projectAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupViews() {
        // Setup Cancel Button (if using onCreateView)
        binding.btnCancelOpenProject.setOnClickListener { dismiss() }
    }


    private fun observeViewModel() {
        // Observe the project list from MainViewModel
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.projectList.collect { projectNames ->
                    Log.d(TAG,"Updating project list with ${projectNames.size} items.")
                    projectAdapter.submitList(projectNames)
                    // Show/hide empty placeholder
                    binding.tvOpenProjectEmpty.isVisible = projectNames.isEmpty()
                    binding.recyclerViewOpenProject.isVisible = projectNames.isNotEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerViewOpenProject.adapter = null // Clean up adapter
        _binding = null // Clean up binding
    }

    override fun onDetach() {
        super.onDetach()
        listener = null // Clean up listener
    }
}