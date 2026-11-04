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
        val pruned = readIds.filterTo(mutableSetOf()) { it in activeIds || isPeriodForeignCurrencyId(it) }
        if (pruned != readIds) {
            preferencesRepository.setReadIds(pruned)
        }
    }

    // Old ids carried a translated period title; only the period-start form survives pruning.
    private fun isPeriodForeignCurrencyId(id: String): Boolean =
        id.startsWith(FOREIGN_CURRENCY_PREFIX) &&
            runCatching { java.time.LocalDate.parse(id.removePrefix(FOREIGN_CURRENCY_PREFIX)) }.isSuccess

    companion object {
        const val ID_SMS_PERMISSION: String = "sms_permission"
        const val ID_REVIEW_REQUIRED: String = "review_required"
        const val ID_UNREGISTERED_CARDS: String = "unregistered_cards"
        const val ID_UNREGISTERED_ACCOUNTS: String = "unregistered_accounts"

        private const val FOREIGN_CURRENCY_PREFIX: String = "foreign_currency:"

        fun foreignCurrencyId(periodKey: String): String = "$FOREIGN_CURRENCY_PREFIX$periodKey"

        fun updateAvailableId(version: String): String = "update_available:$version"

        fun updateReadyId(version: String): String = "update_ready:$version"
    }
}
