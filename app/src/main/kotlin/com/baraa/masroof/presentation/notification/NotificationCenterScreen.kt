package com.baraa.masroof.presentation.notification

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.smsRescanStatusMessage
import com.baraa.masroof.application.notification.NotificationAction
import com.baraa.masroof.application.notification.NotificationItem
import com.baraa.masroof.application.notification.NotificationType
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.dashboard.SmsRescanStatus
import com.baraa.masroof.presentation.settings.SettingsNavRow
import com.baraa.masroof.presentation.common.MasroofIcons

@Composable
fun NotificationCenterRoute(
    viewModel: NotificationCenterViewModel,
    externalState: NotificationCenterExternalState,
    onBack: () -> Unit,
    onNavigate: (NotificationAction) -> Unit,
) {
    LaunchedEffect(externalState) {
        viewModel.refresh(externalState)
    }
    val state by viewModel.uiState.collectAsState()
    NotificationCenterScreen(
        state = state,
        onBack = onBack,
        onOpenNotification = { item ->
            onNavigate(viewModel.onNotificationOpened(item))
        },
    )
}

@Composable
internal fun NotificationCenterScreen(
    state: NotificationCenterUiState,
    onBack: () -> Unit,
    onOpenNotification: (NotificationItem) -> Unit,
) {
    MasroofSecondaryScaffold(
        title = stringResource(R.string.notification_center_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.review_back),
        actions = {
            val unread = com.baraa.masroof.presentation.analytics.notificationUnreadCount(state)
            if (unread > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.notification_center_new_count, unread, unread),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) { contentModifier ->
        com.baraa.masroof.presentation.analytics.AnalysisNotificationContent(state, contentModifier) { item, divider ->
            NotificationCenterRow(item, { onOpenNotification(item) }, divider)
        }
    }
}

@Composable
private fun NotificationCenterRow(
    item: NotificationItem,
    onClick: () -> Unit,
    divider: Boolean = true,
) {
    com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(
        icon = notificationIcon(item.type),
        title = notificationTitle(item),
        subtitle = notificationSubtitle(item),
        onClick = onClick,
        divider = divider,
        status = if (item.isRead) null else stringResource(R.string.reference_unread),
        showChevron = false,
    )
}

@Composable
private fun notificationIcon(type: NotificationType) =
    when (type) {
        NotificationType.SMS_PERMISSION -> MasroofIcons.sms
        NotificationType.REVIEW_REQUIRED -> MasroofIcons.reviewQueue
        NotificationType.UNREGISTERED_CARDS -> MasroofIcons.cardPayment
        NotificationType.UNREGISTERED_ACCOUNTS -> MasroofIcons.moneyMovement
        NotificationType.FOREIGN_CURRENCY -> MasroofIcons.periodHint
        NotificationType.RESCAN_STATUS -> MasroofIcons.rescan
        NotificationType.APP_UPDATE_AVAILABLE,
        NotificationType.APP_UPDATE_READY,
        -> MasroofIcons.importBackup
    }

@Composable
private fun notificationTitle(item: NotificationItem): String =
    when (item.type) {
        NotificationType.SMS_PERMISSION ->
            stringResource(R.string.notification_center_sms_permission_title)
        NotificationType.REVIEW_REQUIRED ->
            stringResource(R.string.settings_hub_review_title)
        NotificationType.UNREGISTERED_CARDS ->
            stringResource(R.string.notification_center_unregistered_cards_title)
        NotificationType.UNREGISTERED_ACCOUNTS ->
            stringResource(R.string.notification_center_unregistered_accounts_title)
        NotificationType.FOREIGN_CURRENCY ->
            stringResource(R.string.dashboard_excluded_other_currency_title)
        NotificationType.RESCAN_STATUS ->
            stringResource(R.string.notification_center_rescan_title)
        NotificationType.APP_UPDATE_AVAILABLE ->
            stringResource(R.string.settings_updates_title)
        NotificationType.APP_UPDATE_READY ->
            stringResource(R.string.settings_updates_title)
    }

@Composable
private fun notificationSubtitle(item: NotificationItem): String =
    when (item.type) {
        NotificationType.SMS_PERMISSION ->
            stringResource(R.string.notification_center_sms_permission_body)
        NotificationType.REVIEW_REQUIRED ->
            stringResource(R.string.settings_hub_review_subtitle_count, item.count)
        NotificationType.UNREGISTERED_CARDS ->
            if (item.count > 1) {
                stringResource(R.string.dashboard_unregistered_cards_more, item.count - 1)
            } else {
                stringResource(R.string.dashboard_unregistered_cards_body)
            }
        NotificationType.UNREGISTERED_ACCOUNTS ->
            stringResource(R.string.notification_center_unregistered_accounts_body, item.count)
        NotificationType.FOREIGN_CURRENCY ->
            stringResource(R.string.dashboard_excluded_other_currency, item.count)
        NotificationType.RESCAN_STATUS ->
            rescanStatusMessage(item.rescanStatusName)
        NotificationType.APP_UPDATE_AVAILABLE ->
            stringResource(R.string.settings_updates_available, item.updateVersion.orEmpty())
        NotificationType.APP_UPDATE_READY ->
            stringResource(R.string.settings_updates_ready, item.updateVersion.orEmpty())
    }

@Composable
private fun rescanStatusMessage(statusName: String?): String {
    val status = runCatching { SmsRescanStatus.valueOf(statusName.orEmpty()) }.getOrNull()
        ?: return stringResource(R.string.dashboard_rescan_ok)
    return smsRescanStatusMessage(status)
}
