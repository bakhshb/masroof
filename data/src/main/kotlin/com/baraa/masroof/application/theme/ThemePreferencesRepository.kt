package com.baraa.masroof.application.theme

interface ThemePreferencesRepository {
    fun getThemeMode(): ThemeMode

    fun setThemeMode(mode: ThemeMode)
}
