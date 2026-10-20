package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*

// Original production bodies with immutable formatted inputs; financial values stay in FinanceCore.
internal data class ApprovedAccountTileUi(val title: String,val bank: String,val remaining: String,val inflow: String,val outflow: String)
internal data class ApprovedCreditTileUi(val title: String,val bank: String,val subtitle: String,val salary: String,val statement: String,
    val available: String,val due: String,val dueDate: String,val shared: Boolean)
internal data class ApprovedDebitTileUi(val title: String,val bank: String,val account: String,val gross: String,val refunds: String)
internal data class ApprovedAverageUi(val value: String,val hint: String,val guidance: String)

@Composable
internal fun ApprovedAccountSummaryTile(ui: ApprovedAccountTileUi,actionModifier: Modifier, onClick: (() -> Unit)?,enabled: Boolean=true) {
    ApprovedEntitySummaryCard(ui.title, "", MasroofOutlineIcons.Landmark, MasroofThemeExtras.extendedColors.account,
        onClick=onClick,enabled=enabled,actionModifier=actionModifier,
        guidance=stringResource(R.string.beta_approved_dashboard_account_remaining_calculated_hint),
        actionLabel=stringResource(R.string.beta_approved_reference_account_details),bankLabel=ui.bank) {
        ApprovedDetailRow(stringResource(R.string.beta_approved_dashboard_remaining_title),ui.remaining,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_dashboard_total_inflow),ui.inflow,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_dashboard_total_spent),ui.outflow,dense=true)
    }
}

@Composable
internal fun ApprovedCreditSummaryTile(ui: ApprovedCreditTileUi,actionModifier: Modifier,onClick: (() -> Unit)?,average: ApprovedAverageUi?,enabled: Boolean=true) {
    ApprovedEntitySummaryCard(ui.title,ui.subtitle,MasroofOutlineIcons.CreditCard,MasroofThemeExtras.extendedColors.card,
        onClick=onClick,enabled=enabled,actionModifier=actionModifier,actionLabel=stringResource(R.string.beta_approved_reference_card_details),bankLabel=ui.bank) {
        ApprovedDetailRow(stringResource(R.string.beta_approved_reference_salary_spending),ui.salary,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_reference_statement_spending),ui.statement,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_approved_available_balance),ui.available,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_approved_due_amount),ui.due,
            guidance=if(ui.shared) stringResource(R.string.beta_approved_approved_shared_due) else null,
            valueColor=MasroofThemeExtras.extendedColors.liability,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_approved_due_date),ui.dueDate,dense=true)
        average?.let {ApprovedCardAverage(it,true)}
    }
}

@Composable
internal fun ApprovedDebitSummaryTile(ui: ApprovedDebitTileUi,actionModifier: Modifier,onClick: (() -> Unit)?,average: ApprovedAverageUi?,enabled: Boolean=true) {
    ApprovedEntitySummaryCard(ui.title,stringResource(R.string.beta_approved_card_network_mada),MasroofOutlineIcons.CreditCard,
        MasroofThemeExtras.extendedColors.account,onClick=onClick,enabled=enabled,actionModifier=actionModifier,
        actionLabel=stringResource(R.string.beta_approved_reference_card_details),bankLabel=ui.bank) {
        ApprovedDetailRow(stringResource(R.string.beta_approved_dashboard_debit_card_linked_account),ui.account,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_dashboard_expenses_before_refunds),ui.gross,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_reference_refunds),ui.refunds,dense=true,valueColor=MasroofThemeExtras.extendedColors.inflow)
        average?.let {ApprovedCardAverage(it,true)}
    }
}

@Composable
internal fun ApprovedLoanSummaryTile(ui: ApprovedLoanDetailUi,actionModifier: Modifier,onClick: (() -> Unit)?,enabled: Boolean=true) {
    ApprovedEntitySummaryCard(ui.title,"",MasroofOutlineIcons.Landmark,MasroofThemeExtras.extendedColors.liability,
        onClick=onClick,enabled=enabled,actionModifier=actionModifier,actionLabel=stringResource(R.string.beta_approved_reference_loan_details),bankLabel=ui.bank) {
        ApprovedDetailRow(stringResource(R.string.beta_approved_approved_loan_remaining),ui.remaining,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_approved_last_installment),ui.installment,valueColor=MasroofThemeExtras.extendedColors.liability,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_approved_installment_date),ui.installmentDate,dense=true)
        ApprovedDetailRow(stringResource(R.string.beta_approved_approved_balance_date),ui.balanceDate,dense=true)
    }
}

@Composable
internal fun ApprovedCardAverage(ui: ApprovedAverageUi,inCard: Boolean=false) {
    val spacing=Modifier.padding(top=MasroofReference.sectionGap)
    if(inCard) ApprovedAverageBand(ui.value,ui.hint,ui.guidance,spacing)
    else MasroofCard {ApprovedAverageBand(ui.value,ui.hint,ui.guidance,spacing)}
}

@Composable
internal fun ApprovedAverageBand(value: String, periodHint: String, guidance: String?,
    modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MasroofReference.selectedNavigationColor(),
        shape = MasroofShapes.small) {
        Row(Modifier.padding(MasroofReference.contextualBandPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                ApprovedGuidanceLabel(androidx.compose.ui.res.stringResource(R.string.beta_approved_approved_daily_average),
                    guidance, style = MaterialTheme.typography.bodySmall.copy(fontWeight = MasroofFontWeights.semiBold),
                    contentColor = MaterialTheme.colorScheme.onSurface)
                ApprovedText(periodHint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ApprovedText(value.substringBeforeLast(' '), style = MasroofReference.averageValue)
                ApprovedText(androidx.compose.ui.res.stringResource(R.string.beta_approved_reference_average_unit,
                    value.substringAfterLast(' ')), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
