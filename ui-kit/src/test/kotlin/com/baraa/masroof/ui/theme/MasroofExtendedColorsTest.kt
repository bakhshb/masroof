package com.baraa.masroof.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasroofExtendedColorsTest {
    @Test
    fun darkExtendedColors_useDarkSurfaces() {
        val light = lightExtendedColors()
        val dark = darkExtendedColors()

        assertNotEquals(light.cardSurface, dark.cardSurface)
        assertNotEquals(light.navCardSurface, dark.navCardSurface)
        assertNotEquals(light.miniBackground, dark.miniBackground)

        assertTrue(isDark(dark.cardSurface))
        assertTrue(isDark(dark.navCardSurface))
        assertTrue(isDark(dark.miniBackground))
        assertTrue(isLight(light.cardSurface))
    }

    @Test
    fun darkExtendedColors_keepSemanticContrast() {
        val dark = darkExtendedColors()
        assertTrue(dark.inflow.red + dark.inflow.green + dark.inflow.blue > 0.5f)
        assertTrue(dark.outflow.red + dark.outflow.green + dark.outflow.blue > 0.5f)
    }

    @Test
    fun lightNavCardSurface_contrastsWithPageBackground() {
        val light = lightExtendedColors()
        assertTrue(relativeLuminance(light.navCardSurface) > relativeLuminance(MasroofLightColors.Background))
        assertTrue(isLight(light.navCardSurface))
    }

    @Test
    fun darkNavigationAndCards_shareTheApprovedSurface() {
        val dark = darkExtendedColors()
        assertEquals(dark.cardSurface, dark.navCardSurface)
    }

    private fun isDark(color: androidx.compose.ui.graphics.Color): Boolean {
        val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
        return luminance < 0.5f
    }

    private fun isLight(color: androidx.compose.ui.graphics.Color): Boolean {
        val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
        return luminance > 0.8f
    }

    private fun relativeLuminance(color: androidx.compose.ui.graphics.Color): Float {
        return 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    }
}
