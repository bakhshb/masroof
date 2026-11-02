package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofTypography
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTypographyTest {
    @Test
    fun dashboardTextSizes_comeFromTheTheme() {
        assertEquals(18, MasroofTypography.headlineSmall.fontSize.value.toInt())
        assertEquals(14, MasroofTypography.labelLarge.fontSize.value.toInt())
    }
}
