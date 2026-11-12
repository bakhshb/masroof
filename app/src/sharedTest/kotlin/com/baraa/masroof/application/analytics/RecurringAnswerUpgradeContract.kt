package com.baraa.masroof.application.analytics

import android.content.Context
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.data.analytics.AnalyticsDatabase
import com.baraa.masroof.data.analytics.AnalyticsJson
import com.baraa.masroof.data.analytics.RecurringFeedbackRow
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.MigrationOpenPathContract
import com.baraa.masroof.data.room.UserDecisionBackfillContract
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals

/**
 * Plan 5.2, run on the JVM and on a device: an install at v18 with recurring answers of all four kinds in
 * `analytics.db` opens with the same answers in force, now `RECURRING` rows of `user_decision`, and every other row intact.
 */
object RecurringAnswerUpgradeContract {
    /** One answer of each kind, as an older version stored them, ordered by series key. */
    val ANSWERS: List<RecurringFeedbackRow> = listOf(
        answer("series-a", "CONFIRMED", "MONTHLY", 1_000),
        answer("series-b", "CONFIRM_PENDING", "WEEKLY", 2_000),
        answer("series-c", "NOT_RECURRING", "NONE", 3_000),
        answer("series-d", "DISMISSED", null, 4_000),
    )

    private fun answer(seriesKey: String, kind: String, label: String?, at: Long) = RecurringFeedbackRow(seriesKey, kind,
        AnalyticsJson.format.encodeToString(RecurringFeedbackPayload(seriesKey, listOf("$seriesKey-1", "$seriesKey-2"), label)), at)

    /** The rows `user_decision` holds after the copy: the v18 fixture's backfilled decisions, then [ANSWERS]. */
    val DECISIONS: List<String> = (UserDecisionBackfillContract.V18_FIXTURE_DECISIONS + ANSWERS.mapIndexed { index, it ->
        UserDecisionEntity(UserDecisionBackfillContract.V18_FIXTURE_DECISIONS.size + 1L + index, null, null, "RECURRING", it.decidedAt,
            subjectKey = it.seriesKey, answer = it.kind, payload = it.payload)
    }).map(UserDecisionBackfillContract::dump)

    fun upgradeKeepsEveryAnswer(context: Context, fixture: () -> InputStream) = runBlocking {
        val live = MigrationOpenPathContract.copyFixture(context, MasroofDatabase.NAME, fixture)
        try {
            val before = MigrationOpenPathContract.readOnly(live, MigrationOpenPathContract::rows)
            writeAnalytics(context, ANSWERS)
            val c = AppContainer(context)
            try {
                assertEquals(ANSWERS, c.storage.recurringAnswerStore.effective())
            } finally { c.close() }
            assertEquals(emptyList<RecurringFeedbackRow>(), readAnalytics(context))
            val after = MigrationOpenPathContract.readOnly(live, MigrationOpenPathContract::rows)
            // The open also seeds the bundled patterns into the new pattern table (plan 5.1).
            MigrationOpenPathContract.assertKeepsEveryRow(before, after - "pattern_configuration", mapOf("user_decision" to DECISIONS))
            // A later start finds nothing to copy and changes nothing.
            val again = AppContainer(context)
            try {
                assertEquals(ANSWERS, again.storage.recurringAnswerStore.effective())
            } finally { again.close() }
            assertEquals(after, MigrationOpenPathContract.readOnly(live, MigrationOpenPathContract::rows))
        } finally {
            live.parentFile?.deleteRecursively()
        }
    }

    /** Writes [rows] into `analytics.db` as an older version left them. */
    fun writeAnalytics(context: Context, rows: List<RecurringFeedbackRow>) = runBlocking {
        val database = AnalyticsDatabase.open(context)
        try { rows.forEach { database.analytics().feedback(it) } } finally { database.close() }
    }

    fun readAnalytics(context: Context): List<RecurringFeedbackRow> = runBlocking {
        val database = AnalyticsDatabase.open(context)
        try { database.analytics().feedback() } finally { database.close() }
    }
}
