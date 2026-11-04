package com.baraa.masroof.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.baraa.masroof.application.analytics.parseAnalysisAmount
import java.math.BigDecimal

class CommitmentAmountParsingTest {
    @Test
    fun acceptsTypedFormsAndNeverReadsADecimalCommaAsThousands() {
        assertEquals(BigDecimal("1234.50"), parseAnalysisAmount("1,234.50"))
        assertEquals(BigDecimal("1200"), parseAnalysisAmount("١٬٢٠٠"))
        assertEquals(BigDecimal("12.5"), parseAnalysisAmount("١٢٫٥"))
        assertEquals(BigDecimal("99"), parseAnalysisAmount(" 99 "))
        assertNull(parseAnalysisAmount("1,5"))
        assertNull(parseAnalysisAmount("12,50"))
        assertNull(parseAnalysisAmount("1,23,456"))
    }
}
