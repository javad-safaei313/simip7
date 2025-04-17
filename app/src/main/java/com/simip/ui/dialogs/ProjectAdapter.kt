package com.simip.ui.dialogs // Or com.simip.ui.adapters if you prefer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simip.databinding.ItemProjectBinding // Import generated ViewBinding class

/**
 * Adapter for the RecyclerView in the Open Project dialog.
 * Displays a list of project names.
 */
class ProjectAdapter(
    private val onProjectClicked: (projectName: String) -> Unit // Callback when a project name is clicked
) : ListAdapter<String, ProjectAdapter.ProjectViewHolder>(ProjectDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder {
        val binding = ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ProjectViewHolder(binding, onProjectClicked)
    }

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        val projectName = getItem(position)
        holder.bind(projectName)
    }

    // --- ViewHolder ---
    class ProjectViewHolder(
        private val binding: ItemProjectBinding,
        private val onProjectClicked: (projectName: String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(projectName: String) {
            binding.itemTvProjectName.text = projectName
            binding.root.setOnClickListener {
                onProjectClicked(projectName)
            }
            // You can customize the icon visibility/source here if needed
            binding.itemIvProjectIcon.visibility = android.view.View.VISIBLE // Assuming icon is always shown
        }
    }

    // --- DiffUtil Callback ---
    class ProjectDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            // Project names are assumed to be unique identifiers in this list context
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}