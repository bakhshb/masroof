package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.PostingKey
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BetaDashboardAnalysisPanel(snapshot: DashboardSnapshot, enabled: Boolean, preferences: BetaDashboardViewPreferencesWorkflow? = null, onTransaction: (PostingKey) -> Unit) {
    val coroutineScope = rememberBetaUiScope()
    var preferenceBusy by remember { mutableStateOf(false) }
    var preferenceError by remember { mutableStateOf(false) }
    var preferencesLoaded by rememberSaveable { mutableStateOf(false) }
    var scopeName by rememberSaveable { mutableStateOf(TrendScope.OVERALL.name) }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var assetBank by rememberSaveable { mutableStateOf<String?>(null) }
    var assetId by rememberSaveable { mutableStateOf<String?>(null) }
    var bars by rememberSaveable { mutableStateOf(true) }
    var customize by rememberSaveable { mutableStateOf(false) }
    var draftScope by rememberSaveable { mutableStateOf(scopeName) }
    var draftCategory by rememberSaveable { mutableStateOf(categoryName) }
    var draftBank by rememberSaveable { mutableStateOf(assetBank) }
    var draftAsset by rememberSaveable { mutableStateOf(assetId) }
    var draftBars by rememberSaveable { mutableStateOf(bars) }
    var selectedDay by rememberSaveable(snapshot.query.from.toString()) { mutableStateOf<String?>(null) }
    val assets = dashboardAssetLabels(snapshot)
    BetaLaunchedEffect(preferences) {
        if (!preferencesLoaded && preferences != null) {
            preferenceBusy = true
            try {
                val saved = preferences.read()
                val asset = saved.choice.asset
                val valid = asset == null || asset in assets
                scopeName = if (valid) saved.choice.scope.name else TrendScope.OVERALL.name
                categoryName = if (valid) saved.choice.category?.name else null
                assetBank = if (valid) asset?.bank?.value else null; assetId = if (valid) asset?.localId else null
                bars = saved.bars; preferencesLoaded = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { preferenceError = true }
            finally { preferenceBusy = false }
        }
    }
    fun selected(bank: String?, id: String?) = assets.keys.singleOrNull { it.bank.value == bank && it.localId == id }
    val choice = BetaChartChoice(TrendScope.valueOf(scopeName), categoryName?.let(ChartCategory::valueOf), selected(assetBank, assetId))
    val selectedTrend = BetaDashboardExplorer.trend(snapshot, choice)
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        if (preferenceError) Text(stringResource(R.string.beta_chart_save_error), color = MaterialTheme.colorScheme.error)
        OutlinedButton(enabled = enabled && !preferenceBusy, modifier = Modifier.testTag("dash-customize"), onClick = {
            draftScope = scopeName; draftCategory = categoryName; draftBank = assetBank; draftAsset = assetId; draftBars = bars; customize = true
        }) { Text(stringResource(R.string.beta_chart_customize)) }
        Text(stringResource(trendScopeLabel(choice.scope)), style = MaterialTheme.typography.titleMedium)
        choice.category?.let { Text(stringResource(categoryLabel(it))) }
        choice.asset?.let { Text(checkNotNull(assets[it])) }
        Text(stringResource(R.string.beta_chart_full_scope_help), style = MaterialTheme.typography.bodySmall)
        if (selectedTrend == null) Text(stringResource(R.string.beta_dash_unavailable_help), modifier = Modifier.testTag("chart-unavailable"))
        else {
            Text(stringResource(R.string.beta_dash_average)); Text(dashboardAmount(selectedTrend.averageDailySpending), modifier = Modifier.testTag("chart-average"))
            Text(stringResource(R.string.beta_chart_signed_help), style = MaterialTheme.typography.bodySmall)
            if (!bars) {
                val visual = BetaDashboardVisualMapper.trend(selectedTrend)
                if (visual == null) Text(stringResource(R.string.beta_dash_unavailable_help), modifier = Modifier.testTag("chart-line-unavailable"))
                else com.baraa.masroof.ui.components.MasroofInteractiveLineChart(
                    values = visual.values, referenceValue = visual.average ?: java.math.BigDecimal.ZERO,
                    labels = com.baraa.masroof.ui.components.LineChartLabels(stringResource(R.string.beta_chart_day_axis),
                        stringResource(R.string.beta_chart_previous_point), stringResource(R.string.beta_chart_next_point),
                        stringResource(R.string.beta_chart_average_announcement, dashboardAmount(selectedTrend.averageDailySpending))),
                    modifier = Modifier.testTag("beta-shared-line-chart"),
                    selectedPointIndex = visual.dates.indexOf(selectedDay).takeIf { it >= 0 } ?: 0,
                    onPointSelected = { index -> if (enabled) selectedDay = visual.dates[index] },
                    pointLabel = { index -> visual.dates[index] + " " + visual.values[index].toPlainString() + " " + visual.currency },
                    axisLabel = { index -> visual.dates[index].substringAfterLast('-') },
                    currencyLabel = visual.currency, showAverage = visual.average != null)
            }
            BetaDashboardExplorer.bars(selectedTrend).forEach { point ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(BetaSpacing.section)) {
                    Text(point.day.date.toString()); Text(dashboardAmount(point.day.net))
                    if (bars) point.magnitude?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    if (point.day.net != null) TextButton(enabled = enabled, modifier = Modifier.testTag("chart-day-${point.day.date}"), onClick = { selectedDay = point.day.date.toString() }) { Text(stringResource(R.string.beta_chart_day_transactions)) }
                } }
            }
        }
        selectedDay?.let { date ->
            Text(stringResource(R.string.beta_chart_day_transactions) + ": $date", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { selectedDay = null }, modifier = Modifier.testTag("chart-clear-day")) { Text(stringResource(R.string.beta_chart_clear_day)) }
            if (BetaDashboardExplorer.canDrillDown(snapshot, choice, LocalDate.parse(date)))
                BetaDashboardTransactions(snapshot, BetaDashboardExplorer.transactions(snapshot, choice, day = LocalDate.parse(date)), enabled, onTransaction)
            else Text(stringResource(R.string.beta_dash_unavailable_help), modifier = Modifier.testTag("chart-drilldown-unavailable"))
        }
    }
    if (customize) BetaBottomSheet(stringResource(R.string.beta_chart_customize), preferenceBusy, onCancel = { customize = false },
        canApply = draftScope != TrendScope.ASSET.name || selected(draftBank, draftAsset) != null,
        onApply = {
            if (!preferenceBusy) {
                preferenceBusy = true
                coroutineScope.launch {
                    try {
                        preferences?.save(BetaDashboardViewPreferences(BetaChartChoice(TrendScope.valueOf(draftScope), draftCategory?.let(ChartCategory::valueOf), selected(draftBank, draftAsset)), draftBars))
                        scopeName = draftScope; categoryName = draftCategory; assetBank = draftBank; assetId = draftAsset; bars = draftBars; selectedDay = null; customize = false; preferenceError = false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { preferenceError = true }
                    finally { preferenceBusy = false }
                }
            }
        }) {
        Text(stringResource(R.string.beta_chart_scope), style = MaterialTheme.typography.titleMedium)
        MasroofSheetChoices(TrendScope.entries.toList(),TrendScope.entries.filter {it.name==draftScope}.toSet(),{stringResource(trendScopeLabel(it))},
            {draftScope=it.name;draftCategory=null;draftBank=null;draftAsset=null},{"chart-scope-${it.name}"},enabled=!preferenceBusy)
        if(draftScope==TrendScope.ASSET.name) {
            val choices=assets.filterKeys {id -> snapshot.analysis?.trends?.any {it.assetId==id}==true}.keys.toList()
            MasroofSheetChoices(choices,choices.filter {it.bank.value==draftBank && it.localId==draftAsset}.toSet(),{assets[it].orEmpty()},
                {draftBank=it.bank.value;draftAsset=it.localId},{"chart-asset-${it.bank.value}-${it.localId}"},enabled=!preferenceBusy)
        }
        Text(stringResource(R.string.beta_chart_category),style=MaterialTheme.typography.titleMedium)
        val categories=listOf<ChartCategory?>(null)+ChartCategory.entries
        MasroofSheetChoices(categories,categories.filter {it?.name==draftCategory}.toSet(),{value -> if(value==null) stringResource(R.string.beta_chart_all_categories) else stringResource(categoryLabel(value))},
            {value -> draftCategory=value?.name;if(value!=null) {draftScope=TrendScope.FINANCIAL_OUTFLOWS.name;draftBank=null;draftAsset=null}},
            {"chart-category-${it?.name ?: "ALL"}"},enabled=!preferenceBusy)
        MasroofSheetChoices(listOf(true,false),setOf(draftBars),{stringResource(if(it) R.string.beta_chart_bars else R.string.beta_chart_table)},
            {draftBars=it},{if(it) "chart-bars" else "chart-table"},enabled=!preferenceBusy)

    }
}

@Composable
internal fun BetaDashboardTransactions(snapshot: DashboardSnapshot, rows: List<TransactionFacts> = snapshot.transactions,
    enabled: Boolean, onTransaction: (PostingKey) -> Unit, complete: Boolean = true) {
    var limit by rememberSaveable(snapshot.query.from.toString()) { mutableIntStateOf(20) }
    var search by rememberSaveable {mutableStateOf("")}
    var type by rememberSaveable {mutableStateOf<String?>(null)}
    var assetBank by rememberSaveable {mutableStateOf<String?>(null)}
    var assetLocal by rememberSaveable {mutableStateOf<String?>(null)}
    var filters by rememberSaveable {mutableStateOf(false)}
    val labels=dashboardAssetLabels(snapshot)
    val asset=labels.keys.singleOrNull {it.bank.value==assetBank && it.localId==assetLocal}
    val filtered=remember(rows,search,type,asset) {BetaDashboardExplorer.filterTransactions(rows,search,type,asset)}
    Text(stringResource(R.string.beta_transaction_title),style=MaterialTheme.typography.headlineSmall)
    OutlinedTextField(search,{search=it;limit=20},label={Text(stringResource(R.string.beta_fix_search))},
        singleLine=true,modifier=Modifier.fillMaxWidth().testTag("transactions-search"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
    TextButton(onClick={filters=true},enabled=enabled,modifier=Modifier.testTag("transactions-filters")) {Text(stringResource(R.string.beta_fix_filters))}
    if(filters) {
        var draftType by remember {mutableStateOf(type)}
        var draftAsset by remember {mutableStateOf(asset)}
        BetaBottomSheet(stringResource(R.string.beta_fix_filters),false,{filters=false},{type=draftType;assetBank=draftAsset?.bank?.value;assetLocal=draftAsset?.localId;limit=20;filters=false}) {
            val types=listOf<String?>(null)+rows.map {it.effectiveType.name}.distinct()
            MasroofSheetChoices(types,setOf(draftType),{value -> if(value==null) stringResource(R.string.beta_fix_all) else stringResource(financialTypeLabel(com.baraa.masroof.lab.finance.posting.FinancialType.valueOf(value)))},
                {draftType=it},{"transaction-type-${it ?: "all"}"})
            Text(stringResource(R.string.beta_fix_filter_account))
            val choices=listOf<AssetId?>(null)+labels.keys.toList()
            MasroofSheetChoices(choices,setOf(draftAsset),{value -> value?.let {labels[it]}.orEmpty().ifEmpty {stringResource(R.string.beta_fix_all)}},
                {draftAsset=it},{"transaction-asset-${it?.bank?.value}-${it?.localId}"})
        }
    }
    if(!complete) Text(stringResource(R.string.beta_fix_confirmed_only),Modifier.testTag("transactions-unavailable"))
    if(filtered.isEmpty()) Text(stringResource(if(rows.isEmpty()) R.string.beta_dash_empty else R.string.beta_fix_no_matches),Modifier.testTag("transactions-empty"))
    com.baraa.masroof.ui.components.MasroofCard {
        filtered.take(limit).forEach {row ->
            com.baraa.masroof.ui.dashboard.DashboardTransactionRow(
                row.merchant ?: row.counterparty ?: stringResource(financialTypeLabel(row.effectiveType)),
                stringResource(financialTypeLabel(row.effectiveType)) + row.primaryCardLast4?.let {" · •• $it"}.orEmpty(),
                betaMoney(row.amount.amount.toPlainString(),row.amount.currency.name),betaDate(row.time.instant,row.time.zone),
                com.baraa.masroof.ui.components.MasroofOutlineIcons.List,
                MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.secondaryContainer,
                Modifier.testTag("dash-transaction-${row.key.transactionId}"),onClick=if(enabled) ({onTransaction(row.key)}) else null)
        }
    }
    if(filtered.size>limit) OutlinedButton(onClick={limit+=20},modifier=Modifier.testTag("transactions-more")) {Text(stringResource(R.string.beta_dash_more))}

}

@Composable
internal fun BetaDashboardTransactionDetail(snapshot: DashboardSnapshot, transactionId: String) {
    val tx = snapshot.transactions.singleOrNull { it.key.transactionId == transactionId }
    if (tx == null) { Text(stringResource(R.string.beta_dash_missing), modifier = Modifier.testTag("transaction-missing")); return }
    val assets = dashboardAssetLabels(snapshot)
    Column(Modifier.testTag("transaction-detail"),verticalArrangement=Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.sectionGap)) {
        com.baraa.masroof.ui.components.MasroofHeroCard(
            tx.merchant ?: tx.counterparty ?: stringResource(financialTypeLabel(tx.effectiveType)),
            betaMoney(tx.amount.amount.toPlainString(),tx.amount.currency.name),
            subtitle=stringResource(financialTypeLabel(tx.effectiveType)),role=com.baraa.masroof.ui.theme.MasroofHeroRole.Account,
            icon=reviewTypeIcon(tx.effectiveType))
        com.baraa.masroof.ui.components.MasroofCard {
            Text(stringResource(R.string.beta_complete_transactions_data),style=MaterialTheme.typography.titleMedium)
            BetaFinancialDetailRow(stringResource(R.string.beta_review_type),stringResource(financialTypeLabel(tx.effectiveType)))
            BetaFinancialDetailRow(stringResource(R.string.beta_complete_review_date,betaDate(tx.time.instant,tx.time.zone)),"")
            tx.merchant?.let {BetaFinancialDetailRow(stringResource(R.string.beta_transaction_merchant),it)}
            tx.counterparty?.let {BetaFinancialDetailRow(stringResource(R.string.beta_transaction_counterparty),it)}
            BetaFinancialDetailRow(stringResource(R.string.beta_transaction_source),tx.source?.let(assets::get) ?: stringResource(R.string.beta_transaction_external_unknown))
            BetaFinancialDetailRow(stringResource(R.string.beta_transaction_destination),tx.destination?.let(assets::get) ?: stringResource(R.string.beta_transaction_external_unknown))
            if(tx.amount.currency!=com.baraa.masroof.lab.finance.value.Currency.SAR) {
                BetaFinancialDetailRow(stringResource(R.string.beta_transaction_sar_free),betaSummaryMoney(tx.sarEquivalent))
                BetaFinancialDetailRow(stringResource(R.string.beta_transaction_fx),tx.appliedFx?.rateToSar?.toPlainString() ?: stringResource(R.string.beta_dash_unavailable))
            }
            tx.internationalFee?.let {BetaFinancialDetailRow(stringResource(R.string.beta_transaction_fee),betaSummaryMoney(it))}
        }
    }
}

@Composable private fun dashboardAmount(value: SignedAmount?) = value?.let { betaMoney(it.amount.toPlainString(),it.currency.name) } ?: stringResource(R.string.beta_dash_unavailable)

@Composable internal fun dashboardAssetLabels(snapshot: DashboardSnapshot): Map<AssetId, String> = buildMap {
    snapshot.accounts.forEachIndexed { index, row -> put(row.id, (row.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_dash_account_number, index + 1)) + row.last4?.let {" · •• $it"}.orEmpty() + " · " + stringResource(if (row.id.bank.value == "BANK_ALJAZIRA") R.string.beta_bank_aljazira else R.string.beta_bank_title)) }
    snapshot.debitCards.forEach { put(it.cardId, (it.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_debit)) + " · " + it.last4.orEmpty()) }
    snapshot.creditFacilities.forEachIndexed { index, row ->
        put(row.facilityId, stringResource(R.string.beta_controls_facility_number, index + 1))
        row.cards.forEachIndexed { cardIndex, card -> put(card.cardId, (card.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_dash_card_number, cardIndex + 1)) + " · " + card.last4.orEmpty()) }
    }
    snapshot.standaloneCreditCards.forEach { card -> put(card.cardId,(card.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_credit))+" · "+card.last4.orEmpty()) }
    snapshot.loans.forEach { put(it.id, it.label.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_loan)) }
}

internal fun trendScopeLabel(scope: TrendScope): Int = when (scope) {
    TrendScope.OVERALL -> R.string.beta_chart_scope_overall; TrendScope.ACCOUNTS -> R.string.beta_chart_scope_accounts
    TrendScope.CREDIT_CARDS -> R.string.beta_chart_scope_credit; TrendScope.DEBIT_CARDS -> R.string.beta_chart_scope_debit
    TrendScope.PURCHASES -> R.string.beta_chart_scope_purchases; TrendScope.ASSET -> R.string.beta_chart_scope_asset
    TrendScope.FINANCIAL_OUTFLOWS -> R.string.beta_chart_scope_outflows
}
internal fun categoryLabel(category: ChartCategory): Int = when (category) {
    ChartCategory.CREDIT_PURCHASES -> R.string.beta_category_credit; ChartCategory.MADA_PURCHASES -> R.string.beta_category_mada
    ChartCategory.ACCOUNT_PURCHASES -> R.string.beta_category_account; ChartCategory.OTHER_EXPENSES -> R.string.beta_category_other
    ChartCategory.CASH_WITHDRAWALS -> R.string.beta_category_cash; ChartCategory.EXTERNAL_TRANSFERS -> R.string.beta_category_external
    ChartCategory.BILLS -> R.string.beta_category_bills; ChartCategory.LOAN_REPAYMENTS -> R.string.beta_category_loan
    ChartCategory.CREDIT_SETTLEMENTS -> R.string.beta_category_settlement; ChartCategory.FEES -> R.string.beta_category_fees
    ChartCategory.INTERNAL_TRANSFERS -> R.string.beta_category_internal; ChartCategory.UNATTRIBUTED_REFUNDS -> R.string.beta_category_refunds
}
