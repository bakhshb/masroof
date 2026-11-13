package com.baraa.masroof.integrity

import org.junit.Assert.assertFalse
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.application.transaction.ReconciliationCompletionPolicy
import com.baraa.masroof.application.transaction.TransactionReconciliationService
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.application.interpretation.AutomaticInterpretationWriteGuard
import androidx.room.withTransaction
import com.baraa.masroof.testsupport.latestCorrectionFor
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.ExternalTransferDirection
import com.baraa.masroof.application.review.ReviewWorkflowResult
import com.baraa.masroof.application.transaction.IgnoreResult
import com.baraa.masroof.application.transaction.ReclassificationResult
import com.baraa.masroof.application.transaction.RestoreResult
import com.baraa.masroof.application.sms.HistoricalBatchDerivedResult
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Human resolutions survive reparse, reconciliation replay, and legacy transfer repair.
 * Choices are recorded both before derived processing and after it has already run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManualDecisionReplayTest {
    @Test
    fun userNonFinancial_beforeAutomaticProcessing_survivesReplay() = runBlocking {
        val seed = IntegritySeeds.MANUAL
        DashboardLedgerWorld(context()).use { world ->
            val ledger = purchaseLedger(world)
            val batch = ledger.openBatch()
            val parsed = batch.ingestProvider(providerRow("manual-before", bakeryPurchaseBody(), "2026-09-05T06:15:00Z"))
            assertTrue("seed=$seed parse: $parsed", parsed is SmsIngestionResult.Parsed)
            val rawId = (parsed as SmsIngestionResult.Parsed).rawSmsId
            val review = requireReview(world, rawId)
            val resolved = ledger.workflow.resolveAsNonFinancial(review.id)
            assertTrue("seed=$seed resolve: $resolved", resolved is ReviewWorkflowResult.Success)

            val finished = batch.finish()
            assertTrue("seed=$seed finish: $finished", finished is HistoricalBatchDerivedResult.Succeeded)
            assertNull("seed=$seed posted before replay", world.ftRepo.findByRawSmsId(rawId))

            ledger.replayProduction()
            assertResolution(world, seed, rawId, ReviewResolutionKind.USER_NON_FINANCIAL, posted = false)
            assertFinancialInvariants(world, seed, "non_financial_before")
        }
    }

    @Test
    fun userNonFinancial_afterAutomaticProcessing_survivesReplay() = runBlocking {
        val seed = IntegritySeeds.MANUAL
        DashboardLedgerWorld(context()).use { world ->
            val ledger = IntegrityLedger(world)
            val batch = ledger.openBatch()
            val parsed = batch.ingestProvider(
                providerRow("manual-unknown", unknownBankNoticeBody(), "2026-09-04T05:05:00Z"),
            )
            assertTrue("seed=$seed parse: $parsed", parsed is SmsIngestionResult.ReviewRequired)
            assertTrue("seed=$seed finish", batch.finish() is HistoricalBatchDerivedResult.Succeeded)
            val rawId = (parsed as SmsIngestionResult.ReviewRequired).rawSmsId
            val review = world.reviewRepo.findByRawSmsId(rawId)!!
            assertEquals("seed=$seed review before choice", ReviewStatus.REQUIRED, review.status)
            val resolved = ledger.workflow.resolveAsNonFinancial(review.id)
            assertTrue("seed=$seed resolve: $resolved", resolved is ReviewWorkflowResult.Success)

            ledger.replayProduction()
            assertResolution(world, seed, rawId, ReviewResolutionKind.USER_NON_FINANCIAL, posted = false)
            assertFinancialInvariants(world, seed, "non_financial_after")
        }
    }

    @Test
    fun userExternalTransfer_beforeAutomaticPosting_survivesReplay() = runBlocking {
        val seed = IntegritySeeds.MANUAL
        DashboardLedgerWorld(context()).use { world ->
            val ledger = transferLedger(world)
            val batch = ledger.openBatch()
            val parsed = batch.ingestProvider(
                providerRow("manual-ext-out", intraOut("2026-09-03 10:38"), "2026-09-03T07:38:00Z"),
            ) as SmsIngestionResult.Parsed
            val review = requireReview(world, parsed.rawSmsId)
            val resolved = ledger.workflow.resolveTransferAsExternal(review.id, com.baraa.masroof.application.review.ExternalTransferDirection.OUTGOING)
            assertTrue("seed=$seed resolve: $resolved", resolved is ReviewWorkflowResult.Success)
            val success = resolved as ReviewWorkflowResult.Success
            // The intra-bank destination is owned, so the movement leaves one account and enters the other.
            assertEquals(
                "seed=$seed self type",
                FinancialTransactionType.SELF_TRANSFER,
                success.transaction!!.type,
            )

            assertTrue("seed=$seed finish", batch.finish() is HistoricalBatchDerivedResult.Succeeded)
            ledger.replayProduction()
            assertResolution(world, seed, parsed.rawSmsId, ReviewResolutionKind.USER_EXTERNAL_TRANSFER, posted = true)
            assertEquals(
                "seed=$seed rewritten after replay",
                FinancialTransactionType.SELF_TRANSFER,
                world.ftRepo.findByRawSmsId(parsed.rawSmsId)!!.type,
            )
            assertEquals("seed=$seed movements", 1, world.ftRepo.listAll().size)
            assertFinancialInvariants(world, seed, "external_before")
        }
    }

    @Test
    fun userSelfTransferPair_afterTie_survivesReplayAndRepair() = runBlocking {
        val seed = IntegritySeeds.MANUAL
        DashboardLedgerWorld(context()).use { world ->
            val ledger = transferLedger(world)
            val batch = ledger.openBatch()
            for (row in ambiguousTie("manual")) {
                assertTrue(batch.ingestProvider(row) is SmsIngestionResult.Parsed)
            }
            assertTrue("seed=$seed finish", batch.finish() is HistoricalBatchDerivedResult.Succeeded)
            assertTrue("seed=$seed tie posted early", world.ftRepo.listAll().isEmpty())
            val outgoing = reviewFor(world, MessageFamily.TRANSFER_OUT)
            val incoming = reviewFor(world, MessageFamily.TRANSFER_IN)
            val resolved = ledger.workflow.resolveSelfTransferPair(outgoing.id, incoming.id)
            assertTrue("seed=$seed pair: $resolved", resolved is ReviewWorkflowResult.Success)
            val paired = (resolved as ReviewWorkflowResult.Success).transaction!!
            assertEquals("seed=$seed pair type", FinancialTransactionType.SELF_TRANSFER, paired.type)
            val linksBefore = world.ftRepo.listRawSmsIds(paired.id).toSet()
            assertEquals("seed=$seed pair links", setOf(outgoing.rawSmsId, incoming.rawSmsId), linksBefore)

            ledger.replayProduction()
            assertEquals("seed=$seed pair id changed", paired.id, world.ftRepo.findByRawSmsId(outgoing.rawSmsId)!!.id)
            assertEquals(
                "seed=$seed pair links changed",
                linksBefore,
                world.ftRepo.listRawSmsIds(paired.id).toSet(),
            )
            assertResolution(world, seed, outgoing.rawSmsId, ReviewResolutionKind.USER_SELF_TRANSFER_PAIR, posted = true)
            assertResolution(world, seed, incoming.rawSmsId, ReviewResolutionKind.USER_SELF_TRANSFER_PAIR, posted = true)
            assertTrue(
                "seed=$seed a non-self movement was fabricated ${world.ftRepo.listAll().map { it.type }}",
                world.ftRepo.listAll().all { it.type == FinancialTransactionType.SELF_TRANSFER },
            )
            assertFinancialInvariants(world, seed, "self_transfer_pair")
        }
    }

    @Test
    fun userFinancialType_afterTie_survivesReplay_andALaterConflict() = runBlocking {
        val seed = IntegritySeeds.MANUAL
        DashboardLedgerWorld(context()).use { world ->
            val ledger = transferLedger(world)
            ownCard(world, "2210", com.baraa.masroof.domain.model.CardType.DEBIT)
            ownAccounts(world, listOf("3001"))
            val batch = ledger.openBatch()
            for (row in ambiguousTie("manual")) {
                assertTrue(batch.ingestProvider(row) is SmsIngestionResult.Parsed)
            }
            assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
            val outgoing = reviewFor(world, MessageFamily.TRANSFER_OUT)
            val resolved = ledger.workflow.resolveAsFinancialType(outgoing.id, FinancialTransactionType.FEE)
            assertTrue("seed=$seed resolve: $resolved", resolved is ReviewWorkflowResult.Success)
            val keptId = (resolved as ReviewWorkflowResult.Success).transaction!!.id

            ledger.faults.conflictSaves = true
            val second = ledger.openBatch()
            val purchase = second.ingestProvider(
                providerRow("manual-conflict-purchase", bakeryPurchaseBody(), "2026-09-05T06:15:00Z"),
            )
            assertTrue("seed=$seed purchase parse: $purchase", purchase is SmsIngestionResult.Parsed)
            val conflicted = second.finish()
            assertTrue(
                "seed=$seed later conflict looked complete: $conflicted",
                conflicted is HistoricalBatchDerivedResult.Incomplete,
            )
            assertResolution(world, seed, outgoing.rawSmsId, ReviewResolutionKind.USER_FINANCIAL_TYPE, posted = true)
            assertEquals(
                "seed=$seed conflict rewrote the manual type",
                FinancialTransactionType.FEE,
                world.ftRepo.findByRawSmsId(outgoing.rawSmsId)!!.type,
            )
            assertEquals("seed=$seed conflict changed the manual id", keptId, world.ftRepo.findByRawSmsId(outgoing.rawSmsId)!!.id)

            ledger.faults.disarm()
            ledger.derivedRecovery().recoverPending()
            ledger.replayProduction()
            assertResolution(world, seed, outgoing.rawSmsId, ReviewResolutionKind.USER_FINANCIAL_TYPE, posted = true)
            assertEquals(
                "seed=$seed replay rewrote the manual type",
                FinancialTransactionType.FEE,
                world.ftRepo.findByRawSmsId(outgoing.rawSmsId)!!.type,
            )
            assertEquals("seed=$seed replay changed the manual id", keptId, world.ftRepo.findByRawSmsId(outgoing.rawSmsId)!!.id)
            val purchaseId = (purchase as SmsIngestionResult.Parsed).rawSmsId
            assertEquals(
                "seed=$seed purchase replay",
                FinancialTransactionType.EXPENSE,
                world.ftRepo.findByRawSmsId(purchaseId)!!.type,
            )
            assertFinancialInvariants(world, seed, "financial_type")
        }
    }

    @Test
    fun approvedCorrection_survivesReparseReplayAndRepair() = runBlocking {
        val seed = IntegritySeeds.MANUAL
        DashboardLedgerWorld(context()).use { world ->
            val ledger = IntegrityLedger(world)
            val batch = ledger.openBatch()
            val parsed = batch.ingestProvider(
                providerRow("manual-correction", unknownBankNoticeBody(), "2026-09-04T05:05:00Z"),
            )
            assertTrue("seed=$seed parse: $parsed", parsed is SmsIngestionResult.ReviewRequired)
            assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
            val rawId = (parsed as SmsIngestionResult.ReviewRequired).rawSmsId
            val review = world.reviewRepo.findByRawSmsId(rawId)!!
            val amount = Money.of("12.00", Currency.SAR)
            val corrected = ledger.workflow.applyCorrection(
                reviewId = review.id,
                correctedType = MessageFamily.PURCHASE,
                correctedAmount = amount,
                correctedMerchant = "TEST_CORRECTION",
            )
            assertTrue("seed=$seed correction: $corrected", corrected is ReviewWorkflowResult.Success)
            val success = corrected as ReviewWorkflowResult.Success
            assertEquals("seed=$seed correction merchant", "TEST_CORRECTION", success.correction!!.merchant)
            assertEquals("seed=$seed correction amount", amount, success.correction.amount)
            assertEquals("seed=$seed correction family", MessageFamily.PURCHASE, success.correction.messageFamily)

            ledger.replayProduction()
            val stored = ledger.corrections.latestCorrectionFor(rawId)!!
            assertEquals("seed=$seed correction lost merchant", "TEST_CORRECTION", stored.merchant)
            assertEquals("seed=$seed correction lost amount", amount, stored.amount)
            assertEquals("seed=$seed correction lost family", MessageFamily.PURCHASE, stored.messageFamily)
            val effective = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
                world.parsedRepo,
                world.decisionRepo,
            ).findEffectiveByRawSmsId(rawId)!!
            assertEquals("seed=$seed effective family", MessageFamily.PURCHASE, effective.event.messageFamily)
            assertEquals("seed=$seed effective amount", amount, effective.event.amount)
            assertEquals("seed=$seed effective merchant", "TEST_CORRECTION", effective.event.merchant)
            val resolution = world.reviewRepo.findByRawSmsId(rawId)!!.resolutionKind
            if (success.transaction != null) {
                assertEquals("seed=$seed correction resolution", ReviewResolutionKind.USER_CORRECTION, resolution)
                assertEquals(
                    "seed=$seed correction movement",
                    success.transaction.id,
                    world.ftRepo.findByRawSmsId(rawId)!!.id,
                )
            }
            assertFinancialInvariants(world, seed, "correction")
        }
    }

    // Plan 2.3: every decision survives a reparse and reconcile that start from an emptied ledger, and the
    // decided rows come back the same, because processing reads the decisions from user_decision.

    @Test
    fun emptiedLedger_userNonFinancialAndIgnore_postNothing() = emptiedLedgerCase(::purchaseLedger) { world, ledger ->
        val batch = ledger.openBatch()
        val before = (batch.ingestProvider(providerRow("empty-nf", bakeryPurchaseBody(), "2026-09-05T06:15:00Z")) as SmsIngestionResult.Parsed).rawSmsId
        assertTrue(ledger.workflow.resolveAsNonFinancial(requireReview(world, before).id) is ReviewWorkflowResult.Success)
        val ignored = (batch.ingestProvider(providerRow("empty-ignore", bakeryPurchaseBody(), "2026-09-06T06:15:00Z")) as SmsIngestionResult.Parsed).rawSmsId
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
        assertEquals(IgnoreResult.Success, ledger.ignore.ignore(world.ftRepo.findByRawSmsId(ignored)!!.id))
        listOf(before, ignored)
    }

    @Test
    fun emptiedLedger_userExternalTransfer_isRebuiltAsChosen() = emptiedLedgerCase(::transferLedger) { world, ledger ->
        val batch = ledger.openBatch()
        val parsed = batch.ingestProvider(providerRow("empty-ext", intraOut("2026-09-03 10:38"), "2026-09-03T07:38:00Z")) as SmsIngestionResult.Parsed
        val resolved = ledger.workflow.resolveTransferAsExternal(requireReview(world, parsed.rawSmsId).id, ExternalTransferDirection.OUTGOING)
        assertTrue("resolve: $resolved", resolved is ReviewWorkflowResult.Success)
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
        listOf(parsed.rawSmsId)
    }

    @Test
    fun emptiedLedger_userSelfTransferPair_isRebuiltAsOneMovement() = emptiedLedgerCase(::transferLedger) { world, ledger ->
        val batch = ledger.openBatch()
        ambiguousTie("empty").forEach { assertTrue(batch.ingestProvider(it) is SmsIngestionResult.Parsed) }
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
        val outgoing = reviewFor(world, MessageFamily.TRANSFER_OUT)
        val incoming = reviewFor(world, MessageFamily.TRANSFER_IN)
        assertTrue(ledger.workflow.resolveSelfTransferPair(outgoing.id, incoming.id) is ReviewWorkflowResult.Success)
        listOf(outgoing.rawSmsId, incoming.rawSmsId)
    }

    @Test
    fun emptiedLedger_userFinancialType_isRebuiltWithTheChosenType() = emptiedLedgerCase(::transferLedger) { world, ledger ->
        ownCard(world, "2210", com.baraa.masroof.domain.model.CardType.DEBIT)
        ownAccounts(world, listOf("3001"))
        val batch = ledger.openBatch()
        ambiguousTie("empty").forEach { assertTrue(batch.ingestProvider(it) is SmsIngestionResult.Parsed) }
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
        val outgoing = reviewFor(world, MessageFamily.TRANSFER_OUT)
        val resolved = ledger.workflow.resolveAsFinancialType(outgoing.id, FinancialTransactionType.FEE)
        assertTrue("resolve: $resolved", resolved is ReviewWorkflowResult.Success)
        listOf(outgoing.rawSmsId)
    }

    @Test
    fun emptiedLedger_reclassifiedRow_isRebuiltWithTheNewType() = emptiedLedgerCase(::purchaseLedger) { world, ledger ->
        val rawId = postedPurchase(ledger, "empty-reclassify")
        val reclassified = ledger.reclassification.reclassify(world.ftRepo.findByRawSmsId(rawId)!!.id, FinancialTransactionType.FEE)
        assertTrue("reclassify: $reclassified", reclassified is ReclassificationResult.Success)
        listOf(rawId)
    }

    @Test
    fun emptiedLedger_restoredSms_isPostedAgain() = emptiedLedgerCase(::purchaseLedger) { world, ledger ->
        val rawId = postedPurchase(ledger, "empty-restore")
        assertEquals(IgnoreResult.Success, ledger.ignore.ignore(world.ftRepo.findByRawSmsId(rawId)!!.id))
        assertTrue(ledger.restore.restore(rawId) is RestoreResult.Success)
        listOf(rawId)
    }

    @Test
    fun emptiedLedger_approvedCorrection_isReappliedToTheEvidence() = emptiedLedgerCase(::purchaseLedger) { world, ledger ->
        val batch = ledger.openBatch()
        val parsed = batch.ingestProvider(providerRow("empty-correction", bakeryPurchaseBody(), "2026-09-05T06:15:00Z")) as SmsIngestionResult.Parsed
        val review = requireReview(world, parsed.rawSmsId)
        val corrected = ledger.workflow.applyCorrection(review.id, correctedAmount = Money.of("12.00", Currency.SAR),
            correctedMerchant = "TEST_CORRECTION")
        assertTrue("correction: $corrected", corrected is ReviewWorkflowResult.Success)
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
        assertEquals(Money.of("12.00", Currency.SAR), world.ftRepo.findByRawSmsId(parsed.rawSmsId)!!.amount)
        listOf(parsed.rawSmsId)
    }

    /**
     * Plan 2.3, the kept limit: a type edited on the posted row without a review or correction is automatic (owner
     * rule of 2.2) and has no decision. Reconciliation keeps it only because the row is still posted; an emptied
     * ledger rebuilds it from the evidence. Plan 3.2 reports that difference.
     */
    @Test
    fun typeEditedOnlyOnTheRow_isKeptWhilePosted_andAutomaticOnceTheLedgerIsEmptied() = runBlocking {
        DashboardLedgerWorld(context()).use { world ->
            val ledger = purchaseLedger(world)
            val rawId = postedPurchase(ledger, "row-only-type")
            val posted = world.ftRepo.findByRawSmsId(rawId)!!
            assertEquals(FinancialTransactionType.EXPENSE, posted.type)
            assertTrue(world.ftRepo.update(posted.copy(type = FinancialTransactionType.FEE)))
            assertTrue("a row-only edit made a decision", world.db.userDecisionDao().listAll().none { it.rawSmsId == rawId })

            ledger.replayProduction()
            assertEquals(FinancialTransactionType.FEE, world.ftRepo.findByRawSmsId(rawId)!!.type)

            emptyLedger(world)
            ledger.replayProduction()
            assertEquals(FinancialTransactionType.EXPENSE, world.ftRepo.findByRawSmsId(rawId)!!.type)
        }
    }

    /**
     * The open case of 2.2, against today's behaviour: a correction needs a REQUIRED review, so an ignored SMS
     * cannot be corrected; its latest outcome stays IGNORE and it stays unposted, review and store alike.
     */
    @Test
    fun correctionOfAnIgnoredSms_isRejected_andItStaysNonFinancial() = runBlocking {
        DashboardLedgerWorld(context()).use { world ->
            val ledger = purchaseLedger(world)
            val rawId = postedPurchase(ledger, "ignored-correction")
            assertEquals(IgnoreResult.Success, ledger.ignore.ignore(world.ftRepo.findByRawSmsId(rawId)!!.id))
            val review = world.reviewRepo.findByRawSmsId(rawId)!!
            val corrected = ledger.workflow.applyCorrection(review.id, correctedMerchant = "TEST_CORRECTION")
            assertEquals(ReviewWorkflowResult.Rejected("review_not_required"), corrected)
            assertTrue(world.decisionRepo.effectiveFor(rawId).let { it.isNonFinancial && it.corrections.isEmpty() })

            ledger.replayProduction()
            assertResolution(world, IntegritySeeds.MANUAL, rawId, ReviewResolutionKind.USER_NON_FINANCIAL, posted = false)
        }
    }

    /**
     * A decided external transfer whose leg now carries an out-of-date model inference, on an emptied ledger: the
     * write guard rejects the rebuild as `stale_family_inference`, the pass is incomplete with the leg to retry, and
     * nothing is posted for it. No retry can succeed (a decided message is never refreshed); plan 3.x decides it.
     */
    @Test
    fun emptiedLedger_decidedTransferWithAStaleInference_isRejectedAndNeverPosts() = runBlocking {
        DashboardLedgerWorld(context()).use { world ->
            val ledger = transferLedger(world)
            val batch = ledger.openBatch()
            val parsed = batch.ingestProvider(providerRow("stale-ext", intraOut("2026-09-03 10:38"), "2026-09-03T07:38:00Z")) as SmsIngestionResult.Parsed
            val resolved = ledger.workflow.resolveTransferAsExternal(requireReview(world, parsed.rawSmsId).id, ExternalTransferDirection.OUTGOING)
            assertTrue("resolve: $resolved", resolved is ReviewWorkflowResult.Success)
            assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
            val stored = world.parsedRepo.findByRawSmsId(parsed.rawSmsId)!!
            val inferred = stored.event.confidence.copy(reasons = stored.event.confidence.reasons + "family_origin:ML" + "model:old")
            world.parsedRepo.save(stored.event.copy(confidence = inferred), stored.details)
            val decisions = world.db.userDecisionDao().listAll()
            emptyLedger(world)

            val guard = writeGuard(world) { record -> record.event.confidence.reasons.none { it.startsWith("family_origin:") } }
            var conflict: FinancialTransactionSaveResult? = null
            val reconciliation = TransactionReconciliationService(
                rawSmsRepository = world.rawRepo,
                financialTransactionRepository = world.ftRepo,
                ownershipResolver = OwnershipResolver(world.accounts, world.cards, world.loans),
                ownershipConfirmationService = OwnershipConfirmationService(world.accounts, world.cards, world.loans),
                effectiveParsedEventProvider = EffectiveParsedEventProvider(world.parsedRepo, world.decisionRepo),
                reviewRepository = world.reviewRepo,
                userDecisionRepository = world.decisionRepo,
                zoneId = world.zone,
                automaticWrite = { expected, write -> guard.write(expected, write).also { if (it is FinancialTransactionSaveResult.Conflict) conflict = it } },
            )
            val report = reconciliation.reconcileStoredEventsDetailed()

            assertEquals(FinancialTransactionSaveResult.Conflict(parsed.rawSmsId, "stale_family_inference"), conflict)
            assertFalse("a stale rebuild looked complete", ReconciliationCompletionPolicy.isComplete(report))
            assertTrue(parsed.rawSmsId in report.failedRawSmsIds)
            assertNull(world.ftRepo.findByRawSmsId(parsed.rawSmsId))
            assertTrue(world.ftRepo.listAll().isEmpty())
            assertEquals(decisions, world.db.userDecisionDao().listAll())
            assertResolution(world, IntegritySeeds.MANUAL, parsed.rawSmsId, ReviewResolutionKind.USER_EXTERNAL_TRANSFER, posted = false)
        }
    }

    /** Runs [decide], empties the ledger, replays, and requires the same decisions, reviews and rows for its subjects. */
    private fun emptiedLedgerCase(
        ledgerOf: suspend (DashboardLedgerWorld) -> IntegrityLedger,
        decide: suspend (DashboardLedgerWorld, IntegrityLedger) -> List<String>,
    ) = runBlocking {
        val seed = IntegritySeeds.MANUAL
        DashboardLedgerWorld(context()).use { world ->
            val ledger = ledgerOf(world)
            val subjects = decide(world, ledger)
            val decisions = world.db.userDecisionDao().listAll()
            val before = decidedState(world, subjects)
            assertTrue("seed=$seed nothing was decided", decisions.isNotEmpty())

            emptyLedger(world)
            ledger.replayProduction()
            assertEquals("seed=$seed replay changed a decision", decisions, world.db.userDecisionDao().listAll())
            assertEquals("seed=$seed rows not rebuilt as decided", before, decidedState(world, subjects))
            // Every posted decided subject, the paired leg of a pair included, is still protected from automatic writes.
            val guard = writeGuard(world) { true }
            for (id in subjects.filter { world.ftRepo.findByRawSmsId(it) != null }) {
                val record = requireNotNull(EffectiveParsedEventProvider(world.parsedRepo, world.decisionRepo).findEffectiveByRawSmsId(id))
                var wrote = false
                val result = guard.write(listOf(record)) { wrote = true; FinancialTransactionSaveResult.Saved }
                assertTrue("seed=$seed $id lost its protection: $result", result is FinancialTransactionSaveResult.Conflict && !wrote)
            }
            assertFinancialInvariants(world, seed, "emptied_ledger")
        }
    }

    private fun writeGuard(world: DashboardLedgerWorld, inferenceCurrent: suspend (ParsedEventRecord) -> Boolean) =
        AutomaticInterpretationWriteGuard(
            EffectiveParsedEventProvider(world.parsedRepo, world.decisionRepo), world.reviewRepo, world.decisionRepo, world.ftRepo,
            inferenceCurrent = inferenceCurrent,
            inTransaction = { mutation -> world.db.withTransaction { mutation() } },
            // The pattern definitions are current here; only the family inference is under test.
            patternsCurrent = { _, _ -> true },
        )

    private suspend fun decidedState(world: DashboardLedgerWorld, subjects: List<String>) = subjects.associateWith { id ->
        val review = world.reviewRepo.findByRawSmsId(id)
        val posted = world.ftRepo.findByRawSmsId(id)
        Triple(review?.status to review?.resolutionKind, posted, posted?.let { world.ftRepo.listRawSmsIds(it.id).toSet() })
    }

    private fun emptyLedger(world: DashboardLedgerWorld) {
        val sql = world.db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM financial_transaction_raw_sms_link")
        sql.execSQL("DELETE FROM financial_transaction")
    }

    private suspend fun postedPurchase(ledger: IntegrityLedger, id: String): String {
        val batch = ledger.openBatch()
        val parsed = batch.ingestProvider(providerRow(id, bakeryPurchaseBody(), "2026-09-05T06:15:00Z")) as SmsIngestionResult.Parsed
        assertTrue(batch.finish() is HistoricalBatchDerivedResult.Succeeded)
        return parsed.rawSmsId
    }

    private suspend fun requireReview(world: DashboardLedgerWorld, rawSmsId: String): ReviewItem {
        world.reviewRepo.upsertRequired(
            rawSmsId = rawSmsId,
            kind = ReviewKind.NEEDS_REVIEW,
            reasons = listOf("manual_before_processing"),
            now = java.time.Instant.parse("2026-09-15T09:00:00Z"),
        )
        return world.reviewRepo.findByRawSmsId(rawSmsId)!!
    }

    private suspend fun reviewFor(world: DashboardLedgerWorld, family: MessageFamily): ReviewItem {
        val rawId = world.parsedRepo.listAll().first { it.event.messageFamily == family }.event.rawSmsId
        return world.reviewRepo.findByRawSmsId(rawId)!!
    }

    private suspend fun assertResolution(
        world: DashboardLedgerWorld,
        seed: Int,
        rawSmsId: String,
        kind: ReviewResolutionKind,
        posted: Boolean,
    ) {
        val review = world.reviewRepo.findByRawSmsId(rawSmsId)
        assertEquals("seed=$seed $rawSmsId status", ReviewStatus.RESOLVED, review?.status)
        assertEquals("seed=$seed $rawSmsId resolution", kind, review?.resolutionKind)
        if (posted) {
            assertTrue("seed=$seed $rawSmsId lost its movement", world.ftRepo.findByRawSmsId(rawSmsId) != null)
        } else {
            assertNull("seed=$seed $rawSmsId was posted", world.ftRepo.findByRawSmsId(rawSmsId))
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
