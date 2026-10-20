package com.baraa.masroof.lab.beta.application.notification

interface BetaNotificationPreferencesRepository {
    fun getReadIds(): Set<String>

    fun markRead(id: String)

    fun clearRead(id: String)

    fun setReadIds(ids: Set<String>)
}
