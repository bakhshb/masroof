package com.baraa.masroof.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class CommitmentAmountParsingTest {
    @Test
    fun acceptsTypedFormsAndNeverReadsADecimalCommaAsThousands() {
        assertEquals(BigDecimal("1234.50"), parseCommitmentAmount("1,234.50"))
        assertEquals(BigDecimal("1200"), parseCommitmentAmount("١٬٢٠٠"))
        assertEquals(BigDecimal("12.5"), parseCommitmentAmount("١٢٫٥"))
        assertEquals(BigDecimal("99"), parseCommitmentAmount(" 99 "))
        assertNull(parseCommitmentAmount("1,5"))
        assertNull(parseCommitmentAmount("12,50"))
        assertNull(parseCommitmentAmount("1,23,456"))
    }
}
