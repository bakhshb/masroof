package com.baraa.masroof.integrity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.ingestion.DerivedProcessingStage
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.sms.HistoricalBatchDerivedResult
import com.baraa.masroof.application.transaction.ReconciliationCompletionPolicy
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant

/**
 * One injected failure at each processing boundary. A nonthrowing Conflict with
 * summary.failed > 0 is incomplete. After the fault is removed, replay applies
 * the financial outcome once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeterministicProcessingFaultMatrixTest {
    @Test
    fun afterRawSmsCapture_failureThenReplayPostsOnce() = runBlocking {
        val seed = IntegritySeeds.FAULT
        DashboardLedgerWorld(context()).use { world ->
            val ledger = preparedPurchaseLedger(world)
            ledger.faults.parsedSaveFailures = 1
            val batch = ledger.openBatch()
            val failed = batch.ingestProvider(purchaseRow())
            assertTrue("seed=$seed capture did not keep the SMS: $failed", failed is SmsIngestionResult.Failed)
            assertEquals("seed=$seed lost SMS", 1, world.rawRepo.listIdsByReceivedAt().size)
            assertTrue("seed=$seed parsed event written during capture fault", world.parsedRepo.listAll().isEmpty())
            val rawId = world.rawRepo.listIdsByReceivedAt().single()
            val review = world.reviewRepo.findByRawSmsId(rawId)
            assertEquals("seed=$seed forgotten capture review", ReviewStatus.REQUIRED, review?.status)
            assertEquals("seed=$seed posted during capture fault", 0, world.ftRepo.listAll().size)

            ledger.faults.disarm()
            val replay = ledger.liveProcessing.process(rawId)
            assertTrue("seed=$seed replay did not parse: $replay", replay is SmsIngestionResult.Parsed)
            assertPostedOnce(world, seed, "after_capture")
            val second = ledger.liveProcessing.process(rawId)
            assertTrue("seed=$seed second replay failed: $second", second !is SmsIngestionResult.Failed)
            assertPostedOnce(world, seed, "after_capture_second")
        }
    }

    @Test
    fun afterParsedEventSave_failureThenReplayPostsOnce() = runBlocking {
        val seed = IntegritySeeds.FAULT
        DashboardLedgerWorld(context()).use { world ->
            val ledger = preparedPurchaseLedger(world)
            val batch = ledger.openBatch()
            assertTrue(batch.ingestProvider(purchaseRow()) is SmsIngestionResult.Parsed)
            val rawId = world.rawRepo.listIdsByReceivedAt().single()
            assertNotNull("seed=$seed parsed event missing", world.parsedRepo.findByRawSmsId(rawId))
            ledger.faults.parsedReadFailures = 1

            val finished = batch.finish()
            assertEquals(
                "seed=$seed after parsed save",
                DerivedProcessingStage.RECONCILIATION,
                (finished as HistoricalBatchDerivedResult.Incomplete).stage,
            )
            assertNull("seed=$seed orphan posted during read fault", world.ftRepo.findByRawSmsId(rawId))
            assertEquals(
                "seed=$seed retry missing",
                listOf(rawId),
                ledger.retries.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
            )

            ledger.faults.disarm()
            ledger.derivedRecovery().recoverPending()
            assertPostedOnce(world, seed, "after_parsed_save")
            ledger.derivedRecovery().recoverPending()
            assertPostedOnce(world, seed, "after_parsed_save_second")
        }
    }

    @Test
    fun beforeLinkReplace_failureThenReplayPostsOnce() = runBlocking {
        val seed = IntegritySeeds.FAULT
        DashboardLedgerWorld(context()).use { world ->
            val ledger = preparedTransferLedger(world)
            val batch = ledger.openBatch()
            ingestAll(batch, transferPair())
            seedStaleExternals(world)
            assertEquals("seed=$seed stale legs", 2, world.ftRepo.listAll().size)
            ledger.faults.replaceBeforeFailures = 1

            val error = runCatching { ledger.reconciliation.reconcileStoredEventsDetailed() }.exceptionOrNull()
            assertTrue("seed=$seed before link replace: $error", error is IOException)
            assertEquals("seed=$seed stale legs replaced early", 2, world.ftRepo.listAll().size)
            assertTrue(
                "seed=$seed self-transfer posted before replace",
                world.ftRepo.listAll().none { it.type == FinancialTransactionType.SELF_TRANSFER },
            )
            assertEquals("seed=$seed lost SMS", 2, world.rawRepo.listIdsByReceivedAt().size)

            ledger.faults.disarm()
            val report = ledger.reconciliation.reconcileStoredEventsDetailed()
            assertTrue(
                "seed=$seed replay still incomplete failed=${report.summary.failed}",
                ReconciliationCompletionPolicy.isComplete(report),
            )
            val healed = world.ftRepo.listAll().single()
            assertEquals("seed=$seed healed type", FinancialTransactionType.SELF_TRANSFER, healed.type)
            assertEquals("seed=$seed healed links", 2, world.ftRepo.listRawSmsIds(healed.id).size)
            val again = ledger.reconciliation.reconcileStoredEventsDetailed()
            assertTrue("seed=$seed second replay", ReconciliationCompletionPolicy.isComplete(again))
            assertEquals("seed=$seed duplicate movement", healed.id, world.ftRepo.listAll().single().id)
            // Parse now journals an unfinished historical batch. Complete its review/clear boundary too.
            ledger.derivedRecovery().recoverPending()
            assertFinancialInvariants(world, seed, "before_link_replace")
        }
    }

    @Test
    fun afterLinkReplace_committedWriteThenReplayDoesNotDuplicate() = runBlocking {
        val seed = IntegritySeeds.FAULT
        DashboardLedgerWorld(context()).use { world ->
            val ledger = preparedTransferLedger(world)
            val batch = ledger.openBatch()
            ingestAll(batch, transferPair())
            seedStaleExternals(world)
            ledger.faults.replaceAfterFailures = 1

            val error = runCatching { ledger.reconciliation.reconcileStoredEventsDetailed() }.exceptionOrNull()
            assertTrue("seed=$seed after link replace: $error", error is IOException)
            val committed = world.ftRepo.listAll().single()
            assertEquals("seed=$seed committed type", FinancialTransactionType.SELF_TRANSFER, committed.type)
            assertEquals("seed=$seed committed links", 2, world.ftRepo.listRawSmsIds(committed.id).size)

            ledger.faults.disarm()
            val report = ledger.reconciliation.reconcileStoredEventsDetailed()
            assertTrue("seed=$seed replay incomplete", ReconciliationCompletionPolicy.isComplete(report))
            assertEquals("seed=$seed duplicate after replay", committed.id, world.ftRepo.listAll().single().id)
            // Parse now journals an unfinished historical batch. Complete its review/clear boundary too.
            ledger.derivedRecovery().recoverPending()
            assertFinancialInvariants(world, seed, "after_link_replace")
        }
    }

    @Test
    fun beforeReviewUpsert_failureThenReplayKeepsTheTieReviewable() = runBlocking {
        val seed = IntegritySeeds.FAULT
        DashboardLedgerWorld(context()).use { world ->
            val ledger = preparedTransferLedger(world)
            val batch = ledger.openBatch()
            ingestAll(batch, ambiguousTie())
            ledger.faults.reviewBeforeFailures = 1

            val finished = batch.finish()
            assertEquals(
                "seed=$seed before review upsert",
                DerivedProcessingStage.REVIEW_UPDATE,
                (finished as HistoricalBatchDerivedResult.Incomplete).stage,
            )
            assertTrue("seed=$seed tie posted during review fault", world.ftRepo.listAll().isEmpty())
            assertTrue("seed=$seed review written before upsert", world.reviewRepo.listRequired().isEmpty())
            assertEquals("seed=$seed lost SMS", 4, world.rawRepo.listIdsByReceivedAt().size)
            assertEquals(
                "seed=$seed retry set",
                4,
                ledger.retries.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH).size,
            )

            ledger.faults.disarm()
            ledger.derivedRecovery().recoverPending()
            assertTieReviews(world, seed, "before_review_upsert")
            ledger.derivedRecovery().recoverPending()
            assertTieReviews(world, seed, "before_review_upsert_second")
        }
    }

    @Test
    fun afterReviewUpsert_partialReviewThenReplayDoesNotForgetIt() = runBlocking {
        val seed = IntegritySeeds.FAULT
        DashboardLedgerWorld(context()).use { world ->
            val ledger = preparedTransferLedger(world)
            val batch = ledger.openBatch()
            ingestAll(batch, ambiguousTie())
            ledger.faults.reviewAfterFailures = 1

            val finished = batch.finish()
            assertEquals(
                "seed=$seed after review upsert",
                DerivedProcessingStage.REVIEW_UPDATE,
                (finished as HistoricalBatchDerivedResult.Incomplete).stage,
            )
            val partial = world.reviewRepo.listRequired()
            assertEquals("seed=$seed partial review count", 1, partial.size)
            assertEquals("seed=$seed partial review", ReviewKind.PENDING_MATCH, partial.single().kind)
            val remembered = partial.single().rawSmsId
            assertTrue("seed=$seed tie posted during partial review", world.ftRepo.listAll().isEmpty())

            ledger.faults.disarm()
            ledger.derivedRecovery().recoverPending()
            assertTieReviews(world, seed, "after_review_upsert")
            assertNotNull(
                "seed=$seed forgot the review written before the fault",
                world.reviewRepo.findByRawSmsId(remembered),
            )
            assertEquals(
                "seed=$seed remembered review status",
                ReviewStatus.REQUIRED,
                world.reviewRepo.findByRawSmsId(remembered)!!.status,
            )
        }
    }

    @Test
    fun beforeClearingRecovery_keepsTheRetry_thenReplayClearsItOnce() = runBlocking {
        val seed = IntegritySeeds.FAULT
        DashboardLedgerWorld(context()).use { world ->
            val ledger = preparedPurchaseLedger(world)
            val batch = ledger.openBatch()
            assertTrue(batch.ingestProvider(purchaseRow()) is SmsIngestionResult.Parsed)
            val rawId = world.rawRepo.listIdsByReceivedAt().single()
            ledger.retries.markRequired(rawId, Instant.parse("2026-09-15T09:00:00Z"), ProcessingRetryMode.HISTORICAL_BATCH)
            ledger.faults.clearBeforeFailures = 1

            val error = runCatching { batch.finish() }.exceptionOrNull()
            assertTrue("seed=$seed before clear: $error", error is IOException)
            assertEquals("seed=$seed posted during clear fault", 1, world.ftRepo.listAll().size)
            assertEquals(
                "seed=$seed recovery cleared early",
                listOf(rawId),
                ledger.retries.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
            )

            ledger.faults.disarm()
            val finished = batch.finish()
            assertTrue(
                "seed=$seed second finish: $finished",
                finished is HistoricalBatchDerivedResult.Succeeded,
            )
            assertTrue(
                "seed=$seed retry remains",
                ledger.retries.listRetryableRawSmsIds().isEmpty(),
            )
            assertPostedOnce(world, seed, "before_clear")
        }
    }

    @Test
    fun nonthrowingConflict_failedCountIsIncomplete_thenReplayPostsOnce() = runBlocking {
        val seed = IntegritySeeds.FAULT
        DashboardLedgerWorld(context()).use { world ->
            val ledger = preparedPurchaseLedger(world)
            val batch = ledger.openBatch()
            val ingested = batch.ingestProvider(purchaseRow()) as SmsIngestionResult.Parsed
            ledger.faults.conflictSaves = true

            val report = ledger.reconciliation.reconcileAffectedRawSmsIds(listOf(ingested.rawSmsId))
            assertFalse(
                "seed=$seed conflict treated as success failed=${report.summary.failed}",
                ReconciliationCompletionPolicy.isComplete(report),
            )
            assertTrue("seed=$seed conflict failed count", report.summary.failed > 0)
            val finished = batch.finish()
            assertEquals(
                "seed=$seed batch treated conflict as success",
                DerivedProcessingStage.RECONCILIATION,
                (finished as HistoricalBatchDerivedResult.Incomplete).stage,
            )
            assertNull("seed=$seed conflict posted", world.ftRepo.findByRawSmsId(ingested.rawSmsId))
            assertEquals("seed=$seed lost SMS", 1, world.rawRepo.listIdsByReceivedAt().size)
            assertEquals(
                "seed=$seed retry missing",
                listOf(ingested.rawSmsId),
                ledger.retries.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH),
            )

            ledger.faults.disarm()
            ledger.derivedRecovery().recoverPending()
            assertPostedOnce(world, seed, "nonthrowing_conflict")
        }
    }

    private suspend fun preparedPurchaseLedger(world: DashboardLedgerWorld): IntegrityLedger {
        ownAccounts(world, listOf("3001"))
        ownCard(world, "2210", CardType.DEBIT)
        return IntegrityLedger(world)
    }

    private suspend fun preparedTransferLedger(world: DashboardLedgerWorld): IntegrityLedger {
        ownAccounts(world, listOf("3001", "3002"))
        return IntegrityLedger(world)
    }

    private fun purchaseRow(): ProviderSmsRecord =
        providerRow("fault-purchase", bakeryPurchaseBody(), "2026-09-05T06:15:00Z")

    private fun transferPair(): List<ProviderSmsRecord> = listOf(
        providerRow("fault-out", intraOut("2026-09-03 10:38"), "2026-09-03T07:38:00Z"),
        providerRow("fault-in", intraIn("2026-09-03 10:38"), "2026-09-03T07:38:05Z"),
    )

    private fun ambiguousTie(): List<ProviderSmsRecord> = listOf(
        providerRow("fault-tie-out-a", intraOut("2026-09-02 10:00"), "2026-09-02T07:00:00Z"),
        providerRow("fault-tie-out-b", intraOut("2026-09-02 10:00"), "2026-09-02T07:00:01Z"),
        providerRow("fault-tie-in-a", intraIn("2026-09-02 10:00"), "2026-09-02T07:00:02Z"),
        providerRow("fault-tie-in-b", intraIn("2026-09-02 10:00"), "2026-09-02T07:00:03Z"),
    )

    private suspend fun ingestAll(
        batch: com.baraa.masroof.application.sms.HistoricalSmsBatchProcessor.Batch,
        rows: List<ProviderSmsRecord>,
    ) {
        for (row in rows) {
            val result = batch.ingestProvider(row)
            assertTrue("$result", result is SmsIngestionResult.Parsed)
        }
    }

    private suspend fun seedStaleExternals(world: DashboardLedgerWorld) {
        for (record in world.parsedRepo.listAll()) {
            val type = when (record.event.messageFamily) {
                MessageFamily.TRANSFER_OUT -> FinancialTransactionType.EXTERNAL_TRANSFER_OUT
                MessageFamily.TRANSFER_IN -> FinancialTransactionType.EXTERNAL_TRANSFER_IN
                else -> continue
            }
            val raw = world.rawRepo.getById(record.event.rawSmsId)!!
            world.ftRepo.save(
                FinancialTransaction(
                    id = "tx-stale-${record.event.rawSmsId}",
                    type = type,
                    amount = record.event.amount!!,
                    occurredAt = record.event.occurredAt ?: raw.receivedAt,
                    sourceContainerId = record.event.sourceAccountRef?.let {
                        FinancialContainerIdFactory.accountId(it)
                    },
                    destinationContainerId = record.event.destinationAccountRef?.let {
                        FinancialContainerIdFactory.accountId(it)
                    },
                    merchant = null,
                    counterparty = record.event.counterparty,
                    categoryId = null,
                    linkedParsedEventIds = listOf(record.event.id),
                    occurredAtZone = "Asia/Riyadh",
                ),
                listOf(record.event.rawSmsId),
            )
        }
    }

    private suspend fun assertPostedOnce(world: DashboardLedgerWorld, seed: Int, label: String) {
        assertEquals("seed=$seed $label raw", 1, world.rawRepo.listIdsByReceivedAt().size)
        val transaction = world.ftRepo.listAll().single()
        assertEquals("seed=$seed $label type", FinancialTransactionType.EXPENSE, transaction.type)
        assertEquals(
            "seed=$seed $label links",
            world.rawRepo.listIdsByReceivedAt(),
            world.ftRepo.listRawSmsIds(transaction.id),
        )
        val rawId = world.rawRepo.listIdsByReceivedAt().single()
        val review = world.reviewRepo.findByRawSmsId(rawId)
        if (review != null) {
            assertEquals("seed=$seed $label forgotten processing review", ReviewStatus.RESOLVED, review.status)
        }
        assertFinancialInvariants(world, seed, label)
    }

    private suspend fun assertTieReviews(world: DashboardLedgerWorld, seed: Int, label: String) {
        assertEquals("seed=$seed $label movements", 0, world.ftRepo.listAll().size)
        assertEquals("seed=$seed $label sms", 4, world.rawRepo.listIdsByReceivedAt().size)
        val required = world.reviewRepo.listRequired()
        assertEquals("seed=$seed $label reviews", 4, required.size)
        assertTrue(
            "seed=$seed $label review kind",
            required.all { it.kind == ReviewKind.PENDING_MATCH && it.status == ReviewStatus.REQUIRED },
        )
        assertFinancialInvariants(world, seed, label)
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()
}
