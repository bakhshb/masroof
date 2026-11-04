package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.baraa.masroof.R
import com.baraa.masroof.application.settings.DeleteBlocker
import com.baraa.masroof.presentation.common.MasroofButton
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofOutlinedButton
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.common.MasroofTypeToConfirmDeleteDialog
import com.baraa.masroof.presentation.common.toUiLabelRes
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.presentation.locale.formatLocalizedTransactionDate
import com.baraa.masroof.ui.theme.MasroofIconSizes
import com.baraa.masroof.ui.theme.MasroofSpacing

/**
 * Delete confirmation for an account, card or loan. [name] must be the label the row shows.
 * A blocked request lists what still references the item and offers stop tracking instead.
 */
@Composable
fun SettingsDeleteDialog(
    request: RegistryDeleteUi,
    name: String,
    updating: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onStopTracking: () -> Unit,
) {
    // Dialog windows may expose a different Android context; resolve all copy, dates and amounts before opening one.
    val title = stringResource(when (request.target) {
        is StopTrackingTarget.Account -> R.string.registry_delete_account_title
        is StopTrackingTarget.Card -> R.string.registry_delete_card_title
        is StopTrackingTarget.Loan -> R.string.registry_delete_loan_title
    })
    val identity = when (val target = request.target) {
        is StopTrackingTarget.Account -> target.account.id
        is StopTrackingTarget.Card -> target.card.id
        is StopTrackingTarget.Loan -> target.loan.id
    }
    val stopped = when (val target = request.target) {
        is StopTrackingTarget.Account -> target.account.ownership
        is StopTrackingTarget.Card -> target.card.ownership
        is StopTrackingTarget.Loan -> target.loan.ownership
    } == com.baraa.masroof.domain.model.OwnershipStatus.EXTERNAL
    val blocker = request.blocker
    if (blocker == null) {
        MasroofTypeToConfirmDeleteDialog(
            title = title,
            help = stringResource(R.string.registry_delete_help),
            name = name,
            identity = identity,
            nameLabel = stringResource(R.string.registry_delete_name),
            copyLabel = stringResource(R.string.registry_delete_copy),
            confirmationLabel = stringResource(R.string.registry_delete_confirmation),
            deleteLabel = stringResource(R.string.registry_delete),
            testTagPrefix = "registry-delete",
            onDismiss = onDismiss,
            onDelete = onDelete,
            busy = updating,
        )
        return
    }
    val reason = when (blocker) {
        DeleteBlocker.LINKED_TRANSACTIONS ->
            pluralStringResource(R.plurals.registry_delete_blocked_transactions, request.linkedCount, request.linkedCount)
        DeleteBlocker.SUPPLEMENTARY_CARDS -> stringResource(R.string.registry_delete_blocked_supplementary)
        DeleteBlocker.LINKED_CARD -> stringResource(R.string.registry_delete_blocked_linked_card)
        DeleteBlocker.MESSAGE_PATTERN -> stringResource(R.string.registry_delete_blocked_pattern)
    }
    val more = (request.linkedCount - request.linked.size).takeIf { it > 0 }
        ?.let { stringResource(R.string.registry_delete_more, it) }
    val close = stringResource(R.string.registry_delete_close)
    val stop = stringResource(R.string.reference_stop_tracking)
    val blockedTitle = stringResource(R.string.registry_delete_blocked_title)
    val rows = request.linked.map { tx ->
        Triple(formatLocalizedTransactionDate(tx.occurredOn, "d MMM yyyy"),
            tx.title ?: stringResource(tx.type.toUiLabelRes()), formatLocalizedMoney(tx.amount))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("registry-delete-blocked"),
        icon = { Icon(MasroofIcons.warning, null, Modifier.size(MasroofIconSizes.md), tint = MaterialTheme.colorScheme.error) },
        title = { Text(blockedTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("registry-delete-blocked-name"))
                Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEach { (date, title, amount) ->
                    Row(Modifier.fillMaxWidth().testTag("registry-delete-linked"), verticalAlignment = Alignment.CenterVertically) {
                        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(MasroofSpacing.inlineGap))
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(amount, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                more?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                MasroofOutlinedButton(onDismiss, modifier = Modifier.weight(1f).testTag("registry-delete-close")) { Text(close) }
                if (!stopped) {
                    MasroofButton(onStopTracking, enabled = !updating, modifier = Modifier.weight(1f).testTag("registry-delete-stop")) { Text(stop) }
                }
            }
        },
    )
}

/** Delete wiring shared by the account, card and loan screens. */
class RegistryDeleteActions(
    val onRequest: (StopTrackingTarget) -> Unit,
    val onDismiss: () -> Unit,
    val onConfirm: () -> Unit,
    val onStopInstead: () -> Unit,
)

@Composable
internal fun SettingsDeleteDialogHost(state: SettingsUiState, actions: RegistryDeleteActions, name: @Composable (StopTrackingTarget) -> String?) {
    val request = state.deleteRequest ?: return
    val label = name(request.target) ?: return
    SettingsDeleteDialog(request, label, state.updating, actions.onDismiss, actions.onConfirm, actions.onStopInstead)
}
