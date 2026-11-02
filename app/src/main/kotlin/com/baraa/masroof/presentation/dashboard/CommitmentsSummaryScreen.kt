package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CommitmentDashboardRow
import com.baraa.masroof.application.dashboard.CommitmentDashboardSource
import com.baraa.masroof.application.dashboard.CommitmentMatchIssue
import com.baraa.masroof.application.dashboard.CommitmentPaymentStatus
import com.baraa.masroof.presentation.common.MasroofBadge
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofCardAccent
import com.baraa.masroof.presentation.common.MasroofHeroCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofReferenceMoney
import com.baraa.masroof.presentation.common.MasroofText
import com.baraa.masroof.presentation.common.MasroofTextButton
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.ui.theme.MasroofHeroRole
import com.baraa.masroof.ui.theme.MasroofReference
import com.baraa.masroof.ui.theme.MasroofShapes
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras
import java.time.format.DateTimeFormatter

@Composable
fun CommitmentsSummaryScreen(state: DashboardUiState, onBack: () -> Unit,
    onOpenCommitment: (CommitmentDashboardRow) -> Unit, onOpenPayment: ((String) -> Unit)? = null) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val overview = state.commitmentsOverview
    val date = DateTimeFormatter.ofPattern("d MMM", LocalConfiguration.current.locales[0])
    val colors = MasroofThemeExtras.extendedColors
    DashboardSummaryScaffold(stringResource(R.string.approved_commitments), state, onBack) {
        MasroofHeroCard(stringResource(R.string.dashboard_commitments_remaining), formatLocalizedMoney(overview.remaining),
            stringResource(R.string.dashboard_commitments_total) + " " + formatLocalizedMoney(overview.total) + " · " +
                stringResource(R.string.dashboard_commitments_paid) + " " + formatLocalizedMoney(overview.paid), role = MasroofHeroRole.Liability)
        MasroofCard {
            overview.rows.forEach { row ->
                val paid = row.status == CommitmentPaymentStatus.PAID
                val tint = if (paid) colors.inflow else colors.liability
                Row(Modifier.fillMaxWidth().clickable { onOpenCommitment(row) }.padding(vertical = MasroofReference.rowVertical),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MasroofReference.rowGap)) {
                    Surface(shape = MasroofShapes.small, color = if (paid) colors.inflowSoft else colors.liabilitySoft) {
                        Box(Modifier.size(MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                            Icon(if (paid) MasroofIcons.success else MasroofIcons.calendar, null,
                                Modifier.size(MasroofReference.icon), tint = tint)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                        MasroofText(commitmentRowName(row), style = MasroofReference.pair)
                        row.expectedDate?.let { MasroofText(date.format(it), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        MasroofBadge(stringResource(if (paid) R.string.dashboard_commitments_status_paid else R.string.dashboard_commitments_status_unpaid),
                            if (paid) MasroofCardAccent.Inflow else MasroofCardAccent.Liability)
                        row.paymentMatchIssue?.let { issue ->
                            MasroofText(stringResource(when (issue) {
                                CommitmentMatchIssue.AMBIGUOUS_PAYMENT -> R.string.commitment_payment_ambiguous
                                CommitmentMatchIssue.MISSING_SOURCE -> R.string.commitment_payment_missing_source
                            }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        row.paymentTransactionId?.let { id ->
                            if (onOpenPayment != null) MasroofTextButton(onClick = { onOpenPayment(id) }) {
                                MasroofText(stringResource(R.string.commitment_open_payment))
                            }
                        }
                    }
                    MasroofReferenceMoney(formatLocalizedMoney(row.amount), style = MaterialTheme.typography.titleSmall)
                }
                HorizontalDivider(color = colors.cardBorder)
            }
            if (overview.rows.isEmpty()) MasroofText(stringResource(R.string.reference_no_commitments))
        }
    }
}
