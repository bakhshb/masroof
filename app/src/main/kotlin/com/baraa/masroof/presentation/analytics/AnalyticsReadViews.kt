package com.baraa.masroof.presentation.analytics

import com.baraa.masroof.presentation.common.launchLogged
import com.baraa.masroof.presentation.navigation.AnalyticsNavigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.analytics.*
import com.baraa.masroof.application.notification.NotificationItem
import com.baraa.masroof.presentation.notification.NotificationCenterUiState
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.presentation.locale.formatLocalizedTransactionDate
import com.baraa.masroof.ui.theme.MasroofSpacing
import kotlinx.coroutines.delay
import java.time.ZoneId

@Composable internal fun HistoricalPurchases(state: AnalysisUiState, count: Int) {
    val history = remember(state.purchasePeriods, count) { state.purchaseHistory(count) }
    val periods = history.periods
    MasroofCard(Modifier.testTag("analysis-history")) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            MasroofGuidanceLabel(stringResource(R.string.lab_analysis_history_purchases), listOf(stringResource(R.string.lab_analysis_history_purchases_hint),
                stringResource(R.string.lab_analysis_partial)).joinToString("\n\n"), style = MaterialTheme.typography.titleSmall,
                contentColor = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.analysis_history_average_label), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    history.average?.let { MasroofAmountText(formatLocalizedMoney(analysisAmount(it)), MasroofAmountRole.Hero) }
                }
                Text(com.baraa.masroof.application.locale.AppDigits.display(stringResource(R.string.lab_analysis_available_periods, history.available, count)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Missing periods keep their empty slot and partly covered ones are striped; the chart never bridges a gap.
            if (periods.any { it.actual != null }) {
                var selected by rememberSaveable(count) { mutableIntStateOf(periods.indexOfLast { it.actual != null }) }
                val index = selected.coerceIn(periods.indices)
                val labels = periods.map { period -> com.baraa.masroof.application.locale.AppDigits.display("${day(period.start)} — ${day(period.end - 1)} · " +
                    (period.actual?.let { formatLocalizedMoney(analysisAmount(it)) } ?: stringResource(R.string.lab_analysis_period_unavailable))) }
                Text(labels[index], style = MaterialTheme.typography.bodySmall)
                com.baraa.masroof.ui.components.MasroofDailyBarChart(periods.mapIndexed { i, period ->
                    com.baraa.masroof.ui.components.MasroofDailyBar(period.actual?.let { analysisAmount(it).amount }, period.actual != null && !period.complete,
                        labels[i], com.baraa.masroof.application.locale.AppDigits.display(monthLabel(period.start)))
                }, index, { selected = it }, stringResource(R.string.chart_previous_point), stringResource(R.string.chart_next_point),
                    { com.baraa.masroof.application.locale.AppDigits.display(it.stripTrailingZeros().toPlainString()) },
                    Modifier.testTag("analysis-history-bars"), average = history.average?.let { analysisAmount(it).amount })
            }
            history.latestChange?.let { change ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                    Text(stringResource(R.string.analysis_history_latest_change), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    MasroofBadge(com.baraa.masroof.application.locale.AppDigits.display(stringResource(
                        if (change < 0) R.string.analysis_amount_down else R.string.analysis_amount_up,
                        formatLocalizedMoney(analysisAmount(kotlin.math.abs(change))))),
                        if (change < 0) MasroofCardAccent.Inflow else MasroofCardAccent.Outflow)
                }
            }
        }
    }
}

@Composable private fun monthLabel(epochDay: Long): String = java.time.format.DateTimeFormatter.ofPattern("MMM",
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(java.time.LocalDate.ofEpochDay(epochDay))

@Composable internal fun EvidenceGroup(ids: List<String>, reason: String, workflow: AnalyticsWorkflow,
    source: String, onOpenTransaction: (String) -> Unit, collapsible: Boolean = true) {
    var expanded by rememberSaveable(reason) { mutableStateOf(!collapsible) }
    var visible by rememberSaveable(reason) { mutableIntStateOf(20) }
    var retry by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<AnalysisEvidence>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, ids, source, visible, retry) {
        if (!expanded) return@LaunchedEffect
        rows = null; failed = false
        try {
            val sorted = workflow.sortEvidenceIds(ids)
            rows = sorted.take(visible).chunked(20).flatMap { workflow.evidence(it) }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
        if (collapsible) MasroofTextButton(onClick = { expanded = !expanded }) { Text("$reason (${ids.size})") }
        else Text(stringResource(R.string.analysis_transaction_count, ids.size), style = MaterialTheme.typography.titleSmall)
        if (expanded) {
            when {
                ids.isEmpty() -> Text(stringResource(R.string.analysis_no_transactions), style = MaterialTheme.typography.bodySmall)
                failed -> MasroofTextButton(onClick = { retry++ }) { Text(stringResource(R.string.dashboard_retry)) }
                rows == null -> CircularProgressIndicator()
                else -> rows.orEmpty().forEach { evidence ->
                    val tx = evidence.transaction
                    if (tx == null) Text(stringResource(R.string.lab_analysis_transaction_missing)) else {
                        val effective = evidence.facts?.effectiveType ?: tx.type
                        val title = tx.merchant?.takeIf { it.isNotBlank() } ?: tx.counterparty
                        val row = TransactionPreviewUi(tx.id, title, tx.amount, tx.occurredAt.atZone(ZoneId.systemDefault()).toLocalDate(),
                            "", "", tx.type, effective, TransactionTypePresentation.direction(effective), evidence.facts?.primaryCardLast4,
                            tx.sourceContainerId, tx.destinationContainerId, title.orEmpty(), evidence.facts?.sarEquivalent,
                            tx.appliedExchangeRate, tx.exchangeRateSource)
                        TransactionPreviewRow(row, Modifier.testTag("lab-evidence-${tx.id}"),
                            ownedCards = evidence.cards.map { OwnedCardUi(it.bank, it.last4, it.displayName, it.cardNetwork, it.cardRole, it.parentCardLast4) }, onClick = { onOpenTransaction(tx.id) })
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (rows != null && visible < ids.size) MasroofTextButton(onClick = { visible += 20 }) { Text(stringResource(R.string.lab_analysis_more)) }
        }
    }
}

/** Original and analytics rows share one list/card. Analytics read state remains in its existing store. */
@Composable fun AnalysisNotificationContent(state: NotificationCenterUiState, analysis: AnalysisUiState, alerts: List<com.baraa.masroof.data.analytics.AnalysisAlertRow>,
    modifier: Modifier, originalRow: @Composable (NotificationItem, Boolean) -> Unit) {
    // The original notification screen also renders independently in the design matrix.
    // Only expose analysis actions when its workflow is supplied by the app root.
    val workflow = LocalAnalyticsWorkflow.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        if (state.loading && state.items.isEmpty() && alerts.isEmpty()) CircularProgressIndicator()
        else if (state.items.isEmpty() && alerts.isEmpty()) Text(stringResource(R.string.notification_center_empty))
        else MasroofCard {
            state.items.forEachIndexed { i, item -> originalRow(item, i != state.items.lastIndex || alerts.isNotEmpty()) }
            alerts.forEachIndexed { i, alert ->
                var menu by remember(alert.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().testTag("lab-alert-container-${alert.id}"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    val kindLabel = stringResource(when (alert.kind) {
                        "DUE" -> R.string.lab_analysis_due_alert
                        "PAYMENT" -> R.string.lab_analysis_payment_alert
                        "ACTUAL" -> R.string.lab_analysis_actual_alert
                        else -> R.string.lab_analysis_budget_alert
                    })
                    val amount = formatLocalizedMoney(analysisAmount(alert.amount))
                    val dueDay = alert.dueEpochDay()
                    // A named due alert leads with the commitment name and shows its amount and due date.
                    val named = alert.kind == "DUE" && alert.title.isNotBlank()
                    val due = analysis.dueFor(alert)
                    val title = com.baraa.masroof.application.analytics.CommitmentNames.dueAlert(
                        androidx.compose.ui.platform.LocalContext.current, alert, due)
                    MasroofReferenceNavigationRow(title = if (named) title else kindLabel,
                        subtitle = if (named && dueDay != null) stringResource(R.string.notification_due_subtitle, amount,
                            formatLocalizedTransactionDate(java.time.LocalDate.ofEpochDay(dueDay)))
                        else if (named) amount
                        else listOf(alert.title, amount).filter { it.isNotBlank() }.joinToString(" · "),
                        icon = MasroofIcons.notifications, modifier = Modifier.weight(1f).testTag("lab-alert-${alert.id}"),
                        status = if (alert.read) null else stringResource(R.string.reference_unread), showChevron = false,
                        divider = i != alerts.lastIndex, onClick = { AnalyticsNavigation.open(alert.id, fromCenter = true) },
                        iconDescription = kindLabel.takeIf { named })
                    Box {
                        androidx.compose.material3.IconButton(onClick = { menu = true }) {
                            androidx.compose.material3.Icon(MasroofIcons.more, stringResource(R.string.lab_analysis_actions), Modifier.size(com.baraa.masroof.ui.theme.MasroofIconSizes.md))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!alert.read) DropdownMenuItem(text = { Text(stringResource(R.string.lab_analysis_read)) }, onClick = {
                                menu = false; scope.launchLogged(workflow?.appLogService, "LAB_ANALYTICS", "Mark analysis alert read") { workflow?.markAlert(alert.id) }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.lab_analysis_snooze)) }, onClick = {
                                menu = false; scope.launchLogged(workflow?.appLogService, "LAB_ANALYTICS", "Snooze analysis alert") { workflow?.markAlert(alert.id, snooze = true) }
                            })
                        }
                    }
                }
            }
        }
        if (workflow != null) MasroofCard {
            MasroofReferenceNavigationRow(stringResource(R.string.lab_analysis_settings), stringResource(R.string.notification_settings_row_subtitle),
                MasroofIcons.settings, { AnalyticsNavigation.open("settings", fromCenter = true) }, divider = false)
        }
    }
}

/** Analysis state read once per Notification Center, so the top-bar count and the list share one clock. */
@Composable fun notificationAnalysisState(): AnalysisUiState =
    LocalAnalyticsWorkflow.current?.state?.collectAsState()?.value ?: AnalysisUiState()

@Composable internal fun currentAnalysisTime(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    return now
}

@Composable fun AnalysisEvidenceDetailFallback(loading: Boolean, onBack: () -> Unit) {
    MasroofScreenBackground {
        MasroofSecondaryScaffold(stringResource(R.string.transaction_detail_title), onBack,
            stringResource(R.string.lab_analysis_back)) { modifier ->
            Column(modifier) {
                if (loading) CircularProgressIndicator() else Text(stringResource(R.string.lab_analysis_transaction_missing))
            }
        }
    }
}
