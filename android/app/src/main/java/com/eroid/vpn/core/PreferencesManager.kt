package com.eroid.vpn.core

import android.content.Context
import android.content.SharedPreferences
import com.eroid.vpn.model.AppSettings
import com.google.gson.Gson

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("eroid_vpn_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_SETTINGS = "app_settings"
    }

    fun saveSettings(settings: AppSettings) {
        val json = gson.toJson(settings)
        prefs.edit().putString(KEY_SETTINGS, json).apply()
    }

    fun loadSettings(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return try {
            val settings = gson.fromJson(json, AppSettings::class.java) ?: AppSettings()
            if (settings.core.stack.equals("mixed", ignoreCase = true) || settings.core.stack.isBlank()) {
                settings.core.stack = "gvisor"
            }
            settings
        } catch (_: Exception) {
            AppSettings()
        }
    }
}
