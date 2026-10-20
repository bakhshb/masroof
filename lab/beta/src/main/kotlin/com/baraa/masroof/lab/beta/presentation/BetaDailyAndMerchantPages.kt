package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.DashboardMetric
import com.baraa.masroof.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Content copied from MerchantSpendingSummaryScreen; navigation and amounts are bound to lab facts. */
@Composable
internal fun BetaMerchantsPage(snapshot: DashboardSnapshot,selectedKey: String?,enabled: Boolean,complete: Boolean,
    onMerchant: (String)->Unit,onDaily: ()->Unit,onTransaction: (String)->Unit) {
    val rows=snapshot.analysis?.merchants.orEmpty()
    val selected=rows.singleOrNull {it.merchantKey==selectedKey}
    if(selected!=null) {
        ApprovedMerchantRow(selected,false) {}
        val transactions=BetaDailyExpensesWorkflow.merchantTransactions(snapshot,selected.merchantKey)
        BetaScopeTransactionRows(snapshot,transactions,
            stringResource(R.string.beta_approved_dashboard_summary_transactions_title,transactions.size),enabled,onTransaction,complete)
    } else {
        Row(horizontalArrangement=Arrangement.spacedBy(MasroofReference.shortcutGap)) {
            ApprovedChoice(stringResource(R.string.beta_approved_reference_period_view),true,{},enabled=enabled)
            ApprovedChoice(stringResource(R.string.beta_approved_reference_day_view),false,onDaily,enabled=enabled,modifier=Modifier.testTag("merchants-daily"))
        }
        ApprovedSectionTitle(stringResource(R.string.beta_approved_dashboard_merchants_summary_title))
        when {
            !complete -> ApprovedText(stringResource(R.string.beta_dash_unavailable_help),Modifier.testTag("dash-unavailable"))
            rows.isEmpty() -> ApprovedText(stringResource(R.string.beta_approved_dashboard_merchants_empty),Modifier.testTag("dash-empty"),
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                ApprovedText(stringResource(R.string.beta_approved_dashboard_merchants_qualifying_count,rows.size),
                    style=MaterialTheme.typography.titleSmall.copy(fontWeight=MasroofFontWeights.bold),color=MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEach {row -> ApprovedMerchantRow(row,enabled) {onMerchant(row.merchantKey)}}
            }
        }
    }
}
@Composable
private fun ApprovedMerchantRow(row: MerchantSpending,enabled: Boolean,onClick: ()->Unit) {
    MasroofCard(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onClick).testTag("merchant-${row.merchantKey}")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ApprovedText(row.merchant,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=MasroofFontWeights.semiBold),
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                row.purchaseTransactionCount?.let {ApprovedText(stringResource(R.string.beta_approved_dashboard_merchants_transaction_count,it),
                    Modifier.padding(top=MasroofSpacing.inlineGap),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            ApprovedText(betaSummaryMoney(row.net),style=MaterialTheme.typography.titleSmall.copy(fontWeight=MasroofFontWeights.bold),color=MasroofThemeExtras.extendedColors.outflow)
        }
    }
}

/** Production DailyExpensesScreen controls, hero, chart and scoped rows; no money arithmetic in Compose. */
@Composable
internal fun BetaDailyExpensesPage(snapshot: DashboardSnapshot,enabled: Boolean,complete: Boolean,onTransaction: (String)->Unit) {
    var bank by rememberSaveable {mutableStateOf<String?>(null)}
    var localId by rememberSaveable {mutableStateOf<String?>(null)}
    var day by rememberSaveable {mutableStateOf<String?>(null)}
    val labels=dashboardAssetLabels(snapshot)
    val cards=snapshot.creditFacilities.flatMap {it.cards.map {c -> c.cardId}}+snapshot.standaloneCreditCards.map {it.cardId}+snapshot.debitCards.map {it.cardId}
    val asset=cards.singleOrNull {it.bank.value==bank && it.localId==localId}
    val workflow=LocalBetaChartDataWorkflow.current
    val preferences by workflow.preferences.collectAsState()
    val selection=remember(snapshot,preferences,asset,day) {BetaDailyExpensesWorkflow.select(snapshot,workflow,asset,day?.let {runCatching {LocalDate.parse(it)}.getOrNull()})}
    if(selection==null) {ApprovedText(stringResource(R.string.beta_dash_unavailable_help),Modifier.testTag("daily-unavailable"));return}
    val point=selection.point
    if(point==null) {MasroofCard {ApprovedText(stringResource(R.string.beta_home_no_elapsed_days),style=MaterialTheme.typography.bodySmall)};return}
    val date=DateTimeFormatter.ofPattern("d MMM",LocalConfiguration.current.locales[0])
    val all=stringResource(R.string.beta_approved_approved_all_expenses)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofReference.metricGap)) {
        val keys=listOf(null)+cards
        ApprovedSelect(asset?.let(labels::get) ?: all,keys.map {it?.let(labels::get) ?: all},{index ->
            bank=keys[index]?.bank?.value;localId=keys[index]?.localId},Modifier.weight(1f),label=all,enabled=enabled,tag="daily-scope")
        ApprovedSelect(date.format(point.date),selection.selected.points.map {date.format(it.date)},
            {day=selection.selected.points[it].date.toString()},Modifier.weight(1f),
            label=stringResource(R.string.beta_approved_reference_day_view),enabled=enabled,tag="daily-date")
    }
    ApprovedHeroCard(stringResource(R.string.beta_approved_reference_daily_net,date.format(point.date)),betaSummaryMoney(point.net),modifier=Modifier.testTag("daily-net"))
    ApprovedSpendingTrend(selection.source,stringResource(R.string.beta_approved_reference_daily_title),
        stringResource(when {asset==null -> R.string.beta_approved_dashboard_all_trend_basis
            snapshot.debitCards.any {it.cardId==asset} -> R.string.beta_approved_dashboard_mada_trend_basis
            else -> R.string.beta_approved_approved_card_expense_basis}),
        MasroofHeroRole.Brand,enabled,selection.chartKey,false,selectedDate=point.date,onDaySelected={day=it.toString()},scopeLabel=asset?.let(labels::get) ?: all)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofReference.metricGap)) {
        DashboardMetric(betaSummaryMoney(point.beforeRefunds),MasroofThemeExtras.extendedColors.outflow,Modifier.weight(1f).testTag("daily-before-refunds")) {
            ApprovedText(stringResource(R.string.beta_approved_reference_before_refunds),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        DashboardMetric(betaSummaryMoney(point.refunds),MasroofThemeExtras.extendedColors.inflow,Modifier.weight(1f).testTag("daily-refunds")) {
            ApprovedText(stringResource(R.string.beta_approved_reference_day_refunds),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    BetaScopeTransactionRows(snapshot,selection.transactions,stringResource(R.string.beta_approved_reference_day_transactions),enabled,onTransaction,complete && selection.complete)
}
