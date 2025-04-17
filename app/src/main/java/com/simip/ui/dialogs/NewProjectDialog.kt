package com.simip.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels // Access MainViewModel shared by Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simip.R
import com.simip.data.model.Project
import com.simip.data.model.ProjectType
import com.simip.databinding.DialogNewProjectBinding // Import generated ViewBinding class
import com.simip.ui.main.MainViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

/**
 * DialogFragment for creating a new project.
 * Uses ViewBinding and communicates results back to the hosting Activity/Fragment
 * via the NewProjectDialogListener interface.
 */
class NewProjectDialog : DialogFragment() {

    companion object {
        const val TAG = "NewProjectDialog"
    }

    private var _binding: DialogNewProjectBinding? = null
    private val binding get() = _binding!!

    // Listener to communicate back to the Activity
    private var listener: NewProjectDialogListener? = null

    // Access the shared MainViewModel
    private val mainViewModel: MainViewModel by activityViewModels()

    // Interface for communication
    interface NewProjectDialogListener {
        fun onProjectCreated(projectName: String, projectType: ProjectType)
        fun onLastProjectSelected(project: Project) // If user clicks the last project name
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Verify that the host activity implements the callback interface
        try {
            listener = context as NewProjectDialogListener
        } catch (e: ClassCastException) {
            // The activity doesn't implement the interface, throw exception
            throw ClassCastException("$context must implement NewProjectDialogListener")
        }
    }

    /* Using onCreateView for custom layout instead of default AlertDialog content */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogNewProjectBinding.inflate(inflater, container, false)
        setupViews()
        return binding.root
    }


    /* Alternative: Using MaterialAlertDialogBuilder for standard dialog structure */
    /*
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogNewProjectBinding.inflate(LayoutInflater.from(context))

        setupViews() // Setup listeners and initial values

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_title_new_project)
            .setView(binding.root)
            // Buttons are handled manually in setupViews now
            // .setPositiveButton(R.string.button_ok) { _, _ -> handleOkClick() }
            // .setNegativeButton(R.string.button_cancel) { dialog, _ -> dialog.cancel() }
            .create()
    }
    */

    private fun setupViews() {
        // Setup Project Type Spinner
        // Adapter already set via android:entries in XML, but could be set here too
        // ArrayAdapter.createFromResource(...) etc.

        // Display Last Project Info (if available)
        // Accessing StateFlow value directly (consider collecting if needed long-term)
        // Needs to run blocking or collect in lifecycle scope, runBlocking is simpler here for init
        val lastProject = runBlocking { mainViewModel.lastProject.firstOrNull() } // Get current value
        if (lastProject != null) {
            val lastProjectText = "${lastProject.name} (${lastProject.type.key})"
            binding.tvLastProjectName.text = lastProjectText
            binding.tvLastProjectName.setOnClickListener {
                listener?.onLastProjectSelected(lastProject)
                dismiss() // Close dialog after selecting last project
            }
            binding.layoutLastProject.visibility = View.VISIBLE
        } else {
            binding.tvLastProjectName.text = getString(R.string.placeholder_no_last_project)
            binding.layoutLastProject.visibility = View.VISIBLE // Show label even if no last project
            binding.tvLastProjectName.isClickable = false
        }


        // Setup Buttons (if using onCreateView)
        binding.btnOkNewProject.setOnClickListener { handleOkClick() }
        binding.btnCancelNewProject.setOnClickListener { dismiss() }

    }

    private fun handleOkClick() {
        val projectName = binding.etNewProjectName.text.toString().trim()
        val selectedTypeString = binding.spinnerProjectType.selectedItem as? String
        val projectType = selectedTypeString?.let { ProjectType.fromKey(it) }

        // Basic Validation
        if (projectName.isBlank()) {
            binding.inputLayoutNewProjectName.error = getString(R.string.error_project_name_empty)
            return
        } else {
            binding.inputLayoutNewProjectName.error = null // Clear error
        }
        if (!projectName.matches(Regex("[a-zA-Z0-9_\\- ]+"))) {
            binding.inputLayoutNewProjectName.error = getString(R.string.error_project_name_invalid)
            return
        } else {
            binding.inputLayoutNewProjectName.error = null
        }


        if (projectType == null) {
            // Should not happen if spinner is populated correctly
            Log.e(TAG,"Selected project type is null or invalid: $selectedTypeString")
            // Show a generic error or highlight spinner?
            return
        }

        // Call listener and dismiss
        listener?.onProjectCreated(projectName, projectType)
        dismiss()
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Clean up binding
    }

    override fun onDetach() {
        super.onDetach()
        listener = null // Clean up listener
    }
}