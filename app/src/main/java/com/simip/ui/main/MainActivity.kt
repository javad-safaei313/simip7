package com.simip.ui.main



import android.view.MenuInflater
import com.simip.data.model.ProjectType
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.ComponentActivity // Or AppCompatActivity if using AppCompat features directly
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.simip.R
import com.simip.data.db.AppDatabase
import com.simip.data.model.DeviceStatus
import com.simip.data.model.Project
import com.simip.data.repository.* // Import repositories
import com.simip.databinding.ActivityMainBinding // Import generated ViewBinding class
import com.simip.ui.acq.AcqFragment
import com.simip.ui.acq.AcqViewModel // Needed for passing project info
import com.simip.ui.acq.AcqViewModelFactory
import com.simip.ui.analysis.AnalysisFragment
import com.simip.ui.dlist.DListFragment
import com.simip.ui.dlist.DListViewModel // Needed for passing project info
import com.simip.ui.dlist.DListViewModelFactory
import com.simip.ui.dialogs.AboutDialog // Assuming DialogFragment implementations
import com.simip.ui.dialogs.NewProjectDialog
import com.simip.ui.dialogs.OpenProjectDialog
import com.simip.util.* // Import helpers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), MenuProvider,
    NewProjectDialog.NewProjectDialogListener,
    OpenProjectDialog.OpenProjectDialogListener {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var mediaPlayer: MediaPlayer? = null

    // --- Instantiate dependencies (Ideally use Dependency Injection - Hilt/Koin) ---
    // Manual instantiation for now:
    private val database by lazy { AppDatabase.getDatabase(applicationContext) }
    private val measurementDao by lazy { database.measurementDao() }
    private val measurementRepository: MeasurementRepository by lazy {
        MeasurementRepositoryImpl(measurementDao, applicationContext)
    }
    // Pass applicationScope if DeviceRepositoryImpl needs it
    private val deviceRepository: DeviceRepository by lazy {
        DeviceRepositoryImpl(applicationContext, lifecycleScope) // Use lifecycleScope tied to Activity? Or applicationScope?
        // Using lifecycleScope might cancel operations if activity is destroyed.
        // Let's assume DeviceRepoImpl is designed to handle scope lifecycle or use Application scope.
        // Reverting to passing application scope from SimipApp
        // val app = application as SimipApp // Cast Application context
        // DeviceRepositoryImpl(applicationContext, app.getApplicationScope())
        // Simpler: Use lifecycleScope for now, as long-running connection might be better in a Service.
        DeviceRepositoryImpl(applicationContext, lifecycleScope)
    }
    private val locationHelper: LocationHelper by lazy { LocationHelper(applicationContext) }


    // --- ViewModels ---
    private val mainViewModel: MainViewModel by viewModels {
        MainViewModelFactory(application, deviceRepository, measurementRepository)
    }
    // ViewModels for fragments - they might get their own instances via fragment-ktx delegate
    private val acqViewModel: AcqViewModel by viewModels {
        AcqViewModelFactory(application, deviceRepository, measurementRepository, locationHelper)
    }
    private val dListViewModel: DListViewModel by viewModels {
        DListViewModelFactory(application, measurementRepository)
    }


    // --- Permission Management ---
    private val permissionManager = PermissionManager()
    private lateinit var requestAllPermissionsLauncher: ActivityResultLauncher<Array<String>>


    // --- Activity Lifecycle ---

    override fun attachBaseContext(newBase: Context) {
        // Apply locale before inflating layout
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Log.d(TAG, "onCreate")

        setupToolbar()
        setupViewPagerAndTabs()
        setupPermissionLauncher() // Register permission launcher
        observeViewModel()

        // Initial permission check on startup
        checkAndRequestPermissions()

        // Prepare sound player
        mediaPlayer = MediaPlayer.create(this, R.raw.ding)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release() // Release media player resources
        mediaPlayer = null
        Log.d(TAG, "onDestroy")
        // Optional: Explicitly disconnect if not handled by ViewModel onCleared
        // lifecycleScope.launch { deviceRepository.disconnectFromDevice() }
    }

    // --- Setup Functions ---

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true) // Show app name title
        // Add MenuProvider to handle menu creation and clicks
        addMenuProvider(this, this, Lifecycle.State.RESUMED)
        Log.d(TAG, "Toolbar and MenuProvider setup.")
    }

    private fun setupViewPagerAndTabs() {
        val adapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = adapter
        // Link TabLayout and ViewPager2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_acq)
                1 -> getString(R.string.tab_dlist)
                2 -> getString(R.string.tab_analysis)
                else -> null
            }
        }.attach()
        // Initially hide TabLayout
        binding.tabLayout.visibility = View.GONE
        Log.d(TAG, "ViewPager and Tabs setup.")
    }

    private fun setupPermissionLauncher() {
        requestAllPermissionsLauncher = permissionManager.registerRequestMultiplePermissionsLauncher(this) { grantResults ->
            val allGranted = grantResults.all { it.value }
            if (allGranted) {
                Log.i(TAG, "All required permissions granted.")
                // Permissions granted, try connecting again or enable features
                mainViewModel.startDeviceConnection() // Try connection now
            } else {
                Log.w(TAG, "One or more permissions were denied: $grantResults")
                // Show message to user explaining why permissions are needed
                Snackbar.make(binding.root, "Required permissions denied. Some features may not work.", Snackbar.LENGTH_LONG)
                    .setAction("Settings") {
                        // Intent to open app settings
                        AppUtils.openAppSettings(this)
                    }
                    .show()
            }
        }
    }

    // --- ViewModel Observation ---

    private fun observeViewModel() {
        Log.d(TAG, "Setting up ViewModel observers.")
        // Observe connection state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.connectionState.collect { state ->
                    Log.d(TAG, "Connection State Changed: $state")
                    updateConnectionStatusUI(state)
                }
            }
        }

        // Observe device status (battery, temp, vmn)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.deviceStatus.collect { status ->
                    updateDeviceStatusUI(status)
                }
            }
        }

        // Observe current project changes
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.currentProject.collect { project ->
                    Log.d(TAG, "Current Project Changed: ${project?.name}")
                    updateUiForProject(project)
                    // Notify fragment ViewModels about the project change
                    acqViewModel.setCurrentProject(project)
                    dListViewModel.loadDataForProject(project) // Trigger data loading for DList
                }
            }
        }

        // Observe user messages (Snackbar) and signals
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.userMessage.collect { message ->
                    Log.d(TAG, "Received User Message/Signal: $message")
                    when (message) {
                        // Handle special signals
                        getString(R.string.signal_recreate_activity) -> {
                            Log.i(TAG,"Recreating activity due to locale change.")
                            recreate()
                        }
                        getString(R.string.signal_play_sound_ding) -> playSoundDing()
                        // Handle regular messages
                        else -> {
                            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        // Observe messages from AcqViewModel too
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                acqViewModel.acqMessage.collect { message ->
                    Log.d(TAG, "Received Acq Message/Signal: $message")
                    when (message) {
                        getString(R.string.signal_play_sound_ding) -> playSoundDing()
                        else -> {
                            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Observe dialog state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.dialogState.collect { dialogType ->
                    handleDialogState(dialogType)
                }
            }
        }
    }

    // --- UI Update Functions ---

    private fun updateConnectionStatusUI(state: DeviceRepository.ConnectionState) {
        var statusText = ""
        var statusColor = ContextCompat.getColor(this, R.color.simip_status_searching_text) // Default color

        when (state) {
            DeviceRepository.ConnectionState.DISCONNECTED -> statusText = getString(R.string.status_searching) // Show searching on disconnect
            DeviceRepository.ConnectionState.SEARCHING_WIFI -> statusText = getString(R.string.status_searching)
            DeviceRepository.ConnectionState.CONNECTING_WIFI -> statusText = getString(R.string.status_wifi_connecting)
            DeviceRepository.ConnectionState.WIFI_CONNECTED -> statusText = getString(R.string.status_wifi_connected_tcp_pending)
            DeviceRepository.ConnectionState.CONNECTING_TCP -> statusText = getString(R.string.status_tcp_connecting)
            DeviceRepository.ConnectionState.VERIFYING_DEVICE -> statusText = getString(R.string.status_verifying_device)
            DeviceRepository.ConnectionState.CONNECTED -> {
                // Combine SSID and Version from their respective flows
                val ssid = mainViewModel.connectedSsid.value ?: "Unknown"
                val version = mainViewModel.deviceVersion.value ?: "?.?"
                statusText = getString(R.string.status_connected, ssid, version)
                statusColor = ContextCompat.getColor(this, R.color.simip_status_connected_text)
            }
            DeviceRepository.ConnectionState.CONNECTION_ERROR -> {
                statusText = getString(R.string.status_connection_error)
                statusColor = ContextCompat.getColor(this, R.color.simip_status_error_text)
            }
            DeviceRepository.ConnectionState.DEVICE_ERROR -> {
                // Potentially get more error details from deviceStatus or another flow later
                statusText = getString(R.string.status_device_error, "Comm Failed")
                statusColor = ContextCompat.getColor(this, R.color.simip_status_error_text)
            }
        }
        binding.tvStatusConnection.text = statusText
        binding.tvStatusConnection.setTextColor(statusColor)
    }

    private fun updateDeviceStatusUI(status: DeviceStatus) {
        binding.tvStatusVMN.text = status.measureMNvolt?.let { getString(R.string.status_unit_mv, it) } ?: getString(R.string.status_value_placeholder)
        binding.tvStatusBattery.text = status.voltageBat?.let { getString(R.string.status_unit_v, it) } ?: getString(R.string.status_value_placeholder)
        binding.tvStatusTemp.text = status.temperature?.let { getString(R.string.status_unit_c, it) } ?: getString(R.string.status_value_placeholder)
    }

    private fun updateUiForProject(project: Project?) {
        if (project != null) {
            binding.tabLayout.visibility = View.VISIBLE
            invalidateOptionsMenu() // Update menu to enable export
        } else {
            binding.tabLayout.visibility = View.GONE
            invalidateOptionsMenu() // Update menu to disable export
        }
    }

    private fun playSoundDing() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
                mediaPlayer?.prepare() // Prepare for next play
            }
            mediaPlayer?.start()
            Log.d(TAG,"Played Ding sound.")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing sound: ${e.message}", e)
        }
    }

    // --- Permission Handling ---

    private fun checkAndRequestPermissions() {
        val requiredPermissions = PermissionManager.allRequiredPermissions
        if (!permissionManager.arePermissionsGranted(this, requiredPermissions)) {
            Log.i(TAG, "Requesting required permissions...")
            permissionManager.requestMultiplePermissionsIfNeeded(
                context = this,
                launcher = requestAllPermissionsLauncher,
                permissions = requiredPermissions,
                activity = this // Needed for rationale
            ) {
                // Rationale lambda: Show a dialog explaining why permissions are needed
                showPermissionsRationaleDialog()
                true // Return true to proceed with request after showing rationale
            }
        } else {
            Log.i(TAG, "All required permissions already granted.")
            // Permissions already granted, proceed with connection attempt immediately
            mainViewModel.startDeviceConnection()
        }
    }

    private fun showPermissionsRationaleDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permissions Required")
            .setMessage("This app requires Location and Wi-Fi permissions to find and connect to the measurement device, and Storage permission (on older Android) to export data. Please grant these permissions.")
            .setPositiveButton("OK") { dialog, _ ->
                // User acknowledges rationale, request will proceed via PermissionManager callback
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss() // User cancelled, request won't be launched by PermissionManager
            }
            .show()
    }

    // --- Menu Handling (MenuProvider) ---

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        // Inflate the menu resource defined in xml
        // Note: We used a wrapper item in menu xml to force overflow behavior
        // menuInflater.inflate(R.menu.main_menu, menu)
        Log.d(TAG, "onCreateMenu")
    }

    override fun onPrepareMenu(menu: Menu) {
        // Enable/disable items based on state before menu is shown
        val exportItem = menu.findItem(R.id.action_export)
        exportItem?.isEnabled = mainViewModel.currentProject.value != null // Enable only if project is open
        Log.d(TAG, "onPrepareMenu - Export enabled: ${exportItem?.isEnabled}")
        super.onPrepareOptionsMenu(menu) // Recommended call
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        Log.d(TAG, "onMenuItemSelected: ${menuItem.title}")
        return when (menuItem.itemId) {
            R.id.action_new -> {
                mainViewModel.showNewProjectDialog()
                true
            }
            R.id.action_open -> {
                mainViewModel.showOpenProjectDialog()
                true
            }
            R.id.action_export -> {
                mainViewModel.exportCurrentProject()
                true
            }
            R.id.action_language_toggle -> {
                // Simple toggle logic: if current is fa, switch to en, else switch to fa
                val currentLang = LocaleHelper.getPersistedLanguage(this) ?: "en"
                val newLang = if (currentLang == "fa") "en" else "fa"
                mainViewModel.changeLanguage(newLang)
                true
            }
            R.id.action_about -> {
                mainViewModel.showAboutDialog()
                true
            }
            else -> false // Let the system handle other items (e.g., home button)
        }
    }

    // --- Dialog Handling ---

    private fun handleDialogState(dialogType: MainViewModel.DialogType) {
        // Dismiss any existing dialog first? Could lead to flicker. Manage carefully.
        Log.d(TAG, "Handling Dialog State: $dialogType")
        when(dialogType) {
            MainViewModel.DialogType.NONE -> { /* Dismiss any shown dialog if needed */ }
            MainViewModel.DialogType.NEW_PROJECT -> {
                NewProjectDialog().show(supportFragmentManager, NewProjectDialog.TAG)
            }
            MainViewModel.DialogType.OPEN_PROJECT -> {
                OpenProjectDialog().show(supportFragmentManager, OpenProjectDialog.TAG)
            }
            MainViewModel.DialogType.ABOUT -> {
                AboutDialog().show(supportFragmentManager, AboutDialog.TAG)
            }
        }
    }

    // --- Dialog Listener Callbacks ---

    override fun onProjectCreated(projectName: String, projectType: ProjectType) {
        // Called from NewProjectDialog
        mainViewModel.createNewProject(projectName, projectType)
    }

    override fun onOpenProjectSelected(projectName: String) {
        // Called from OpenProjectDialog
        mainViewModel.openProject(projectName)
    }

    override fun onLastProjectSelected(project: Project) {
        // Called from NewProjectDialog when last project is clicked
        mainViewModel.createNewProject(project.name, project.type) // Treat as creating/selecting
    }


    // --- ViewPager Adapter ---
    private inner class ViewPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3 // Acq, DList, Analysis

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> AcqFragment.newInstance() // Use factory methods if needed
                1 -> DListFragment.newInstance()
                2 -> AnalysisFragment.newInstance()
                else -> throw IllegalStateException("Invalid position: $position")
            }
        }
    }
}

// Helper for opening app settings (AppUtils.kt or similar utility class)
object AppUtils {
    fun openAppSettings(context: Context) {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = android.net.Uri.parse("package:${context.packageName}")
        context.startActivity(intent)
    }
}