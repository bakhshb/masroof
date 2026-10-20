package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.components.MasroofCard
import com.baraa.masroof.ui.theme.*

// Renderers ported from production summary components. Only the input/read-model adapter differs.
internal data class ApprovedCreditDetailUi(val title: String,val bank: String,val available: String,val due: String,
    val dueDate: String,val type: String,val balanceDate: String?,val linkedPrimary: String?,val shared: Boolean)
internal data class ApprovedDebitDetailUi(val title: String,val bank: String,val spending: String,
    val linkedAccount: String,val beforeRefunds: String,val refunds: String)
internal data class ApprovedLoanDetailUi(val title: String,val bank: String,val remaining: String,val type: String,
    val installment: String,val installmentDate: String,val balanceDate: String)

@Composable
internal fun ApprovedCreditDetailSummaryCard(ui: ApprovedCreditDetailUi,modifier: Modifier = Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedHeroCard(ui.title+" · "+stringResource(R.string.beta_approved_approved_available_balance),ui.available,
            ui.bank+(ui.linkedPrimary?.takeIf {ui.shared}?.let {" · "+stringResource(R.string.beta_approved_reference_linked_primary_caption,it)} ?: ""),role=MasroofHeroRole.Card)
        MasroofCard {
            ApprovedText(stringResource(R.string.beta_approved_reference_card_data),style=MaterialTheme.typography.titleMedium)
            ApprovedDetailRow(stringResource(if(ui.shared) R.string.beta_approved_reference_shared_due_amount else R.string.beta_approved_approved_due_amount),ui.due,
                guidance=if(ui.shared) stringResource(R.string.beta_approved_approved_shared_due) else null,valueColor=MasroofThemeExtras.extendedColors.liability)
            ApprovedDetailRow(stringResource(R.string.beta_approved_approved_due_date),ui.dueDate)
            ApprovedDetailRow(stringResource(R.string.beta_approved_reference_card_type),ui.type)
            ui.balanceDate?.let {ApprovedDetailRow(stringResource(R.string.beta_approved_approved_balance_date),it)}
            if(ui.shared && ui.linkedPrimary!=null) ApprovedDetailRow(stringResource(R.string.beta_approved_approved_linked_primary),"•• "+ui.linkedPrimary,divider=false)
        }
    }
}

@Composable
internal fun ApprovedDebitDetailSummaryCard(ui: ApprovedDebitDetailUi,modifier: Modifier = Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedHeroCard(ui.title,ui.spending,ui.bank,role=MasroofHeroRole.Account)
        MasroofCard {
            ApprovedText(stringResource(R.string.beta_approved_reference_card_data),style=MaterialTheme.typography.titleMedium)
            ApprovedDetailRow(stringResource(R.string.beta_approved_reference_card_type),stringResource(R.string.beta_approved_card_network_mada))
            ApprovedDetailRow(stringResource(R.string.beta_approved_dashboard_debit_card_linked_account),ui.linkedAccount)
            ApprovedDetailRow(stringResource(R.string.beta_approved_dashboard_expenses_before_refunds),ui.beforeRefunds)
            ApprovedDetailRow(stringResource(R.string.beta_approved_reference_refunds),ui.refunds,valueColor=MasroofThemeExtras.extendedColors.inflow)
        }
    }
}

@Composable
internal fun ApprovedLoanDetailSummaryCard(ui: ApprovedLoanDetailUi,modifier: Modifier = Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        ApprovedHeroCard(ui.title+" · "+stringResource(R.string.beta_approved_approved_loan_remaining),ui.remaining,ui.bank,role=MasroofHeroRole.Liability)
        MasroofCard {
            ApprovedText(stringResource(R.string.beta_approved_reference_loan_data),style=MaterialTheme.typography.titleMedium)
            ApprovedDetailRow(stringResource(R.string.beta_approved_reference_loan_type),ui.type)
            ApprovedDetailRow(stringResource(R.string.beta_approved_approved_last_installment),ui.installment,valueColor=MasroofThemeExtras.extendedColors.liability)
            ApprovedDetailRow(stringResource(R.string.beta_approved_approved_installment_date),ui.installmentDate)
            ApprovedDetailRow(stringResource(R.string.beta_approved_approved_balance_date),ui.balanceDate)
        }
    }
}
