package com.baraa.masroof.presentation.dashboard

import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CommitmentPaymentStatus
import com.baraa.masroof.application.dashboard.CommitmentDashboardSource
import com.baraa.masroof.application.dashboard.CommitmentsOverview
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.ui.theme.MasroofThemeExtras
import java.time.format.DateTimeFormatter

@Composable
fun CommitmentsSection(overview: CommitmentsOverview, modifier: Modifier = Modifier,
    onOpenCredit: (() -> Unit)? = null) {
    if (!overview.hasContent) return
    val colors = MasroofThemeExtras.extendedColors
    val pending = overview.rows.filter { it.status == CommitmentPaymentStatus.UNPAID }
    val locale = LocalConfiguration.current.locales[0]
    com.baraa.masroof.ui.dashboard.DashboardCommitmentBand(
        stringResource(R.string.reference_period_commitments),
        stringResource(R.string.reference_waiting_payment, formatLocalizedMoney(overview.remaining)),
        pending.take(2).map { row -> com.baraa.masroof.application.locale.AppDigits.display(commitmentRowName(row) +
            (row.expectedDate?.let { " · " + DateTimeFormatter.ofPattern("d MMM", locale).format(it) } ?: "")) },
        modifier, details = if (onOpenCredit != null && pending.any { it.source == CommitmentDashboardSource.CREDIT_CARD }) ({
            com.baraa.masroof.presentation.common.MasroofTextButton(onClick = onOpenCredit) {
                Text(stringResource(R.string.reference_card_details), style = MaterialTheme.typography.bodySmall, color = colors.liability)
            }
        }) else null)
}
