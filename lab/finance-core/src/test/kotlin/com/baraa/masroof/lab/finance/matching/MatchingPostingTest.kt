package com.baraa.masroof.lab.finance.matching

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MatchingPostingTest {
    private val bank = BankId("BANK_ALJAZIRA")
    private fun id(value: String, environment: Environment = Environment.BETA, issuer: BankId = bank) =
        AssetId(environment, issuer, value)
    private val source = id("account-a")
    private val destination = id("account-b")
    private val registry = mapOf(source to OwnedAsset(source, AssetKind.ACCOUNT),
        destination to OwnedAsset(destination, AssetKind.ACCOUNT))
    private val reference = ConfirmedBankTransferReference(bank, "bank-transfer-42")
    private fun input(key: String = "out", evidence: String = "sms-out", environment: Environment = Environment.BETA) =
        FinancialClassificationInput(PostingKey(environment, key), PostingRevision(0, 1), FinancialType.SELF_TRANSFER,
            Money.of("25", Currency.SAR), TransactionTime(Instant.parse("2026-10-16T10:00:00Z"), ZoneId.of("Asia/Riyadh")),
            FinancialEndpoint.Owned(source), FinancialEndpoint.Owned(destination), registry, setOf(evidence), true)
    private fun direct(key: String = "out", evidence: String = "sms-out") = input(key, evidence).copy(storedType = FinancialType.EXPENSE, destination = FinancialEndpoint.External)
    private fun observations(out: FinancialClassificationInput = input(), incoming: FinancialClassificationInput = input("in", "sms-in")) =
        listOf(TransferObservation(out, TransferDirection.OUTBOUND, reference),
            TransferObservation(incoming, TransferDirection.INBOUND, reference))
    private fun match(rows: List<TransferObservation> = observations(), proof: TransferMatchProof = TransferMatchProof.BankReference(reference)) =
        SelfTransferMatcher.match(rows, proof)
    private fun paired(rows: List<TransferObservation> = observations()) = (match(rows) as TransferMatchResult.Matched).transfer
    private fun decision(rows: List<TransferObservation> = observations()) = rows.let {
        TransferMatchProof.UserPair("manual-pair-1", it[0].input.key, it[0].input.revision, it[0].input.evidenceIds,
            it[1].input.key, it[1].input.revision, it[1].input.evidenceIds)
    }
    private fun assertReview(result: TransferMatchResult, reason: ReviewReason? = null) {
        assertTrue(result is TransferMatchResult.Review)
        if (reason != null) assertTrue((result as TransferMatchResult.Review).reasons.contains(reason))
    }

    @Test fun ownershipRequiresExactIdEnvironmentAndCorrectParent() {
        val wrong = source.copy(environment = Environment.MIGRATION_REHEARSAL)
        assertTrue(ConfirmedOwnershipResolver.resolve(source, Environment.BETA, registry) is AssetResolution.Confirmed)
        assertEquals(AssetResolution.Unconfirmed, ConfirmedOwnershipResolver.resolve(source, Environment.MIGRATION_REHEARSAL, registry))
        assertEquals(AssetResolution.Unconfirmed, ConfirmedOwnershipResolver.resolve(source, Environment.BETA, mapOf(source to OwnedAsset(wrong, AssetKind.ACCOUNT))))
        val card = id("mada")
        val owned = OwnedAsset(card, AssetKind.DEBIT_CARD, "1234", source)
        assertEquals(AssetResolution.Unconfirmed, ConfirmedOwnershipResolver.resolve(card, Environment.BETA, mapOf(card to owned)))
        assertTrue(ConfirmedOwnershipResolver.resolve(card, Environment.BETA, registry + (card to owned)) is AssetResolution.Confirmed)
        assertEquals(AssetResolution.Unconfirmed, ConfirmedOwnershipResolver.resolve(card, Environment.BETA,
            registry + (source to OwnedAsset(source, AssetKind.CREDIT_FACILITY)) + (card to owned)))
    }

    @Test fun sharedSuffixNeverBecomesOwnershipOrPairsUnrelatedMessages() {
        val first = id("card-1")
        val second = id("card-2")
        val cards = registry + (first to OwnedAsset(first, AssetKind.DEBIT_CARD, "1234", source)) +
            (second to OwnedAsset(second, AssetKind.DEBIT_CARD, "1234", destination))
        assertNotEquals((ConfirmedOwnershipResolver.resolve(first, Environment.BETA, cards) as AssetResolution.Confirmed).asset.id,
            (ConfirmedOwnershipResolver.resolve(second, Environment.BETA, cards) as AssetResolution.Confirmed).asset.id)
        assertEquals(AssetResolution.Unconfirmed, ConfirmedOwnershipResolver.resolve(id("1234"), Environment.BETA, cards))
        assertReview(match(observations().map { it.copy(reference = null) }))
    }

    @Test fun pairsOneTransferAndRetainsOriginalTimeZoneRevisionEvidenceAndFx() {
        val rows = observations(incoming = input("in", "sms-in").copy(time = TransactionTime(
            Instant.parse("2026-10-16T10:03:00Z"), ZoneId.of("UTC"))))
        val transfer = paired(rows)
        assertEquals(input().key, transfer.input.key)
        assertEquals(input().time, transfer.input.time)
        assertEquals(setOf("sms-out", "sms-in"), transfer.input.evidenceIds)
        assertEquals(rows, transfer.observations)
        assertNotEquals(transfer.observations[0].input.time, transfer.observations[1].input.time)
    }

    @Test fun automaticWindowUsesStoredInstantAndIncludesBoundary() {
        val original = input("in", "sms-in")
        assertTrue(match(observations(incoming = original.copy(time = original.time.copy(
            instant = original.time.instant.plusSeconds(600))))) is TransferMatchResult.Matched)
        assertReview(match(observations(incoming = original.copy(time = original.time.copy(
            instant = original.time.instant.minusSeconds(601))))))
        assertReview(match(observations(incoming = original.copy(time = original.time.copy(
            instant = original.time.instant.plusSeconds(86400))))))
    }

    @Test fun sharedReferenceManyToOneAndDuplicateDirectionStayReviewable() {
        val rows = observations()
        assertReview(match(rows + rows[1].copy(input = input("third", "sms-third"))))
        assertReview(match(rows.map { it.copy(direction = TransferDirection.OUTBOUND) }))
        assertReview(match(listOf(rows[0], rows[0])))
        assertReview(match(rows + rows[0].copy(input = input("third", "sms-out"), reference = null)))
    }

    @Test fun amountCurrencyEndpointEnvironmentAndFxMismatchNeverPair() {
        val incoming = input("in", "sms-in")
        val variants = listOf(incoming.copy(amount = Money.of("26", Currency.SAR)),
            incoming.copy(amount = Money.of("25", Currency.USD)), incoming.copy(source = incoming.destination),
            incoming.copy(key = incoming.key.copy(environment = Environment.MIGRATION_REHEARSAL)),
            incoming.copy(storedType = FinancialType.EXTERNAL_TRANSFER_IN),
            incoming.copy(appliedFx = AppliedFx(java.math.BigDecimal("3.750000000001"), "persisted")))
        variants.forEach { assertReview(match(observations(incoming = it))) }
    }

    @Test fun referenceDoesNotEstablishCrossBankOwnershipOrGuessCounterparty() {
        val other = id("other-account", issuer = BankId("D360"))
        val crossBank = observations().map { it.copy(input = it.input.copy(destination = FinancialEndpoint.Owned(other),
            assets = registry + (other to OwnedAsset(other, AssetKind.ACCOUNT)))) }
        assertReview(match(crossBank))
        assertTrue(match(crossBank, decision(crossBank)) is TransferMatchResult.Matched)
        assertReview(match(observations().map { it.copy(input = it.input.copy(destination = FinancialEndpoint.Unresolved)) }), ReviewReason.OWNERSHIP)
    }

    @Test fun explicitUserPairCanSelectTimeGapButCannotLiftGateOrChangeDecisionFacts() {
        val rows = observations(incoming = input("in", "sms-in").let { it.copy(time = it.time.copy(instant = it.time.instant.plusSeconds(86400))) })
        assertTrue(match(rows, decision(rows)) is TransferMatchResult.Matched)
        val blocked = rows.map { it.copy(input = it.input.copy(validatedParse = false, authority = CorrectionAuthority.METADATA_ONLY)) }
        assertReview(match(blocked, decision(blocked)), ReviewReason.VALIDATION)
        assertReview(match(rows, decision(rows).copy(outboundRevision = PostingRevision(1, 1))))
        assertReview(match(rows, decision(rows).copy(inboundEvidence = setOf("different-evidence"))))
        val unresolved = rows.map { it.copy(input = it.input.copy(unresolved = setOf(ReviewReason.AMBIGUOUS_MATCH))) }
        assertReview(match(unresolved, decision(unresolved)), ReviewReason.AMBIGUOUS_MATCH)
    }

    @Test fun bothLegGatesApplyAndSharedEvidenceIsNeverTwoLegs() {
        val incoming = input("in", "sms-in")
        assertReview(match(observations(incoming = incoming.copy(validatedParse = false))), ReviewReason.VALIDATION)
        assertReview(match(observations(incoming = incoming.copy(evidenceIds = setOf("sms-out")))))
        val wrongKind = incoming.copy(assets = registry + (source to OwnedAsset(source, AssetKind.LOAN)))
        assertReview(match(observations(incoming = wrongKind)), ReviewReason.OWNERSHIP)
    }

    @Test fun oneMatchedTransferPostsTwoLegsExactlyOnceAcrossRetry() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        val pair = paired()
        assertTrue(ledger.post(pair) is FinancialPostingResult.Applied)
        assertTrue(ledger.post(pair) is FinancialPostingResult.AlreadyApplied)
        assertEquals(1, ledger.size())
        val stored = requireNotNull(ledger.read(pair.input.key))
        assertEquals(2, stored.classification.accountMovements.size)
        assertEquals(2, stored.command.evidenceIds.size)
        assertEquals(1, ledger.history(pair.input.key).size)
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED), ledger.post(direct("in", "sms-in")))
    }

    @Test fun similarIndependentMessagesRemainIndependentTransactions() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        repeat(3) { assertTrue(ledger.post(direct("independent-$it", "evidence-$it")) is FinancialPostingResult.Applied) }
        assertEquals(3, ledger.size())
    }

    @Test fun plainSelfTransferInputCannotBypassPairingAndPostEachMessageSeparately() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        assertTrue(ledger.post(input()) is FinancialPostingResult.Review)
        assertTrue(ledger.post(input("in", "sms-in")) is FinancialPostingResult.Review)
        assertEquals(0, ledger.size())
        assertTrue(ledger.post(paired()) is FinancialPostingResult.Applied)
        assertEquals(1, ledger.size())
    }

    @Test fun confirmedFeeCannotBecomeLoanRepaymentOnInterpretationRetry() = runBlocking {
        val loan = id("loan")
        val original = direct().copy(storedType = FinancialType.FEE, authority = CorrectionAuthority.USER_FINANCIAL_TYPE,
            revision = PostingRevision(1, 1), assets = registry + (loan to OwnedAsset(loan, AssetKind.LOAN)))
        val ledger = InMemoryFinancialPostingLedger()
        assertTrue(ledger.post(original) is FinancialPostingResult.Applied)
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.MANUAL_DECISION), ledger.post(original.copy(
            revision = PostingRevision(1, 2), financingLoan = loan)))
        assertEquals(FinancialType.FEE, ledger.read(original.key)?.classification?.effectiveType)
    }

    @Test fun completeFxCannotChangeOrDisappearOnInterpretationRetryOrUnconfirmedCorrection() = runBlocking {
        val original = direct().copy(appliedFx = AppliedFx(java.math.BigDecimal("3.750000000001"), "persisted-bank"))
        val ledger = InMemoryFinancialPostingLedger()
        ledger.post(original)
        val updated = AppliedFx(java.math.BigDecimal("3.800000000001"), "later-market")
        listOf(original.copy(revision = PostingRevision(0, 2), appliedFx = updated),
            original.copy(revision = PostingRevision(0, 2), appliedFx = null),
            original.copy(revision = PostingRevision(1, 1), appliedFx = updated)).forEach {
            assertEquals(FinancialPostingResult.Conflict(PostingConflict.IMMUTABLE_FX), ledger.post(it))
        }
        assertTrue(ledger.post(original.copy(revision = PostingRevision(1, 1),
            authority = CorrectionAuthority.USER_FINANCIAL_TYPE, appliedFx = updated)) is FinancialPostingResult.Applied)
    }

    @Test fun aRetainedFxPairCannotBeReusedForAnotherNativeCurrencyEvenByNewManualDecision() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        val original = direct().copy(amount = Money.of("25", Currency.USD),
            appliedFx = AppliedFx(java.math.BigDecimal("3.750000000001"), "confirmed-usd-rate"))
        assertTrue(ledger.post(original) is FinancialPostingResult.Applied)
        for (currency in listOf(Currency.EUR, Currency.SAR)) {
            for (authority in listOf(CorrectionAuthority.NONE, CorrectionAuthority.USER_FINANCIAL_TYPE)) {
                assertEquals(FinancialPostingResult.Conflict(PostingConflict.IMMUTABLE_FX), ledger.post(original.copy(
                    revision = PostingRevision(1, 0), authority = authority, amount = Money.of("25", currency))))
            }
        }
        assertTrue(ledger.post(original.copy(revision = PostingRevision(0, 2), amount = Money.of("30", Currency.USD))) is FinancialPostingResult.Applied)
        assertTrue(ledger.post(original.copy(revision = PostingRevision(1, 0), authority = CorrectionAuthority.USER_FINANCIAL_TYPE,
            amount = Money.of("25", Currency.EUR), appliedFx = AppliedFx(java.math.BigDecimal("4.100000000001"), "confirmed-eur-rate"))) is FinancialPostingResult.Applied)
    }

    @Test fun equalRevisionNeedsCompleteIdenticalPayloadNotJustTransactionKey() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        val original = direct()
        ledger.post(original)
        val variants = listOf(original.copy(amount = Money.of("26", Currency.SAR)), original.copy(evidenceIds = setOf("new")),
            original.copy(time = original.time.copy(instant = original.time.instant.plusSeconds(1))),
            original.copy(appliedFx = AppliedFx(java.math.BigDecimal("1.00000001"), "persisted")),
            original.copy(authority = CorrectionAuthority.USER_FINANCIAL_TYPE))
        variants.forEach { assertEquals(FinancialPostingResult.Conflict(PostingConflict.REVISION_PAYLOAD), ledger.post(it)) }
        assertTrue(ledger.post(original) is FinancialPostingResult.AlreadyApplied)
    }

    @Test fun staleCannotReplaceResultAndLaterRevisionKeepsHistoryAndOldEvidenceReserved() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        val original = direct().copy(evidenceIds = setOf("sms-out", "old-link"))
        ledger.post(original)
        val update = original.copy(revision = PostingRevision(0, 2), amount = Money.of("40", Currency.SAR), evidenceIds = setOf("sms-out"))
        assertTrue(ledger.post(update) is FinancialPostingResult.Applied)
        assertTrue(ledger.post(original) is FinancialPostingResult.Stale)
        assertEquals(update.amount, ledger.read(original.key)?.command?.amount)
        assertEquals(2, ledger.history(original.key).size)
        assertEquals(original.amount, ledger.history(original.key).first().command.amount)
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED), ledger.post(direct("other", "old-link")))
    }

    @Test fun pairingReferenceIsReservedEvenWhenAnotherPairHasNewEvidence() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        ledger.post(paired())
        val second = paired(observations(input("other-out", "other-sms-out"), input("other-in", "other-sms-in")))
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.MATCH_ALREADY_CLAIMED), ledger.post(second))
        assertEquals(1, ledger.size())
    }

    @Test fun interpretationRetryCannotDiscardPreviouslyMatchedTransferProof() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        val pair = paired()
        ledger.post(pair)
        val replacement = direct().copy(revision = PostingRevision(0, 2), evidenceIds = pair.input.evidenceIds)
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.PAIR_PROVENANCE), ledger.post(replacement))
        assertEquals(FinancialType.SELF_TRANSFER, ledger.read(pair.input.key)?.command?.type)
        assertTrue(ledger.post(replacement.copy(revision = PostingRevision(1, 1),
            authority = CorrectionAuthority.USER_FINANCIAL_TYPE)) is FinancialPostingResult.Applied)
        assertEquals(2, ledger.history(pair.input.key).size)
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED),
            ledger.post(direct("different-key", "sms-in")))
    }

    @Test fun correctionRetainsManualDecisionAcrossInterpretationRetry() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        val original = direct().copy(authority = CorrectionAuthority.USER_FINANCIAL_TYPE, revision = PostingRevision(1, 1))
        ledger.post(original)
        val removal = original.copy(authority = CorrectionAuthority.NONE, revision = PostingRevision(1, 2))
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.MANUAL_DECISION), ledger.post(removal))
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.MANUAL_DECISION), ledger.post(removal.copy(
            revision = PostingRevision(2, 1))))
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.MANUAL_DECISION), ledger.post(original.copy(
            revision = PostingRevision(1, 2), amount = Money.of("30", Currency.SAR))))
        assertTrue(ledger.post(original.copy(revision = PostingRevision(1, 2))) is FinancialPostingResult.Applied)
        assertTrue(ledger.post(original.copy(revision = PostingRevision(2, 1), amount = Money.of("30", Currency.SAR))) is FinancialPostingResult.Applied)
    }

    @Test fun reviewProducesNoResultOrEvidenceClaimEvenForExplicitPairDecision() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        val original = direct().copy(validatedParse = false, authority = CorrectionAuthority.METADATA_ONLY)
        assertTrue(ledger.post(original) is FinancialPostingResult.Review)
        assertNull(ledger.read(original.key))
        assertTrue(ledger.post(direct("other", "sms-out")) is FinancialPostingResult.Applied)
    }

    @Test fun failureBeforeCommitLeavesNoPartialRevisionResultEvidenceOrMatchAndRetrySucceeds() = runBlocking {
        val fail = AtomicBoolean(true)
        val ledger = InMemoryFinancialPostingLedger { if (fail.getAndSet(false)) error("simulated-storage-failure") }
        val transfer = paired()
        try { ledger.post(transfer); fail("Expected storage failure") } catch (_: IllegalStateException) { }
        assertEquals(0, ledger.size())
        assertTrue(ledger.history(transfer.input.key).isEmpty())
        assertTrue(ledger.post(transfer) is FinancialPostingResult.Applied)
        assertTrue(ledger.post(transfer) is FinancialPostingResult.AlreadyApplied)
    }

    @Test fun failedUpdatePreservesPreviousRevisionAndDoesNotClaimNewEvidence() = runBlocking {
        val fail = AtomicBoolean(false)
        val ledger = InMemoryFinancialPostingLedger { if (fail.getAndSet(false)) error("simulated-storage-failure") }
        val original = direct()
        ledger.post(original)
        fail.set(true)
        val update = original.copy(revision = PostingRevision(0, 2), evidenceIds = setOf("new-link"))
        try { ledger.post(update); fail("Expected storage failure") } catch (_: IllegalStateException) { }
        assertEquals(original.revision, ledger.read(original.key)?.command?.revision)
        assertEquals(1, ledger.history(original.key).size)
        assertTrue(ledger.post(direct("other", "new-link")) is FinancialPostingResult.Applied)
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED), ledger.post(update))
    }

    @Test fun snapshotsProtectCallerAndReadersFromChangingPreviouslyValidatedFacts() = runBlocking {
        val assets = registry.toMutableMap()
        val evidence = mutableSetOf("sms-out")
        val unresolved = mutableSetOf<ReviewReason>()
        val original = direct().copy(assets = assets, evidenceIds = evidence, unresolved = unresolved)
        val ledger = InMemoryFinancialPostingLedger()
        val applied = (ledger.post(original) as FinancialPostingResult.Applied).posting
        assets.clear(); evidence.clear(); unresolved += ReviewReason.OWNERSHIP
        assertEquals(setOf("sms-out"), applied.command.evidenceIds)
        assertEquals(registry, applied.classification.input.assets)
        assertTrue(applied.classification.input.unresolved.isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (applied.command.evidenceIds as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (applied.classification.input.assets as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (applied.classification.accountMovements as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (ledger.history(original.key) as MutableList).clear() }
        Unit
    }

    @Test fun pairedSnapshotsAreFrozenBeforeLaterCallerMutation() = runBlocking {
        val evidence = mutableSetOf("sms-out")
        val assets = registry.toMutableMap()
        val pair = paired(observations(input().copy(evidenceIds = evidence, assets = assets)))
        evidence.clear(); assets.clear()
        assertEquals(setOf("sms-out"), pair.observations.first().input.evidenceIds)
        assertEquals(2, pair.input.evidenceIds.size)
        assertTrue(InMemoryFinancialPostingLedger().post(pair) is FinancialPostingResult.Applied)
    }

    @Test fun environmentNamespacesAllowSameEvidenceOnlyInSeparateLedgerNamespace() = runBlocking {
        val ledger = InMemoryFinancialPostingLedger()
        val original = direct()
        ledger.post(original)
        val migratedSource = source.copy(environment = Environment.MIGRATION_REHEARSAL)
        val migratedDestination = destination.copy(environment = Environment.MIGRATION_REHEARSAL)
        val rehearsal = original.copy(key = original.key.copy(environment = Environment.MIGRATION_REHEARSAL),
            source = FinancialEndpoint.Owned(migratedSource), destination = FinancialEndpoint.External,
            assets = mapOf(migratedSource to OwnedAsset(migratedSource, AssetKind.ACCOUNT),
                migratedDestination to OwnedAsset(migratedDestination, AssetKind.ACCOUNT)))
        assertTrue(ledger.post(rehearsal) is FinancialPostingResult.Applied)
        assertEquals(2, ledger.size())
    }

    @Test fun concurrentSameEvidenceCommitsOnlyOneTransaction() {
        val ledger = InMemoryFinancialPostingLedger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..24).map { index -> pool.submit<FinancialPostingResult> {
                start.await(); runBlocking { ledger.post(direct("parallel-$index", "same-evidence")) }
            } }
            start.countDown()
            val results = jobs.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is FinancialPostingResult.Applied })
            assertEquals(23, results.count { it == FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED) })
            assertEquals(1, ledger.size())
        } finally { pool.shutdownNow() }
    }

    @Test fun concurrentSameCommandAppliesOnceAndConvergesOnIdenticalResult() {
        val ledger = InMemoryFinancialPostingLedger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..24).map { pool.submit<FinancialPostingResult> { start.await(); runBlocking { ledger.post(direct()) } } }
            start.countDown()
            val results = jobs.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is FinancialPostingResult.Applied })
            assertEquals(23, results.count { it is FinancialPostingResult.AlreadyApplied })
            assertEquals(1, ledger.history(input().key).size)
        } finally { pool.shutdownNow() }
    }
}
