package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.*
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.*
import com.baraa.masroof.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Production Home order and neutral renderers; all money comes from the Beta read model. */
@Composable
internal fun BetaDashboardHome(read: DashboardProduction, enabled: Boolean,
    preferences: BetaDashboardViewPreferencesWorkflow?, onRoute: (String) -> Unit,
    onTransaction: (String) -> Unit, onImport: () -> Unit, layoutOpen: Boolean = false,
    onSetup: () -> Unit = onImport, pendingCardCount: Int = 0, onCards: () -> Unit = {}, onLayoutClose: () -> Unit = {}, onConfiguration: () -> Unit = {}) {
    val snapshot = read.snapshot
    val unavailable = stringResource(R.string.beta_dash_unavailable)
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    fun money(value: SignedAmount?) = value?.let { MasroofMoneyDisplay.format(it.amount, it.currency.name, language) } ?: unavailable
    val analysis = snapshot.analysis
    val overall = BetaDashboardExplorer.trend(snapshot, BetaChartChoice(TrendScope.OVERALL))
    val icons = MasroofOutlineIcons
    var layout by remember { mutableStateOf(BetaHomeLayout()) }
    var layoutLoaded by remember { mutableStateOf(preferences == null) }
    var layoutError by remember { mutableStateOf(false) }
    BetaLaunchedEffect(preferences) {
        try { layout = preferences?.readHomeLayout() ?: BetaHomeLayout(); layoutLoaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { layoutError = true }
    }
    if (layoutError) Text(stringResource(R.string.beta_chart_save_error), color = MaterialTheme.colorScheme.error)
    if (layoutOpen && layoutLoaded) BetaHomeLayoutSheet(layout, preferences, onClose = onLayoutClose,
        onSaved = { layout = it; onLayoutClose() }, onConfiguration = onConfiguration)
    // Keep recovery outside customizable sections, even when Recent transactions is hidden.
    if(pendingCardCount>0) MasroofCard(Modifier.testTag("home-card-setup")) {
        Text(stringResource(R.string.beta_home_cards),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.beta_parity_card_missing))
        Button(enabled=enabled,onClick=onCards,modifier=Modifier.fillMaxWidth().testTag("home-configure-cards")) {Text(stringResource(R.string.beta_home_cards))}
    }
    if (snapshot.transactions.isEmpty() && pendingCardCount==0) {
        MasroofCard(Modifier.testTag("home-setup-help")) {
            Text(stringResource(R.string.beta_setup_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.beta_setup_empty_help), style = MaterialTheme.typography.bodyMedium)
            Button(enabled = enabled, onClick = onSetup, modifier = Modifier.fillMaxWidth().testTag("home-setup")) {
                Text(stringResource(R.string.beta_setup_continue))
            }
        }
    }
    layout.sections.filter { it.visible }.forEach { entry -> key(entry.section) {
        when (entry.section) {
            BetaHomeSection.HERO -> {
    DashboardHomeHero(BetaDashboardVisualMapper.hero(read, stringResource(R.string.beta_home_remaining),
        stringResource(R.string.beta_home_inflow), stringResource(R.string.beta_home_outflow), unavailable, language),
        icons.Wallet, onIncome = { onRoute("FLOW_IN") }, onOutflow = { onRoute("FLOW_OUT") },
        enabled = enabled, modifier = Modifier.testTag("beta-home-hero"))

            }
            BetaHomeSection.SHORTCUTS -> {
    MasroofShortcutStrip(listOf(
        MasroofShortcutItem(stringResource(R.string.beta_home_accounts), icons.Landmark, MasroofCardAccent.Account) { onRoute("ACCOUNTS") },
        MasroofShortcutItem(stringResource(R.string.beta_home_cards), icons.CreditCard, MasroofCardAccent.Credit) { onRoute("CARDS") },
        MasroofShortcutItem(stringResource(R.string.beta_home_loans), icons.HandCoins, MasroofCardAccent.Liability) { onRoute("LOANS") },
        MasroofShortcutItem(stringResource(R.string.beta_home_commitments_shortcut), icons.CalendarCheck, MasroofCardAccent.Liability) { onRoute("COMMITMENTS") }
    ), modifier = Modifier.testTag("home-shortcuts"), enabled = enabled)


            }
            BetaHomeSection.COMMITMENTS -> {
    val commitments = snapshot.commitments
    if (commitments == null || DashboardSection.COMMITMENTS in read.unavailableSections) {
        HomeUnavailable(stringResource(R.string.beta_home_commitments), "home-commitments-unavailable")
    } else if (commitments.rows.isNotEmpty()) {
        val locale = LocalConfiguration.current.locales[0]
        val labels = dashboardAssetLabels(snapshot)
        val pending = commitments.rows.filter { it.status == PaymentStatus.UNPAID }
        DashboardCommitmentBand(stringResource(R.string.beta_home_commitments),
            stringResource(R.string.beta_home_waiting, commitments.remaining?.let { MasroofMoneyDisplay.format(it.amount, it.currency.name, language) } ?: unavailable),
            pending.take(2).map { row ->
                (row.facilityId?.let(labels::get) ?: row.loanId?.let(labels::get) ?: row.label) +
                    (row.expectedDate?.let { " · " + DateTimeFormatter.ofPattern("d MMM", locale).format(it) } ?: "")
            }, Modifier.testTag("home-commitments"), details = if (pending.any { it.facilityId != null }) ({
                MasroofTextButton(enabled = enabled, onClick = { onRoute("CARDS") }) {
                    Text(stringResource(R.string.beta_home_card_details), style = MaterialTheme.typography.bodySmall,
                        color = MasroofThemeExtras.extendedColors.liability)
                }
            }) else null)
    }

            }
            BetaHomeSection.METRICS -> {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        val colors = MasroofThemeExtras.extendedColors
        val total = overall?.totalSpending
        val tone = when (total?.amount?.signum()) { 1 -> colors.outflow; -1 -> colors.inflow; else -> MaterialTheme.colorScheme.onSurface }
        if (layout.expensesVisible) DashboardMetric(money(total), tone, Modifier.weight(1f).testTag("dash-summary-spending")) {
            HomeGuidanceLabel(stringResource(R.string.beta_home_expenses))
        }
        if (layout.averageVisible) DashboardMetric(money(overall?.averageDailySpending), MaterialTheme.colorScheme.primary,
            Modifier.weight(1f).testTag("home-daily-average")) {
            HomeGuidanceLabel(stringResource(R.string.beta_home_daily_average),
                stringResource(R.string.beta_home_average_basis, overall?.points?.size ?: 0))
        }
    }

            }
            BetaHomeSection.TREND -> {
    BetaHomeTrend(snapshot, enabled, preferences, onRoute)


            }
            BetaHomeSection.MERCHANTS -> {
    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        DashboardSectionHeading(stringResource(R.string.beta_dash_analysis), icons.ChartLine)
        DashboardSectionHeading(stringResource(R.string.beta_home_top_merchants), icons.Store,
            actionLabel = stringResource(R.string.beta_home_view_all), onAction = if (enabled && DashboardSection.MERCHANT_TOTALS !in read.unavailableSections && analysis?.merchants?.isNotEmpty() == true) ({ onRoute("ANALYSIS") }) else null)
        when {
            DashboardSection.MERCHANT_TOTALS in read.unavailableSections || analysis == null ->
                Text(stringResource(R.string.beta_dash_unavailable_help), Modifier.testTag("home-merchants-unavailable"))
            analysis.merchants.isEmpty() -> Text(stringResource(R.string.beta_home_merchants_empty),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                val rows = analysis.merchants.take(5)
                val maximum = rows.maxOf { it.net.amount.max(java.math.BigDecimal.ZERO) }
                Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
                    rows.forEach { row -> MasroofRankedBarRow(row.merchant, money(row.net),
                        MasroofBarChart.progress(row.net.amount, maximum),
                        subtitle = row.purchaseTransactionCount?.let { stringResource(R.string.beta_home_merchant_count, it) },
                        style = MasroofHorizontalBarStyle.Outflow) }
                }
            }
        }
    }

            }
            BetaHomeSection.TRANSACTIONS -> {
    DashboardSectionHeading(stringResource(R.string.beta_home_recent), icons.List,
        modifier = Modifier.testTag("home-recent-heading"), actionLabel = stringResource(R.string.beta_home_view_all),
        onAction = if (enabled) ({ onRoute("TRANSACTIONS") }) else null)
    val rows = BetaDashboardExplorer.transactions(snapshot)
    when {
        DashboardSection.RUNTIME_SOURCE in read.unavailableSections ->
            Text(stringResource(R.string.beta_dash_unavailable_help), Modifier.testTag("home-transactions-unavailable"))
        rows.isEmpty() -> {
            Text(stringResource(R.string.beta_home_empty), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = enabled, onClick = onImport, modifier = Modifier.fillMaxWidth().testTag("home-import")) {
                Icon(icons.RefreshCw, null); Text(stringResource(R.string.beta_history_title))
            }
        }
        else -> MasroofCard {
            rows.take(5).forEach { row ->
                val type = row.effectiveType
                val inward = type in setOf(FinancialType.INCOME, FinancialType.EXTERNAL_TRANSFER_IN, FinancialType.REFUND)
                val neutral = type in setOf(FinancialType.SELF_TRANSFER, FinancialType.ADJUSTMENT, FinancialType.UNKNOWN)
                val colors = MasroofThemeExtras.extendedColors
                val (color, background) = when (type) {
                    FinancialType.LOAN_REPAYMENT -> colors.liability to colors.liabilitySoft
                    FinancialType.CREDIT_CARD_PAYMENT -> colors.card to colors.cardSoft
                    else -> if (inward) colors.inflow to colors.inflowSoft else if (neutral) colors.account to colors.accountSoft else colors.outflow to colors.outflowSoft
                }
                val label = stringResource(financialTypeLabel(type))
                val value = MasroofMoneyDisplay.format(row.amount.amount, row.amount.currency.name, language)
                val locale = LocalConfiguration.current.locales[0]
                DashboardTransactionRow(row.merchant ?: row.counterparty ?: label,
                    label + (row.primaryCardLast4?.let { " · $it" } ?: ""),
                    (if (inward) "+" else if (neutral) "" else "−") + value.substringBeforeLast(' '),
                    DateTimeFormatter.ofPattern("d MMM", locale).format(row.time.instant.atZone(row.time.zone).toLocalDate()) + " · " + value.substringAfterLast(' '),
                    transactionIcon(type), color, background, Modifier.testTag("home-transaction-${row.key.transactionId}"),
                    onClick = if (enabled) ({ onTransaction(row.key.transactionId) }) else null)
            }
        }
    }
            }
        }
    } }

}

@Composable
private fun BetaHomeTrend(snapshot: DashboardSnapshot, enabled: Boolean,
    preferences: BetaDashboardViewPreferencesWorkflow?, onRoute: (String) -> Unit) {
    var scope by rememberSaveable { mutableStateOf(TrendScope.PURCHASES) }
    var loaded by remember { mutableStateOf(preferences == null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val coroutine = rememberBetaUiScope()
    BetaLaunchedEffect(preferences) {
        try { scope = preferences?.readHomeScope() ?: TrendScope.PURCHASES; loaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true; loaded = true }
    }
    val trend = BetaDashboardExplorer.trend(snapshot, BetaChartChoice(scope))
    val visual = trend?.let(BetaDashboardVisualMapper::trend)
    var selectedDate by rememberSaveable(snapshot.query.from.toString(), scope) { mutableStateOf<String?>(null) }
    val index = visual?.dates?.indexOf(selectedDate)?.takeIf { it >= 0 } ?: visual?.dates?.lastIndex ?: 0
    val locale = LocalConfiguration.current.locales[0]
    val labels = listOf(stringResource(R.string.beta_home_purchases), stringResource(R.string.beta_home_all_expenses))
    MasroofCard(Modifier.testTag("home-trend-card")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            HomeGuidanceLabel(labels[if (scope == TrendScope.PURCHASES) 0 else 1],
                stringResource(R.string.beta_home_chart_basis), Modifier.weight(1f), title = true)
            IconButton(enabled = enabled, onClick = { onRoute("ANALYSIS") }, modifier = Modifier.testTag("home-chart-customize")) {
                Icon(DashboardChartMenuIcon, stringResource(R.string.beta_chart_customize), Modifier.size(MasroofReference.icon))
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = MasroofSpacing.sectionGap)) {
            MasroofChartScopeTabs(labels, if (scope == TrendScope.PURCHASES) 0 else 1, enabled = enabled && loaded && !saving, onSelected = { selected ->
                if (enabled && loaded && !saving) {
                    val next = if (selected == 0) TrendScope.PURCHASES else TrendScope.OVERALL
                    saving = true
                    coroutine.launch {
                        try { preferences?.saveHomeScope(next); scope = next; selectedDate = null; error = false }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = true }
                        finally { saving = false }
                    }
                }
            })
        }
        if (error) Text(stringResource(R.string.beta_chart_save_error), color = MaterialTheme.colorScheme.error)
        when {
            !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("home-scope-loading"))
            trend?.points?.isEmpty() == true -> Text(stringResource(R.string.beta_home_no_elapsed_days), style = MaterialTheme.typography.bodySmall)
            visual == null -> Text(stringResource(R.string.beta_dash_unavailable_help), Modifier.testTag("home-chart-unavailable"))
            else -> {
                fun money(value: java.math.BigDecimal) = MasroofMoneyDisplay.format(value, visual.currency, locale.toLanguageTag())
                DashboardTrendHead(DateTimeFormatter.ofPattern("d MMM", locale).format(LocalDate.parse(visual.dates[index])), money(visual.values[index]),
                    average = visual.average?.let { average -> ({
                        MasroofChartAverageLegend(stringResource(R.string.beta_home_chart_average, money(average)),
                            Modifier.weight(1f).testTag("home-chart-average"))
                    }) })
                MasroofInteractiveLineChart(visual.values, visual.average ?: java.math.BigDecimal.ZERO,
                    modifier = Modifier.testTag("home-spending-chart"), selectedPointIndex = index,
                    labels = LineChartLabels(stringResource(R.string.beta_chart_day_axis), stringResource(R.string.beta_chart_previous_point),
                        stringResource(R.string.beta_chart_next_point), stringResource(R.string.beta_chart_average_announcement,
                            visual.average?.let(::money) ?: stringResource(R.string.beta_dash_unavailable))),
                    onPointSelected = { if (enabled) selectedDate = visual.dates[it] },
                    pointLabel = { DateTimeFormatter.ofPattern("d MMM", locale).format(LocalDate.parse(visual.dates[it])) + " · " + money(visual.values[it]) },
                    axisLabel = { DateTimeFormatter.ofPattern("d/M", locale).format(LocalDate.parse(visual.dates[it])) },
                    currencyLabel = MasroofMoneyDisplay.currencyLabel(visual.currency, locale.toLanguageTag()), showAverage = visual.average != null)
            }
        }
    }
}

@Composable
private fun HomeGuidanceLabel(label: String, message: String? = null, modifier: Modifier = Modifier, title: Boolean = false) {
    val color = if (title) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = if (title) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall, color = color)
        if (message != null) MasroofGuidanceButton(label, message, contentColor = color,
            description = stringResource(R.string.beta_home_about, label))
    }
}

@Composable
private fun HomeUnavailable(title: String, tag: String) {
    DashboardSectionHeading(title, MasroofOutlineIcons.Clock3)
    Text(stringResource(R.string.beta_fix_commitments_unknown), Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall)
}

private fun transactionIcon(type: FinancialType) = when (type) {
    FinancialType.INCOME -> MasroofOutlineIcons.TrendingUp
    FinancialType.REFUND -> MasroofOutlineIcons.RotateCcw
    FinancialType.EXTERNAL_TRANSFER_IN -> MasroofOutlineIcons.ArrowDownLeft
    FinancialType.EXTERNAL_TRANSFER_OUT -> MasroofOutlineIcons.ArrowUpRight
    FinancialType.CREDIT_CARD_PAYMENT -> MasroofOutlineIcons.CreditCard
    FinancialType.CASH_WITHDRAWAL -> MasroofOutlineIcons.Banknote
    FinancialType.LOAN_REPAYMENT -> MasroofOutlineIcons.Landmark
    FinancialType.SELF_TRANSFER -> MasroofOutlineIcons.ArrowLeftRight
    FinancialType.EXPENSE -> MasroofOutlineIcons.ShoppingBag
    FinancialType.BILL_PAYMENT -> MasroofOutlineIcons.ReceiptText
    FinancialType.FEE -> MasroofOutlineIcons.Receipt
    FinancialType.ADJUSTMENT -> MasroofOutlineIcons.ArrowRightLeft
    FinancialType.UNKNOWN -> MasroofOutlineIcons.Info
}
