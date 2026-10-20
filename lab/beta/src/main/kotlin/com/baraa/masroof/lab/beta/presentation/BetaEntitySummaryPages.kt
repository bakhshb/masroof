package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

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

/** Stable-ID bindings around the copied production renderer. */
@Composable
internal fun BetaEntitySummaryCard(title: String,subtitle: String,bank: String,icon: ImageVector,accent: Color,
    id: AssetId,enabled: Boolean,onClick: (AssetId)->Unit,content: @Composable ColumnScope.()->Unit) {
    com.baraa.masroof.lab.beta.presentation.approved.ApprovedEntitySummaryCard(
        title, subtitle, icon, accent, onClick = if(enabled) ({onClick(id)}) else null,
        modifier=Modifier.testTag("dash-asset-${id.bank.value}-${id.localId}"), bankLabel=bank, content=content)
}
@Composable
internal fun BetaFinancialDetailRow(label: String,value: String,color: Color=MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical=MasroofSpacing.sectionHeaderGap),horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.cardInnerGap),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=MasroofFontWeights.semiBold),color=color)
    }
}
@Composable
private fun BetaSummaryHeader(title: String,enabled: Boolean,onManage: ()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(title,style=MaterialTheme.typography.titleLarge)
        MasroofTextButton(enabled=enabled,onClick=onManage) {Text(stringResource(R.string.beta_complete_manage))}
    }
}
@Composable internal fun betaSummaryMoney(value: SignedAmount?)=value?.let {betaMoney(it.amount.toPlainString(),it.currency.name)} ?: stringResource(R.string.beta_dash_unavailable)
@Composable internal fun betaSummaryMoney(value: Money?)=value?.let {betaMoney(it.amount.toPlainString(),it.currency.name)} ?: stringResource(R.string.beta_dash_unavailable)
@Composable private fun entityBank(id: AssetId)=stringResource(if(id.bank.value=="BANK_ALJAZIRA") R.string.beta_bank_aljazira else R.string.beta_bank_title)
@Composable private fun BetaSummaryMetric(label: Int,value: String,color: Color,modifier: Modifier) {
    DashboardMetric(value,color,modifier) {Text(stringResource(label),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}
@Composable
internal fun BetaAccountsSummary(snapshot: DashboardSnapshot,limit: Int,enabled: Boolean,onManage: ()->Unit,onSelect: (AssetId)->Unit) {
    val colors=MasroofThemeExtras.extendedColors
    MasroofHeroCard(stringResource(R.string.beta_fix_account_remaining),betaSummaryMoney(snapshot.totals?.accountRemaining),role=MasroofHeroRole.Account,icon=MasroofOutlineIcons.Landmark)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofReference.metricGap)) {
        BetaSummaryMetric(R.string.beta_fix_inflow,betaSummaryMoney(snapshot.totals?.accountInflow),colors.inflow,Modifier.weight(1f))
        BetaSummaryMetric(R.string.beta_fix_outflow,betaSummaryMoney(snapshot.totals?.accountOutflow),colors.outflow,Modifier.weight(1f))
    }
    BetaSectionTrend(snapshot,null,MasroofHeroRole.Account,enabled,TrendScope.ACCOUNTS)
    BetaSummaryHeader(stringResource(R.string.beta_complete_accounts_count,snapshot.accounts.size),enabled,onManage)
    if(snapshot.accounts.isEmpty()) Text(stringResource(R.string.beta_dash_empty),Modifier.testTag("dash-empty"))
    snapshot.accounts.take(limit).forEachIndexed {index,a ->
        BetaEntitySummaryCard(a.label ?: stringResource(R.string.beta_dash_account_number,index+1),"•• "+a.last4.orEmpty(),entityBank(a.id),MasroofOutlineIcons.Landmark,colors.account,a.id,enabled,onSelect) {
            BetaFinancialDetailRow(stringResource(R.string.beta_fix_account_remaining),betaSummaryMoney(a.flow?.cashPosition),colors.account)
            BetaFinancialDetailRow(stringResource(R.string.beta_fix_inflow),betaSummaryMoney(a.flow?.totalIn),colors.inflow)
            BetaFinancialDetailRow(stringResource(R.string.beta_fix_outflow),betaSummaryMoney(a.flow?.totalOut),colors.outflow)
        }
    }
}
@Composable
internal fun BetaCardsSummary(snapshot: DashboardSnapshot,limit: Int,enabled: Boolean,credit: Boolean,onCredit: (Boolean)->Unit,onManage: ()->Unit,onSelect: (AssetId)->Unit) {
    val colors=MasroofThemeExtras.extendedColors
    Row(horizontalArrangement=Arrangement.spacedBy(MasroofReference.shortcutGap)) {
        BetaChoice(credit,{onCredit(true)},Modifier.testTag("cards-tab-credit"),enabled) {Text(stringResource(R.string.beta_asset_credit))}
        BetaChoice(!credit,{onCredit(false)},Modifier.testTag("cards-tab-mada"),enabled) {Text(stringResource(R.string.beta_asset_debit))}
    }
    if(credit) {
        MasroofHeroCard(stringResource(R.string.beta_dash_due),betaSummaryMoney(snapshot.totals?.facilityDue),role=MasroofHeroRole.Card,icon=MasroofOutlineIcons.CreditCard)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofReference.metricGap)) {
            BetaSummaryMetric(R.string.beta_dash_salary_spending,betaSummaryMoney(snapshot.totals?.creditSalarySpending),colors.card,Modifier.weight(1f))
            BetaSummaryMetric(R.string.beta_dash_statement_spending,betaSummaryMoney(snapshot.totals?.creditStatementSpending),MaterialTheme.colorScheme.onSurface,Modifier.weight(1f))
        }
        BetaSectionTrend(snapshot,null,MasroofHeroRole.Card,enabled,TrendScope.CREDIT_CARDS)
    } else {
        MasroofHeroCard(stringResource(R.string.beta_home_expenses),betaSummaryMoney(snapshot.totals?.debitSalarySpending),role=MasroofHeroRole.Account,icon=MasroofOutlineIcons.CreditCard)
        val trend=BetaDashboardExplorer.trend(snapshot,BetaChartChoice(TrendScope.DEBIT_CARDS))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofReference.metricGap)) {
            BetaSummaryMetric(R.string.beta_detail_before_refunds,betaSummaryMoney(trend?.totalBeforeRefunds),colors.outflow,Modifier.weight(1f))
            BetaSummaryMetric(R.string.beta_dash_refunds,betaSummaryMoney(trend?.totalRefunds),colors.inflow,Modifier.weight(1f))
        }
    }
    val cards=snapshot.creditFacilities.flatMap {it.cards}+snapshot.standaloneCreditCards
    BetaSummaryHeader(stringResource(R.string.beta_complete_cards_count,if(credit) cards.size else snapshot.debitCards.size),enabled,onManage)
    if(if(credit) cards.isEmpty() else snapshot.debitCards.isEmpty()) Text(stringResource(R.string.beta_dash_empty),Modifier.testTag("dash-empty"))
    if(credit) cards.take(limit).forEach {c ->
        val facility=snapshot.creditFacilities.singleOrNull {f -> f.cards.any {it.cardId==c.cardId}}
        BetaEntitySummaryCard(c.label ?: stringResource(R.string.beta_asset_credit),"•• "+c.last4.orEmpty(),entityBank(c.cardId),MasroofOutlineIcons.CreditCard,colors.card,c.cardId,enabled,onSelect) {
            BetaFinancialDetailRow(stringResource(R.string.beta_dash_available),betaSummaryMoney(c.availableBalance))
            BetaFinancialDetailRow(stringResource(R.string.beta_dash_salary_spending),betaSummaryMoney(c.salarySpending?.net),colors.card)
            if(c.role!=CreditCardRole.SUPPLEMENTARY) BetaFinancialDetailRow(stringResource(R.string.beta_dash_due),betaSummaryMoney(facility?.statementDue),colors.liability)
        }
    } else snapshot.debitCards.take(limit).forEach {c ->
        BetaEntitySummaryCard(c.label ?: stringResource(R.string.beta_asset_debit),"•• "+c.last4.orEmpty(),entityBank(c.cardId),MasroofOutlineIcons.CreditCard,colors.card,c.cardId,enabled,onSelect) {
            BetaFinancialDetailRow(stringResource(R.string.beta_dash_salary_spending),betaSummaryMoney(c.spending),colors.outflow)
        }
    }
}
@Composable
internal fun BetaLoansSummary(snapshot: DashboardSnapshot,limit: Int,enabled: Boolean,onManage: ()->Unit,onSelect: (AssetId)->Unit) {
    val colors=MasroofThemeExtras.extendedColors
    MasroofHeroCard(stringResource(R.string.beta_complete_loans_remaining),betaSummaryMoney(snapshot.totals?.loanRemaining),role=MasroofHeroRole.Liability,icon=MasroofOutlineIcons.HandCoins)
    if(snapshot.loans.isEmpty()) Text(stringResource(R.string.beta_dash_empty),Modifier.testTag("dash-empty"))
    snapshot.loans.take(limit).forEach {loan ->
        BetaEntitySummaryCard(loan.label.takeIf(String::isNotBlank) ?: loanLabel(loan.type),"",entityBank(loan.id),MasroofOutlineIcons.HandCoins,colors.liability,loan.id,enabled,onSelect) {
            BetaFinancialDetailRow(stringResource(R.string.beta_dash_loan_remaining),betaSummaryMoney(loan.remaining))
            BetaFinancialDetailRow(stringResource(R.string.beta_complete_last_installment),betaSummaryMoney(loan.latestInstallment),colors.liability)
            BetaFinancialDetailRow(stringResource(R.string.beta_complete_installment_date),loan.installmentAsOf?.let {betaSummaryDate(it,snapshot.query.zone)} ?: stringResource(R.string.beta_dash_unavailable))
            BetaFinancialDetailRow(stringResource(R.string.beta_detail_balance_date),loan.remainingAsOf?.let {betaSummaryDate(it,snapshot.query.zone)} ?: stringResource(R.string.beta_dash_unavailable))
        }
    }
    MasroofTextButton(enabled=enabled,onClick=onManage,modifier=Modifier.fillMaxWidth()) {Text(stringResource(R.string.beta_complete_manage))}
}
@Composable
internal fun loanLabel(type: String)=stringResource(when(type) {"PERSONAL"->R.string.beta_detail_loan_personal;"AUTO"->R.string.beta_detail_loan_auto;"MORTGAGE"->R.string.beta_detail_loan_mortgage;else->R.string.beta_asset_loan})

@Composable
internal fun BetaCommitmentsSummary(snapshot: DashboardSnapshot, summary: CommitmentsSummary, limit: Int, enabled: Boolean, onSelect: (AssetId)->Unit) {
    val colors=MasroofThemeExtras.extendedColors
    val date=java.time.format.DateTimeFormatter.ofPattern("d MMM", androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
    MasroofHeroCard(stringResource(R.string.beta_dash_remaining),betaSummaryMoney(summary.remaining),
        stringResource(R.string.beta_dash_total)+" "+betaSummaryMoney(summary.total)+" · "+stringResource(R.string.beta_dash_paid)+" "+betaSummaryMoney(summary.paid),role=MasroofHeroRole.Liability,icon=MasroofOutlineIcons.CalendarCheck)
    MasroofCard {
        if(summary.rows.isEmpty()) Text(stringResource(R.string.beta_dash_empty),Modifier.testTag("dash-empty"))
        summary.rows.take(limit).forEach {row ->
            val paid=row.status==PaymentStatus.PAID
            val target=row.loanId ?: row.facilityId
            val tint=if(paid) colors.inflow else colors.liability
            Row(Modifier.fillMaxWidth().clickable(enabled=enabled && target!=null) {target?.let(onSelect)}.padding(vertical=MasroofReference.rowVertical),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(MasroofReference.rowGap)) {
                Surface(shape=MasroofShapes.small,color=if(paid) colors.inflowSoft else colors.liabilitySoft) {
                    Box(Modifier.size(MasroofReference.iconBox),contentAlignment=Alignment.Center) {Icon(if(paid) MasroofOutlineIcons.CircleCheck else MasroofOutlineIcons.CalendarDays,null,Modifier.size(MasroofReference.icon),tint=tint)}
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                    Text(when {
                        row.loanId!=null -> snapshot.loans.singleOrNull {it.id==row.loanId}?.let {it.label.takeIf(String::isNotBlank) ?: loanLabel(it.type)} ?: stringResource(R.string.beta_asset_loan)
                        row.facilityId!=null -> stringResource(R.string.beta_asset_credit)
                        else -> if(row.label.isBlank()) stringResource(R.string.beta_dash_commitments) else row.label
                    },style=MaterialTheme.typography.bodyMedium)
                    row.expectedDate?.let {Text(date.format(it),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Text(stringResource(if(paid) R.string.beta_dash_paid else R.string.beta_dash_unpaid),style=MaterialTheme.typography.labelSmall,color=tint)
                }
                Text(betaSummaryMoney(row.amount),style=MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider(color=colors.cardBorder)
        }
    }
}

@Composable
private fun betaSummaryDate(value: java.time.Instant,zone: java.time.ZoneId): String =
    java.time.format.DateTimeFormatter.ofPattern("d MMM",androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(value.atZone(zone))
