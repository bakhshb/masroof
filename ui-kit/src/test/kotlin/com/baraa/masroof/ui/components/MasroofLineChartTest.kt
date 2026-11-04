package com.baraa.masroof.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class MasroofLineChartTest {
    @Test
    fun cappedOutlier_onlyWhenOneDayIsMoreThanFourTimesTheNext() {
        fun values(vararg v: String) = v.map(::BigDecimal)
        assertEquals(2, MasroofLineChart.cappedOutlierIndex(values("500", "700", "29600", "300")))
        assertEquals(null, MasroofLineChart.cappedOutlierIndex(values("500", "700", "2800", "300")))
        assertEquals(null, MasroofLineChart.cappedOutlierIndex(values("29600", "29600", "300")))
        assertEquals(null, MasroofLineChart.cappedOutlierIndex(values("0", "0", "900")))
        assertEquals(null, MasroofLineChart.cappedOutlierIndex(values("100", "9000")))
    }

    @Test
    fun chartRange_stopsJustAboveTheOtherDaysWhenCapped() {
        val values = listOf("500", "700", "29600", "300").map(::BigDecimal)
        assertEquals(BigDecimal("800"), MasroofLineChart.chartRange(values, BigDecimal("400"), showAverage = true, capOutlier = true).maximum)
        assertEquals(MasroofLineChart.referenceRange(values), MasroofLineChart.chartRange(values, BigDecimal.ZERO, showAverage = false, capOutlier = false))
    }

    @Test
    fun chartRange_ignoresTheAverageRaisedByTheCappedDay() {
        val values = listOf("100", "150", "120", "5000", "80").map(::BigDecimal)
        val average = values.reduce(BigDecimal::add).divide(BigDecimal(values.size))
        assertEquals(BigDecimal("1090"), average)
        assertEquals(BigDecimal("200"), MasroofLineChart.chartRange(values, average, showAverage = true, capOutlier = true).maximum)
        assertEquals(BigDecimal("5500"), MasroofLineChart.chartRange(values, average, showAverage = true, capOutlier = false).maximum)
    }

    @Test
    fun nearestPointIndex_selectsClosestPointAndClampsToChartBounds() {
        assertEquals(0, MasroofLineChart.nearestPointIndex(x = -10f, width = 100f, pointCount = 3))
        assertEquals(1, MasroofLineChart.nearestPointIndex(x = 54f, width = 100f, pointCount = 3))
        assertEquals(2, MasroofLineChart.nearestPointIndex(x = 120f, width = 100f, pointCount = 3))
    }

    @Test
    fun nearestPointIndex_handlesSingleAndInvalidCharts() {
        assertEquals(0, MasroofLineChart.nearestPointIndex(x = 50f, width = 100f, pointCount = 1))
        assertEquals(null, MasroofLineChart.nearestPointIndex(x = 50f, width = 0f, pointCount = 3))
        assertEquals(null, MasroofLineChart.nearestPointIndex(x = 50f, width = 100f, pointCount = 0))
    }

    @Test
    fun xForPoint_distributesPointsAcrossChartWidth() {
        assertEquals(0f, MasroofLineChartLayout.xForPoint(index = 0, width = 100f, pointCount = 5))
        assertEquals(50f, MasroofLineChartLayout.xForPoint(index = 2, width = 100f, pointCount = 5))
        assertEquals(100f, MasroofLineChartLayout.xForPoint(index = 4, width = 100f, pointCount = 5))
    }

    @Test
    fun xForPoint_mirrorsPointsForRtl() {
        assertEquals(100f, MasroofLineChartLayout.xForPoint(index = 0, width = 100f, pointCount = 5, mirrorHorizontally = true))
        assertEquals(50f, MasroofLineChartLayout.xForPoint(index = 2, width = 100f, pointCount = 5, mirrorHorizontally = true))
        assertEquals(0f, MasroofLineChartLayout.xForPoint(index = 4, width = 100f, pointCount = 5, mirrorHorizontally = true))
    }

    @Test
    fun touchXForNearestPoint_mirrorsRtlTouchesBackToLogicalAxis() {
        assertEquals(80f, MasroofLineChartLayout.touchXForNearestPoint(touchX = 20f, width = 100f, mirrorHorizontally = true))
        assertEquals(20f, MasroofLineChartLayout.touchXForNearestPoint(touchX = 80f, width = 100f, mirrorHorizontally = true))
        assertEquals(20f, MasroofLineChartLayout.touchXForNearestPoint(touchX = 20f, width = 100f, mirrorHorizontally = false))
    }

    @Test
    fun yFor_mapsValuesIntoDrawableHeight() {
        val range = MasroofLineChart.ValueRange(BigDecimal.ZERO, BigDecimal("100"))

        assertEquals(100f, MasroofLineChartLayout.yFor(BigDecimal.ZERO, range, top = 10f, chartHeight = 90f))
        assertEquals(10f, MasroofLineChartLayout.yFor(BigDecimal("100"), range, top = 10f, chartHeight = 90f))
        assertEquals(55f, MasroofLineChartLayout.yFor(BigDecimal("50"), range, top = 10f, chartHeight = 90f))
    }

    @Test
    fun tooltipLeft_centersOnAnchorAndClampsToChartEdges() {
        assertEquals(10f, MasroofLineChartLayout.tooltipLeft(anchorX = 20f, tooltipWidth = 40f, chartWidth = 100f, horizontalPadding = 10f))
        assertEquals(50f, MasroofLineChartLayout.tooltipLeft(anchorX = 70f, tooltipWidth = 40f, chartWidth = 100f, horizontalPadding = 10f))
        assertEquals(10f, MasroofLineChartLayout.tooltipLeft(anchorX = 5f, tooltipWidth = 40f, chartWidth = 100f, horizontalPadding = 10f))
    }

    @Test
    fun coerceSelectedIndex_clampsToValidRange() {
        assertEquals(null, MasroofLineChartLayout.coerceSelectedIndex(index = null, pointCount = 5))
        assertEquals(null, MasroofLineChartLayout.coerceSelectedIndex(index = -1, pointCount = 5))
        assertEquals(null, MasroofLineChartLayout.coerceSelectedIndex(index = 5, pointCount = 5))
        assertEquals(2, MasroofLineChartLayout.coerceSelectedIndex(index = 2, pointCount = 5))
        assertEquals(null, MasroofLineChartLayout.coerceSelectedIndex(index = 0, pointCount = 0))
    }
}
