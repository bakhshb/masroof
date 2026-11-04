package com.baraa.masroof.presentation.common
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
@Composable
fun MasroofChartScopeTabs(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) =
    com.baraa.masroof.ui.components.MasroofChartScopeTabs(labels.map(com.baraa.masroof.application.locale.AppDigits::display), selected, onSelected = onSelected)
@Composable
fun MasroofChartAverageLegend(label: String, modifier: Modifier = Modifier) =
    com.baraa.masroof.ui.components.MasroofChartAverageLegend(com.baraa.masroof.application.locale.AppDigits.display(label), modifier)
