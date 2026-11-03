package com.baraa.masroof.presentation.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionPreviewSubtitleTest {
    @Test
    fun typeIsDroppedOnlyWhenTheTitleAlreadySaysItAndACardRemains() {
        assertEquals("Mada ···8219", previewSubtitle("Cash withdrawal", "Cash withdrawal", "Mada ···8219"))
        assertEquals("Purchase · Card ···7271", previewSubtitle("Keeta", "Purchase", "Card ···7271"))
        assertEquals("Cash withdrawal", previewSubtitle("Cash withdrawal", "Cash withdrawal", null))
    }
}
