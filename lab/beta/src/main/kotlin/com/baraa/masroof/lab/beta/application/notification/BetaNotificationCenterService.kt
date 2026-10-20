package com.baraa.masroof.lab.beta.application.notification

class BetaNotificationCenterService(
    private val preferencesRepository: BetaNotificationPreferencesRepository,
) {
    fun build(snapshot: BetaNotificationCenterSnapshot): BetaNotificationCenterResult {
        val candidates = buildCandidates(snapshot)
        val activeIds = candidates.map { it.id }.toSet()
        pruneStaleReadIds(activeIds)

        val readIds = preferencesRepository.getReadIds()
        val items = candidates.map { candidate ->
            candidate.copy(isRead = candidate.id in readIds)
        }
        return BetaNotificationCenterResult(
            items = items,
            unreadCount = items.count { !it.isRead },
        )
    }

    fun markRead(id: String) {
        preferencesRepository.markRead(id)
    }

    private fun buildCandidates(snapshot: BetaNotificationCenterSnapshot): List<BetaNotificationItem> {
        val items = mutableListOf<BetaNotificationItem>()

        if (!snapshot.smsPermissionGranted) {
            items += BetaNotificationItem(
                id = ID_SMS_PERMISSION,
                type = BetaNotificationType.SMS_PERMISSION,
                action = BetaNotificationAction.REQUEST_SMS_PERMISSION,
            )
        }

        if (snapshot.reviewRequiredCount > 0) {
            items += BetaNotificationItem(
                id = ID_REVIEW_REQUIRED,
                type = BetaNotificationType.REVIEW_REQUIRED,
                count = snapshot.reviewRequiredCount,
                action = BetaNotificationAction.OPEN_REVIEW,
            )
        }

        if (snapshot.unregisteredCardCount > 0) {
            items += BetaNotificationItem(
                id = ID_UNREGISTERED_CARDS,
                type = BetaNotificationType.UNREGISTERED_CARDS,
                count = snapshot.unregisteredCardCount,
                action = BetaNotificationAction.OPEN_SETTINGS_CARDS,
            )
        }

        if (snapshot.unregisteredAccountCount > 0) {
            items += BetaNotificationItem(
                id = ID_UNREGISTERED_ACCOUNTS,
                type = BetaNotificationType.UNREGISTERED_ACCOUNTS,
                count = snapshot.unregisteredAccountCount,
                action = BetaNotificationAction.OPEN_SETTINGS_ACCOUNTS,
            )
        }

        if (snapshot.excludedForeignCurrencyCount > 0) {
            items += BetaNotificationItem(
                id = foreignCurrencyId(snapshot.periodLabel),
                type = BetaNotificationType.FOREIGN_CURRENCY,
                count = snapshot.excludedForeignCurrencyCount,
                periodLabel = snapshot.periodLabel,
                action = BetaNotificationAction.MARK_READ_ONLY,
            )
        }

        snapshot.rescanStatusName?.let { statusName ->
            items += BetaNotificationItem(
                id = rescanId(statusName),
                type = BetaNotificationType.RESCAN_STATUS,
                rescanStatusName = statusName,
                action = BetaNotificationAction.DISMISS_RESCAN,
            )
        }

        snapshot.updateReadyVersion?.let { version ->
            items += BetaNotificationItem(
                id = updateReadyId(version),
                type = BetaNotificationType.APP_UPDATE_READY,
                updateVersion = version,
                action = BetaNotificationAction.OPEN_SETTINGS_ABOUT,
            )
        } ?: snapshot.updateAvailableVersion?.let { version ->
            items += BetaNotificationItem(
                id = updateAvailableId(version),
                type = BetaNotificationType.APP_UPDATE_AVAILABLE,
                updateVersion = version,
                action = BetaNotificationAction.OPEN_SETTINGS_ABOUT,
            )
        }

        return items
    }

    private fun pruneStaleReadIds(activeIds: Set<String>) {
        val readIds = preferencesRepository.getReadIds()
        val pruned = readIds.intersect(activeIds)
        if (pruned != readIds) {
            preferencesRepository.setReadIds(pruned)
        }
    }

    companion object {
        const val ID_SMS_PERMISSION: String = "sms_permission"
        const val ID_REVIEW_REQUIRED: String = "review_required"
        const val ID_UNREGISTERED_CARDS: String = "unregistered_cards"
        const val ID_UNREGISTERED_ACCOUNTS: String = "unregistered_accounts"

        fun foreignCurrencyId(periodLabel: String): String = "foreign_currency:$periodLabel"

        fun rescanId(statusName: String): String = "rescan:$statusName"

        fun updateAvailableId(version: String): String = "update_available:$version"

        fun updateReadyId(version: String): String = "update_ready:$version"
    }
}
