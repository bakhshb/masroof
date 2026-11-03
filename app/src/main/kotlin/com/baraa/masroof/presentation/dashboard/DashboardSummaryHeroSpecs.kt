package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.AccountsSummary
import com.baraa.masroof.application.dashboard.CreditFacilitiesOverview
import com.baraa.masroof.application.dashboard.LoansOverview
import com.baraa.masroof.application.dashboard.SignedMoneyAmount
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.dashboard.aggregateRemainingBalance
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.presentation.common.MasroofCardAccent
import java.util.Locale

@Composable
fun accountsSummaryHeroSpec(
    fleet: AccountsSummary,
): DashboardSummaryHeroSpec {
    val totalInflow = fleet.totalInflow
    val totalOutflow = fleet.totalOutflow
    val unavailableLabel = stringResource(R.string.dashboard_value_unavailable)
    val locale = accountsSummaryHeroLocale
    val languageTag = locale.toLanguageTag()
    val formulaHint = if (totalInflow != null && totalOutflow != null) {
        stringResource(
            R.string.dashboard_remaining_formula,
            MoneyUiFormatter.format(totalInflow, languageTag),
            MoneyUiFormatter.format(totalOutflow, languageTag),
        )
    } else {
        null
    }

    return buildAccountsSummaryHeroSpec(
        fleet = fleet,
        remainingTitle = stringResource(R.string.dashboard_accounts_remaining_total_title),
        inflowTitle = stringResource(R.string.dashboard_total_inflow),
        outflowTitle = stringResource(R.string.dashboard_total_spent),
        unavailableLabel = unavailableLabel,
        formulaHint = formulaHint,
        footerHint = stringResource(R.string.dashboard_accounts_fleet_total_hint),
        languageTag = languageTag,
    )
}

private val accountsSummaryHeroLocale: Locale
    @Composable get() = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]

/** Cards hero: facility due (or paid statement, using the commitments decision) and the latest available limit. */
@Composable
fun CreditFacilitiesSummaryHero(overview: CreditFacilitiesOverview, locale: Locale) {
    val paid = overview.allFacilityDuesPaid
    val single = overview.facilities.singleOrNull()
    val longDate = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    val subtitle = single?.facilityDue?.dueDate?.let {
        stringResource(if (paid) R.string.entities_due_paid_caption else R.string.reference_due_date_caption, longDate.format(it))
    }
    com.baraa.masroof.presentation.common.MasroofHeroCard(
        title = stringResource(if (paid) R.string.entities_statement_amount else R.string.dashboard_credit_card_aggregate_due),
        value = overview.totalFacilityDue?.let { MoneyUiFormatter.format(it, locale.toLanguageTag()) }
            ?: stringResource(R.string.dashboard_value_unavailable),
        subtitle = subtitle,
        role = com.baraa.masroof.ui.theme.MasroofHeroRole.Card,
        badge = if (paid) ({
            com.baraa.masroof.presentation.common.MasroofBadge("✓ " + stringResource(R.string.dashboard_commitments_status_paid), MasroofCardAccent.Inflow)
        }) else null,
        footer = single?.latestAvailableBalance?.let { available -> {
            androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                com.baraa.masroof.presentation.common.MasroofText(stringResource(R.string.entities_available_limit),
                    androidx.compose.ui.Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                com.baraa.masroof.presentation.common.MasroofText(MoneyUiFormatter.format(available, locale.toLanguageTag()),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(fontWeight = com.baraa.masroof.ui.theme.MasroofFontWeights.semiBold))
            }
            single.latestAvailableBalanceAt?.let { at ->
                com.baraa.masroof.presentation.common.MasroofText(stringResource(R.string.entities_latest_bank_message,
                    java.time.format.DateTimeFormatter.ofPattern("d MMM", locale).format(at.atZone(java.time.ZoneId.systemDefault()))),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        } },
    )
}

@Composable
fun loansSummaryHeroSpec(
    overview: LoansOverview,
): DashboardSummaryHeroSpec {
    val locale = accountsSummaryHeroLocale
    return buildLoansSummaryHeroSpec(
        overview = overview,
        remainingTitle = stringResource(R.string.dashboard_loans_remaining_total_title),
        unavailableLabel = stringResource(R.string.dashboard_value_unavailable),
        languageTag = locale.toLanguageTag(),
    )
}

internal fun buildAccountsSummaryHeroSpec(
    fleet: AccountsSummary,
    remainingTitle: String,
    inflowTitle: String,
    outflowTitle: String,
    unavailableLabel: String,
    formulaHint: String?,
    footerHint: String,
    languageTag: String = AppLocale.TAG_AR,
): DashboardSummaryHeroSpec {
    val totalRemaining = fleet.totalRemaining
    return DashboardSummaryHeroSpec(
        accent = MasroofCardAccent.Account,
        primary = DashboardSummaryMetricItem(
            title = remainingTitle,
            amount = totalRemaining?.let { MoneyUiFormatter.format(it, languageTag) } ?: unavailableLabel,
            tone = DashboardMetricTone.Signed,
            signedAmount = totalRemaining?.amount,
            hint = formulaHint,
        ),
        secondary = listOf(
            moneyHeroMetric(inflowTitle, fleet.totalInflow, DashboardMetricTone.Inflow, unavailableLabel, languageTag),
            moneyHeroMetric(outflowTitle, fleet.totalOutflow, DashboardMetricTone.Outflow, unavailableLabel, languageTag),
        ),
        footerHint = footerHint,
    )
}

internal fun buildLoansSummaryHeroSpec(
    overview: LoansOverview,
    remainingTitle: String,
    unavailableLabel: String,
    languageTag: String = AppLocale.TAG_AR,
): DashboardSummaryHeroSpec {
    val aggregateRemaining = overview.aggregateRemainingBalance()
    return DashboardSummaryHeroSpec(
        accent = MasroofCardAccent.Liability,
        primary = DashboardSummaryMetricItem(
            title = remainingTitle,
            amount = aggregateRemaining?.let { MoneyUiFormatter.format(it, languageTag) } ?: unavailableLabel,
            tone = DashboardMetricTone.Liability,
        ),
        secondary = emptyList(),
    )
}

private fun moneyHeroMetric(
    title: String,
    money: Money?,
    tone: DashboardMetricTone,
    unavailableLabel: String,
    languageTag: String,
): DashboardSummaryMetricItem =
    DashboardSummaryMetricItem(
        title = title,
        amount = money?.let { MoneyUiFormatter.format(it, languageTag) } ?: unavailableLabel,
        tone = tone,
    )

