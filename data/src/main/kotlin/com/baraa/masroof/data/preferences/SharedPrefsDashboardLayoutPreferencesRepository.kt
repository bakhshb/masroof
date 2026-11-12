package com.baraa.masroof.data.preferences

import android.content.SharedPreferences
import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import kotlinx.serialization.json.Json

class SharedPrefsDashboardLayoutPreferencesRepository(
    private val prefs: SharedPreferences,
) : DashboardLayoutPreferencesRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): DashboardLayoutSnapshot {
        val raw = prefs.getString(KEY_LAYOUT_JSON, null) ?: return DashboardLayoutSnapshot.default()
        return runCatching {
            val decoded = json.decodeFromString(DashboardLayoutSnapshot.serializer(), raw)
            val merged = decoded.withMergedSections()
            if (merged != decoded) {
                save(merged)
            }
            merged
        }.getOrElse { DashboardLayoutSnapshot.default() }
    }

    override fun save(snapshot: DashboardLayoutSnapshot) {
        prefs.edit()
            .putString(KEY_LAYOUT_JSON, json.encodeToString(DashboardLayoutSnapshot.serializer(), snapshot))
            .apply()
    }

    companion object {
        const val PREFS_NAME: String = "dashboard_layout_prefs"
        const val KEY_LAYOUT_JSON: String = "layout_json"
    }
}
