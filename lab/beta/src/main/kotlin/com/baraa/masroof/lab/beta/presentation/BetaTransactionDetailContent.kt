package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.BetaDisplayDigits
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading
import java.time.format.DateTimeFormatter

/** Source TransactionDetailScreen content. Persisted dashboard facts supply every financial value. */
@Composable
internal fun BetaDashboardTransactionDetail(snapshot: DashboardSnapshot,transactionId: String,
    onShare: (BetaListShare?)->Unit = {}) {
    val tx=snapshot.transactions.singleOrNull {it.key.transactionId==transactionId}
    if(tx==null) {Text(stringResource(R.string.beta_dash_missing),Modifier.testTag("transaction-missing"));return}
    val title=tx.merchant ?: tx.counterparty
    val type=stringResource(approvedFinancialTypeLabel(tx.effectiveType))
    val date=DateTimeFormatter.ofPattern("d MMM",LocalConfiguration.current.locales[0]).format(tx.time.instant.atZone(tx.time.zone))
    val direction=detailDirection(tx.effectiveType)
    val directionLabel=stringResource(direction.label)
    val card=tx.primaryCardLast4?.let {last4 ->
        val candidates=(snapshot.debitCards.map {Triple(it.cardId,it.last4,it.label)}+
            snapshot.creditFacilities.flatMap {it.cards}.map {Triple(it.cardId,it.last4,it.label)}+
            snapshot.standaloneCreditCards.map {Triple(it.cardId,it.last4,it.label)}).filter {it.first in tx.cards && it.second==last4}
        candidates.singleOrNull()?.third?.trim()?.takeIf(String::isNotEmpty)
            ?: stringResource(R.string.beta_detail_dashboard_credit_card_last4,last4)
    }
    val rateSource=tx.appliedFx?.let {fx ->stringResource(when(fx.source) {
        "SMS"->R.string.beta_detail_exchange_rate_source_sms
        "HISTORICAL_MERCHANT"->R.string.beta_detail_exchange_rate_source_historical_merchant
        "MARKET"->R.string.beta_detail_exchange_rate_source_market
        else->R.string.beta_approved_dashboard_value_unavailable
    })}
    val rateLabel=stringResource(R.string.beta_detail_transaction_detail_exchange_rate,tx.amount.currency.name)
    val fields=listOfNotNull(
        title?.let {stringResource(R.string.beta_detail_transaction_detail_merchant)+": "+it},
        stringResource(R.string.beta_detail_transaction_share_amount)+": "+betaSummaryMoney(tx.amount),
        stringResource(R.string.beta_detail_transaction_detail_type)+": "+type,
        stringResource(R.string.beta_detail_transaction_detail_date)+": "+date,
        stringResource(R.string.beta_detail_transaction_detail_direction)+": "+directionLabel,
        tx.primaryCardLast4?.let {stringResource(R.string.beta_detail_transaction_detail_card)+": "+stringResource(R.string.beta_detail_dashboard_credit_card_last4,it)},
        tx.sarEquivalent?.let {stringResource(R.string.beta_detail_transaction_detail_sar_equivalent)+": "+betaSummaryMoney(it)},
        tx.appliedFx?.let {rateLabel+": "+it.rateToSar.stripTrailingZeros().toPlainString()},
        rateSource?.let {stringResource(R.string.beta_detail_transaction_detail_exchange_rate_source)+": "+it})
    val share=BetaListShare(stringResource(R.string.beta_detail_transaction_detail_title),BetaDisplayDigits.display(
        (listOf(stringResource(R.string.beta_detail_transaction_share_detail_heading,stringResource(R.string.beta_home_brand)))+fields).map(String::trim).filter(String::isNotEmpty).joinToString("\n")),true,detail=true)
    SideEffect {onShare(share)}
    DisposableEffect(Unit) {onDispose {onShare(null)}}
    val colors=MasroofThemeExtras.extendedColors
    Column(Modifier.testTag("transaction-detail"),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.screenVertical)) {
        MasroofCard {
            Text(title ?: type,style=MaterialTheme.typography.titleMedium)
            ApprovedAmountText(betaSummaryMoney(tx.amount),ApprovedAmountRole.Hero,
                Modifier.padding(top=MasroofSpacing.sectionHeaderGap),color=when(direction) {
                    DetailDirection.INWARD,DetailDirection.INCOME,DetailDirection.TRANSFER_IN->colors.inflow
                    DetailDirection.OUTWARD->colors.outflow
                    else->MaterialTheme.colorScheme.onSurface
                })
        }
        DashboardSectionHeading(stringResource(R.string.beta_detail_transaction_detail_info_section),MasroofOutlineIcons.List)
        MasroofCard {
            Column(verticalArrangement=Arrangement.spacedBy(MasroofSpacing.carouselGap)) {
                ApprovedIconLabelRow(approvedFinancialTypeIcon(tx.effectiveType),stringResource(R.string.beta_detail_transaction_detail_type),trailing=type)
                ApprovedIconLabelRow(MasroofOutlineIcons.CalendarCheck,stringResource(R.string.beta_detail_transaction_detail_date),trailing=date)
                ApprovedIconLabelRow(direction.icon,stringResource(R.string.beta_detail_transaction_detail_direction),trailing=directionLabel)
                card?.let {ApprovedIconLabelRow(MasroofOutlineIcons.CreditCard,stringResource(R.string.beta_detail_transaction_detail_card),trailing=it)}
                title?.let {ApprovedIconLabelRow(MasroofOutlineIcons.Store,stringResource(R.string.beta_detail_transaction_detail_merchant),trailing=it)}
                tx.sarEquivalent?.let {ApprovedIconLabelRow(MasroofOutlineIcons.TrendingUp,stringResource(R.string.beta_detail_transaction_detail_sar_equivalent),trailing=betaSummaryMoney(it))}
                tx.appliedFx?.let {ApprovedIconLabelRow(MasroofOutlineIcons.ArrowUpRight,rateLabel,trailing=it.rateToSar.stripTrailingZeros().toPlainString())}
                rateSource?.let {ApprovedIconLabelRow(MasroofOutlineIcons.ShieldCheck,stringResource(R.string.beta_detail_transaction_detail_exchange_rate_source),trailing=it)}
            }
        }
    }
}

private enum class DetailDirection(val label: Int,val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    OUTWARD(R.string.beta_detail_dashboard_direction_out,MasroofOutlineIcons.ArrowUpRight),
    INWARD(R.string.beta_detail_dashboard_direction_in,MasroofOutlineIcons.Undo2),
    INCOME(R.string.beta_detail_dashboard_direction_income,MasroofOutlineIcons.TrendingUp),
    TRANSFER_IN(R.string.beta_detail_dashboard_direction_transfer_in,MasroofOutlineIcons.ArrowDownLeft),
    NEUTRAL(R.string.beta_detail_dashboard_direction_neutral,MasroofOutlineIcons.ArrowRightLeft),
}
private fun detailDirection(type: FinancialType)=when(type) {
    FinancialType.EXPENSE,FinancialType.FEE,FinancialType.EXTERNAL_TRANSFER_OUT,FinancialType.CREDIT_CARD_PAYMENT,
    FinancialType.CASH_WITHDRAWAL,FinancialType.BILL_PAYMENT,FinancialType.LOAN_REPAYMENT->DetailDirection.OUTWARD
    FinancialType.INCOME->DetailDirection.INCOME
    FinancialType.REFUND->DetailDirection.INWARD
    FinancialType.EXTERNAL_TRANSFER_IN->DetailDirection.TRANSFER_IN
    else->DetailDirection.NEUTRAL
}
