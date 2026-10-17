package com.baraa.masroof.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.ui.theme.MasroofHeroRole
import java.math.BigDecimal

@Composable
fun MasroofLineChart(values: List<BigDecimal>, referenceValue: BigDecimal,
    modifier: Modifier = Modifier, selectedPointIndex: Int? = null,
    onPointSelected: ((Int) -> Unit)? = null, pointLabel: (Int) -> String = { "" },
    role: MasroofHeroRole = MasroofHeroRole.Brand,
    axisLabel: (Int) -> String = { "" }, currencyLabel: String = "", showAverage: Boolean = false) {
    MasroofInteractiveLineChart(values, referenceValue, modifier, selectedPointIndex,
        onPointSelected, pointLabel, role, axisLabel, currencyLabel, showAverage)
}

@Composable
fun MasroofInteractiveLineChart(values: List<BigDecimal>, referenceValue: BigDecimal,
    modifier: Modifier = Modifier, selectedPointIndex: Int? = null,
    onPointSelected: ((Int) -> Unit)? = null, pointLabel: (Int) -> String = { "" },
    role: MasroofHeroRole = MasroofHeroRole.Brand,
    axisLabel: (Int) -> String = { "" }, currencyLabel: String = "", showAverage: Boolean = false) {
    com.baraa.masroof.ui.components.MasroofInteractiveLineChart(values, referenceValue,
        com.baraa.masroof.ui.components.LineChartLabels(
            stringResource(R.string.reference_axis_day), stringResource(R.string.chart_previous_point),
            stringResource(R.string.chart_next_point), stringResource(R.string.dashboard_chart_average_value,
                com.baraa.masroof.application.locale.AppDigits.display("${referenceValue.toPlainString()} $currencyLabel"))),
        modifier, selectedPointIndex, onPointSelected, pointLabel, role, axisLabel, currencyLabel, showAverage,
        com.baraa.masroof.application.locale.AppDigits::display)
}
