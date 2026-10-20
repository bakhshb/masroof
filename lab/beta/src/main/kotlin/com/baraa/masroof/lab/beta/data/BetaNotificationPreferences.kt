package com.baraa.masroof.lab.beta.data

import android.content.SharedPreferences
import com.baraa.masroof.lab.beta.application.notification.BetaNotificationPreferencesRepository
import org.json.JSONArray

/** Strings only, so lab backup/restore retains the existing preference format. */
internal class BetaNotificationPreferences(private val preferences: SharedPreferences) : BetaNotificationPreferencesRepository {
    override fun getReadIds(): Set<String> {
        val values=JSONArray(preferences.getString(KEY,"[]"))
        require(values.length()<=1000)
        return (0 until values.length()).mapTo(linkedSetOf()) {index -> values.getString(index).also {
            require(it.isNotBlank() && it.length<=200)
        }}
    }
    override fun markRead(id: String)=setReadIds(getReadIds()+id)
    override fun clearRead(id: String)=setReadIds(getReadIds()-id)
    override fun setReadIds(ids: Set<String>) {
        require(ids.size<=1000 && ids.all {it.isNotBlank() && it.length<=200})
        check(preferences.edit().putString(KEY,JSONArray(ids.sorted()).toString()).commit()) {"Notification state could not be saved"}
    }
    private companion object {const val KEY="beta_notification_read_v1"}
}
