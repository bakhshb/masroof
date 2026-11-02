package com.baraa.masroof.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedColorContrastTest {
    @Test
    fun heroesHaveReadableText_inLightAndDarkModes() {
        listOf(false, true).forEach { dark ->
            MasroofHeroRole.entries.forEach { role ->
                val (background, foreground) = heroColors(role, dark)
                assertTrue("$role dark=$dark must reach 4.5:1", contrast(background, foreground) >= 4.5)
            }
        }
        assertEquals(Color.White, MasroofLightColors.OnPrimary)
    }

    @Test
    fun accountBlueAndOtherSemanticText_remainReadableOnTheirSurfaces() {
        listOf(lightExtendedColors(), darkExtendedColors()).forEach { colors ->
            listOf(colors.account to colors.accountSoft, colors.card to colors.cardSoft,
                colors.liability to colors.liabilitySoft, colors.inflow to colors.inflowSoft,
                colors.outflow to colors.outflowSoft).forEach { (foreground, background) ->
                assertTrue("Semantic text must reach 4.5:1", contrast(background, foreground) >= 4.5)
            }
        }
    }

    @Test
    fun primaryPairsSupportLargeText_inLightAndDarkModes() {
        listOf(
            MasroofLightColors.OnPrimaryContainer to MasroofLightColors.PrimaryContainer,
            MasroofLightColors.OnPrimary to MasroofLightColors.Primary,
            MasroofDarkColors.OnPrimaryContainer to MasroofDarkColors.PrimaryContainer,
            MasroofDarkColors.OnPrimary to MasroofDarkColors.Primary,
        ).forEach { (foreground, background) ->
            assertTrue("Primary text must reach 3:1", contrast(background, foreground) >= 3.0)
        }
    }

    @Test
    fun darkOnSurface_supportsNormalTextOnCardSurface() {
        assertTrue(contrast(MasroofDarkColors.SurfaceContainer, MasroofDarkColors.OnSurface) >= 4.5)
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = luminance(first); val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double = if (value <= 0.04045f) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
