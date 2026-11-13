package com.baraa.masroof.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitBankSelectionTest {
    @Test
    fun onlyRouteReasonsOfferABankChoice() {
        assertTrue(ExplicitBankSelection.offersBankChoice(listOf(ExplicitBankSelection.REASON_SUSPECTED_BANK)))
        assertTrue(ExplicitBankSelection.offersBankChoice(listOf("needs_review", ExplicitBankSelection.REASON_AMBIGUOUS_BANK_ROUTE)))
        assertFalse(ExplicitBankSelection.offersBankChoice(listOf("needs_review")))
    }
}
