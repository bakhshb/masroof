package com.baraa.masroof.data.preferences

import android.content.SharedPreferences
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.theme.ThemePreferencesRepository

class SharedPrefsThemePreferencesRepository(
    private val prefs: SharedPreferences,
) : ThemePreferencesRepository {
    override fun getThemeMode(): ThemeMode =
        ThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, ThemeMode.DEFAULT.name))

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    companion object {
        const val PREFS_NAME: String = "theme_prefs"
        const val KEY_THEME_MODE: String = "theme_mode"
    }
}
