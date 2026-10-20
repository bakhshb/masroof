package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.application.approvedRegistryDisplayLabel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.BetaChartChoice
import com.baraa.masroof.lab.beta.application.BetaDashboardExplorer
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.value.Money
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.*
import com.baraa.masroof.ui.theme.*

@Composable
internal fun BetaFinancialDetailRow(label: String,value: String,color: Color=MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical=MasroofSpacing.sectionHeaderGap),horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.cardInnerGap),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=MasroofFontWeights.semiBold),color=color)
    }
}
@Composable internal fun betaSummaryMoney(value: SignedAmount?)=value?.let {betaMoney(it.amount.toPlainString(),it.currency.name)} ?: stringResource(R.string.beta_approved_dashboard_value_unavailable)
@Composable internal fun betaSummaryMoney(value: Money?)=value?.let {betaMoney(it.amount.toPlainString(),it.currency.name)} ?: stringResource(R.string.beta_approved_dashboard_value_unavailable)
@Composable private fun entityBank(id: AssetId)=stringResource(if(id.bank.value=="BANK_ALJAZIRA") R.string.beta_bank_aljazira else R.string.beta_bank_title)
@Composable private fun BetaSummaryMetric(label: Int,value: String,color: Color,modifier: Modifier) {
    DashboardMetric(value,color,modifier) {Text(stringResource(label),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}
@Composable
internal fun BetaAccountsSummary(snapshot: DashboardSnapshot,limit: Int,enabled: Boolean,onManage: ()->Unit,onSelect: (AssetId)->Unit) {
    val colors=MasroofThemeExtras.extendedColors
    ApprovedHeroCard(stringResource(R.string.beta_approved_dashboard_accounts_remaining_total_title),betaSummaryMoney(snapshot.totals?.accountRemaining),role=MasroofHeroRole.Account,
        guidance=listOfNotNull(if(snapshot.totals?.accountInflow!=null && snapshot.totals?.accountOutflow!=null)
            stringResource(R.string.beta_approved_dashboard_remaining_formula,betaSummaryMoney(snapshot.totals?.accountInflow),betaSummaryMoney(snapshot.totals?.accountOutflow)) else null,
            stringResource(R.string.beta_approved_dashboard_accounts_fleet_total_hint)).joinToString("\n\n"))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofReference.metricGap)) {
        BetaSummaryMetric(R.string.beta_approved_dashboard_total_inflow,betaSummaryMoney(snapshot.totals?.accountInflow),colors.inflow,Modifier.weight(1f))
        BetaSummaryMetric(R.string.beta_approved_dashboard_total_spent,betaSummaryMoney(snapshot.totals?.accountOutflow),colors.outflow,Modifier.weight(1f))
    }
    BetaSectionTrend(snapshot,null,MasroofHeroRole.Account,enabled,TrendScope.ACCOUNTS)
    ApprovedSectionTitle(stringResource(R.string.beta_approved_reference_followed_accounts))
    if(snapshot.accounts.isEmpty()) Text(stringResource(R.string.beta_approved_dashboard_accounts_summary_empty),Modifier.testTag("dash-empty"))
    snapshot.accounts.take(limit).forEach {a ->
        ApprovedAccountSummaryTile(ApprovedAccountTileUi(betaAccountDisplayLabel(a),entityBank(a.id),
            betaSummaryMoney(a.flow?.cashPosition),betaSummaryMoney(a.flow?.totalIn),betaSummaryMoney(a.flow?.totalOut)),
            Modifier.testTag("dash-asset-${a.id.bank.value}-${a.id.localId}"),({onSelect(a.id)}),enabled=enabled)
    }
}
@Composable
internal fun BetaCardsSummary(snapshot: DashboardSnapshot,limit: Int,enabled: Boolean,credit: Boolean,onCredit: (Boolean)->Unit,onManage: ()->Unit,onSelect: (AssetId)->Unit) {
    val colors=MasroofThemeExtras.extendedColors
    if(snapshot.creditFacilities.isEmpty() && snapshot.standaloneCreditCards.isEmpty() && snapshot.debitCards.isEmpty()) {
        Text(stringResource(R.string.beta_approved_dashboard_cards_summary_empty),Modifier.testTag("dash-empty"));return
    }
    Row(horizontalArrangement=Arrangement.spacedBy(MasroofReference.shortcutGap)) {
        ApprovedChoice(stringResource(R.string.beta_approved_reference_credit_tab),credit,{onCredit(true)},Modifier.testTag("cards-tab-credit"),enabled)
        ApprovedChoice(stringResource(R.string.beta_approved_reference_mada_tab),!credit,{onCredit(false)},Modifier.testTag("cards-tab-mada"),enabled)
    }
    if(credit) {
        ApprovedHeroCard(stringResource(R.string.beta_approved_dashboard_credit_card_aggregate_due),betaSummaryMoney(snapshot.totals?.facilityDue),
            subtitle=snapshot.creditFacilities.singleOrNull()?.takeIf {snapshot.standaloneCreditCards.isEmpty()}?.dueDate?.let {
                stringResource(R.string.beta_approved_reference_due_date_caption,java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy",androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(it))},
            role=MasroofHeroRole.Card,modifier=Modifier.testTag("cards-facility-due-total"))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofReference.metricGap)) {
            BetaSummaryMetric(R.string.beta_approved_reference_salary_spending,betaSummaryMoney(snapshot.totals?.creditSalarySpending),colors.card,Modifier.weight(1f))
            BetaSummaryMetric(R.string.beta_approved_reference_statement_spending,betaSummaryMoney(snapshot.totals?.creditStatementSpending),MaterialTheme.colorScheme.onSurface,Modifier.weight(1f))
        }
        BetaSectionTrend(snapshot,null,MasroofHeroRole.Card,enabled,TrendScope.CREDIT_CARDS)
    } else {
        ApprovedHeroCard(stringResource(R.string.beta_home_expenses),betaSummaryMoney(snapshot.totals?.debitSalarySpending),role=MasroofHeroRole.Account)
        val trend=BetaDashboardExplorer.trend(snapshot,BetaChartChoice(TrendScope.DEBIT_CARDS))
        if(trend!=null) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofReference.metricGap)) {
            BetaSummaryMetric(R.string.beta_detail_before_refunds,betaSummaryMoney(trend?.totalBeforeRefunds),colors.outflow,Modifier.weight(1f))
            BetaSummaryMetric(R.string.beta_dash_refunds,betaSummaryMoney(trend?.totalRefunds),colors.inflow,Modifier.weight(1f))
        }
    }
    val cards=snapshot.creditFacilities.flatMap {it.cards}+snapshot.standaloneCreditCards
    ApprovedSectionTitle(stringResource(R.string.beta_approved_reference_followed_cards))
    if(if(credit) cards.isEmpty() else snapshot.debitCards.isEmpty()) Text(stringResource(R.string.beta_dash_empty),Modifier.testTag("dash-empty"))
    if(credit) cards.take(limit).forEach {c ->
        val facility=snapshot.creditFacilities.singleOrNull {f -> f.cards.any {it.cardId==c.cardId}}
        val subtitle=stringResource(when(c.role) {
            CreditCardRole.PRIMARY -> R.string.beta_approved_dashboard_credit_facility_primary
            CreditCardRole.SUPPLEMENTARY -> R.string.beta_approved_dashboard_credit_facility_additional
            else -> R.string.beta_approved_dashboard_credit_card_credit_badge
        })
        ApprovedCreditSummaryTile(ApprovedCreditTileUi(betaCreditDisplayLabel(c),entityBank(c.cardId),subtitle,
            betaSummaryMoney(c.salarySpending?.net),betaSummaryMoney(c.statementSpending?.net),betaSummaryMoney(c.availableBalance),
            betaSummaryMoney(facility?.statementDue),facility?.dueDate?.let {betaSummaryDay(it)} ?: stringResource(R.string.beta_approved_dashboard_value_unavailable),c.role==CreditCardRole.SUPPLEMENTARY),
            Modifier.testTag("dash-asset-${c.cardId.bank.value}-${c.cardId.localId}"),({onSelect(c.cardId)}),
            betaCardAverage(snapshot,c.cardId),enabled=enabled)
    } else snapshot.debitCards.take(limit).forEach {c ->
        val linked=c.accountId?.let {id -> snapshot.accounts.singleOrNull {it.id==id}}?.let {betaAccountDisplayLabel(it)} ?: stringResource(R.string.beta_approved_dashboard_value_unavailable)
        val trend=BetaDashboardExplorer.trend(snapshot,BetaChartChoice(TrendScope.ASSET,asset=c.cardId))
        ApprovedDebitSummaryTile(ApprovedDebitTileUi(approvedRegistryDisplayLabel(c.label,stringResource(R.string.beta_asset_debit),c.last4.orEmpty()),
            entityBank(c.cardId),linked,betaSummaryMoney(trend?.totalBeforeRefunds),betaSummaryMoney(trend?.totalRefunds)),
            Modifier.testTag("dash-asset-${c.cardId.bank.value}-${c.cardId.localId}"),({onSelect(c.cardId)}),
            betaCardAverage(snapshot,c.cardId),enabled=enabled)
    }
}
@Composable
internal fun BetaLoansSummary(snapshot: DashboardSnapshot,limit: Int,enabled: Boolean,onManage: ()->Unit,onSelect: (AssetId)->Unit) {
    val colors=MasroofThemeExtras.extendedColors
    if(snapshot.loans.isEmpty()) {Text(stringResource(R.string.beta_approved_dashboard_loans_summary_empty),Modifier.testTag("dash-empty"));return}
    ApprovedHeroCard(stringResource(R.string.beta_approved_dashboard_loans_remaining_total_title),betaSummaryMoney(snapshot.totals?.loanRemaining),role=MasroofHeroRole.Liability)
    snapshot.loans.take(limit).forEach {loan ->
        ApprovedLoanSummaryTile(ApprovedLoanDetailUi(loan.label.takeIf(String::isNotBlank) ?: loanLabel(loan.type),entityBank(loan.id),
            betaSummaryMoney(loan.remaining),"",betaSummaryMoney(loan.latestInstallment),
            loan.installmentAsOf?.let {betaSummaryDate(it,snapshot.query.zone)} ?: stringResource(R.string.beta_approved_dashboard_value_unavailable),
            loan.remainingAsOf?.let {betaSummaryDate(it,snapshot.query.zone)} ?: stringResource(R.string.beta_approved_dashboard_value_unavailable)),
            Modifier.testTag("dash-asset-${loan.id.bank.value}-${loan.id.localId}"),({onSelect(loan.id)}),enabled=enabled)
    }
}
@Composable
internal fun loanLabel(type: String)=stringResource(when(type) {"PERSONAL"->R.string.beta_approved_settings_loan_type_personal;"AUTO"->R.string.beta_approved_settings_loan_type_auto;"MORTGAGE"->R.string.beta_approved_settings_loan_type_mortgage;else->R.string.beta_asset_loan})

@Composable
internal fun BetaCommitmentsSummary(summary: CommitmentsSummary, enabled: Boolean, onSelect: (CommitmentRow)->Unit) {
    val colors=MasroofThemeExtras.extendedColors
    val date=java.time.format.DateTimeFormatter.ofPattern("d MMM", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
    MasroofHeroCard(stringResource(R.string.beta_commitment_summary_dashboard_commitments_remaining),betaSummaryMoney(summary.remaining),
        stringResource(R.string.beta_commitment_summary_dashboard_commitments_total)+" "+betaSummaryMoney(summary.total)+" · "+stringResource(R.string.beta_commitment_summary_dashboard_commitments_paid)+" "+betaSummaryMoney(summary.paid),role=MasroofHeroRole.Liability,icon=MasroofOutlineIcons.Wallet)
    MasroofCard {
        if(summary.rows.isEmpty()) Text(stringResource(R.string.beta_commitment_summary_reference_no_commitments),Modifier.testTag("dash-empty"))
        summary.rows.forEach {row ->
            val paid=row.status==PaymentStatus.PAID
            val tint=if(paid) colors.inflow else colors.liability
            Row(Modifier.fillMaxWidth().testTag("commitment-summary-${row.occurrenceId}").clickable(enabled=enabled) {onSelect(row)}.padding(vertical=MasroofReference.rowVertical),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(MasroofReference.rowGap)) {
                Surface(shape=MasroofShapes.small,color=if(paid) colors.inflowSoft else colors.liabilitySoft) {
                    Box(Modifier.size(MasroofReference.iconBox),contentAlignment=Alignment.Center) {Icon(if(paid) MasroofOutlineIcons.CircleCheck else MasroofOutlineIcons.CalendarCheck,null,Modifier.size(MasroofReference.icon),tint=tint)}
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                    Text(row.label,style=MasroofReference.pair)
                    row.expectedDate?.let {Text(date.format(it),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    ApprovedBadge(stringResource(if(paid) R.string.beta_commitment_summary_dashboard_commitments_status_paid else R.string.beta_commitment_summary_dashboard_commitments_status_unpaid),if(paid) MasroofCardAccent.Inflow else MasroofCardAccent.Liability)
                }
                MasroofReferenceMoney(betaSummaryMoney(row.amount),style=MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider(color=colors.cardBorder)
        }
    }
}

@Composable
private fun betaSummaryDate(value: java.time.Instant,zone: java.time.ZoneId): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM",androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(value.atZone(zone))

@Composable internal fun betaAccountDisplayLabel(account: AccountSummary): String = approvedRegistryDisplayLabel(account.label,
    stringResource(when(account.category) {AccountCategory.CURRENT->R.string.beta_parity_current;AccountCategory.SAVINGS->R.string.beta_parity_savings;
        AccountCategory.WALLET->R.string.beta_approved_settings_account_type_wallet;AccountCategory.OTHER->R.string.beta_asset_account}),account.last4.orEmpty())
@Composable internal fun betaCreditDisplayLabel(card: CreditCardSummary): String=approvedRegistryDisplayLabel(card.label,
    stringResource(when(card.role) {CreditCardRole.PRIMARY->R.string.beta_approved_dashboard_credit_facility_primary;
        CreditCardRole.SUPPLEMENTARY->R.string.beta_approved_dashboard_credit_facility_additional;else->R.string.beta_approved_dashboard_credit_card_credit_badge}),card.last4.orEmpty())
@Composable private fun betaSummaryDay(value: java.time.LocalDate): String=
    java.time.format.DateTimeFormatter.ofPattern("d MMM",androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(value)
@Composable internal fun betaCardAverage(snapshot: DashboardSnapshot,id: AssetId): ApprovedAverageUi? {
    val trend=BetaDashboardExplorer.trend(snapshot,BetaChartChoice(TrendScope.ASSET,asset=id)) ?: return null
    if(trend.points.isEmpty() || trend.averageDailySpending==null) return null
    return ApprovedAverageUi(betaSummaryMoney(trend.averageDailySpending),
        stringResource(R.string.beta_approved_reference_period_average,betaSummaryDay(trend.points.last().date),trend.points.size),
        stringResource(R.string.beta_approved_approved_average_basis,trend.points.size)+"\n"+stringResource(R.string.beta_approved_chart_full_card_average_hint))
}
