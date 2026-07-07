package com.gongpx.androidacpclient.data.store

import android.content.Context

enum class AppLanguageMode {
    System,
    English,
    Chinese,
}

class AppSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun loadLanguageMode(): AppLanguageMode {
        return runCatching {
            AppLanguageMode.valueOf(preferences.getString(KEY_LANGUAGE_MODE, AppLanguageMode.System.name) ?: AppLanguageMode.System.name)
        }.getOrDefault(AppLanguageMode.System)
    }

    fun saveLanguageMode(mode: AppLanguageMode) {
        preferences.edit().putString(KEY_LANGUAGE_MODE, mode.name).apply()
    }

    private companion object {
        const val KEY_LANGUAGE_MODE = "language_mode"
    }
}
