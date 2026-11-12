package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.RawSmsEntity
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.testsupport.recordDecision
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The read model of plan 2.3: how `UserDecisionRepository.effectiveFor` folds the rows of one SMS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserDecisionReaderTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MasroofDatabase::class.java)
        .allowMainThreadQueries().build()
    private val reader = RoomUserDecisionRepository(db)

    @After
    fun close() = db.close()

    @Test
    fun outcomes_supersedeOneAnother_byAppendOrderNotClock() = runBlocking {
        sms("a")
        decide(UserDecision("a", UserDecisionKind.FINANCIAL_TYPE, at(3), transactionType = FinancialTransactionType.EXPENSE))
        decide(UserDecision("a", UserDecisionKind.IGNORE, at(2)))
        assertTrue(reader.effectiveFor("a").isNonFinancial)
        decide(UserDecision("a", UserDecisionKind.RESTORE, at(1)))
        val restored = reader.effectiveFor("a")
        assertFalse(restored.isNonFinancial)
        assertTrue(restored.confirmsFinancial)
        assertEquals(UserDecisionKind.RESTORE, restored.outcome?.kind)
        decide(UserDecision("a", UserDecisionKind.RESTORE_REVERTED, at(4)))
        assertTrue(reader.effectiveFor("a").isNonFinancial)
    }

    @Test
    fun kindsMapToTheReviewResolutionTheyStoodFor() = runBlocking {
        val financial = setOf(UserDecisionKind.FINANCIAL_TYPE, UserDecisionKind.RECLASSIFY, UserDecisionKind.RESTORE)
        val nothing = setOf(UserDecisionKind.NON_FINANCIAL, UserDecisionKind.IGNORE, UserDecisionKind.RESTORE_REVERTED)
        for (kind in UserDecisionKind.entries - UserDecisionKind.CORRECTION - UserDecisionKind.BANK_SELECTION) {
            sms(kind.name)
            decide(UserDecision(kind.name, kind, at(1)))
            val read = reader.effectiveFor(kind.name)
            assertEquals(kind, read.outcome?.kind)
            assertEquals(kind.name, kind in financial, read.confirmsFinancial)
            assertEquals(kind.name, kind in nothing, read.isNonFinancial)
        }
    }

    @Test
    fun aPairDecision_isTheOutcomeOfBothLegs() = runBlocking {
        sms("out")
        sms("in")
        decide(UserDecision("out", UserDecisionKind.SELF_TRANSFER_PAIR, at(1), pairedRawSmsId = "in",
            transactionType = FinancialTransactionType.SELF_TRANSFER))
        val read = reader.effectiveFor(listOf("out", "in"))
        assertEquals(read.getValue("out").outcome, read.getValue("in").outcome)
        assertEquals(UserDecisionKind.SELF_TRANSFER_PAIR, read.getValue("in").outcome?.kind)
    }

    /** The open case of 2.2: a correction is its own layer, so it never lifts a "post nothing" outcome. */
    @Test
    fun correctionsAndBankSelection_areSeparateLayers() = runBlocking {
        sms("c")
        decide(UserDecision("c", UserDecisionKind.NON_FINANCIAL, at(1)))
        decide(UserDecision("c", UserDecisionKind.CORRECTION, at(5), merchant = "LATE"))
        decide(UserDecision("c", UserDecisionKind.CORRECTION, at(2), amount = Money.of("12.00", Currency.SAR)))
        decide(UserDecision("c", UserDecisionKind.CORRECTION, at(2), messageFamily = MessageFamily.PURCHASE))
        decide(UserDecision("c", UserDecisionKind.BANK_SELECTION, at(3), bankId = "BANK_SNB"))
        decide(UserDecision("c", UserDecisionKind.BANK_SELECTION, at(1), bankId = "BANK_ALJAZIRA"))
        val read = reader.effectiveFor("c")
        assertTrue(read.isNonFinancial)
        assertEquals(UserDecisionKind.NON_FINANCIAL, read.outcome?.kind)
        // Oldest decision time first; equal times keep the append order.
        assertEquals(listOf(Money.of("12.00", Currency.SAR), null, null), read.corrections.map { it.amount })
        assertEquals(listOf(null, MessageFamily.PURCHASE, null), read.corrections.map { it.messageFamily })
        assertEquals("LATE", read.corrections.last().merchant)
        assertEquals("BANK_ALJAZIRA", read.selectedBankId)
    }

    @Test
    fun manyIds_areReadInChunks_withEveryIdAnswered() = runBlocking {
        val ids = (0 until 700).map { "m%03d".format(it) }
        ids.forEach { sms(it) }
        // The two legs fall in different bind chunks.
        decide(UserDecision(ids[0], UserDecisionKind.SELF_TRANSFER_PAIR, at(1), pairedRawSmsId = ids[600]))
        decide(UserDecision(ids[300], UserDecisionKind.IGNORE, at(1)))
        val read = reader.effectiveFor(ids + "missing")
        assertEquals((ids + "missing").toSet(), read.keys)
        assertEquals(UserDecisionKind.SELF_TRANSFER_PAIR, read.getValue(ids[600]).outcome?.kind)
        assertTrue(read.getValue(ids[300]).isNonFinancial)
        assertEquals(EffectiveUserDecisions.NONE, read.getValue("missing"))
        assertEquals(3, read.values.count { it != EffectiveUserDecisions.NONE })
    }

    @Test
    fun retrySet_excludesAnSmsWhoseLatestOutcomePostsNothing() = runBlocking {
        listOf("kept", "ignored", "restored", "undecided").forEach { sms(it) }
        val retries = RoomProcessingRetryRepository(db.processingRetryDao())
        retries.markRequired(listOf("kept", "ignored", "restored", "undecided"), at(1), ProcessingRetryMode.HISTORICAL_BATCH)
        decide(UserDecision("ignored", UserDecisionKind.IGNORE, at(1)))
        decide(UserDecision("restored", UserDecisionKind.NON_FINANCIAL, at(1)))
        decide(UserDecision("restored", UserDecisionKind.RESTORE, at(2)))
        decide(UserDecision("restored", UserDecisionKind.CORRECTION, at(3), merchant = "X"))
        decide(UserDecision("kept", UserDecisionKind.NON_FINANCIAL, at(1)))
        decide(UserDecision("kept", UserDecisionKind.FINANCIAL_TYPE, at(2)))
        decide(UserDecision("ignored", UserDecisionKind.BANK_SELECTION, at(3), bankId = "BANK_SNB"))
        assertEquals(listOf("kept", "restored", "undecided"), retries.listRetryableRawSmsIds().sorted())
        assertEquals(listOf("kept", "restored", "undecided"),
            retries.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH).sorted())
    }

    /** The SQL copy of the rule (`UserDecisionDao` constants) agrees with `EffectiveUserDecisions` for every kind. */
    @Test
    fun retrySql_andReader_agreeForEveryKind() = runBlocking {
        val retries = RoomProcessingRetryRepository(db.processingRetryDao())
        for (kind in UserDecisionKind.entries) {
            sms(kind.name)
            decide(UserDecision(kind.name, UserDecisionKind.FINANCIAL_TYPE, at(1)))
            decide(UserDecision(kind.name, kind, at(2)))
            retries.markRequired(kind.name, at(1), ProcessingRetryMode.HISTORICAL_BATCH)
        }
        val retryable = retries.listRetryableRawSmsIds().toSet()
        for (kind in UserDecisionKind.entries) {
            assertEquals(kind.name, !reader.effectiveFor(kind.name).isNonFinancial, kind.name in retryable)
        }
    }

    private suspend fun decide(decision: UserDecision) = db.recordDecision(decision)

    private suspend fun sms(id: String) {
        db.rawSmsDao().insertIfAbsent(RawSmsEntity(id = id, sender = "AlJazira", body = id, receivedAtEpochMillis = 1,
            deviceMessageId = id, bodyHash = id, dedupeKey = id))
    }

    private fun at(second: Long): Instant = Instant.ofEpochSecond(1_800_000_000 + second)
}
