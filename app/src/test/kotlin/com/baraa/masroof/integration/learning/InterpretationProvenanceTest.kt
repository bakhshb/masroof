package com.baraa.masroof.integration.learning

import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.application.interpretation.*
import com.baraa.masroof.testsupport.InterpretationTestAdapter
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.parsing.model.*
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserCorrectionRepository
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class InterpretationProvenanceTest {
    private fun record(origin: String? = "ML", status: ParseStatus = ParseStatus.SUCCESS): ParsedEventRecord {
        val engine = RuleInterpreter(AlJaziraRuleProfile.current())
        val result = InterpretationTestAdapter { engine.interpretRouted(it, BankId("BANK_ALJAZIRA")) }.parse(
            SmsParseInput("provenance", "AlJazira", "شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR 60.99\nلدى: TEST\nفي: 2026-10-06 13:57", Instant.parse("2026-10-06T12:00:00Z"))) as ParseResult.Success
        return ParsedEventRecord(result.event.copy(parseStatus = status, confidence = Confidence(0.99,
            listOf("engine:current", "rules:current") + origin?.let { listOf("family_origin:$it", "model:test-model") }.orEmpty())), result.details)
    }
    @Test fun currentOriginSelectsTheExplanationWithoutUsingConfidenceOrActiveModel() {
        assertEquals(InterpretationOrigin.ML, InterpretationProvenanceWorkflow.project(record()).origin)
        assertEquals(InterpretationOrigin.HUMAN_TEMPLATE, InterpretationProvenanceWorkflow.project(record("HUMAN_TEMPLATE")).origin)
        assertEquals(InterpretationOrigin.RULES, InterpretationProvenanceWorkflow.project(record(null)).origin)
        assertEquals(InterpretationOrigin.UNAVAILABLE, InterpretationProvenanceWorkflow.project(record("RULES")).origin)
        val base = record()
        assertEquals(InterpretationProvenanceWorkflow.project(base), InterpretationProvenanceWorkflow.project(
            base.copy(event = base.event.copy(confidence = base.event.confidence.copy(score = 0.01)))))
    }
    @Test fun incompleteInferenceIsPresentedAsAnUnapprovedSuggestion() {
        assertTrue(InterpretationProvenanceWorkflow.project(record(status = ParseStatus.REVIEW_REQUIRED)).pendingReview)
        assertTrue(InterpretationProvenanceWorkflow.project(record(), requiredReview = true).pendingReview)
        assertFalse(InterpretationProvenanceWorkflow.project(record()).pendingReview)
    }
    @Test fun manualAndUnavailableSourcesNeverClaimCurrentMlParticipation() {
        val base = record()
        for (resolution in listOf(ReviewResolutionKind.USER_FINANCIAL_TYPE, ReviewResolutionKind.USER_NON_FINANCIAL))
            assertEquals(InterpretationOrigin.MANUAL, InterpretationProvenanceWorkflow.project(base, resolution).origin)
        assertEquals(InterpretationOrigin.MANUAL, InterpretationProvenanceWorkflow.project(base.copy(userCorrected = true, automationConfirmed = true)).origin)
        // A merchant-only correction leaves the original family inference intact.
        assertNotNull(InterpretationProvenanceWorkflow.project(base.copy(userCorrected = true)))
        assertEquals(InterpretationOrigin.ML,InterpretationProvenanceWorkflow.project(base.copy(userCorrected=true),ReviewResolutionKind.USER_CORRECTION).origin)
        for (reasons in listOf(listOf("model:active"), listOf("family_origin:ML"), listOf("family_origin:ML", "model:"),
            listOf("family_origin:ML", "family_origin:HUMAN_TEMPLATE", "model:x"))) {
            assertEquals(InterpretationOrigin.UNAVAILABLE, InterpretationProvenanceWorkflow.project(base.copy(event = base.event.copy(confidence = Confidence(0.9, reasons)))).origin)
        }
        assertEquals(InterpretationOrigin.UNRESOLVED, InterpretationProvenanceWorkflow.project(base.copy(event = base.event.copy(messageFamily = MessageFamily.UNKNOWN))).origin)
    }
    @Test fun metadataFailureIsExplicitAndCancellationPropagates() {
        fun workflow(failure: RuntimeException): InterpretationProvenanceWorkflow {
            fun <T> port(type: Class<T>, answer: (String) -> Any?): T = type.cast(java.lang.reflect.Proxy.newProxyInstance(
                type.classLoader, arrayOf(type)) { _, method, _ -> answer(method.name) })!!
            val parsed = port(ParsedEventRepository::class.java) { name ->
                check(name == "findByRawSmsId"); record()
            }
            val corrections = port(UserCorrectionRepository::class.java) { name ->
                check(name == "listForRawSmsId"); emptyList<UserCorrection>()
            }
            val reviews = port(ReviewRepository::class.java) { throw failure }
            return InterpretationProvenanceWorkflow(EffectiveParsedEventProvider(parsed,corrections),reviews)
        }
        assertEquals(InterpretationOrigin.UNAVAILABLE, runBlocking { workflow(IllegalStateException("unavailable")).load("provenance") }.origin)
        assertThrows(CancellationException::class.java) {
            runBlocking { workflow(CancellationException("screen closed")).load("provenance") }
        }
    }
}
