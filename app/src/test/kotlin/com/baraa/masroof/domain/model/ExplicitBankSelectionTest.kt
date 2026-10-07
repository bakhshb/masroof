package com.baraa.masroof.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitBankSelectionTest {
    @Test
    fun mergeKeepsStoredSelectionWhenQueueRefreshOmitsIt() {
        val existing = listOf(
            ExplicitBankSelection.REASON_AMBIGUOUS_BANK_ROUTE,
            ExplicitBankSelection.reasonFor(Bank.BANK_ALJAZIRA),
        )
        val merged = ExplicitBankSelection.mergePreservedSelection(
            existing = existing,
            incoming = listOf("needs_review"),
        )
        assertEquals(
            listOf(
                "needs_review",
                ExplicitBankSelection.reasonFor(Bank.BANK_ALJAZIRA),
            ),
            merged,
        )
        assertEquals(Bank.BANK_ALJAZIRA.id, ExplicitBankSelection.selectedBankId(merged))
    }

    @Test
    fun incomingSelectionReplacesTheStoredOne() {
        val merged = ExplicitBankSelection.mergePreservedSelection(
            existing = listOf(ExplicitBankSelection.reasonFor(Bank.BANK_ALJAZIRA)),
            incoming = listOf("needs_review", ExplicitBankSelection.reasonFor(Bank("LOOKALIKE_BANK"))),
        )
        assertEquals("LOOKALIKE_BANK", ExplicitBankSelection.selectedBankId(merged))
        assertFalse(merged.contains(ExplicitBankSelection.reasonFor(Bank.BANK_ALJAZIRA)))
    }

    @Test
    fun twoStoredSelectionsAreNotAChoice() {
        val reasons = listOf(
            ExplicitBankSelection.reasonFor(Bank.BANK_ALJAZIRA),
            ExplicitBankSelection.reasonFor(Bank("OTHER")),
        )
        assertNull(ExplicitBankSelection.selectedBankId(reasons))
        assertTrue(ExplicitBankSelection.offersBankChoice(listOf(ExplicitBankSelection.REASON_SUSPECTED_BANK)))
        assertFalse(ExplicitBankSelection.offersBankChoice(listOf("needs_review")))
    }
}
