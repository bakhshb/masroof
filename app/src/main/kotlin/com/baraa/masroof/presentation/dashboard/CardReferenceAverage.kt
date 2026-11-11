package com.baraa.masroof.presentation.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import com.baraa.masroof.ui.theme.MasroofReference
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.DailySpendingTrend
import com.baraa.masroof.presentation.common.MasroofAverageBand
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.locale.formatLocalizedMoney
import com.baraa.masroof.presentation.locale.formatLocalizedTransactionDate

@Composable
internal fun CardReferenceAverage(trend: DailySpendingTrend, inCard: Boolean = false) {
    if (trend.points.isEmpty()) return
    val value = formatLocalizedMoney(trend.averageDailySpending)
    val hint = stringResource(R.string.reference_period_average, formatLocalizedTransactionDate(trend.points.last().date), trend.points.size)
    val guidance = stringResource(R.string.average_basis, trend.points.size) + "\n" +
        stringResource(R.string.chart_full_card_average_hint)
    val spacing = Modifier.padding(top = MasroofReference.sectionGap)
    if (inCard) MasroofAverageBand(value, hint, guidance, spacing)
    else MasroofCard { MasroofAverageBand(value, hint, guidance, spacing) }
}
