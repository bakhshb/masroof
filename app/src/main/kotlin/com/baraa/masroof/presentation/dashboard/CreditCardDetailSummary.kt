package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CreditCardDashboardRow
import com.baraa.masroof.application.dashboard.CreditFacilityOverview
import com.baraa.masroof.application.dashboard.statementDueFor
import com.baraa.masroof.presentation.common.MasroofHeroCard
import com.baraa.masroof.presentation.common.MasroofDetailRow
import com.baraa.masroof.ui.theme.MasroofHeroRole
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CreditCardDetailSummaryCard(
    row: CreditCardDashboardRow,
    zoneId: ZoneId,
    cardNetwork: CardNetwork? = null,
    ownedCards: List<OwnedCardUi> = emptyList(),
    modifier: Modifier = Modifier,
    facility: CreditFacilityOverview? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val date = DateTimeFormatter.ofPattern("d MMM", locale)
    val unavailable = stringResource(R.string.dashboard_value_unavailable)
    val statement = row.statementDueFor(facility)
    val due = statement?.amount
    val dueDate = statement?.dueDate
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofHeroCard(
            title = row.displayLabel(ownedCards) + " · " + stringResource(R.string.approved_available_balance),
            value = row.snapshot?.availableBalance?.let { formatLocalizedMoney(it) } ?: unavailable,
            subtitle = com.baraa.masroof.presentation.settings.settingsBankLabel(row.bank) +
                (if (facility != null && facility.primaryLast4 != row.last4) " · " + stringResource(R.string.reference_linked_primary_caption, facility.primaryLast4) else ""),
            role = MasroofHeroRole.Card,
        )
        MasroofCard {
            com.baraa.masroof.presentation.common.MasroofText(stringResource(R.string.reference_card_data), style = MaterialTheme.typography.titleMedium)
            val shared = facility != null && facility.primaryLast4 != row.last4
            MasroofDetailRow(stringResource(if (shared) R.string.reference_shared_due_amount else R.string.approved_due_amount), due?.let { formatLocalizedMoney(it) } ?: unavailable,
                guidance = if (facility != null && facility.primaryLast4 != row.last4) stringResource(R.string.approved_shared_due) else null,
                valueColor = com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.liability)
            MasroofDetailRow(stringResource(R.string.approved_due_date), dueDate?.let(date::format) ?: unavailable)
            val registry = ownedCards.firstOrNull { it.bank == row.bank && it.last4 == row.last4 }
            val cardTypeLabel = when {
                registry?.cardRole == com.baraa.masroof.domain.model.CardRole.SUPPLEMENTARY ||
                    (facility != null && facility.primaryLast4 != row.last4) -> R.string.reference_supplementary_credit_type
                registry?.cardRole == com.baraa.masroof.domain.model.CardRole.PRIMARY -> R.string.reference_primary_credit_type
                else -> R.string.reference_credit_type
            }
            MasroofDetailRow(stringResource(R.string.reference_card_type), stringResource(cardTypeLabel))
            row.snapshot?.updatedAt?.let { MasroofDetailRow(stringResource(R.string.approved_balance_date), date.format(it.atZone(zoneId))) }
            if (facility != null && facility.primaryLast4 != row.last4) {
                MasroofDetailRow(stringResource(R.string.approved_linked_primary), "•• " + com.baraa.masroof.presentation.common.formatCardLast4(facility.primaryLast4), divider = false)
            }
        }
    }
}
