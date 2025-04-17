package com.simip.ui.dialogs



import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simip.R
import com.simip.databinding.DialogAboutBinding // Import generated ViewBinding class

/**
 * DialogFragment to display application information (About).
 * Shows app name, version, company name, and copyright.
 */
class AboutDialog : DialogFragment() {

    companion object {
        const val TAG = "AboutDialog"
    }

    private var _binding: DialogAboutBinding? = null
    private val binding get() = _binding!!

    /* Using onCreateView for custom layout */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAboutBinding.inflate(inflater, container, false)
        setupViews()
        return binding.root
    }

    /* Alternative: Using MaterialAlertDialogBuilder */
    /*
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAboutBinding.inflate(LayoutInflater.from(context))

        setupViews() // Setup version text etc.

        return MaterialAlertDialogBuilder(requireContext())
            // No title needed as it's part of the custom layout? Or set it here?
            // .setTitle(R.string.dialog_title_about)
            .setView(binding.root)
            // OK button is handled manually in setupViews now
            // .setPositiveButton(R.string.button_ok) { dialog, _ -> dialog.dismiss() }
            .create()
    }
    */

    private fun setupViews() {
        // Get app version name
        val version = getAppVersionName()
        binding.tvAppVersionAbout.text = getString(R.string.app_version_placeholder, version)

        // Setup close button (using onCreateView)
        binding.btnCloseAbout.setOnClickListener { dismiss() }

        // Set other static texts (already done via XML references)
        // binding.tvAppNameAbout.text = getString(R.string.app_name)
        // binding.tvCompanyNameAbout.text = getString(R.string.company_name)
        // binding.tvCopyrightAbout.text = getString(R.string.copyright_text)
    }

    /**
     * Helper function to get the application's version name from package manager.
     */


    private fun getAppVersionName(): String {
        return try {
            // از requireContext() به جای requireActivity() استفاده کنید
            val currentContext = requireContext()
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentContext.packageManager.getPackageInfo(currentContext.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                currentContext.packageManager.getPackageInfo(currentContext.packageName, 0)
            }
            packageInfo.versionName ?: "N/A" // افزودن بررسی null برای versionName
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Could not get package info", e)
            "N/A" // Return placeholder if version cannot be found
        } catch (e: IllegalStateException) {
            // این خطا ممکن است رخ دهد اگر requireContext() قبل از attach شدن کامل فراخوانی شود
            Log.e(TAG, "Fragment not attached, cannot get package info", e)
            "N/A"
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Clean up binding
    }
}