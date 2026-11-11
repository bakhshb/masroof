package com.baraa.masroof.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class MasroofDesignTokensTest {
    @Test
    fun spacing_screenTokens_useSharedRhythm() {
        assertEquals(MasroofSpacing.screenHorizontal, MasroofSpacing.screenVertical)
        assertEquals(24, MasroofSpacing.screenPaddingLarge.value.toInt())
        assertEquals(12, MasroofSpacing.sectionGap.value.toInt())
        assertEquals(16, MasroofSpacing.cardInnerPadding.value.toInt())
    }

    @Test
    fun iconSizes_sectionHeader_matchesMedium() {
        assertEquals(MasroofIconSizes.md, MasroofIconSizes.sectionHeader)
        assertEquals(MasroofIconSizes.md, MasroofIconSizes.moneyRowLeading)
    }

    @Test
    fun elevation_cardsAreFlat() {
        assertEquals(0, MasroofElevation.card.value.toInt())
        assertEquals(0, MasroofElevation.navCard.value.toInt())
    }

    @Test
    fun iconSizes_logoAndHero_areDefined() {
        assertEquals(36, MasroofIconSizes.logo.value.toInt())
        assertEquals(48, MasroofIconSizes.hero.value.toInt())
    }
}
