package com.baraa.masroof.integrity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.review.ReviewWorkflowResult
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
            val resolved = ledger.workflow.resolveTransferAsExternal(review.id)
            assertTrue("seed=$seed resolve: $resolved", resolved is ReviewWorkflowResult.Success)
            val success = resolved as ReviewWorkflowResult.Success
            assertEquals(
                "seed=$seed external type",
                FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
                success.transaction!!.type,
            )

            assertTrue("seed=$seed finish", batch.finish() is HistoricalBatchDerivedResult.Succeeded)
            ledger.replayProduction()
            assertResolution(world, seed, parsed.rawSmsId, ReviewResolutionKind.USER_EXTERNAL_TRANSFER, posted = true)
            assertEquals(
                "seed=$seed rewritten after replay",
                FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
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
            for (row in ambiguousTie()) {
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
            for (row in ambiguousTie()) {
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
            assertEquals("seed=$seed correction merchant", "TEST_CORRECTION", success.correction!!.correctedMerchant)
            assertEquals("seed=$seed correction amount", amount, success.correction!!.correctedAmount)
            assertEquals("seed=$seed correction family", MessageFamily.PURCHASE, success.correction!!.correctedType)

            ledger.replayProduction()
            val stored = ledger.corrections.latestForRawSmsId(rawId)!!
            assertEquals("seed=$seed correction lost merchant", "TEST_CORRECTION", stored.correctedMerchant)
            assertEquals("seed=$seed correction lost amount", amount, stored.correctedAmount)
            assertEquals("seed=$seed correction lost family", MessageFamily.PURCHASE, stored.correctedType)
            val effective = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
                world.parsedRepo,
                ledger.corrections,
            ).findEffectiveByRawSmsId(rawId)!!
            assertEquals("seed=$seed effective family", MessageFamily.PURCHASE, effective.event.messageFamily)
            assertEquals("seed=$seed effective amount", amount, effective.event.amount)
            assertEquals("seed=$seed effective merchant", "TEST_CORRECTION", effective.event.merchant)
            val resolution = world.reviewRepo.findByRawSmsId(rawId)!!.resolutionKind
            if (success.transaction != null) {
                assertEquals("seed=$seed correction resolution", ReviewResolutionKind.USER_CORRECTION, resolution)
                assertEquals(
                    "seed=$seed correction movement",
                    success.transaction!!.id,
                    world.ftRepo.findByRawSmsId(rawId)!!.id,
                )
            }
            assertFinancialInvariants(world, seed, "correction")
        }
    }

    private suspend fun purchaseLedger(world: DashboardLedgerWorld): IntegrityLedger {
        ownAccounts(world, listOf("3001"))
        ownCard(world, "2210", com.baraa.masroof.domain.model.CardType.DEBIT)
        return IntegrityLedger(world)
    }

    private suspend fun transferLedger(world: DashboardLedgerWorld): IntegrityLedger {
        ownAccounts(world, listOf("3001", "3002"))
        return IntegrityLedger(world)
    }

    private fun ambiguousTie() = listOf(
        providerRow("manual-tie-out-a", intraOut("2026-09-02 10:00"), "2026-09-02T07:00:00Z"),
        providerRow("manual-tie-out-b", intraOut("2026-09-02 10:00"), "2026-09-02T07:00:01Z"),
        providerRow("manual-tie-in-a", intraIn("2026-09-02 10:00"), "2026-09-02T07:00:02Z"),
        providerRow("manual-tie-in-b", intraIn("2026-09-02 10:00"), "2026-09-02T07:00:03Z"),
    )

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
