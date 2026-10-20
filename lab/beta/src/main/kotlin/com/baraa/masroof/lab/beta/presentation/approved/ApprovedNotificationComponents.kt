package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import com.baraa.masroof.lab.beta.application.notification.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

@Composable
internal fun ApprovedReferenceNavigationRow(title: String, subtitle: String, icon: ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true,
    status: String? = null, showChevron: Boolean = true, enabled: Boolean = true) {
    Column(modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(top = MasroofReference.rowVertical,
            bottom = if (divider) MasroofReference.rowVertical else MasroofReference.lastRowBottom),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofReference.rowGap)) {
            Surface(shape = MasroofShapes.small, color = MasroofThemeExtras.extendedColors.accountSoft) {
                Box(Modifier.size(MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(MasroofReference.icon), tint = MasroofThemeExtras.extendedColors.account)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofReference.metadataGap)) {
                ApprovedText(title, style = MasroofReference.pair.copy(fontWeight = MasroofFontWeights.semiBold))
                ApprovedText(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status?.let { ApprovedBadge(it, MasroofCardAccent.Liability) }
            if (showChevron) Icon(if (androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl)
                MasroofOutlineIcons.ChevronLeft else MasroofOutlineIcons.ChevronRight,
                null, Modifier.size(MasroofReference.icon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(color = MasroofThemeExtras.extendedColors.cardBorder)
    }
}
@Composable
internal fun ApprovedNotificationIconButton(reviewCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MasroofThemeExtras.extendedColors
    Box(modifier.size(MasroofReference.iconButton)) {
        Surface(onClick = onClick, enabled = enabled, modifier = Modifier.matchParentSize(), shape = MasroofShapes.small,
            border = BorderStroke(MasroofSpacing.borderWidth, colors.cardBorder)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(MasroofOutlineIcons.Bell,
                    if (reviewCount > 0) stringResource(R.string.beta_approved_notification_center_badge_count, reviewCount)
                    else stringResource(R.string.beta_approved_notification_center_title),
                    Modifier.size(MasroofReference.icon))
            }
        }
        if (reviewCount > 0) Surface(modifier = Modifier.align(Alignment.TopEnd)
            .offset(x = MasroofReference.notificationOffsetX, y = MasroofReference.notificationOffsetY)
            .size(MasroofReference.notificationBadge), shape = MasroofShapes.extraLarge,
            color = colors.liability, contentColor = if (MaterialTheme.colorScheme.background == com.baraa.masroof.ui.theme.MasroofDarkColors.Background)
                colors.liabilitySoft else MaterialTheme.colorScheme.surface) {
            Box(contentAlignment = Alignment.Center) {
                ApprovedText(if (reviewCount > 99) "99+" else reviewCount.toString(), style = MasroofReference.notificationCount)
            }
        }
    }
}

@Composable
internal fun ApprovedNotificationCenterContent(items: List<BetaNotificationItem>, unreadCount: Int,
    loading: Boolean, failed: Boolean, onRetry: () -> Unit, onOpen: (BetaNotificationItem) -> Unit) {
    when {
        failed -> { ApprovedText(stringResource(R.string.beta_action_failed),color=MaterialTheme.colorScheme.error)
            ApprovedOutlinedButton(onClick=onRetry) {ApprovedText(stringResource(R.string.beta_retry))} }
        loading && items.isEmpty() -> CircularProgressIndicator()
        items.isEmpty() -> Column(Modifier.fillMaxWidth().padding(MasroofSpacing.screenPaddingLarge),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            ApprovedText(stringResource(R.string.beta_approved_notification_center_empty),style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
        else -> MasroofCard {
            ApprovedText(stringResource(R.string.beta_approved_notification_center_title),style=MaterialTheme.typography.titleMedium)
            ApprovedText(stringResource(R.string.beta_approved_notification_center_badge_count,unreadCount),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            items.forEachIndexed {index,item -> NotificationCenterRow(item,{onOpen(item)},index!=items.lastIndex)}
        }
    }
}

@Composable
private fun NotificationCenterRow(
    item: BetaNotificationItem,
    onClick: () -> Unit,
    divider: Boolean = true,
) {
    ApprovedReferenceNavigationRow(
        icon = notificationIcon(item.type),
        title = notificationTitle(item),
        subtitle = notificationSubtitle(item),
        onClick = onClick,
        divider = divider,
        status = if (item.isRead) null else stringResource(R.string.beta_approved_reference_unread),
        showChevron = false,
    )
}

@Composable
private fun notificationIcon(type: BetaNotificationType) =
    when (type) {
        BetaNotificationType.SMS_PERMISSION -> MasroofOutlineIcons.MessageSquare
        BetaNotificationType.REVIEW_REQUIRED -> MasroofOutlineIcons.ShieldCheck
        BetaNotificationType.UNREGISTERED_CARDS -> MasroofOutlineIcons.CreditCard
        BetaNotificationType.UNREGISTERED_ACCOUNTS -> MasroofOutlineIcons.Landmark
        BetaNotificationType.FOREIGN_CURRENCY -> MasroofOutlineIcons.Info
        BetaNotificationType.RESCAN_STATUS -> MasroofOutlineIcons.RefreshCw
        BetaNotificationType.APP_UPDATE_AVAILABLE,
        BetaNotificationType.APP_UPDATE_READY,
        -> MasroofOutlineIcons.Download
    }

@Composable
private fun notificationTitle(item: BetaNotificationItem): String =
    when (item.type) {
        BetaNotificationType.SMS_PERMISSION ->
            stringResource(R.string.beta_approved_notification_center_sms_permission_title)
        BetaNotificationType.REVIEW_REQUIRED ->
            stringResource(R.string.beta_approved_settings_hub_review_title)
        BetaNotificationType.UNREGISTERED_CARDS ->
            stringResource(R.string.beta_approved_notification_center_unregistered_cards_title)
        BetaNotificationType.UNREGISTERED_ACCOUNTS ->
            stringResource(R.string.beta_approved_notification_center_unregistered_accounts_title)
        BetaNotificationType.FOREIGN_CURRENCY ->
            stringResource(R.string.beta_approved_dashboard_excluded_other_currency_title)
        BetaNotificationType.RESCAN_STATUS ->
            stringResource(R.string.beta_approved_notification_center_rescan_title)
        BetaNotificationType.APP_UPDATE_AVAILABLE ->
            stringResource(R.string.beta_approved_settings_updates_title)
        BetaNotificationType.APP_UPDATE_READY ->
            stringResource(R.string.beta_approved_settings_updates_title)
    }

@Composable
private fun notificationSubtitle(item: BetaNotificationItem): String =
    when (item.type) {
        BetaNotificationType.SMS_PERMISSION ->
            stringResource(R.string.beta_approved_notification_center_sms_permission_body)
        BetaNotificationType.REVIEW_REQUIRED ->
            stringResource(R.string.beta_approved_settings_hub_review_subtitle_count, item.count)
        BetaNotificationType.UNREGISTERED_CARDS ->
            if (item.count > 1) {
                stringResource(R.string.beta_approved_dashboard_unregistered_cards_more, item.count - 1)
            } else {
                stringResource(R.string.beta_approved_dashboard_unregistered_cards_body)
            }
        BetaNotificationType.UNREGISTERED_ACCOUNTS ->
            stringResource(R.string.beta_approved_notification_center_unregistered_accounts_body, item.count)
        BetaNotificationType.FOREIGN_CURRENCY ->
            stringResource(R.string.beta_approved_dashboard_excluded_other_currency, item.count)
        BetaNotificationType.RESCAN_STATUS ->
            rescanStatusMessage(item.rescanStatusName)
        BetaNotificationType.APP_UPDATE_AVAILABLE ->
            stringResource(R.string.beta_approved_settings_updates_available, item.updateVersion.orEmpty())
        BetaNotificationType.APP_UPDATE_READY ->
            stringResource(R.string.beta_approved_settings_updates_ready, item.updateVersion.orEmpty())
    }

@Composable
private fun rescanStatusMessage(statusName: String?): String {
    val status = statusName.orEmpty()
    return stringResource(
        when (status) {
            "OK" -> R.string.beta_approved_dashboard_rescan_ok
            "ALREADY_UP_TO_DATE" -> R.string.beta_approved_dashboard_rescan_already_up_to_date
            "NEEDS_REVIEW" -> R.string.beta_approved_dashboard_rescan_needs_review
            "NO_MESSAGES" -> R.string.beta_approved_dashboard_rescan_no_messages
            "NO_BANK_SMS" -> R.string.beta_approved_dashboard_rescan_no_bank_sms
            "NO_TRANSACTIONS" -> R.string.beta_approved_dashboard_rescan_no_transactions
            "PERMISSION_DENIED" -> R.string.beta_approved_dashboard_rescan_permission_denied
            "FAILED" -> R.string.beta_approved_dashboard_rescan_failed
            else -> R.string.beta_approved_dashboard_rescan_ok
        },
    )
}
