package com.baraa.masroof.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DomainModelInvariantTest {

    @Test
    fun confidence_rejectsScoreOutsideUnitInterval() {
        assertThrows(IllegalArgumentException::class.java) {
            Confidence(score = -0.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Confidence(score = 1.1)
        }
        Confidence(score = 0.0)
        Confidence(score = 1.0, reasons = listOf("exact match"))
    }

    @Test
    fun bank_isStableIdNotClosedEnum() {
        assertEquals(Bank.BANK_ALJAZIRA, Bank("BANK_ALJAZIRA"))
        assertEquals(Bank.UNKNOWN, Bank("UNKNOWN"))
        // Cross-bank owned transfers can name another institution without a
        // hardcoded enum member or multi-bank parser.
        val d360 = Bank("D360")
        assertNotEquals(Bank.BANK_ALJAZIRA, d360)
        assertEquals("D360", d360.id)
        assertThrows(IllegalArgumentException::class.java) {
            Bank("  ")
        }
    }
}
