package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.corrections.*
import com.baraa.masroof.lab.finance.matching.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomBetaCorrectionLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BetaDatabase
    private val bank = BankId("BANK_ALJAZIRA")
    private val asset = OwnedAsset(AssetId(Environment.BETA, bank, "account"), AssetKind.ACCOUNT, "1234")
    private val destination = OwnedAsset(asset.id.copy(localId = "other"), AssetKind.ACCOUNT, "5678")
    private val assets = listOf(asset, destination).associateBy { it.id }
    private fun base(id: String = "transaction", interpretation: Long = 0) = CorrectionObservation(FinancialClassificationInput(
        PostingKey(Environment.BETA, id), PostingRevision(0, interpretation), FinancialType.EXPENSE, Money.of("42.35", Currency.USD),
        TransactionTime(Instant.parse("2026-10-17T12:00:00.123456789Z"), ZoneId.of("Asia/Riyadh")), FinancialEndpoint.Owned(asset.id),
        FinancialEndpoint.External, assets, setOf("evidence-$id"), false, appliedFx = AppliedFx(BigDecimal("3.75000000012345678900"), "confirmed-rate")), ObservationSafety.ORDINARY)
    private fun command(observation: CorrectionObservation, revision: Long = 1, expected: Long = revision - 1,
        decision: FinancialDecision = FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE), id: String = "decision-$revision-${observation.input.key.transactionId}",
        metadata: Map<String, String> = emptyMap()) = CorrectionRequest(id, observation.input.key, revision, observation.input.revision.interpretation,
        observation.input.evidenceIds, decision, metadata, expected)
    private fun applied(result: BetaCorrectionResult) = (result as BetaCorrectionResult.Applied).current
    private fun store() = RoomBetaCorrectionLifecycle(db)
    private fun ledger() = RoomBetaFinancialLedger(db)
    @Before fun open() = runBlocking {
        BetaContainer.resetOffline(context); db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        assets.values.forEach { BetaAssetRegistry(db).register(it) }
    }
    @After fun close() { db.close(); BetaContainer.resetOffline(context) }
    private fun reopen() { db.close(); db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build() }

    @Test fun suppressedReviewUsesThePersistedDecisionTimeAcrossRebaseAndReopen() = runBlocking {
        val observation=base()
        val received=Instant.parse("2020-01-01T12:00:00Z")
        RoomBetaEvidenceStore(db).capture(CapturedEvidence("evidence-transaction",EvidenceRequest("review-time",EvidenceOrigin.EXPLICIT_SAMPLE),
            EvidenceMessage("evidence-transaction","fixture","synthetic only",received,ReceiptTimeSource.USER_SUPPLIED),received,
            EvidenceRoute(RouteKind.MATCHED,setOf(bank),"review-time"),emptyList()))
        db.evidenceDao().clearRetry("evidence-transaction")
        store().submit(observation,command(observation))
        val suppression=command(observation,2,decision=FinancialDecision.SuppressAsNonFinancial)
        store().submit(observation,suppression)
        val ignoredAt=Instant.parse("2026-10-19T23:30:00Z")
        db.openHelper.writableDatabase.execSQL("UPDATE beta_audit_log SET createdAt=? WHERE eventKind='CORRECTION_REVISION' AND decisionId=?",
            arrayOf(ignoredAt.toString(),suppression.decisionId))
        assertEquals(ignoredAt,BetaReviewResolutionWorkflow(db).detail("evidence-transaction").resolvedAt)
        val next=observation.copy(input=observation.input.copy(revision=PostingRevision(0,1)))
        assertTrue(store().rebase(next,2) is BetaCorrectionResult.Applied)
        reopen()
        assertEquals(ignoredAt,BetaReviewResolutionWorkflow(db).detail("evidence-transaction").resolvedAt)
        val ignored=BetaReviewInboxWorkflow(db).page(ignored=true).rows.single()
        assertEquals(received,ignored.receivedAt);assertEquals(ignoredAt,ignored.resolvedAt)
    }

    @Test fun financialSuppressionAndRestorationCommitDecisionAndOnlyActiveImpact() = runBlocking {
        val observation = base()
        val first = applied(store().submit(observation, command(observation)))
        assertEquals(BetaLedgerActivity.ACTIVE, first.activity); assertNotNull(ledger().read(observation.input.key))
        val snapshot = ledger().readAudit(observation.input.key)
        val suppression = command(observation, 2, decision = FinancialDecision.SuppressAsNonFinancial)
        assertEquals(BetaLedgerActivity.SUPPRESSED, applied(store().submit(observation, suppression)).activity)
        assertNull(ledger().read(observation.input.key)); assertEquals(snapshot, ledger().readAudit(observation.input.key))
        assertEquals(1, ledger().history(observation.input.key).size)
        reopen()
        assertTrue(store().submit(observation, suppression) is BetaCorrectionResult.AlreadyApplied)
        assertTrue(ledger().post(observation.input.copy(validatedParse = true, revision = PostingRevision(999, 0))) is FinancialPostingResult.Review)
        try { ledger().postCorrection(observation.input.copy(validatedParse = true, revision = PostingRevision(999, 0))); fail() } catch (_: IllegalStateException) { }
        try { db.withTransaction { ledger().postCorrection(observation.input.copy(validatedParse = true, revision = PostingRevision(999, 0))) }; fail() } catch (_: IllegalStateException) { }
        assertEquals(BetaLedgerActivity.ACTIVE, applied(store().submit(observation, command(observation, 3))).activity)
        assertNotNull(ledger().read(observation.input.key)); assertEquals(2, ledger().history(observation.input.key).size)
        assertEquals(3, store().history(observation.input.key).size)
        assertTrue(ledger().history(observation.input.key).all { it.command.amount == observation.input.amount })
    }

    @Test fun suppressionBeforeAnyPostingHasNoFakeLedgerRowButReservesEvidenceAcrossReopen() = runBlocking {
        val observation = base()
        val suppressed = applied(store().submit(observation, command(observation, decision = FinancialDecision.SuppressAsNonFinancial)))
        assertEquals(BetaLedgerActivity.SUPPRESSED, suppressed.activity)
        assertNull(ledger().readAudit(observation.input.key)); assertTrue(ledger().history(observation.input.key).isEmpty())
        reopen()
        assertEquals(BetaLedgerActivity.SUPPRESSED, store().read(observation.input.key)!!.activity)
        val duplicate = observation.input.copy(key = PostingKey(Environment.BETA, "another"), validatedParse = true)
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED), ledger().post(duplicate))
        val another = observation.copy(input = duplicate.copy(validatedParse = false))
        assertEquals(BetaCorrectionResult.PostingConflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED), store().submit(another, command(another)))
        assertNull(store().read(another.input.key))
    }

    @Test fun existingManualPostingIsSeededWithoutInventingDecisionHistoryOrReplacingFacts() = runBlocking {
        val original = base().input.copy(revision = PostingRevision(5, 0), authority = CorrectionAuthority.USER_FINANCIAL_TYPE)
        val posted = (ledger().post(original) as FinancialPostingResult.Applied).posting
        val changed = base(interpretation = 1).let { it.copy(input = it.input.copy(amount = Money.of("99", Currency.USD),
            appliedFx = AppliedFx(BigDecimal("3.99"), "new-parser-market-rate"))) }
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.STALE), store().submit(changed, command(changed)))
        val keep = command(changed, 6, 0, FinancialDecision.Keep, metadata = mapOf("merchant" to "explicit metadata"))
        val view = applied(store().submit(changed, keep))
        assertEquals(BetaLedgerActivity.ACTIVE, view.activity)
        assertEquals(1, view.overlay.history.size)
        assertEquals(posted, CorrectionSnapshots.export(view.overlay).seedPosting)
        assertEquals(original.amount, view.posting!!.command.amount)
        assertEquals(original.appliedFx, view.posting.command.appliedFx)
        assertEquals(original.authority, view.posting.classification.input.authority)
        reopen()
        val suppression = command(changed, 7, 6, FinancialDecision.SuppressAsNonFinancial)
        store().submit(changed, suppression)
        val newer = changed.copy(input = changed.input.copy(revision = PostingRevision(0, 2), amount = Money.of("101", Currency.USD)))
        store().rebase(newer, 7)
        assertNull(ledger().read(original.key))
        val restored = applied(store().submit(newer, command(newer, 8, 7)))
        assertEquals(original.amount, restored.posting!!.command.amount)
        assertEquals(original.time, restored.posting.command.time); assertEquals(original.appliedFx, restored.posting.command.appliedFx)
        assertEquals(3, restored.overlay.history.size)
    }

    @Test fun rebasePinsFinancialDecisionAndMetadataAcrossReopenAndDoesNotReinterpretFx() = runBlocking {
        val first = base()
        val request = command(first, metadata = mapOf("merchant" to "manual merchant"))
        store().submit(first, request)
        val next = base(interpretation = 1).let { it.copy(input = it.input.copy(amount = Money.of("100", Currency.USD),
            time = it.input.time.copy(instant = it.input.time.instant.plusSeconds(90)), appliedFx = AppliedFx(BigDecimal("4.1"), "later"))) }
        val rebased = applied(store().rebase(next, 1))
        assertEquals(first.input.amount, rebased.posting!!.command.amount); assertEquals(first.input.time, rebased.posting.command.time)
        assertEquals(first.input.appliedFx, rebased.posting.command.appliedFx)
        assertEquals(mapOf("merchant" to "manual merchant"), rebased.overlay.metadata)
        reopen(); assertEquals(rebased.posting, ledger().read(first.input.key))
        assertTrue(store().rebase(next, 1) is BetaCorrectionResult.AlreadyApplied)
        assertTrue(store().submit(first, request) is BetaCorrectionResult.AlreadyApplied)
        assertEquals(2, store().history(first.input.key).size)
    }

    @Test fun newBlockersAndStickySecurityTransitionWholeEffectToReviewWithoutLosingManualDecision() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val blocked = base(interpretation = 1).let { it.copy(input = it.input.copy(unresolved = setOf(ReviewReason.OWNERSHIP))) }
        val review = applied(store().rebase(blocked, 1))
        assertEquals(BetaLedgerActivity.REVIEW, review.activity); assertNull(ledger().read(first.input.key)); assertNotNull(ledger().readAudit(first.input.key))
        assertTrue(review.reasons.contains(ReviewReason.OWNERSHIP)); assertEquals(1, ledger().history(first.input.key).size)
        val security = base(interpretation = 2).copy(safety = ObservationSafety.SECURITY)
        assertEquals(BetaLedgerActivity.REVIEW, applied(store().rebase(security, 1)).activity)
        val suppressed = applied(store().submit(security, command(security, 2, decision = FinancialDecision.SuppressAsNonFinancial)))
        assertEquals(BetaLedgerActivity.REVIEW, suppressed.activity); assertTrue(suppressed.reasons.contains(ReviewReason.VALIDATION))
        val ordinary = base(interpretation = 3)
        assertEquals(BetaLedgerActivity.REVIEW, applied(store().rebase(ordinary, 2)).activity)
        assertEquals(BetaLedgerActivity.REVIEW, applied(store().submit(ordinary, command(ordinary, 3))).activity)
    }

    @Test fun currentRegistryRecheckedInsideFinancialTransactionAndOwnershipChangeStaysReview() = runBlocking {
        val first = base(); store().submit(first, command(first))
        db.assetDao().update(BetaAssetEntity("BETA", bank.value, asset.id.localId, "ACCOUNT", "9999", null, null))
        val next = base(interpretation = 1)
        val review = applied(store().rebase(next, 1))
        assertEquals(BetaLedgerActivity.REVIEW, review.activity); assertTrue(review.reasons.contains(ReviewReason.OWNERSHIP))
        assertNull(ledger().read(first.input.key)); assertEquals(1, ledger().history(first.input.key).size)
        assertEquals(BetaLedgerActivity.REVIEW, store().read(first.input.key)!!.activity)
    }

    @Test fun optimisticRevisionExactRequestIdempotencyAndGlobalDecisionIdentityAreDurable() = runBlocking {
        val first = base(); val request = command(first)
        store().submit(first, request)
        assertTrue(store().submit(first, request) is BetaCorrectionResult.AlreadyApplied)
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.CONFLICT), store().submit(first, request.copy(metadata = mapOf("note" to "different"))))
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.STALE), store().submit(first, command(first, 2, 0)))
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.STALE), store().rebase(base(interpretation = 1), 0))
        val other = base("other"); assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.CONFLICT), store().submit(other, command(other, id = request.decisionId)))
        assertNull(store().read(other.input.key)); assertEquals(1, store().history(first.input.key).size)
    }

    @Test fun thrownCommitLeavesNeitherDecisionNorFinancialResultOrClaimsAndRetriesExactlyOnce() = runBlocking {
        val first = base(); val request = command(first)
        try { RoomBetaCorrectionLifecycle(db) { error("after-all-writes") }.submit(first, request); fail() } catch (_: IllegalStateException) { }
        reopen()
        assertNull(store().read(first.input.key)); assertNull(ledger().readAudit(first.input.key))
        assertNull(db.correctionDao().decision("BETA", request.decisionId))
        assertNull(db.correctionDao().evidenceOwner("BETA", first.input.evidenceIds.single()))
        assertNull(db.ledgerDao().evidenceOwner("BETA", first.input.evidenceIds.single()))
        assertTrue(store().submit(first, request) is BetaCorrectionResult.Applied)
        assertTrue(store().submit(first, request) is BetaCorrectionResult.AlreadyApplied)
        assertEquals(1, ledger().history(first.input.key).size); assertEquals(1, store().history(first.input.key).size)
    }

    @Test fun failedSuppressionRollsBackOverlayAuditAndKeepsPreviousActiveImpact() = runBlocking {
        val first = base(); store().submit(first, command(first)); val active = ledger().read(first.input.key)
        val request = command(first, 2, decision = FinancialDecision.SuppressAsNonFinancial)
        try { RoomBetaCorrectionLifecycle(db) { error("rollback-suppression") }.submit(first, request); fail() } catch (_: IllegalStateException) { }
        reopen(); assertEquals(active, ledger().read(first.input.key))
        assertEquals(BetaLedgerActivity.ACTIVE, store().read(first.input.key)!!.activity)
        assertEquals(1, store().history(first.input.key).size)
        assertEquals(BetaLedgerActivity.SUPPRESSED, applied(store().submit(first, request)).activity)
    }

    private fun pair(): MatchedSelfTransfer {
        val out = base().input.copy(validatedParse = true, storedType = FinancialType.SELF_TRANSFER, destination = FinancialEndpoint.Owned(destination.id))
        val incoming = out.copy(key = PostingKey(Environment.BETA, "incoming"), evidenceIds = setOf("incoming-evidence"))
        val reference = ConfirmedBankTransferReference(bank, "pair-proof")
        return (SelfTransferMatcher.match(listOf(TransferObservation(out, TransferDirection.OUTBOUND, reference), TransferObservation(incoming, TransferDirection.INBOUND, reference)),
            TransferMatchProof.BankReference(reference)) as TransferMatchResult.Matched).transfer
    }

    @Test fun wholePairSuppressionAndCheckedRestorationRetainBothObservationsProofAndClaims() = runBlocking {
        val pair = pair(); ledger().post(pair)
        val observation = CorrectionObservation(pair.input.copy(revision = PostingRevision(0, 0)), ObservationSafety.ORDINARY)
        assertEquals(BetaLedgerActivity.SUPPRESSED, applied(store().submit(observation, command(observation, decision = FinancialDecision.SuppressAsNonFinancial))).activity)
        assertNull(ledger().read(pair.input.key)); assertEquals(2, ledger().readAudit(pair.input.key)!!.observations.size)
        reopen()
        val restored = applied(store().submit(observation, command(observation, 2, decision = FinancialDecision.ResolveFinancialType(FinancialType.SELF_TRANSFER))))
        assertEquals(BetaLedgerActivity.ACTIVE, restored.activity); assertEquals(2, restored.posting!!.observations.size)
        assertEquals(pair.identity, restored.posting.matchIdentity)
        assertEquals(pair.input.key.transactionId, db.correctionDao().proofOwner("BETA", "BANK_REFERENCE", bank.value, "pair-proof"))
        for (evidence in pair.input.evidenceIds) assertEquals(pair.input.key.transactionId, db.correctionDao().evidenceOwner("BETA", evidence))
        assertEquals(2, ledger().history(pair.input.key).size)
    }

    @Test fun pairOneLegCorrectionAndFinancialEditAreExplicitlyRefusedWithoutPartialChanges() = runBlocking {
        val pair = pair(); ledger().post(pair)
        val oneLeg = CorrectionObservation(pair.observations.first().input, ObservationSafety.ORDINARY)
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.EVIDENCE), store().submit(oneLeg, command(oneLeg, decision = FinancialDecision.SuppressAsNonFinancial)))
        assertNotNull(ledger().read(pair.input.key)); assertNull(store().read(pair.input.key))
        val whole = CorrectionObservation(pair.input, ObservationSafety.ORDINARY)
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.UNSUPPORTED_CHANGE), store().submit(whole,
            command(whole, decision = FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("50", Currency.USD)))))
        assertNull(store().read(pair.input.key)); assertNotNull(ledger().read(pair.input.key))
    }

    @Test fun pairRestoreCannotClearNewOwnershipValidationOrSecurityBlockers() = runBlocking {
        val pair = pair(); ledger().post(pair)
        val first = CorrectionObservation(pair.input, ObservationSafety.ORDINARY)
        store().submit(first, command(first, decision = FinancialDecision.SuppressAsNonFinancial))
        val blocked = first.copy(input = first.input.copy(revision = PostingRevision(0, 1), unresolved = setOf(ReviewReason.VALIDATION, ReviewReason.AMBIGUOUS_MATCH)))
        val review = applied(store().submit(blocked, command(blocked, 2, decision = FinancialDecision.ResolveFinancialType(FinancialType.SELF_TRANSFER))))
        assertEquals(BetaLedgerActivity.REVIEW, review.activity)
        assertTrue(review.reasons.contains(ReviewReason.VALIDATION)); assertTrue(review.reasons.contains(ReviewReason.AMBIGUOUS_MATCH))
        assertNull(ledger().read(pair.input.key)); assertEquals(1, ledger().history(pair.input.key).size)
    }

    @Test fun concurrentDifferentDecisionsUseOptimisticRevisionAndConcurrentRetriesAreIdempotent() {
        val first = base(); val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((0 until 16).map { index -> Callable { runBlocking {
                store().submit(first, command(first, id = "concurrent-$index", metadata = mapOf("note" to "$index")))
            } } }).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is BetaCorrectionResult.Applied })
            assertEquals(15, results.count { it == BetaCorrectionResult.Rejected(CorrectionRejection.STALE) })
            runBlocking { assertEquals(1, store().history(first.input.key).size); assertEquals(1, ledger().history(first.input.key).size) }
        } finally { executor.shutdownNow() }
    }

    @Test fun corruptedSnapshotFailsClosedOnExistingFinancialReadWithoutPublishingAnyNewRevision() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val original = db.correctionDao().current("BETA", first.input.key.transactionId)!!
        for (payload in listOf(original.payload.replace("BETA_CORRECTION_1", "BETA_CORRECTION_999"), original.payload.replace("checksum", "wrong-checksum"))) {
            db.correctionDao().saveCurrent(original.copy(payload = payload))
            try { ledger().read(first.input.key); fail() } catch (_: IllegalStateException) { }
            try { store().rebase(base(interpretation = 1), 1); fail() } catch (_: IllegalStateException) { }
            assertEquals(1, store().history(first.input.key).size)
        }
    }

    @Test fun currencyEditWithCompleteFxIsRejectedWithoutWritingAConfirmedDecision() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val request = command(first, 2, decision = FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("42", Currency.EUR)))
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.UNSUPPORTED_CHANGE), store().submit(first, request))
        assertEquals(1, store().history(first.input.key).size)
        assertEquals(first.input.appliedFx, ledger().read(first.input.key)!!.command.appliedFx)
    }

    @Test fun typedTimeAndEndpointEditsPersistExactlyAcrossReopenWithoutLosingFxAuthority() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val time = first.input.time.copy(instant = first.input.time.instant.plusSeconds(901))
        val change = command(first, 2, decision = FinancialDecision.EditFacts(time, FinancialEndpoint.Owned(destination.id)))
        val view = applied(store().submit(first, change))
        assertEquals(BetaLedgerActivity.ACTIVE, view.activity)
        assertEquals(time, view.posting!!.command.time)
        assertEquals(destination.id, view.posting.command.source)
        assertEquals(first.input.appliedFx, view.posting.command.appliedFx)
        reopen()
        assertEquals(time, store().read(first.input.key)!!.posting!!.command.time)
        assertTrue(store().submit(first, change) is BetaCorrectionResult.AlreadyApplied)
        assertEquals(2, store().history(first.input.key).size)
    }
    @Test fun reviewPageIncludesSuppressedAndUnpostedCorrectionsAndUsesBoundedCursor() = runBlocking {
        for (id in listOf("a", "b", "c")) { val first = base(id); store().submit(first, command(first, decision = FinancialDecision.SuppressAsNonFinancial)) }
        val workflow = BetaReviewWorkflow(db)
        assertEquals(listOf("a", "b"), workflow.page(limit = 2).map { it.key.transactionId })
        assertEquals(listOf("c"), workflow.page("b", 2).map { it.key.transactionId })
        assertTrue(workflow.page(limit = 3).all { it.activity == BetaLedgerActivity.SUPPRESSED })
    }
    @Test fun reviewSaveBindsToCurrentInterpretationAndTransitionWithoutAcceptingUiFacts() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val workflow = BetaReviewWorkflow(db)
        val shown = workflow.detail(first.input.key)!!
        val result = workflow.save(shown.token, "review-note", FinancialDecision.Keep, mapOf("note" to "reviewed"))
        assertTrue(result is BetaCorrectionResult.Applied)
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.STALE), workflow.save(shown.token, "stale", FinancialDecision.SuppressAsNonFinancial))
        assertTrue(workflow.save(shown.token, "review-note", FinancialDecision.Keep, mapOf("note" to "reviewed")) is BetaCorrectionResult.AlreadyApplied)
        assertEquals("reviewed", store().read(first.input.key)!!.overlay.metadata["note"])
        assertEquals(2, store().history(first.input.key).size)
    }
    @Test fun reviewSaveRejectsChangedConfirmedRegistryWithoutPartialDecision() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val workflow = BetaReviewWorkflow(db); val shown = workflow.detail(first.input.key)!!
        db.openHelper.writableDatabase.execSQL("UPDATE beta_account_registry SET last4='9999' WHERE localId='account'")
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.STALE), workflow.save(shown.token, "stale-asset", FinancialDecision.Keep))
        assertEquals(1, store().history(first.input.key).size)
    }

    @Test fun optionalAdoptionHoldAndRetryNeverFallBackToOldOrdinaryFinancialSnapshot() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val workflow = BetaReviewWorkflow(db); val token = workflow.detail(first.input.key)!!.token
        val capturedAt = Instant.parse("2026-10-17T12:00:00Z")
        RoomBetaEvidenceStore(db).capture(CapturedEvidence("evidence-transaction",
            EvidenceRequest("review-fixture", EvidenceOrigin.EXPLICIT_SAMPLE),
            EvidenceMessage("evidence-transaction", "fixture", "synthetic only", capturedAt, ReceiptTimeSource.USER_SUPPLIED),
            capturedAt, EvidenceRoute(RouteKind.MATCHED, setOf(bank), "review-fixture"), emptyList()))
        db.evidenceDao().clearRetry("evidence-transaction")
        db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS beta_financial_adoption(evidenceId TEXT PRIMARY KEY, transactionId TEXT UNIQUE, status TEXT)")
        db.financialAdoptionDao().put(BetaFinancialAdoptionEntity("evidence-transaction","transaction",bank.value,"fixture","REVIEW","SECURITY","{}",0,0,"{}",0))
        assertNull(workflow.detail(first.input.key))
        assertEquals(BetaLedgerActivity.REVIEW, workflow.page().single().activity)
        assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.STALE), workflow.save(token, "held", FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE)))
        db.openHelper.writableDatabase.execSQL("UPDATE beta_review_item SET status='ACTIVE'")
        assertNotNull(workflow.detail(first.input.key))
        db.evidenceDao().retry(BetaEvidenceRetryEntity("evidence-transaction", "INTERPRETATION", 1, "PENDING", capturedAt.toString()))
        assertNull(workflow.detail(first.input.key))
        db.evidenceDao().clearRetry("evidence-transaction")
        assertNotNull(workflow.detail(first.input.key))
        db.openHelper.writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS beta_financial_approval_retry(evidenceId TEXT PRIMARY KEY, request TEXT)")
        db.openHelper.writableDatabase.execSQL("INSERT INTO beta_processing_retry(rawId,kind,stage,attempts,code,updatedAt,request) VALUES ('evidence-transaction','APPROVAL','',0,'','','pending')")
        assertNull(workflow.detail(first.input.key))
        assertEquals(1, store().history(first.input.key).size)
    }

    @Test fun reviewRejectsUnknownSchemaAndMissingV7FinancialHoldCapabilities() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val workflow = BetaReviewWorkflow(db); val token = workflow.detail(first.input.key)!!.token
        val hasApprovalRetry = db.openHelper.writableDatabase.query("SELECT 1 FROM sqlite_master WHERE name='beta_financial_approval_retry'").use { it.moveToFirst() }
        if (hasApprovalRetry) db.openHelper.writableDatabase.execSQL("DROP VIEW beta_financial_approval_retry")
        try {
            for (version in listOf(7, 999)) {
                db.openHelper.writableDatabase.execSQL("PRAGMA user_version=$version")
                assertNull(workflow.detail(first.input.key))
                assertEquals(BetaCorrectionResult.Rejected(CorrectionRejection.STALE), workflow.save(token, "unknown-$version", FinancialDecision.Keep))
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("PRAGMA user_version=${BetaDatabase.VERSION}")
            if (hasApprovalRetry) db.openHelper.writableDatabase.execSQL("CREATE VIEW beta_financial_approval_retry AS SELECT rawId AS evidenceId,request FROM beta_processing_retry WHERE kind='APPROVAL'")
        }
    }

    @Test fun unrelatedRegistryEditDoesNotBlockAnOtherwiseCurrentReviewDecision() = runBlocking {
        val first = base(); store().submit(first, command(first))
        val workflow = BetaReviewWorkflow(db); val shown = workflow.detail(first.input.key)!!
        db.openHelper.writableDatabase.execSQL("UPDATE beta_account_registry SET last4='9999' WHERE localId='other'")
        assertTrue(workflow.save(shown.token, "unrelated-asset-note", FinancialDecision.Keep, mapOf("note" to "still current")) is BetaCorrectionResult.Applied)
        assertEquals("still current", store().read(first.input.key)!!.overlay.metadata["note"])
    }
}
