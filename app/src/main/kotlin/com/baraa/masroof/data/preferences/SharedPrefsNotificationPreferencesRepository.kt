package com.baraa.masroof.data.preferences

import android.content.SharedPreferences
import com.baraa.masroof.application.notification.NotificationPreferencesRepository

class SharedPrefsNotificationPreferencesRepository(
    private val prefs: SharedPreferences,
) : NotificationPreferencesRepository {
    override fun getReadIds(): Set<String> =
        prefs.getStringSet(KEY_READ_IDS, emptySet()).orEmpty()

    override fun markRead(id: String) {
        val updated = getReadIds().toMutableSet()
        updated.add(id)
        prefs.edit().putStringSet(KEY_READ_IDS, updated).apply()
    }

    override fun setReadIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_READ_IDS, ids.toSet()).apply()
    }

    companion object {
        const val PREFS_NAME: String = "notification_prefs"
        private const val KEY_READ_IDS: String = "read_notification_ids"
    }
}
