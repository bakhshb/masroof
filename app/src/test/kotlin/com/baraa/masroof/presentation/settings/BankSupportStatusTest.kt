package com.baraa.masroof.presentation.settings

import com.baraa.masroof.R
import com.baraa.masroof.application.patterns.PatternApprovalStatus
import com.baraa.masroof.application.patterns.PatternApprovalStatus.APPROVED
import com.baraa.masroof.application.patterns.PatternApprovalStatus.BUNDLED
import com.baraa.masroof.application.patterns.PatternApprovalStatus.PREVIEW_ONLY
import com.baraa.masroof.application.patterns.PatternBankView
import com.baraa.masroof.application.patterns.PatternDefinitionView
import com.baraa.masroof.presentation.common.MasroofCardAccent
import org.junit.Assert.assertEquals
import org.junit.Test

class BankSupportStatusTest {
    @Test
    fun enabledNoticeAloneDoesNotMakeABankReady() {
        val bank = bank(pattern("PURCHASE", BUNDLED, enabled = false), pattern("BALANCE_NOTICE", BUNDLED))
        assertEquals(BankSupportStatus(R.string.settings_bank_patterns_disabled, MasroofCardAccent.None), bankSupportStatus(bank))
    }

    @Test
    fun builtInAndApprovedPatternsAreBothPlainReady() {
        val ready = BankSupportStatus(R.string.settings_bank_bundled, MasroofCardAccent.Inflow)
        assertEquals(ready, bankSupportStatus(bank(pattern("PURCHASE", BUNDLED))))
        assertEquals(ready, bankSupportStatus(bank(pattern("PURCHASE", BUNDLED), pattern("TRANSFER_IN", APPROVED),
            pattern("TRANSFER_IN", BUNDLED, enabled = false))))
    }

    @Test
    fun pendingPatternsAreCountedOnlyWhenOthersWork() {
        assertEquals(BankSupportStatus(R.plurals.settings_bank_partly_approved, MasroofCardAccent.Liability, 1),
            bankSupportStatus(bank(pattern("TRANSFER_OUT", APPROVED), pattern("TRANSFER_OUT", PREVIEW_ONLY))))
        assertEquals(BankSupportStatus(R.string.settings_bank_needs_approval, MasroofCardAccent.Liability),
            bankSupportStatus(bank(pattern("TRANSFER_OUT", PREVIEW_ONLY), pattern("PURCHASE", APPROVED, enabled = false))))
    }

    @Test
    fun aBankWithoutMovementPatternsNeedsTeaching() {
        assertEquals(R.string.settings_bank_needs_teaching, bankSupportStatus(bank()).label)
        assertEquals(R.string.settings_bank_needs_teaching, bankSupportStatus(bank(pattern("NON_FINANCIAL", BUNDLED))).label)
    }

    private var next = 0
    private fun pattern(label: String, status: PatternApprovalStatus, enabled: Boolean = true) =
        PatternDefinitionView("p${next++}", "stc", label, 1, enabled, status, "", emptyList(), emptyList())
    private fun bank(vararg patterns: PatternDefinitionView) = PatternBankView("stc", null, emptyList(), patterns.toList())
}
