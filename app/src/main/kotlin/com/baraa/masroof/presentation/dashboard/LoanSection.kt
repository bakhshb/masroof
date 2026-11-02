package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.LoanOverview
import com.baraa.masroof.presentation.common.MasroofHeroCard
import com.baraa.masroof.presentation.common.MasroofDetailRow
import com.baraa.masroof.ui.theme.MasroofHeroRole
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.settings.loanTypeLabel
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.ui.theme.MasroofThemeExtras
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LoanSummaryTile(loan: LoanOverview, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val date = DateTimeFormatter.ofPattern("d MMM", LocalConfiguration.current.locales[0])
    val unavailable = stringResource(R.string.dashboard_value_unavailable)
    EntitySummaryCard(loanName(loan), "", MasroofIcons.moneyMovement,
        MasroofThemeExtras.extendedColors.liability, modifier, onClick, actionLabel = stringResource(R.string.reference_loan_details), bankLabel = com.baraa.masroof.presentation.settings.settingsBankLabel(loan.bank)) {
        MasroofDetailRow(stringResource(R.string.approved_loan_remaining), loan.remainingBalance?.let { formatLocalizedMoney(it) } ?: unavailable, dense = true)
        MasroofDetailRow(stringResource(R.string.approved_last_installment), loan.latestInstallmentAmount?.let { formatLocalizedMoney(it) } ?: unavailable, valueColor = MasroofThemeExtras.extendedColors.liability, dense = true)
        MasroofDetailRow(stringResource(R.string.approved_installment_date), loan.latestInstallmentAsOf?.let { date.format(it.atZone(ZoneId.systemDefault())) } ?: unavailable, dense = true)
        MasroofDetailRow(stringResource(R.string.approved_balance_date), loan.remainingBalanceAsOf?.let { date.format(it.atZone(ZoneId.systemDefault())) } ?: unavailable, dense = true)
    }
}

@Composable
fun LoanDetailSummaryCard(
    loan: LoanOverview,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    val locale = LocalConfiguration.current.locales[0]
    val date = DateTimeFormatter.ofPattern("d MMM", locale)
    val unavailable = stringResource(R.string.dashboard_value_unavailable)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofHeroCard(loanName(loan) + " · " + stringResource(R.string.approved_loan_remaining),
            loan.remainingBalance?.let { formatLocalizedMoney(it) } ?: unavailable,
            subtitle = com.baraa.masroof.presentation.settings.settingsBankLabel(loan.bank), role = MasroofHeroRole.Liability)
        MasroofCard {
            com.baraa.masroof.presentation.common.MasroofText(stringResource(R.string.reference_loan_data), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            MasroofDetailRow(stringResource(R.string.reference_loan_type), loanTypeLabel(loan.loanType))
            MasroofDetailRow(stringResource(R.string.approved_last_installment), loan.latestInstallmentAmount?.let { formatLocalizedMoney(it) } ?: unavailable, valueColor = MasroofThemeExtras.extendedColors.liability)
            MasroofDetailRow(stringResource(R.string.approved_installment_date), loan.latestInstallmentAsOf?.let { date.format(it.atZone(zoneId)) } ?: unavailable)
            MasroofDetailRow(stringResource(R.string.approved_balance_date), loan.remainingBalanceAsOf?.let { date.format(it.atZone(zoneId)) } ?: unavailable)
        }
    }
}

/** The user's name for the loan, or its type in the app language. */
@Composable
internal fun loanName(loan: LoanOverview): String = loan.customName ?: loanTypeLabel(loan.loanType)

/** A commitment row's name; an unnamed loan shows its type in the app language. */
@Composable
internal fun commitmentRowName(row: com.baraa.masroof.application.dashboard.CommitmentDashboardRow): String =
    row.loanTypeFallback?.let { loanTypeLabel(it) } ?: row.displayName
