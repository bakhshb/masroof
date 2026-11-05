package com.baraa.masroof.presentation.dashboard

import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CommitmentDashboardSource
import com.baraa.masroof.application.dashboard.CommitmentsOverview
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.presentation.common.dueCountdown
import com.baraa.masroof.ui.theme.MasroofThemeExtras

@Composable
fun CommitmentsSection(overview: CommitmentsOverview, modifier: Modifier = Modifier,
    onOpenCredit: (() -> Unit)? = null) {
    if (!overview.hasContent) return
    val colors = MasroofThemeExtras.extendedColors
    val pending = overview.upcomingUnpaid
    val today = java.time.LocalDate.now()
    com.baraa.masroof.ui.dashboard.DashboardCommitmentBand(
        stringResource(R.string.reference_period_commitments),
        stringResource(R.string.reference_waiting_payment, formatLocalizedMoney(overview.remaining)),
        // The name is isolated so a Latin bill name keeps its place on an Arabic line.
        pending.take(2).map { row -> com.baraa.masroof.application.locale.AppDigits.display("\u2068${commitmentRowName(row)}\u2069" +
            (row.daysUntil(today)?.let { " · " + dueCountdown(it).first } ?: "")) },
        modifier, details = if (onOpenCredit != null && pending.any { it.source == CommitmentDashboardSource.CREDIT_CARD }) ({
            com.baraa.masroof.presentation.common.MasroofTextButton(onClick = onOpenCredit) {
                Text(stringResource(R.string.reference_card_details), style = MaterialTheme.typography.bodySmall, color = colors.liability)
            }
        }) else null)
}
