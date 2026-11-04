package com.baraa.masroof.presentation.notification

import com.baraa.masroof.application.notification.NotificationItem

data class NotificationCenterExternalState(
    val periodLabel: String = "",
    /** Language-independent period identity (its start date) for the read state. */
    val periodKey: String = "",
    val excludedForeignCurrencyCount: Int = 0,
    val rescanStatusName: String? = null,
    val updateAvailableVersion: String? = null,
    val updateReadyVersion: String? = null,
)

data class NotificationCenterUiState(
    val loading: Boolean = true,
    val items: List<NotificationItem> = emptyList(),
    val unreadCount: Int = 0,
)
