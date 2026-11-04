package com.baraa.masroof.application.notification

enum class NotificationType {
    SMS_PERMISSION,
    REVIEW_REQUIRED,
    UNREGISTERED_CARDS,
    UNREGISTERED_ACCOUNTS,
    FOREIGN_CURRENCY,
    RESCAN_STATUS,
    APP_UPDATE_AVAILABLE,
    APP_UPDATE_READY,
}

enum class NotificationAction {
    REQUEST_SMS_PERMISSION,
    OPEN_APP_SETTINGS,
    OPEN_REVIEW,
    OPEN_SETTINGS_CARDS,
    OPEN_SETTINGS_ACCOUNTS,
    OPEN_SETTINGS_ABOUT,
    DISMISS_RESCAN,
    MARK_READ_ONLY,
}

data class NotificationItem(
    val id: String,
    val type: NotificationType,
    val count: Int = 0,
    val rescanStatusName: String? = null,
    val updateVersion: String? = null,
    val periodLabel: String? = null,
    val isRead: Boolean = false,
    val action: NotificationAction,
)

data class NotificationCenterSnapshot(
    val smsPermissionGranted: Boolean,
    val reviewRequiredCount: Int,
    val unregisteredCardCount: Int,
    val unregisteredAccountCount: Int,
    val excludedForeignCurrencyCount: Int,
    val periodLabel: String,
    /** Language-independent period identity (its start date) for the read state. */
    val periodKey: String = "",
    val rescanStatusName: String? = null,
    val updateAvailableVersion: String? = null,
    val updateReadyVersion: String? = null,
)

data class NotificationCenterResult(
    val items: List<NotificationItem>,
    val unreadCount: Int,
)
