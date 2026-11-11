package com.baraa.masroof.integration.bank

import com.baraa.masroof.engine.bank.*
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.testsupport.bundledPatterns
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.bank.InterpretationDraftMapper
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.ParseResult
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class InterpretationDraftGateTest {
    private val input = MessageInput("gate", "AlJazira", "شراء عبر الإنترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR 60.99\nلدى: TEST_SHOP\nفي: 2026-10-06 13:57",Instant.parse("2026-10-06T11:00:00Z"),true)
    private fun parsed() = PatternFactInterpreter(bundledPatterns("BANK_ALJAZIRA")).interpretRouted(input,BankId("BANK_ALJAZIRA"))
    @Test fun completeFactsNeverOverrideAnEngineBlocker() {
        val facts = parsed(); assertEquals(ParseStatus.SUCCESS,facts.status)
        assertTrue(InterpretationDraftMapper().map(input,facts.copy(blockers=setOf(Blocker.CONFLICTING_FACTS)),Bank.BANK_ALJAZIRA) is ParseResult.ReviewRequired)
    }
    @Test fun missingAmountEvidenceCannotPassTheProductionFinalizer() {
        val facts = parsed(); assertEquals(ParseStatus.SUCCESS,facts.status)
        assertTrue(InterpretationDraftMapper().map(input,facts.copy(evidence=facts.evidence.filterNot { it.field=="AMOUNT" }),Bank.BANK_ALJAZIRA) is ParseResult.ReviewRequired)
    }
    @Test fun informationalConflictRemainsReviewable() {
        val facts = parsed().copy(facts=Facts(MessageFamily.NON_FINANCIAL),status=ParseStatus.NON_FINANCIAL,blockers=setOf(Blocker.CONFLICTING_FACTS))
        assertTrue(InterpretationDraftMapper().map(input,facts,Bank.BANK_ALJAZIRA) is ParseResult.ReviewRequired)
    }
    @Test fun explicitStoredBankSelectionIsNotRedetected() {
        val routed = PatternFactInterpreter(bundledPatterns("BANK_ALJAZIRA")).interpretRouted(input.copy(sender="Unrecognized sender"),BankId("BANK_ALJAZIRA"))
        assertEquals(ParseStatus.SUCCESS,routed.status)
    }
}
