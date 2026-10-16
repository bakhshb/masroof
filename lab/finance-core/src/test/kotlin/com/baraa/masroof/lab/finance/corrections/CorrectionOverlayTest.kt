package com.baraa.masroof.lab.finance.corrections

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CorrectionOverlayTest {
    private val account = AssetId(Environment.BETA, BankId("BANK_ALJAZIRA"), "account-a")
    private val assets = mapOf(account to OwnedAsset(account, AssetKind.ACCOUNT))
    private fun input(validated: Boolean = false) = FinancialClassificationInput(
        PostingKey(Environment.BETA, "transaction-a"), PostingRevision(0, 1), FinancialType.EXPENSE,
        Money.of("25", Currency.SAR), TransactionTime(Instant.parse("2026-10-17T10:00:00Z"), ZoneId.of("Asia/Riyadh")),
        FinancialEndpoint.Owned(account), FinancialEndpoint.External, assets, setOf("raw-a"), validated)
    private fun observe(input: FinancialClassificationInput = input(), safety: ObservationSafety = ObservationSafety.ORDINARY) = CorrectionObservation(input, safety)
    private fun request(observation: CorrectionObservation = observe(), decision: FinancialDecision = FinancialDecision.Keep,
        revision: Long = 1, id: String = "decision-$revision") = CorrectionRequest(id, observation.input.key,
        revision, observation.input.revision.interpretation, observation.input.evidenceIds, decision,
        expectedCorrectionRevision = revision - 1)
    private fun apply(observation: CorrectionObservation = observe(), decision: FinancialDecision = FinancialDecision.Keep) =
        CorrectionOverlays.submit(observation, request(observation, decision)) as CorrectionResult.Applied
    private fun updated(base: CorrectionObservation, version: Long = 2) = base.copy(input = base.input.copy(revision = PostingRevision(0, version)))
    private fun assertRejected(reason: CorrectionRejection, result: CorrectionResult) = assertEquals(CorrectionResult.Rejected(reason), result)

    @Test fun metadataNeverPromotesPartialAndNeverChangesOriginalFacts() {
        val observation = observe()
        val result = CorrectionOverlays.submit(observation, request().copy(metadata = mapOf("merchant" to "A"))) as CorrectionResult.Applied
        assertEquals(CorrectionDisposition.FINANCIAL_REVIEW, result.disposition)
        assertFalse(result.effectiveInput!!.validatedParse)
        assertEquals(CorrectionAuthority.METADATA_ONLY, result.effectiveInput.authority)
        assertEquals(observation.input, result.overlay.original)
        assertEquals("A", result.overlay.metadata["merchant"])
    }

    @Test fun explicitAmountConfirmationCanSatisfyExistingGateWithoutParseSuccess() {
        val result = apply(decision = FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("30", Currency.SAR)))
        assertEquals(CorrectionDisposition.CLASSIFIED, result.disposition)
        assertEquals(Money.of("30", Currency.SAR), result.effectiveInput!!.amount)
        assertFalse(result.effectiveInput.validatedParse)
        assertEquals(CorrectionAuthority.CONFIRMED_FAMILY_OR_AMOUNT, result.effectiveInput.authority)
        assertEquals(Money.of("25", Currency.SAR), result.overlay.original.amount)
    }

    @Test fun confirmedFamilyTypeAndAmountHavePositiveGateWithoutInventingParseSuccess() {
        val result = apply(observe(input().copy(storedType = FinancialType.UNKNOWN)),
            FinancialDecision.ConfirmFamilyOrAmount(FinancialType.FEE, Money.of("7", Currency.SAR)))
        assertEquals(CorrectionDisposition.CLASSIFIED, result.disposition)
        assertEquals(FinancialType.FEE, (result.classification as FinancialClassification.Classified).effectiveType)
        assertEquals(Money.of("7", Currency.SAR), result.effectiveInput!!.amount)
        assertFalse(result.effectiveInput.validatedParse)
        assertEquals(FinancialType.UNKNOWN, result.overlay.original.storedType)
    }

    @Test fun explicitTypeIsFinancialAuthorityButUnknownAndAdjustmentStayReview() {
        assertEquals(CorrectionDisposition.CLASSIFIED, apply(decision = FinancialDecision.ResolveFinancialType(FinancialType.FEE)).disposition)
        for (type in listOf(FinancialType.UNKNOWN, FinancialType.ADJUSTMENT)) {
            assertEquals(CorrectionDisposition.FINANCIAL_REVIEW, apply(decision = FinancialDecision.ResolveFinancialType(type)).disposition)
        }
    }

    @Test fun financialTypeEditCannotSubstituteForCheckedSelfTransferPairProof() {
        val second = account.copy(localId = "account-b")
        val observation = observe(input(true).copy(destination = FinancialEndpoint.Owned(second),
            assets = assets + (second to OwnedAsset(second, AssetKind.ACCOUNT))))
        for (decision in listOf(FinancialDecision.ResolveFinancialType(FinancialType.SELF_TRANSFER),
            FinancialDecision.ConfirmFamilyOrAmount(type = FinancialType.SELF_TRANSFER))) {
            val result = apply(observation, decision)
            assertEquals(CorrectionDisposition.FINANCIAL_REVIEW, result.disposition)
            assertTrue((result.classification as FinancialClassification.Review).reasons.contains(ReviewReason.AMBIGUOUS_MATCH))
        }
    }

    @Test fun everyExistingBlockerSurvivesEitherFinancialAuthority() {
        for (reason in ReviewReason.entries) for (decision in listOf(
            FinancialDecision.ConfirmFamilyOrAmount(type = FinancialType.EXPENSE), FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE))) {
            val result = apply(observe(input().copy(unresolved = setOf(reason))), decision)
            assertTrue((result.classification as FinancialClassification.Review).reasons.contains(reason))
        }
    }

    @Test fun securityUnexecutedAndInformationalNeverBecomeFinancialEvenWithValidParse() {
        for (safety in ObservationSafety.entries.filter { it != ObservationSafety.ORDINARY }) {
            val result = apply(observe(input(true), safety), FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE))
            assertEquals(CorrectionDisposition.FINANCIAL_REVIEW, result.disposition)
            assertTrue(result.effectiveInput!!.unresolved.contains(ReviewReason.VALIDATION))
        }
    }

    @Test fun reparseCannotErasePriorSecurityHold() {
        val original = observe(safety = ObservationSafety.SECURITY)
        val first = apply(original, FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE))
        val ordinary = updated(original).copy(safety = ObservationSafety.ORDINARY)
        val next = CorrectionOverlays.rebase(ordinary, first.overlay) as CorrectionResult.Applied
        assertEquals(CorrectionDisposition.FINANCIAL_REVIEW, next.disposition)
        assertEquals(next.disposition, (CorrectionOverlays.rebase(ordinary, next.overlay) as CorrectionResult.Applied).disposition)
    }

    @Test fun suppressionCannotHideAnUnresolvedSafetyReview() {
        for (safety in ObservationSafety.entries.filter { it != ObservationSafety.ORDINARY }) {
            val result = apply(observe(input().copy(unresolved = setOf(ReviewReason.OWNERSHIP)), safety), FinancialDecision.SuppressAsNonFinancial)
            assertEquals(CorrectionDisposition.FINANCIAL_REVIEW, result.disposition)
            assertEquals(setOf(ReviewReason.VALIDATION, ReviewReason.OWNERSHIP), (result.classification as FinancialClassification.Review).reasons)
            assertNull(result.effectiveInput)
        }
    }

    @Test fun identityEnvironmentAndExactEvidenceAreBoundBeforeAuthority() {
        val observation = observe()
        assertRejected(CorrectionRejection.IDENTITY, CorrectionOverlays.submit(observation, request().copy(key = observation.input.key.copy(environment = Environment.MIGRATION_REHEARSAL))))
        assertRejected(CorrectionRejection.EVIDENCE, CorrectionOverlays.submit(observation, request().copy(expectedEvidenceIds = setOf("different"))))
        val previous = apply().overlay
        assertRejected(CorrectionRejection.IDENTITY, CorrectionOverlays.rebase(updated(observation).copy(input = updated(observation).input.copy(key = PostingKey(Environment.BETA, "other"))), previous))
    }

    @Test fun staleUiInterpretationCannotConfirmNewFacts() {
        assertRejected(CorrectionRejection.STALE, CorrectionOverlays.submit(updated(observe()), request()))
        val first = apply().overlay
        assertRejected(CorrectionRejection.STALE, CorrectionOverlays.submit(observe(), request(revision = 3).copy(expectedCorrectionRevision = 0), first))
    }

    @Test fun callerCannotFeedAlreadyPromotedInputAsCleanInterpretation() {
        for (authority in CorrectionAuthority.entries.filter { it != CorrectionAuthority.NONE }) {
            val observation = observe(input().copy(authority = authority))
            assertRejected(CorrectionRejection.UNTRUSTED_BASE, CorrectionOverlays.submit(observation, request(observation)))
        }
        val observation = observe(input().copy(revision = PostingRevision(1, 1)))
        assertRejected(CorrectionRejection.UNTRUSTED_BASE, CorrectionOverlays.submit(observation, request(observation)))
    }

    @Test fun sameRevisionIsIdempotentOnlyForIdenticalDecision() {
        val first = apply()
        val same = CorrectionOverlays.submit(observe(), request(), first.overlay) as CorrectionResult.Applied
        assertEquals(1, same.overlay.history.size)
        assertRejected(CorrectionRejection.CONFLICT, CorrectionOverlays.submit(observe(), request().copy(metadata = mapOf("note" to "different")), first.overlay))
        assertRejected(CorrectionRejection.CONFLICT, CorrectionOverlays.submit(observe(), request().copy(decisionId = "other"), first.overlay))
    }

    @Test fun staleCorrectionsAndReusedDecisionIdsAreRejected() {
        val first = apply()
        val second = CorrectionOverlays.submit(observe(), request(revision = 2), first.overlay) as CorrectionResult.Applied
        assertRejected(CorrectionRejection.STALE, CorrectionOverlays.submit(observe(), request(), second.overlay))
        assertRejected(CorrectionRejection.CONFLICT, CorrectionOverlays.submit(observe(), request(revision = 3, id = "decision-1"), second.overlay))
        assertEquals(2, second.overlay.history.size)
        assertEquals(1, first.overlay.history.size)
    }

    @Test fun sameInterpretationDifferentFinancialPayloadConflictsAndLowerIsStale() {
        val first = apply()
        assertRejected(CorrectionRejection.CONFLICT, CorrectionOverlays.rebase(observe(input().copy(amount = Money.of("26", Currency.SAR))), first.overlay))
        assertRejected(CorrectionRejection.STALE, CorrectionOverlays.rebase(observe(input().copy(revision = PostingRevision(0, 0))), first.overlay))
    }

    @Test fun laterInterpretationRetainsManualTypeAmountCurrencyTimeEndpointsAndFxPrecision() {
        val fx = AppliedFx(BigDecimal("3.7500123456789"), "persisted-bank")
        val original = observe(input().copy(amount = Money.of("10", Currency.USD), appliedFx = fx))
        val first = apply(original, FinancialDecision.ResolveFinancialType(FinancialType.FEE))
        val changed = updated(original).copy(input = updated(original).input.copy(storedType = FinancialType.INCOME,
            amount = Money.of("999", Currency.EUR), time = original.input.time.copy(zone = ZoneId.of("UTC")),
            source = FinancialEndpoint.External, destination = FinancialEndpoint.Owned(account), appliedFx = null,
            incomeAttribution = IncomeAttribution.OTHER))
        val result = CorrectionOverlays.rebase(changed, first.overlay) as CorrectionResult.Applied
        val effective = result.effectiveInput!!
        assertEquals(FinancialType.FEE, effective.storedType)
        assertEquals(original.input.amount, effective.amount)
        assertEquals(original.input.time, effective.time)
        assertEquals(original.input.source, effective.source)
        assertEquals(original.input.destination, effective.destination)
        assertEquals(fx, effective.appliedFx)
        assertNull(effective.incomeAttribution)
        assertEquals(PostingRevision(1, 2), effective.revision)
    }

    @Test fun currencyChangeCannotReuseAnExistingRateForTheWrongNativeCurrency() {
        val fx = AppliedFx(BigDecimal("3.7500123456789"), "sms-usd-rate")
        val observation = observe(input().copy(amount = Money.of("10", Currency.USD), appliedFx = fx))
        val first = apply(observation, FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE))
        for (currency in listOf(Currency.EUR, Currency.SAR)) {
            assertRejected(CorrectionRejection.UNSUPPORTED_CHANGE, CorrectionOverlays.submit(observation,
                request(observation, FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("20", currency)), 2), first.overlay))
        }
        assertEquals(1, first.overlay.history.size)
        assertEquals(fx, first.effectiveInput!!.appliedFx)
        assertEquals(Money.of("10", Currency.USD), first.effectiveInput.amount)
        val sameCurrency = CorrectionOverlays.submit(observation, request(observation,
            FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("20", Currency.USD)), 2), first.overlay) as CorrectionResult.Applied
        assertEquals(fx, sameCurrency.effectiveInput!!.appliedFx)
    }

    @Test fun metadataAfterFinancialDecisionPreservesAuthorityAndDoesNotTakeChangedParserAmount() {
        val first = apply(decision = FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("40", Currency.SAR)))
        val observation = updated(observe()).copy(input = updated(observe()).input.copy(amount = Money.of("900", Currency.SAR)))
        val result = CorrectionOverlays.submit(observation, request(observation, revision = 2).copy(metadata = mapOf("counterparty" to "B")), first.overlay) as CorrectionResult.Applied
        assertEquals(Money.of("40", Currency.SAR), result.effectiveInput!!.amount)
        assertEquals(CorrectionAuthority.CONFIRMED_FAMILY_OR_AMOUNT, result.effectiveInput.authority)
    }

    @Test fun metadataAloneFollowsNewInterpretationButKeepsGateAndNewBlockers() {
        val first = apply()
        val observation = updated(observe()).copy(input = updated(observe()).input.copy(amount = Money.of("99", Currency.SAR), unresolved = setOf(ReviewReason.OWNERSHIP)))
        val result = CorrectionOverlays.rebase(observation, first.overlay) as CorrectionResult.Applied
        assertEquals(Money.of("99", Currency.SAR), result.effectiveInput!!.amount)
        assertEquals(CorrectionDisposition.FINANCIAL_REVIEW, result.disposition)
        assertTrue((result.classification as FinancialClassification.Review).reasons.containsAll(setOf(ReviewReason.VALIDATION, ReviewReason.OWNERSHIP)))
    }

    @Test fun newExplicitDecisionChangesOnlyItsRequestedFieldsAndKeepsOriginalAudit() {
        val first = apply(decision = FinancialDecision.ResolveFinancialType(FinancialType.FEE))
        val observation = updated(observe()).copy(input = updated(observe()).input.copy(amount = Money.of("999", Currency.SAR)))
        val result = CorrectionOverlays.submit(observation, request(observation,
            FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("12", Currency.USD)), 2), first.overlay) as CorrectionResult.Applied
        assertEquals(FinancialType.FEE, result.effectiveInput!!.storedType)
        assertEquals(Money.of("12", Currency.USD), result.effectiveInput.amount)
        assertEquals(input(), result.overlay.original)
        assertEquals(2, result.overlay.history.size)
    }

    @Test fun suppressionPersistsAndProducesNoFinancialInputOrPostingClaim() {
        val first = apply(decision = FinancialDecision.SuppressAsNonFinancial)
        val next = CorrectionOverlays.rebase(updated(observe(input(true))), first.overlay) as CorrectionResult.Applied
        assertEquals(CorrectionDisposition.SUPPRESSED, next.disposition)
        assertNull(next.classification)
        assertNull(next.effectiveInput)
        val metadata = CorrectionOverlays.submit(next.overlay.latestObservation, request(next.overlay.latestObservation, revision = 2), next.overlay) as CorrectionResult.Applied
        assertEquals(CorrectionDisposition.SUPPRESSED, metadata.disposition)
    }

    @Test fun suppressionCanOnlyBeRestoredByNewExplicitFinancialDecision() {
        val first = apply(decision = FinancialDecision.SuppressAsNonFinancial)
        val result = CorrectionOverlays.submit(observe(), request(decision = FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE), revision = 2), first.overlay) as CorrectionResult.Applied
        assertEquals(CorrectionDisposition.CLASSIFIED, result.disposition)
        assertEquals(2, result.overlay.history.size)
    }

    @Test fun suppressAndRestoreRetainsEarlierConfirmedAmountRatherThanNewParserAmount() {
        val first = apply(decision = FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("14", Currency.USD)))
        val suppressed = CorrectionOverlays.submit(observe(), request(decision = FinancialDecision.SuppressAsNonFinancial, revision = 2), first.overlay) as CorrectionResult.Applied
        val changed = updated(observe()).copy(input = updated(observe()).input.copy(amount = Money.of("999", Currency.SAR)))
        val restored = CorrectionOverlays.submit(changed, request(changed, FinancialDecision.ResolveFinancialType(FinancialType.FEE), 3), suppressed.overlay) as CorrectionResult.Applied
        assertEquals(Money.of("14", Currency.USD), restored.effectiveInput!!.amount)
        assertEquals(FinancialType.FEE, restored.effectiveInput.storedType)
    }

    @Test fun everyObservedEvidenceClaimRemainsBoundAcrossRebases() {
        val first = apply()
        val added = updated(observe()).copy(input = updated(observe()).input.copy(evidenceIds = setOf("raw-a", "raw-b")))
        val second = CorrectionOverlays.rebase(added, first.overlay) as CorrectionResult.Applied
        assertRejected(CorrectionRejection.EVIDENCE, CorrectionOverlays.rebase(updated(observe(), 3), second.overlay))
        assertRejected(CorrectionRejection.EVIDENCE, CorrectionOverlays.rebase(updated(observe(), 3).copy(input = updated(observe(), 3).input.copy(evidenceIds = setOf("raw-b"))), second.overlay))
    }

    @Test fun unknownOwnershipAndChangedParentCannotBecomeConfirmedByTypeEdit() {
        val unresolved = observe(input().copy(source = FinancialEndpoint.Unresolved))
        assertEquals(CorrectionDisposition.FINANCIAL_REVIEW, apply(unresolved, FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE)).disposition)
        val first = apply(decision = FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE))
        val changed = updated(observe()).copy(input = updated(observe()).input.copy(assets = mapOf(account to OwnedAsset(account, AssetKind.CREDIT_FACILITY))))
        val result = CorrectionOverlays.rebase(changed, first.overlay) as CorrectionResult.Applied
        assertTrue((result.classification as FinancialClassification.Review).reasons.contains(ReviewReason.OWNERSHIP))
    }

    @Test fun callerCollectionsAndReturnedViewsCannotMutateConfirmedHistory() {
        val evidence = mutableSetOf("raw-a")
        val metadata = mutableMapOf("note" to "original")
        val registry = assets.toMutableMap()
        val observation = observe(input().copy(evidenceIds = evidence, assets = registry))
        val result = CorrectionOverlays.submit(observation, request(observation, FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE)).copy(metadata = metadata)) as CorrectionResult.Applied
        evidence.clear(); metadata.clear(); registry.clear()
        assertEquals(setOf("raw-a"), result.overlay.original.evidenceIds)
        assertEquals("original", result.overlay.history.single().metadata["note"])
        assertEquals(assets, result.effectiveInput!!.assets)
        assertThrows(UnsupportedOperationException::class.java) { (result.overlay.history as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.overlay.history.single().expectedEvidenceIds as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.effectiveInput.assets as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { ((result.classification as FinancialClassification.Classified).metrics as MutableMap).clear() }
    }

    @Test fun metadataKeysCannotSmuggleFinancialAuthority() {
        assertThrows(IllegalArgumentException::class.java) { request().copy(metadata = mapOf("financialType" to "EXPENSE")) }
        assertThrows(IllegalArgumentException::class.java) { FinancialDecision.ConfirmFamilyOrAmount() }
    }
}
