package com.baraa.masroof.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class MasroofBarChartTest {
    @Test
    fun progress_clampsNegativeValuesToZeroBarWidth() {
        assertEquals(0f, MasroofBarChart.progress(BigDecimal("-10"), BigDecimal("100")), 0.001f)
    }

    @Test
    fun progress_scalesAgainstMax() {
        assertEquals(0.5f, MasroofBarChart.progress(BigDecimal("50"), BigDecimal("100")), 0.001f)
    }

    @Test
    fun progress_returnsZeroWhenMaxIsZero() {
        assertEquals(0f, MasroofBarChart.progress(BigDecimal("50"), BigDecimal.ZERO), 0.001f)
    }
}
