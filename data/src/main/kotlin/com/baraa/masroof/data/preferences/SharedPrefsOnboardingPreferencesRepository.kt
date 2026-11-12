package com.baraa.masroof.data.preferences

import android.content.SharedPreferences
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.application.onboarding.OnboardingSetupDraft
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SharedPrefsOnboardingPreferencesRepository(
    private val prefs: SharedPreferences,
) : OnboardingPreferencesRepository {
    override fun isOnboardingStarted(): Boolean =
        prefs.getBoolean(KEY_ONBOARDING_STARTED, false)

    override fun setOnboardingStarted(started: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_STARTED, started).apply()
    }

    override fun isOnboardingCompleted(): Boolean =
        prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    override fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    override fun getHistoricalImportStartEpochMillis(): Long? =
        if (!prefs.contains(KEY_IMPORT_START_EPOCH_MILLIS)) {
            null
        } else {
            prefs.getLong(KEY_IMPORT_START_EPOCH_MILLIS, 0L)
        }

    override fun setHistoricalImportStartEpochMillis(epochMillis: Long?) {
        prefs.edit().apply {
            if (epochMillis == null) {
                remove(KEY_IMPORT_START_EPOCH_MILLIS)
            } else {
                putLong(KEY_IMPORT_START_EPOCH_MILLIS, epochMillis)
            }
        }.apply()
    }

    override fun isHistoricalImportCompleted(): Boolean =
        prefs.getBoolean(KEY_IMPORT_COMPLETED, false)

    override fun setHistoricalImportCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_IMPORT_COMPLETED, completed).apply()
    }

    override fun getSetupDraft(): OnboardingSetupDraft? = runCatching {
        prefs.getString(KEY_SETUP_DRAFT, null)?.let { Json.decodeFromString<OnboardingSetupDraft>(it) }
    }.getOrNull()

    override fun setSetupDraft(draft: OnboardingSetupDraft?) {
        prefs.edit().putString(KEY_SETUP_DRAFT, draft?.let { Json.encodeToString(it) }).apply()
    }

    override fun isHomeGuideSeen(): Boolean = prefs.getBoolean(KEY_HOME_GUIDE_SEEN, false)

    override fun setHomeGuideSeen(seen: Boolean) {
        prefs.edit().putBoolean(KEY_HOME_GUIDE_SEEN, seen).apply()
    }

    companion object {
        const val PREFS_NAME: String = "onboarding_prefs"
        private const val KEY_HOME_GUIDE_SEEN = "home_guide_seen"
        const val KEY_SETUP_DRAFT: String = "bank_setup_draft"
        private const val KEY_ONBOARDING_STARTED = "onboarding_started"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_IMPORT_START_EPOCH_MILLIS = "historical_import_start_epoch_millis"
        private const val KEY_IMPORT_COMPLETED = "historical_import_completed"
    }
}
