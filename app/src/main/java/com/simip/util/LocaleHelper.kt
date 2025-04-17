package com.simip.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import java.util.Locale
import android.view.View

/**
 * Utility class to manage application language (Locale).
 * Handles setting the language, persisting the choice, and wrapping the Context
 * to apply the selected locale throughout the application.
 */
object LocaleHelper {

    private const val TAG = "LocaleHelper"
    private const val SELECTED_LANGUAGE = Constants.PREF_KEY_LANGUAGE // Key from Constants
    private const val DEFAULT_LANGUAGE = "en" // Default to English

    // Supported languages - ensure these match your values folders (values, values-fa)
    val SUPPORTED_LANGUAGES = listOf("en", "fa")

    /**
     * Sets the current locale for the application and persists the choice.
     * This should typically be called when the user selects a language.
     * The Activity needs to be recreated afterwards for changes to take full effect.
     *
     * @param context Context (preferably Application Context for persisting).
     * @param languageCode The language code to set (e.g., "en", "fa").
     */
    fun setLocale(context: Context, languageCode: String?) {
        val langToSet = if (languageCode != null && SUPPORTED_LANGUAGES.contains(languageCode)) {
            languageCode
        } else {
            Log.w(TAG, "Unsupported or null language code '$languageCode', falling back to default '$DEFAULT_LANGUAGE'.")
            DEFAULT_LANGUAGE
        }
        persistLanguage(context, langToSet)
        updateResources(context.applicationContext, langToSet) // Update app context resources immediately if possible
        Log.d(TAG, "Locale set to: $langToSet. Activity recreation might be needed.")
    }

    /**
     * Attaches the saved locale to the base context of an Activity or Application.
     * Should be called in `attachBaseContext` of Activities and Application class.
     *
     * @param context The base context.
     * @return A ContextWrapper with the updated locale configuration.
     */
    fun onAttach(context: Context): Context {
        val lang = getPersistedLanguage(context) ?: DEFAULT_LANGUAGE
        return updateResources(context, lang)
    }

    /**
     * Retrieves the currently persisted language code.
     * @param context Context to access SharedPreferences.
     * @return The saved language code (e.g., "en", "fa"), or null if none saved yet.
     */
    fun getPersistedLanguage(context: Context): String? {
        val prefs = getPreferences(context)
        return prefs.getString(SELECTED_LANGUAGE, null) // Return null if not found
    }

    /**
     * Gets the Locale object corresponding to the persisted language.
     * Defaults to English if no language is persisted or supported.
     * @param context Context.
     * @return The Locale object.
     */
    fun getCurrentLocale(context: Context): Locale {
        val langCode = getPersistedLanguage(context) ?: DEFAULT_LANGUAGE
        return Locale(langCode)
    }


    /**
     * Persists the selected language code in SharedPreferences.
     */
    private fun persistLanguage(context: Context, languageCode: String) {
        val prefs = getPreferences(context)
        prefs.edit().putString(SELECTED_LANGUAGE, languageCode).apply()
        Log.d(TAG, "Persisted language: $languageCode")
    }

    /**
     * Updates the resources of the given context to use the specified language.
     * @param context The context whose resources need updating.
     * @param languageCode The target language code.
     * @return A new Context with the updated configuration.
     */
    private fun updateResources(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale) // Set default locale for JVM

        val resources: Resources = context.resources
        val config: Configuration = Configuration(resources.configuration)

        // Update configuration based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Set primary locale using LocaleList for Android N+
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            config.setLocale(locale) // Also set single locale for compatibility? Usually locales is enough.
        } else {
            // Use deprecated setLocale for older versions
            @Suppress("deprecation")
            config.setLocale(locale)
        }

        // Set layout direction based on language (fa = RTL, en = LTR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
         //   config.setLayoutDirection(if (languageCode == "fa") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
            // Apply the updated configuration to the context's resources
            config.setLayoutDirection(locale)
            return context.createConfigurationContext(config) // Use createConfigurationContext
        } else {
            // Update resources directly for very old versions (may not update layout direction effectively)
            @Suppress("deprecation")
            resources.updateConfiguration(config, resources.displayMetrics)
            return context // Return original context, update might not be perfect
        }


        /* Alternative approach often seen, modifying resources directly (might be less reliable):
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLayoutDirection(locale) // Set layout direction based on locale
        }
        resources.updateConfiguration(config, resources.displayMetrics)
        return context // Return original context after updating its resources
        */
        // Using createConfigurationContext is generally preferred for API 17+
    }


    /**
     * Helper to get SharedPreferences instance.
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences("SimipAppPrefs", Context.MODE_PRIVATE) // Same name as in MeasurementRepoImpl
    }

    /**
     * Needs to be called from Activity's attachBaseContext
     */
    fun wrapContext(context: Context): Context {
        val savedLocale = getPersistedLanguage(context) ?: return context // Use default if nothing saved
        val locale = Locale(savedLocale)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("deprecation")
            config.setLocale(locale)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLayoutDirection(locale)
            return context.createConfigurationContext(config)
        } else {
            @Suppress("deprecation")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return context
        }
    }
}