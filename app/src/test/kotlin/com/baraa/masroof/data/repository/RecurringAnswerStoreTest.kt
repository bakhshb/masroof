package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.analytics.RecurringDecisionWorkflow
import com.baraa.masroof.data.analytics.AnalyticsDatabase
import com.baraa.masroof.application.analytics.RecurringFeedbackPayload
import com.baraa.masroof.data.analytics.AnalyticsJson
import com.baraa.masroof.data.analytics.RecurringFeedbackRow
import kotlinx.serialization.encodeToString
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.testsupport.TestStorageContext
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan 5.2: recurring answers as append-only `RECURRING` rows of `user_decision` */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecurringAnswerStoreTest {
    private val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "recurring-answers-${java.util.UUID.randomUUID()}")
    private val db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java).allowMainThreadQueries().build()
    private val store = RecurringAnswerStore(db, DatabaseAccessGate())
    private val answers = listOf(
        answer("series-a", "CONFIRMED", "MONTHLY", 1_000),
        answer("series-b", "CONFIRM_PENDING", "WEEKLY", 2_000),
        answer("series-c", "NOT_RECURRING", "NONE", 3_000),
        answer("series-d", "DISMISSED", null, 4_000),
    )
    private val confirmed = answers[0]
    private val pending = answers[1]
    private val notRecurring = answers[2]
    private val dismissed = answers[3]

    @After
    fun close() {
        db.close()
        context.getDatabasePath(AnalyticsDatabase.NAME).parentFile?.deleteRecursively()
    }

    @Test
    fun theLatestRowOfASeriesIsItsAnswer_andUndoMakesItUnanswered() = runBlocking {
        store.record(dismissed)
        store.record(pending)
        val later = notRecurring.copy(seriesKey = dismissed.seriesKey, payload = dismissed.payload, decidedAt = 9_000)
        store.record(later)
        assertEquals(listOf(pending, later), store.effective())
        // Only an answer among the given ones is undone.
        assertFalse(store.undo(pending.seriesKey, setOf("NOT_RECURRING", "DISMISSED"), 10_000))
        assertTrue(store.undo(later.seriesKey, setOf("NOT_RECURRING", "DISMISSED"), 10_000))
        assertEquals(listOf(pending), store.effective())
        assertFalse("an undone series has nothing to undo", store.undo(later.seriesKey, setOf("NOT_RECURRING", "DISMISSED"), 11_000))
        // Nothing was rewritten or removed: every answer is a row, the undo included.
        assertEquals(listOf("CONFIRM_PENDING", "DISMISSED", "NOT_RECURRING", RecurringAnswerStore.UNDONE),
            db.userDecisionDao().listRecurring().map { it.answer })
        store.record(dismissed)
        assertEquals(listOf(pending, dismissed), store.effective())
    }

    @Test
    fun anAnswerIsKeptWhenItsAdvisoryWriteFails_andAFailedUndoKeepsTheAnswer() = runBlocking {
        // The answer commits first: an advisory status never exists without the answer behind it.
        assertThrows(IllegalStateException::class.java) { runBlocking { store.record(confirmed) { error("advisory write failed") } } }
        assertEquals(listOf(confirmed), store.effective())
        store.record(notRecurring)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.undo(notRecurring.seriesKey, setOf("NOT_RECURRING"), 5_000) { error("advisory write failed") } }
        }
        assertEquals(listOf(confirmed, notRecurring).sortedBy { it.seriesKey }, store.effective())
    }

    @Test
    fun promotionAppendsTheConfirmedAnswerWithThePendingPayloadAndTime() = runBlocking {
        val label = "WEEKLY"
        store.record(pending)
        val unchanged = RecurringDecisionWorkflow.promotePending(store, emptyMap())
        assertEquals(listOf(pending), unchanged)
        val lastSource = "${pending.seriesKey}-2"
        val promoted = RecurringDecisionWorkflow.promotePending(store, mapOf(lastSource to label))
        assertEquals(listOf(pending.copy(kind = "CONFIRMED")), promoted)
        assertEquals(promoted, store.effective())
        assertEquals(listOf("CONFIRM_PENDING", "CONFIRMED"), db.userDecisionDao().listRecurring().map { it.answer })
    }

    /** The analysis fingerprint reads the answers as the analytics table gave them: the same text for the same answers. */
    @Test
    fun theFingerprintTextOfTheSameAnswersIsUnchangedByTheMove() = runBlocking {
        fun text(rows: List<RecurringFeedbackRow>) = rows.joinToString { "${it.seriesKey}:${it.kind}:${it.decidedAt}:${it.payload}" }
        // In the old table one row per series, ordered by series key.
        val table = answers.sortedBy { it.seriesKey }
        answers.reversed().forEach { store.record(it) }
        store.record(confirmed.copy(kind = "DISMISSED"))
        store.undo(confirmed.seriesKey, setOf("DISMISSED"), 50)
        store.record(confirmed)
        assertEquals(text(table), text(store.effective()))
    }

    @Test
    fun theDaoAcceptsARecurringRowOnlyWithoutARawSms_andEveryOtherRowOnlyWithOne() = runBlocking {
        val dao = db.userDecisionDao()
        val invalid = listOf(
            UserDecisionEntity(rawSmsId = null, kind = "FINANCIAL_TYPE", decidedAtEpochMillis = 1),
            UserDecisionEntity(rawSmsId = "sms", kind = "RECURRING", decidedAtEpochMillis = 1, subjectKey = "s", answer = "CONFIRMED", payload = "{}"),
            UserDecisionEntity(rawSmsId = null, kind = "RECURRING", decidedAtEpochMillis = 1, answer = "CONFIRMED"),
            UserDecisionEntity(rawSmsId = null, kind = "RECURRING", decidedAtEpochMillis = 1, subjectKey = "s"),
            UserDecisionEntity(rawSmsId = "sms", kind = "IGNORE", decidedAtEpochMillis = 1, subjectKey = "s"),
            UserDecisionEntity(rawSmsId = "sms", kind = "IGNORE", decidedAtEpochMillis = 1, payload = "{}"),
        )
        for (row in invalid) assertThrows(row.toString(), IllegalArgumentException::class.java) { runBlocking { dao.insert(row) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { db.appendDecision(UserDecision("sms", UserDecisionKind.RECURRING, Instant.EPOCH)) }
        }
        assertEquals(emptyList<UserDecisionEntity>(), dao.listAll())
    }

    private fun answer(seriesKey: String, kind: String, label: String?, at: Long) = RecurringFeedbackRow(seriesKey, kind,
        AnalyticsJson.format.encodeToString(RecurringFeedbackPayload(seriesKey, listOf("$seriesKey-1", "$seriesKey-2"), label)), at)
}
