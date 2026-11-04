package com.baraa.masroof.application.notification

class NotificationCenterService(
    private val preferencesRepository: NotificationPreferencesRepository,
) {
    fun build(snapshot: NotificationCenterSnapshot): NotificationCenterResult {
        val candidates = buildCandidates(snapshot)
        val activeIds = candidates.map { it.id }.toSet()
        pruneStaleReadIds(activeIds)

        val readIds = preferencesRepository.getReadIds()
        val items = candidates.map { candidate ->
            candidate.copy(isRead = candidate.id in readIds)
        }
        return NotificationCenterResult(
            items = items,
            unreadCount = items.count { !it.isRead },
        )
    }

    fun markRead(id: String) {
        preferencesRepository.markRead(id)
    }

    private fun buildCandidates(snapshot: NotificationCenterSnapshot): List<NotificationItem> {
        val items = mutableListOf<NotificationItem>()

        if (!snapshot.smsPermissionGranted) {
            items += NotificationItem(
                id = ID_SMS_PERMISSION,
                type = NotificationType.SMS_PERMISSION,
                action = NotificationAction.REQUEST_SMS_PERMISSION,
            )
        }

        if (snapshot.reviewRequiredCount > 0) {
            items += NotificationItem(
                id = ID_REVIEW_REQUIRED,
                type = NotificationType.REVIEW_REQUIRED,
                count = snapshot.reviewRequiredCount,
                action = NotificationAction.OPEN_REVIEW,
            )
        }

        if (snapshot.unregisteredCardCount > 0) {
            items += NotificationItem(
                id = ID_UNREGISTERED_CARDS,
                type = NotificationType.UNREGISTERED_CARDS,
                count = snapshot.unregisteredCardCount,
                action = NotificationAction.OPEN_SETTINGS_CARDS,
            )
        }

        if (snapshot.unregisteredAccountCount > 0) {
            items += NotificationItem(
                id = ID_UNREGISTERED_ACCOUNTS,
                type = NotificationType.UNREGISTERED_ACCOUNTS,
                count = snapshot.unregisteredAccountCount,
                action = NotificationAction.OPEN_SETTINGS_ACCOUNTS,
            )
        }

        if (snapshot.excludedForeignCurrencyCount > 0) {
            items += NotificationItem(
                id = foreignCurrencyId(snapshot.periodKey),
                type = NotificationType.FOREIGN_CURRENCY,
                count = snapshot.excludedForeignCurrencyCount,
                periodLabel = snapshot.periodLabel,
                action = NotificationAction.MARK_READ_ONLY,
            )
        }

        snapshot.rescanStatusName?.let { statusName ->
            items += NotificationItem(
                id = rescanId(statusName),
                type = NotificationType.RESCAN_STATUS,
                rescanStatusName = statusName,
                action = NotificationAction.DISMISS_RESCAN,
            )
        }

        snapshot.updateReadyVersion?.let { version ->
            items += NotificationItem(
                id = updateReadyId(version),
                type = NotificationType.APP_UPDATE_READY,
                updateVersion = version,
                action = NotificationAction.OPEN_SETTINGS_ABOUT,
            )
        } ?: snapshot.updateAvailableVersion?.let { version ->
            items += NotificationItem(
                id = updateAvailableId(version),
                type = NotificationType.APP_UPDATE_AVAILABLE,
                updateVersion = version,
                action = NotificationAction.OPEN_SETTINGS_ABOUT,
            )
        }

        return items
    }

    private fun pruneStaleReadIds(activeIds: Set<String>) {
        val readIds = preferencesRepository.getReadIds()
        // A foreign-currency notice belongs to one period; browsing another period must not forget it was read.
        // Only the newest periods are kept, so the stored set stays bounded.
        val keptPeriods = readIds.mapNotNull(::foreignCurrencyPeriodStart)
            .sortedDescending()
            .take(MAX_KEPT_FOREIGN_CURRENCY_PERIODS)
            .toSet()
        val pruned = readIds.filterTo(mutableSetOf()) { id ->
            id in activeIds || foreignCurrencyPeriodStart(id)?.let { it in keptPeriods } == true
        }
        if (pruned != readIds) {
            preferencesRepository.setReadIds(pruned)
        }
    }

    // Old ids carried a translated period title; only the period-start form survives pruning.
    private fun foreignCurrencyPeriodStart(id: String): java.time.LocalDate? =
        if (!id.startsWith(FOREIGN_CURRENCY_PREFIX)) null
        else runCatching { java.time.LocalDate.parse(id.removePrefix(FOREIGN_CURRENCY_PREFIX)) }.getOrNull()

    companion object {
        const val ID_SMS_PERMISSION: String = "sms_permission"
        const val ID_REVIEW_REQUIRED: String = "review_required"
        const val ID_UNREGISTERED_CARDS: String = "unregistered_cards"
        const val ID_UNREGISTERED_ACCOUNTS: String = "unregistered_accounts"

        private const val FOREIGN_CURRENCY_PREFIX: String = "foreign_currency:"

        /** Read foreign-currency notices kept for periods not currently shown (about a year of salary periods). */
        internal const val MAX_KEPT_FOREIGN_CURRENCY_PERIODS: Int = 12

        fun foreignCurrencyId(periodKey: String): String = "$FOREIGN_CURRENCY_PREFIX$periodKey"

        fun rescanId(statusName: String): String = "rescan:$statusName"

        fun updateAvailableId(version: String): String = "update_available:$version"

        fun updateReadyId(version: String): String = "update_ready:$version"
    }
}
