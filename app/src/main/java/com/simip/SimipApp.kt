package com.simip

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.simip.util.Constants
import com.simip.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Custom Application class for the Simip app.
 * Used for:
 * - Initializing global components or libraries.
 * - Providing a global CoroutineScope.
 * - Applying the selected locale using LocaleHelper.
 */
class SimipApp : Application() {

    // Create a global CoroutineScope that lives as long as the application
    // Use SupervisorJob so that failure of one child doesn't cancel the whole scope
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // If you need a scope primarily for IO tasks that should survive config changes:
    // val applicationIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SimipApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Application onCreate")
        // --- Initialize Global Components Here ---
        // Example: Setup logging library, dependency injection framework, etc.
        // setupLogging()
        // setupDependencyInjection()

        // Instance = this // If using a static instance pattern (use with caution)
    }

    /**
     * Attaches the base context and applies the application's locale.
     * This is called before onCreate().
     */
    override fun attachBaseContext(base: Context) {
        // Apply the saved locale using LocaleHelper
        super.attachBaseContext(LocaleHelper.onAttach(base))
        Log.v(TAG, "Application attachBaseContext - Locale applied")
    }

    /**
     * Handles configuration changes at the application level.
     * Mainly useful if you need to react to locale changes triggered by the system
     * while the app is running (less common than app-driven locale changes).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-apply locale in case the system configuration changed it
        // This might conflict if LocaleHelper relies solely on SharedPreferences
        // Let's comment it out for now, as LocaleHelper.onAttach handles initial setup
        // based on persisted preferences.
        // LocaleHelper.onAttach(this)
        Log.d(TAG, "Application onConfigurationChanged")
    }

    /**
     * Called when the application is terminating.
     * Not guaranteed to be called on all devices/situations (e.g., force close).
     * Use it for cleanup if necessary, but don't rely on it for critical saves.
     */
    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "Application onTerminate")
        // Cancel the application scope to clean up any running coroutines
        applicationScope.cancel("Application is terminating")
        // applicationIoScope.cancel("Application is terminating")
    }

    /**
     * Provides access to the application-level CoroutineScope.
     * Repositories or other long-lived components can use this scope.
     * @return The application's main CoroutineScope.
     */
    fun getApplicationScope(): CoroutineScope {
        return applicationScope
    }

}