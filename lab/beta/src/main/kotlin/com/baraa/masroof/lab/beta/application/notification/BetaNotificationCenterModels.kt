package com.baraa.masroof.lab.beta.application.notification

enum class BetaNotificationType {
    SMS_PERMISSION,
    REVIEW_REQUIRED,
    UNREGISTERED_CARDS,
    UNREGISTERED_ACCOUNTS,
    FOREIGN_CURRENCY,
    RESCAN_STATUS,
    APP_UPDATE_AVAILABLE,
    APP_UPDATE_READY,
}

enum class BetaNotificationAction {
    REQUEST_SMS_PERMISSION,
    OPEN_APP_SETTINGS,
    OPEN_REVIEW,
    OPEN_SETTINGS_CARDS,
    OPEN_SETTINGS_ACCOUNTS,
    OPEN_SETTINGS_ABOUT,
    DISMISS_RESCAN,
    MARK_READ_ONLY,
}

data class BetaNotificationItem(
    val id: String,
    val type: BetaNotificationType,
    val count: Int = 0,
    val rescanStatusName: String? = null,
    val updateVersion: String? = null,
    val periodLabel: String? = null,
    val isRead: Boolean = false,
    val action: BetaNotificationAction,
)

data class BetaNotificationCenterSnapshot(
    val smsPermissionGranted: Boolean,
    val reviewRequiredCount: Int,
    val unregisteredCardCount: Int,
    val unregisteredAccountCount: Int,
    val excludedForeignCurrencyCount: Int,
    val periodLabel: String,
    val rescanStatusName: String? = null,
    val updateAvailableVersion: String? = null,
    val updateReadyVersion: String? = null,
)

data class BetaNotificationCenterResult(
    val items: List<BetaNotificationItem>,
    val unreadCount: Int,
)
