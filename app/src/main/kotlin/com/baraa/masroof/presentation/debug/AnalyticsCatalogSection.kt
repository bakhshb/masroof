package com.baraa.masroof.presentation.debug

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.MasroofSpacing
import java.math.BigDecimal

@Composable
internal fun AnalyticsCatalogSection() {
    Column(Modifier.testTag("analytics-design-components"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofSectionHeader(stringResource(R.string.analysis_shared_charts))
        com.baraa.masroof.presentation.common.MasroofCard {
            Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                MasroofValueBarRow("Current", "120", .8f)
                MasroofValueBarRow("Previous", "90", .6f, previous = true)
                MasroofDivergingBarRow("Increase", "↑ +30", .75f)
                MasroofDivergingBarRow("Decrease", "↓ −20", -.5f)
                MasroofDivergingBarRow("No change", "0", 0f)
                MasroofForecastSplit(BigDecimal("80"), BigDecimal("20"), "Actual 80, expected 20")
                MasroofTimelineRow("Published forecast", "Saved at the time of publication")
                MasroofAnalysisEmptyState("No outstanding payments", "Payments appear by due date", com.baraa.masroof.presentation.common.MasroofIcons.calendar)
                MasroofCoverageGrid(listOf(true, true, false, null, true), "Covered, gap and unknown days")
                var selected by remember { mutableIntStateOf(0) }
                MasroofDailyBarChart(listOf(MasroofDailyBar(BigDecimal("100"), false, "Actual 100", "1"),
                    MasroofDailyBar(null, false, "Coverage missing", "2"),
                    MasroofDailyBar(BigDecimal("-30"), false, "Refund −30", "3"),
                    MasroofDailyBar(BigDecimal("80"), true, "Expected 80", "4")), selected, { selected = it },
                    "Previous", "Next", { it.toPlainString() })
            }
        }
    }
}
