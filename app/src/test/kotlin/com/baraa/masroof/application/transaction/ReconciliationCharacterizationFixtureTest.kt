package com.baraa.masroof.application.transaction

import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.testsupport.ReconciliationCharacterizationFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full-history reconciliation baselines. Scoped reconciliation in later
 * milestones must reproduce these posted shapes for the same seeds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReconciliationCharacterizationFixtureTest {
    @Test
    fun nonTransfer_postsOneExpense() = runBlocking {
        ReconciliationCharacterizationFixture.open(ApplicationProvider.getApplicationContext()).use { world ->
            world.seedNonTransfer()
            world.reconciliation().reconcileStoredEvents()

            val posted = world.snapshot().single()
            assertEquals(FinancialTransactionType.EXPENSE, posted.type)
            assertEquals(Money.of(ReconciliationCharacterizationFixture.PURCHASE_AMOUNT, Currency.SAR), posted.amount)
            assertEquals(setOf(ReconciliationCharacterizationFixture.PURCHASE_SMS), posted.rawSmsIds)
            assertEquals(listOf(ReconciliationCharacterizationFixture.PURCHASE_EVENT), posted.linkedParsedEventIds)
            assertEquals(ReconciliationCharacterizationFixture.purchaseContainerId(), posted.sourceContainerId)
            assertEquals("Keeta", posted.merchant)
        }
    }

    @Test
    fun externalTransfer_staysExternalWhenDestinationIsUnowned() = runBlocking {
        ReconciliationCharacterizationFixture.open(ApplicationProvider.getApplicationContext()).use { world ->
            world.seedExternalTransfer()
            world.reconciliation().reconcileStoredEvents()

            val posted = world.snapshot().single()
            assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, posted.type)
            assertEquals(Money.of(ReconciliationCharacterizationFixture.EXTERNAL_AMOUNT, Currency.SAR), posted.amount)
            assertEquals(setOf(ReconciliationCharacterizationFixture.EXTERNAL_SMS), posted.rawSmsIds)
            assertEquals(listOf(ReconciliationCharacterizationFixture.EXTERNAL_EVENT), posted.linkedParsedEventIds)
            assertEquals(
                ReconciliationCharacterizationFixture.accountContainerId(
                    ReconciliationCharacterizationFixture.EXTERNAL_SOURCE,
                ),
                posted.sourceContainerId,
            )
            assertEquals(
                ReconciliationCharacterizationFixture.accountContainerId(
                    ReconciliationCharacterizationFixture.EXTERNAL_DESTINATION,
                ),
                posted.destinationContainerId,
            )
        }
    }

    @Test
    fun transferInEvidence_usesIncomingDirection() = runBlocking {
        ReconciliationCharacterizationFixture.open(ApplicationProvider.getApplicationContext()).use { world ->
            world.seedMatchedSelfTransfer()

            val directions = world.storedDirections()
            assertEquals(
                MoneyDirection.OUTGOING,
                directions.getValue(ReconciliationCharacterizationFixture.SELF_OUT_EVENT),
            )
            assertEquals(
                MoneyDirection.INCOMING,
                directions.getValue(ReconciliationCharacterizationFixture.SELF_IN_EVENT),
            )
        }
    }

    @Test
    fun matchedSelfTransfer_postsOneTransactionWithBothLegs() = runBlocking {
        ReconciliationCharacterizationFixture.open(ApplicationProvider.getApplicationContext()).use { world ->
            world.seedMatchedSelfTransfer()
            world.reconciliation().reconcileStoredEvents()

            val posted = world.snapshot().single()
            assertEquals(FinancialTransactionType.SELF_TRANSFER, posted.type)
            assertEquals(Money.of(ReconciliationCharacterizationFixture.SELF_AMOUNT, Currency.SAR), posted.amount)
            assertEquals(
                setOf(
                    ReconciliationCharacterizationFixture.SELF_OUT_SMS,
                    ReconciliationCharacterizationFixture.SELF_IN_SMS,
                ),
                posted.rawSmsIds,
            )
            assertEquals(
                listOf(
                    ReconciliationCharacterizationFixture.SELF_IN_EVENT,
                    ReconciliationCharacterizationFixture.SELF_OUT_EVENT,
                ),
                posted.linkedParsedEventIds,
            )
            assertEquals(
                ReconciliationCharacterizationFixture.accountContainerId(
                    ReconciliationCharacterizationFixture.SELF_SOURCE,
                ),
                posted.sourceContainerId,
            )
            assertEquals(
                ReconciliationCharacterizationFixture.accountContainerId(
                    ReconciliationCharacterizationFixture.SELF_DESTINATION,
                ),
                posted.destinationContainerId,
            )
        }
    }

    @Test
    fun staleSingleLeg_healsWhenTheCounterpartArrives() = runBlocking {
        ReconciliationCharacterizationFixture.open(ApplicationProvider.getApplicationContext()).use { world ->
            val stale = world.prepareStaleSingleLeg()
            assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, stale.type)
            assertEquals(setOf(ReconciliationCharacterizationFixture.STALE_OUT_SMS), stale.rawSmsIds)
            assertEquals(listOf(ReconciliationCharacterizationFixture.STALE_OUT_EVENT), stale.linkedParsedEventIds)

            world.seedStaleIncomingCounterpart()
            world.reconciliation().reconcileStoredEvents()

            val healed = world.snapshot().single()
            assertEquals(FinancialTransactionType.SELF_TRANSFER, healed.type)
            assertEquals(Money.of(ReconciliationCharacterizationFixture.STALE_AMOUNT, Currency.SAR), healed.amount)
            assertEquals(
                setOf(
                    ReconciliationCharacterizationFixture.STALE_OUT_SMS,
                    ReconciliationCharacterizationFixture.STALE_IN_SMS,
                ),
                healed.rawSmsIds,
            )
            assertEquals(
                listOf(
                    ReconciliationCharacterizationFixture.STALE_IN_EVENT,
                    ReconciliationCharacterizationFixture.STALE_OUT_EVENT,
                ),
                healed.linkedParsedEventIds,
            )
        }
    }

    @Test
    fun userCorrectedAmount_liftsTheReviewGate() = runBlocking {
        ReconciliationCharacterizationFixture.open(ApplicationProvider.getApplicationContext()).use { world ->
            world.seedReviewRequiredPurchase()
            world.reconciliationWithCorrections().reconcileStoredEvents()
            assertTrue(world.snapshot().isEmpty())

            world.saveAmountCorrection()
            world.reconciliationWithCorrections().reconcileStoredEvents()

            val posted = world.snapshot().single()
            assertEquals(FinancialTransactionType.EXPENSE, posted.type)
            assertEquals(Money.of(ReconciliationCharacterizationFixture.CORRECTION_AMOUNT, Currency.SAR), posted.amount)
            assertEquals(setOf(ReconciliationCharacterizationFixture.CORRECTION_SMS), posted.rawSmsIds)
            assertEquals(listOf(ReconciliationCharacterizationFixture.CORRECTION_EVENT), posted.linkedParsedEventIds)
            assertEquals("Keeta", posted.merchant)
        }
    }

    @Test
    fun scopedReconcile_matchesFullReconcileOnEachCharacterizationSeed() = runBlocking {
        assertScopedMatchesFull(
            affectedRawSmsIds = setOf(ReconciliationCharacterizationFixture.PURCHASE_SMS),
        ) { seedNonTransfer() }
        assertScopedMatchesFull(
            affectedRawSmsIds = setOf(ReconciliationCharacterizationFixture.EXTERNAL_SMS),
        ) { seedExternalTransfer() }
        assertScopedMatchesFull(
            affectedRawSmsIds = setOf(ReconciliationCharacterizationFixture.SELF_IN_SMS),
        ) { seedMatchedSelfTransfer() }
        assertScopedMatchesFull(
            affectedRawSmsIds = setOf(ReconciliationCharacterizationFixture.CORRECTION_SMS),
            withCorrections = true,
            prepare = { saveAmountCorrection() },
        ) { seedReviewRequiredPurchase() }
    }

    @Test
    fun scopedReconcile_healsStaleSingleLegWhenOnlyTheIncomingSmsIsAffected() = runBlocking {
        val full = openCharacterization().use { world ->
            world.prepareStaleSingleLeg()
            world.seedStaleIncomingCounterpart()
            val report = world.reconciliation().reconcileStoredEventsDetailed()
            world.snapshot() to report.reviewCandidates.sortedBy { it.rawSmsId }
        }
        val scoped = openCharacterization().use { world ->
            world.prepareStaleSingleLeg()
            world.seedStaleIncomingCounterpart()
            val report = world.reconciliation().reconcileAffectedRawSmsIds(
                setOf(ReconciliationCharacterizationFixture.STALE_IN_SMS),
            )
            world.snapshot() to report.reviewCandidates.sortedBy { it.rawSmsId }
        }
        assertEquals(full, scoped)
        assertEquals(FinancialTransactionType.SELF_TRANSFER, scoped.first.single().type)
    }

    @Test
    fun scopedReconcile_emptyIds_doesNotPost() = runBlocking {
        openCharacterization().use { world ->
            world.seedNonTransfer()
            val report = world.reconciliation().reconcileAffectedRawSmsIds(emptyList())
            assertTrue(report.reviewCandidates.isEmpty())
            assertTrue(report.settledRawSmsIds.isEmpty())
            assertTrue(world.snapshot().isEmpty())
        }
    }

    private suspend fun assertScopedMatchesFull(
        affectedRawSmsIds: Set<String>,
        withCorrections: Boolean = false,
        prepare: suspend ReconciliationCharacterizationFixture.() -> Unit = {},
        seed: suspend ReconciliationCharacterizationFixture.() -> Unit,
    ) {
        val full = openCharacterization().use { world ->
            world.seed()
            world.prepare()
            val service = if (withCorrections) world.reconciliationWithCorrections() else world.reconciliation()
            val report = service.reconcileStoredEventsDetailed()
            world.snapshot() to report.reviewCandidates.sortedBy { it.rawSmsId }
        }
        val scoped = openCharacterization().use { world ->
            world.seed()
            world.prepare()
            val service = if (withCorrections) world.reconciliationWithCorrections() else world.reconciliation()
            val report = service.reconcileAffectedRawSmsIds(affectedRawSmsIds)
            world.snapshot() to report.reviewCandidates.sortedBy { it.rawSmsId }
        }
        assertEquals(full, scoped)
    }

    private fun openCharacterization(): ReconciliationCharacterizationFixture =
        ReconciliationCharacterizationFixture.open(ApplicationProvider.getApplicationContext())
}
