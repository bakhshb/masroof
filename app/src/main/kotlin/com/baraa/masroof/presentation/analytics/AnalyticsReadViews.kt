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
import com.baraa.masroof.ui.theme.MasroofSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

@Composable internal fun HistoricalPurchases(state: AnalysisUiState, count: Int) {
    val history = remember(state.purchasePeriods, count) { state.purchaseHistory(count) }
    val periods = history.periods
    MasroofCard {
        MasroofGuidanceLabel(stringResource(R.string.lab_analysis_history_purchases), stringResource(R.string.lab_analysis_history_purchases_hint), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.lab_analysis_available_periods, history.available, count), style = MaterialTheme.typography.bodySmall)
        if (history.total != null) {
            val total = history.total
            Text(stringResource(R.string.lab_analysis_history_total))
            MasroofAmountText(formatLocalizedMoney(analysisAmount(total)), MasroofAmountRole.Card)
            history.average?.let { Text(stringResource(R.string.lab_analysis_history_average, history.completeCount, formatLocalizedMoney(analysisAmount(it)))) }
            history.latestChange?.let { Text(stringResource(R.string.lab_analysis_history_change, formatLocalizedMoney(analysisAmount(it)))) }
            if (periods.any { it.actual != null && !it.complete }) Text(stringResource(R.string.lab_analysis_partial), style = MaterialTheme.typography.bodySmall)
            // Never connect a line across a missing or partially covered period.
            if (history.chartPeriods.isNotEmpty()) {
                val chartPeriods = history.chartPeriods
                var selected by rememberSaveable(count) { mutableIntStateOf(0) }
                val index = selected.coerceIn(chartPeriods.indices)
                val labels = chartPeriods.map { "${day(it.start)} — ${day(it.end - 1)} · ${formatLocalizedMoney(analysisAmount(requireNotNull(it.actual)))}" }
                val axes = chartPeriods.map { day(it.start) }
                Text(labels[index], style = MaterialTheme.typography.bodySmall)
                com.baraa.masroof.ui.components.MasroofInteractiveLineChart(chartPeriods.map { analysisAmount(requireNotNull(it.actual)).amount }, java.math.BigDecimal.ZERO,
                    labels = com.baraa.masroof.ui.components.LineChartLabels(stringResource(R.string.lab_analysis_period_axis),
                        stringResource(R.string.chart_previous_point), stringResource(R.string.chart_next_point), ""),
                    displayText = com.baraa.masroof.application.locale.AppDigits::display,
                    selectedPointIndex = index, onPointSelected = { selected = it }, pointLabel = { labels[it] },
                    axisLabel = { axes[it] }, currencyLabel = stringResource(R.string.lab_analysis_sar))
            }
        }
        periods.forEach { period ->
            Text("${day(period.start)} — ${day(period.end - 1)}", style = MaterialTheme.typography.bodySmall)
            if (period.actual == null) Text(stringResource(R.string.lab_analysis_period_unavailable)) else {
                MasroofAmountText(formatLocalizedMoney(analysisAmount(period.actual)), MasroofAmountRole.Card)
                if (!period.complete) Text(stringResource(R.string.lab_analysis_partial))
            }
        }
    }
}

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
@Composable fun AnalysisNotificationContent(state: NotificationCenterUiState, modifier: Modifier,
    originalRow: @Composable (NotificationItem, Boolean) -> Unit) {
    // The original notification screen also renders independently in the design matrix.
    // Only expose analysis actions when its workflow is supplied by the app root.
    val workflow = LocalAnalyticsWorkflow.current
    val analysis = workflow?.state?.collectAsState()?.value ?: AnalysisUiState()
    val scope = rememberCoroutineScope()
    val now = currentAnalysisTime()
    val alerts = visibleAnalysisAlerts(analysis, now)
    val unread = state.unreadCount + alerts.count { !it.read }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (workflow != null) MasroofTextButton(onClick = { AnalyticsNavigation.open("settings", fromCenter = true) }) { Text(stringResource(R.string.lab_analysis_settings)) }
        if (state.loading && state.items.isEmpty() && alerts.isEmpty()) CircularProgressIndicator()
        else if (state.items.isEmpty() && alerts.isEmpty()) Text(stringResource(R.string.notification_center_empty))
        else MasroofCard {
            Text(stringResource(R.string.notification_center_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notification_center_badge_count, unread), style = MaterialTheme.typography.bodySmall)
            state.items.forEachIndexed { i, item -> originalRow(item, i != state.items.lastIndex || alerts.isNotEmpty()) }
            alerts.forEachIndexed { i, alert ->
                var menu by remember(alert.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().testTag("lab-alert-container-${alert.id}"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    MasroofReferenceNavigationRow(title = stringResource(when (alert.kind) {
                        "DUE" -> R.string.lab_analysis_due_alert
                        "PAYMENT" -> R.string.lab_analysis_payment_alert
                        "ACTUAL" -> R.string.lab_analysis_actual_alert
                        else -> R.string.lab_analysis_budget_alert
                    }), subtitle = listOf(alert.title, formatLocalizedMoney(analysisAmount(alert.amount))).filter { it.isNotBlank() }.joinToString(" · "),
                        icon = MasroofIcons.notifications, modifier = Modifier.weight(1f).testTag("lab-alert-${alert.id}"),
                        status = if (alert.read) null else stringResource(R.string.reference_unread), showChevron = false,
                        divider = i != alerts.lastIndex, onClick = { AnalyticsNavigation.open(alert.id, fromCenter = true) })
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
    }
}

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
