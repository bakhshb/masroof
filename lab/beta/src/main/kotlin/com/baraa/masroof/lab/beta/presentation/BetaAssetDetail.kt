package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import com.baraa.masroof.lab.beta.presentation.approved.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.BetaChartChoice
import com.baraa.masroof.lab.beta.application.BetaDashboardExplorer
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.asset.CreditCardRole
import com.baraa.masroof.lab.finance.dashboard.AccountSummary
import com.baraa.masroof.lab.finance.dashboard.CreditCardSummary
import com.baraa.masroof.lab.finance.dashboard.DashboardSnapshot
import com.baraa.masroof.lab.finance.dashboard.DebitSpending
import com.baraa.masroof.lab.finance.dashboard.FacilitySummary
import com.baraa.masroof.lab.finance.dashboard.FlowBucket
import com.baraa.masroof.lab.finance.dashboard.LoanSummary
import com.baraa.masroof.lab.finance.dashboard.SignedAmount
import com.baraa.masroof.lab.finance.dashboard.TrendScope
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.lab.finance.value.Money
import com.baraa.masroof.ui.components.LineChartLabels
import com.baraa.masroof.ui.components.MasroofCard
import com.baraa.masroof.ui.components.MasroofChartAverageLegend
import com.baraa.masroof.ui.components.MasroofGuidanceButton
import com.baraa.masroof.ui.components.MasroofHeroCard
import com.baraa.masroof.ui.components.MasroofInteractiveLineChart
import com.baraa.masroof.ui.components.MasroofMoneyDisplay
import com.baraa.masroof.ui.components.MasroofOutlineIcons
import com.baraa.masroof.ui.dashboard.DashboardMetric
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading
import com.baraa.masroof.ui.dashboard.DashboardTransactionRow
import com.baraa.masroof.ui.dashboard.DashboardTrendHead
import com.baraa.masroof.ui.theme.MasroofFontWeights
import com.baraa.masroof.ui.theme.MasroofHeroRole
import com.baraa.masroof.ui.theme.MasroofReference
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Production detail geometry over current Beta facts. Compose does not sum, convert, or assign facility due to a card. */
@Composable
internal fun BetaAssetDetail(
    snapshot: DashboardSnapshot,
    account: AccountSummary?,
    debit: DebitSpending?,
    card: CreditCardSummary?,
    loan: LoanSummary?,
    enabled: Boolean,
    onTransaction: (String) -> Unit,
    ledgerComplete: Boolean,
) {
    when {
        account != null -> AccountDetail(snapshot, account, enabled, onTransaction, ledgerComplete)
        debit != null -> DebitDetail(snapshot, debit, enabled, onTransaction, ledgerComplete)
        card != null -> CreditDetail(snapshot, card, enabled, onTransaction, ledgerComplete)
        loan != null -> LoanDetail(snapshot, loan, enabled, onTransaction, ledgerComplete)
    }
}

@Composable
private fun AccountDetail(snapshot: DashboardSnapshot, account: AccountSummary, enabled: Boolean,
    onTransaction: (String) -> Unit, ledgerComplete: Boolean) {
    val flow = account.flow
    val unavailable = stringResource(R.string.beta_dash_unavailable)
    val label = account.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_account)
    Column(Modifier.testTag("beta-account-detail"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        val language=LocalConfiguration.current.locales[0].toLanguageTag()
        @Composable fun formatted(buckets: List<FlowBucket>): List<Pair<String,String>> = flow?.let { value ->
            BetaAssetDetailFacts.lines(value,buckets).map {line -> stringResource(bucketLabel(line.bucket)) to MasroofMoneyDisplay.format(line.amount,line.currency,language)}
        }.orEmpty()
        ApprovedAccountPeriodSummaryCard(label+" · "+stringResource(R.string.beta_home_remaining),bankLabel(account.id.bank),
            formatMoney(flow?.cashPosition,unavailable),formatMoney(flow?.totalIn,unavailable),formatMoney(flow?.totalOut,unavailable),
            formatted(BetaAssetDetailFacts.externalIn),formatted(BetaAssetDetailFacts.externalOut),formatted(BetaAssetDetailFacts.internal),
            trend={BetaSectionTrend(snapshot,account.id,MasroofHeroRole.Account,enabled)})
        if(flow==null) Text(stringResource(R.string.beta_dash_unavailable_help),Modifier.testTag("account-flow-unavailable"))
        AssetTransactions(snapshot, account.id, stringResource(R.string.beta_detail_account_movements), enabled, onTransaction, ledgerComplete)
    }
}

@Composable
private fun DebitDetail(snapshot: DashboardSnapshot, debit: DebitSpending, enabled: Boolean,
    onTransaction: (String) -> Unit, ledgerComplete: Boolean) {
    val unavailable = stringResource(R.string.beta_dash_unavailable)
    val label = debit.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_debit)
    val trend = BetaDashboardExplorer.trend(snapshot, BetaChartChoice(TrendScope.ASSET, asset = debit.cardId))
    val linked = debit.accountId?.let { id -> snapshot.accounts.singleOrNull { it.id == id }?.label?.takeIf(String::isNotBlank) }
    Column(Modifier.testTag("beta-debit-detail"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedDebitDetailSummaryCard(ApprovedDebitDetailUi(label,bankLabel(debit.cardId.bank),formatMoney(debit.spending,unavailable),
            linked ?: debit.accountId?.let {id -> snapshot.accounts.singleOrNull {it.id==id}?.last4} ?: unavailable,
            formatMoney(trend?.totalBeforeRefunds,unavailable),formatMoney(trend?.totalRefunds,unavailable)))
        BetaSectionTrend(snapshot, debit.cardId, MasroofHeroRole.Account, enabled)
        AssetTransactions(snapshot, debit.cardId, stringResource(R.string.beta_detail_card_movements), enabled, onTransaction, ledgerComplete)
    }
}

@Composable
private fun CreditDetail(snapshot: DashboardSnapshot, card: CreditCardSummary, enabled: Boolean,
    onTransaction: (String) -> Unit, ledgerComplete: Boolean) {
    val unavailable = stringResource(R.string.beta_dash_unavailable)
    val facility = snapshot.creditFacilities.singleOrNull { member -> card.cardId in member.cards.map { it.cardId } }
    val primary = facility?.cards?.singleOrNull { it.role == CreditCardRole.PRIMARY }?.last4
    val label = card.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_credit)
    Column(Modifier.testTag("beta-credit-detail"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedCreditDetailSummaryCard(ApprovedCreditDetailUi(label,bankLabel(card.cardId.bank),formatMoney(card.availableBalance,unavailable),
            formatMoney(facility?.statementDue,unavailable),facility?.dueDate?.let {day(it)} ?: unavailable,
            stringResource(when(card.role) {CreditCardRole.PRIMARY -> R.string.beta_approved_reference_primary_credit_type;CreditCardRole.SUPPLEMENTARY -> R.string.beta_approved_reference_supplementary_credit_type;CreditCardRole.STANDALONE,null -> R.string.beta_approved_reference_credit_type}),
            day(card.balanceAsOf,snapshot.query.zone),primary,card.role==CreditCardRole.SUPPLEMENTARY))
        BetaSectionTrend(snapshot, card.cardId, MasroofHeroRole.Card, enabled)
        AssetTransactions(snapshot, card.cardId, stringResource(R.string.beta_detail_card_movements), enabled, onTransaction, ledgerComplete)
    }
}

@Composable
private fun LoanDetail(snapshot: DashboardSnapshot, loan: LoanSummary, enabled: Boolean,
    onTransaction: (String) -> Unit, ledgerComplete: Boolean) {
    val unavailable = stringResource(R.string.beta_dash_unavailable)
    val label = loan.label.takeIf(String::isNotBlank) ?: loanLabel(loan.type)
    val installmentDate = day(loan.installmentAsOf, snapshot.query.zone)
    Column(Modifier.testTag("beta-loan-detail"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedLoanDetailSummaryCard(ApprovedLoanDetailUi(label,bankLabel(loan.id.bank),formatMoney(loan.remaining,unavailable),
            BetaAssetDetailFacts.knownLoanType(loan.type)?.let {type -> stringResource(when(type) {"PERSONAL" -> R.string.beta_detail_loan_personal;"AUTO" -> R.string.beta_detail_loan_auto;else -> R.string.beta_detail_loan_mortgage})} ?: unavailable,
            formatMoney(loan.latestInstallment,unavailable),installmentDate ?: unavailable,day(loan.remainingAsOf,snapshot.query.zone) ?: unavailable))
        AssetTransactions(snapshot, loan.id, stringResource(R.string.beta_detail_loan_movements), enabled, onTransaction, ledgerComplete)
    }
}

@Composable
internal fun BetaSectionTrend(snapshot: DashboardSnapshot, asset: AssetId?, role: MasroofHeroRole, enabled: Boolean, scope: TrendScope = TrendScope.ASSET) {
    val trend = BetaDashboardExplorer.trend(snapshot, BetaChartChoice(scope, asset = asset))
    val visual = trend?.let(BetaDashboardVisualMapper::trend)
    var selectedDate by rememberSaveable(asset?.bank?.value, asset?.localId, scope.name, snapshot.query.from.toString()) { mutableStateOf<String?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    DashboardSectionHeading(stringResource(R.string.beta_detail_daily), MasroofOutlineIcons.ChartLine)
    MasroofCard(Modifier.testTag("asset-trend")) {
        when {
            trend == null -> Text(stringResource(R.string.beta_dash_unavailable_help), Modifier.testTag("asset-chart-unavailable"))
            trend.points.isEmpty() -> Text(stringResource(R.string.beta_home_no_elapsed_days), style = MaterialTheme.typography.bodySmall)
            visual == null -> Text(stringResource(R.string.beta_dash_unavailable_help), Modifier.testTag("asset-chart-unavailable"))
            else -> {
                val index = visual.dates.indexOf(selectedDate).takeIf { it >= 0 } ?: visual.dates.lastIndex
                fun money(value: BigDecimal) = MasroofMoneyDisplay.format(value, visual.currency, locale.toLanguageTag())
                DashboardTrendHead(DateTimeFormatter.ofPattern("d MMM", locale).format(LocalDate.parse(visual.dates[index])), money(visual.values[index]),
                    average = visual.average?.let { average -> ({
                        MasroofChartAverageLegend(stringResource(R.string.beta_home_chart_average, money(average)), Modifier.weight(1f))
                    }) })
                MasroofInteractiveLineChart(visual.values, visual.average ?: BigDecimal.ZERO,
                    LineChartLabels(stringResource(R.string.beta_chart_day_axis), stringResource(R.string.beta_chart_previous_point),
                        stringResource(R.string.beta_chart_next_point), stringResource(R.string.beta_chart_average_announcement,
                            visual.average?.let(::money) ?: stringResource(R.string.beta_dash_unavailable))),
                    modifier = Modifier.testTag("asset-spending-chart"), selectedPointIndex = index, role = role,
                    onPointSelected = { if (enabled) selectedDate = visual.dates[it] },
                    pointLabel = { DateTimeFormatter.ofPattern("d MMM", locale).format(LocalDate.parse(visual.dates[it])) + " · " + money(visual.values[it]) },
                    axisLabel = { DateTimeFormatter.ofPattern("d/M", locale).format(LocalDate.parse(visual.dates[it])) },
                    currencyLabel = MasroofMoneyDisplay.currencyLabel(visual.currency, locale.toLanguageTag()), showAverage = visual.average != null)
            }
        }
    }
}

@Composable
private fun AssetTransactions(snapshot: DashboardSnapshot, asset: AssetId, title: String, enabled: Boolean,
    onTransaction: (String) -> Unit, complete: Boolean) {
    var limit by rememberSaveable(snapshot.query.toString(), asset.bank.value, asset.localId) { mutableIntStateOf(20) }
    val rows = BetaDashboardExplorer.transactions(snapshot, asset = asset)
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val locale = LocalConfiguration.current.locales[0]
    DashboardSectionHeading(title, MasroofOutlineIcons.List)
    when {
        !complete -> Text(stringResource(R.string.beta_dash_unavailable_help), Modifier.testTag("transactions-unavailable"))
        rows.isEmpty() -> Text(stringResource(R.string.beta_dash_empty), Modifier.testTag("transactions-empty"))
        else -> MasroofCard {
            rows.take(limit).forEach { row ->
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
                DashboardTransactionRow(row.merchant ?: row.counterparty ?: label,
                    label + (row.primaryCardLast4?.let { " · $it" } ?: ""),
                    (if (inward) "+" else if (neutral) "" else "−") + value.substringBeforeLast(' '),
                    DateTimeFormatter.ofPattern("d MMM", locale).format(row.time.instant.atZone(row.time.zone).toLocalDate()) + " · " + value.substringAfterLast(' '),
                    detailIcon(type), color, background, Modifier.testTag("dash-transaction-${row.key.transactionId}"),
                    onClick = if (enabled) ({ onTransaction(row.key.transactionId) }) else null)
            }
        }
    }
    if (complete && rows.size > limit) OutlinedButton(enabled = enabled, onClick = { limit += 20 }, modifier = Modifier.testTag("transactions-more")) {
        Text(stringResource(R.string.beta_dash_more))
    }
}

@Composable
private fun formatMoney(amount: SignedAmount?, unavailable: String): String =
    amount?.let { MasroofMoneyDisplay.format(it.amount, it.currency.name, LocalConfiguration.current.locales[0].toLanguageTag()) } ?: unavailable

@Composable
private fun formatMoney(amount: Money?, unavailable: String): String =
    amount?.let { MasroofMoneyDisplay.format(it.amount, it.currency.name, LocalConfiguration.current.locales[0].toLanguageTag()) } ?: unavailable

@Composable
private fun bankLabel(bank: BankId): String = stringResource(if (bank.value == "BANK_ALJAZIRA") R.string.beta_bank_aljazira else R.string.beta_bank_title)

@Composable
private fun day(instant: Instant?, zone: ZoneId): String? = instant?.let {
    DateTimeFormatter.ofPattern("d MMM", LocalConfiguration.current.locales[0]).format(it.atZone(zone).toLocalDate())
}

@Composable
private fun day(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMM", LocalConfiguration.current.locales[0]).format(date)

private fun bucketLabel(bucket: FlowBucket): Int = when (bucket) {
    FlowBucket.SALARY -> R.string.beta_detail_salary
    FlowBucket.OTHER_INCOME -> R.string.beta_detail_other_income
    FlowBucket.EXTERNAL_IN -> R.string.beta_review_transfer_in
    FlowBucket.ACCOUNT_REFUNDS -> R.string.beta_review_refund
    FlowBucket.EXTERNAL_OUT -> R.string.beta_review_transfer_out
    FlowBucket.CREDIT_SETTLEMENT -> R.string.beta_review_card_payment
    FlowBucket.CASH_WITHDRAWAL -> R.string.beta_review_cash
    FlowBucket.BILLS -> R.string.beta_review_bill
    FlowBucket.POS_PURCHASES -> R.string.beta_detail_pos
    FlowBucket.FEES -> R.string.beta_review_fee
    FlowBucket.LOAN -> R.string.beta_review_loan
    FlowBucket.INTERNAL_IN -> R.string.beta_detail_internal_in
    FlowBucket.INTERNAL_OUT -> R.string.beta_detail_internal_out
}

private fun detailIcon(type: FinancialType): ImageVector = when (type) {
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
