package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel as financialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeIcon as reviewTypeIcon
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import com.baraa.masroof.lab.beta.presentation.approved.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
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
import com.baraa.masroof.lab.beta.application.transactionDirection
import com.baraa.masroof.lab.beta.application.BetaTransactionDirection
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
    val unavailable = stringResource(R.string.beta_approved_dashboard_value_unavailable)
    val label = betaAccountDisplayLabel(account)
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
        AssetTransactions(snapshot, account.id, stringResource(R.string.beta_approved_reference_account_movements), enabled, onTransaction, ledgerComplete)
    }
}

@Composable
private fun DebitDetail(snapshot: DashboardSnapshot, debit: DebitSpending, enabled: Boolean,
    onTransaction: (String) -> Unit, ledgerComplete: Boolean) {
    val unavailable = stringResource(R.string.beta_approved_dashboard_value_unavailable)
    val label = debit.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_debit)
    val trend = BetaDashboardExplorer.trend(snapshot, BetaChartChoice(TrendScope.ASSET, asset = debit.cardId))
    val linked = debit.accountId?.let { id -> snapshot.accounts.singleOrNull { it.id == id }?.label?.takeIf(String::isNotBlank) }
    Column(Modifier.testTag("beta-debit-detail"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedDebitDetailSummaryCard(ApprovedDebitDetailUi(label,bankLabel(debit.cardId.bank),formatMoney(debit.spending,unavailable),
            linked ?: debit.accountId?.let {id -> snapshot.accounts.singleOrNull {it.id==id}?.last4} ?: unavailable,
            formatMoney(trend?.totalBeforeRefunds,unavailable),formatMoney(trend?.totalRefunds,unavailable)))
        betaCardAverage(snapshot,debit.cardId)?.let {ApprovedCardAverage(it)}
        BetaSectionTrend(snapshot, debit.cardId, MasroofHeroRole.Account, enabled)
        AssetTransactions(snapshot, debit.cardId, stringResource(R.string.beta_approved_reference_card_movements), enabled, onTransaction, ledgerComplete)
    }
}

@Composable
private fun CreditDetail(snapshot: DashboardSnapshot, card: CreditCardSummary, enabled: Boolean,
    onTransaction: (String) -> Unit, ledgerComplete: Boolean) {
    val unavailable = stringResource(R.string.beta_approved_dashboard_value_unavailable)
    val facility = snapshot.creditFacilities.singleOrNull { member -> card.cardId in member.cards.map { it.cardId } }
    val primary = facility?.cards?.singleOrNull { it.role == CreditCardRole.PRIMARY }?.last4
    val label = betaCreditDisplayLabel(card)
    Column(Modifier.testTag("beta-credit-detail"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedCreditDetailSummaryCard(ApprovedCreditDetailUi(label,bankLabel(card.cardId.bank),formatMoney(card.availableBalance,unavailable),
            formatMoney(facility?.statementDue,unavailable),facility?.dueDate?.let {day(it)} ?: unavailable,
            stringResource(when(card.role) {CreditCardRole.PRIMARY -> R.string.beta_approved_reference_primary_credit_type;CreditCardRole.SUPPLEMENTARY -> R.string.beta_approved_reference_supplementary_credit_type;CreditCardRole.STANDALONE,null -> R.string.beta_approved_reference_credit_type}),
            day(card.balanceAsOf,snapshot.query.zone),primary,card.role==CreditCardRole.SUPPLEMENTARY))
        betaCardAverage(snapshot,card.cardId)?.let {ApprovedCardAverage(it)}
        BetaSectionTrend(snapshot, card.cardId, MasroofHeroRole.Card, enabled)
        AssetTransactions(snapshot, card.cardId, stringResource(R.string.beta_approved_reference_card_movements), enabled, onTransaction, ledgerComplete)
    }
}

@Composable
private fun LoanDetail(snapshot: DashboardSnapshot, loan: LoanSummary, enabled: Boolean,
    onTransaction: (String) -> Unit, ledgerComplete: Boolean) {
    val unavailable = stringResource(R.string.beta_approved_dashboard_value_unavailable)
    val label = loan.label.takeIf(String::isNotBlank) ?: loanLabel(loan.type)
    val installmentDate = day(loan.installmentAsOf, snapshot.query.zone)
    Column(Modifier.testTag("beta-loan-detail"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedLoanDetailSummaryCard(ApprovedLoanDetailUi(label,bankLabel(loan.id.bank),formatMoney(loan.remaining,unavailable),
            BetaAssetDetailFacts.knownLoanType(loan.type)?.let {type -> stringResource(when(type) {"PERSONAL" -> R.string.beta_approved_settings_loan_type_personal;"AUTO" -> R.string.beta_approved_settings_loan_type_auto;else -> R.string.beta_approved_settings_loan_type_mortgage})} ?: unavailable,
            formatMoney(loan.latestInstallment,unavailable),installmentDate ?: unavailable,day(loan.remainingAsOf,snapshot.query.zone) ?: unavailable))
        AssetTransactions(snapshot, loan.id, stringResource(R.string.beta_approved_reference_loan_movements), enabled, onTransaction, ledgerComplete)
    }
}

@Composable
internal fun BetaSectionTrend(snapshot: DashboardSnapshot, asset: AssetId?, role: MasroofHeroRole, enabled: Boolean, scope: TrendScope = TrendScope.ASSET) {
    val trend = BetaDashboardExplorer.trend(snapshot, BetaChartChoice(scope, asset = asset))
    val title=stringResource(when {
        scope==TrendScope.ACCOUNTS -> R.string.beta_approved_approved_accounts_daily
        scope==TrendScope.CREDIT_CARDS -> R.string.beta_approved_approved_credit_daily
        snapshot.accounts.any {it.id==asset} -> R.string.beta_approved_approved_account_daily
        else -> R.string.beta_approved_approved_card_daily
    })
    val explanation=stringResource(when {
        scope==TrendScope.ACCOUNTS -> R.string.beta_approved_reference_accounts_scope
        snapshot.accounts.any {it.id==asset} -> R.string.beta_approved_reference_account_detail_scope
        else -> R.string.beta_approved_approved_card_expense_basis
    })
    ApprovedSpendingTrend(trend,title,explanation,role,enabled,
        com.baraa.masroof.lab.beta.application.BetaChartDataWorkflow.key(scope,asset),
        showAverage=role==MasroofHeroRole.Card || snapshot.debitCards.any {it.cardId==asset})
}

@Composable
private fun AssetTransactions(snapshot: DashboardSnapshot, asset: AssetId, title: String, enabled: Boolean,
    onTransaction: (String) -> Unit, complete: Boolean) {
    val rows = BetaDashboardExplorer.transactions(snapshot, asset = asset)
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    val locale = LocalConfiguration.current.locales[0]
    val labels=dashboardAssetLabels(snapshot)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
        ApprovedSectionTitle(title)
        when {
            !complete -> Text(stringResource(R.string.beta_dash_unavailable_help), Modifier.testTag("transactions-unavailable"))
            rows.isEmpty() -> Text(stringResource(R.string.beta_dash_empty), Modifier.testTag("transactions-empty"))
            else -> MasroofCard {
                rows.forEach { row ->
                    val type = row.effectiveType
                    val direction = transactionDirection(row, asset.takeIf { id -> snapshot.accounts.any { it.id == id } })
                    val inward = direction == BetaTransactionDirection.INWARD
                    val neutral = direction == BetaTransactionDirection.NEUTRAL
                    val colors = MasroofThemeExtras.extendedColors
                    val (color, background) = when (type) {
                        FinancialType.LOAN_REPAYMENT -> colors.liability to colors.liabilitySoft
                        FinancialType.CREDIT_CARD_PAYMENT -> colors.card to colors.cardSoft
                        else -> if (inward) colors.inflow to colors.inflowSoft else if (neutral) colors.account to colors.accountSoft else colors.outflow to colors.outflowSoft
                    }
                    val label = stringResource(financialTypeLabel(type))
                    val cardLabel=row.cards.singleOrNull()?.let(labels::get) ?: row.primaryCardLast4?.let {"•• $it"}
                    val value = MasroofMoneyDisplay.format(row.amount.amount, row.amount.currency.name, language)
                    DashboardTransactionRow(row.merchant ?: row.counterparty ?: label,
                        label + (cardLabel?.let { " · $it" } ?: ""),
                        (if (inward) "+" else if (neutral) "" else "−") + value.substringBeforeLast(' '),
                        DateTimeFormatter.ofPattern("d MMM", locale).format(row.time.instant.atZone(row.time.zone).toLocalDate()) + " · " + value.substringAfterLast(' '),
                        reviewTypeIcon(type), color, background, Modifier.testTag("dash-transaction-${row.key.transactionId}"),
                        onClick = if (enabled) ({ onTransaction(row.key.transactionId) }) else null)
                }
            }
        }
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
    FlowBucket.SALARY -> R.string.beta_approved_dashboard_salary
    FlowBucket.OTHER_INCOME -> R.string.beta_approved_dashboard_other_income
    FlowBucket.EXTERNAL_IN -> R.string.beta_approved_dashboard_external_in_short
    FlowBucket.ACCOUNT_REFUNDS -> R.string.beta_approved_dashboard_account_refunds
    FlowBucket.EXTERNAL_OUT -> R.string.beta_approved_dashboard_external_out_short
    FlowBucket.CREDIT_SETTLEMENT -> R.string.beta_approved_dashboard_credit_card_payment
    FlowBucket.CASH_WITHDRAWAL -> R.string.beta_approved_dashboard_cash_withdrawals
    FlowBucket.BILLS -> R.string.beta_approved_dashboard_bill_payments
    FlowBucket.POS_PURCHASES -> R.string.beta_approved_dashboard_pos_purchases_short
    FlowBucket.FEES -> R.string.beta_approved_dashboard_fees_short
    FlowBucket.LOAN -> R.string.beta_approved_dashboard_loan_repayments
    FlowBucket.INTERNAL_IN -> R.string.beta_approved_dashboard_self_transfer_in
    FlowBucket.INTERNAL_OUT -> R.string.beta_approved_dashboard_self_transfer_out
}
