package com.baraa.masroof.data.repository

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.baraa.masroof.data.analytics.RecurringFeedbackRow
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import com.baraa.masroof.domain.model.UserDecisionKind

/**
 * The user's answers about recurring-payment series, kept as `RECURRING` rows of `user_decision` (plan 5.2). Append-only:
 * the answer in force for a series is its latest row, and [UNDONE] makes the series behave as never answered. An answer
 * is a [RecurringFeedbackRow] (series key, answer kind, payload, decidedAt), the shape the analytics table held.
 */
class RecurringAnswerStore(
    private val database: MasroofDatabase,
    private val accessGate: DatabaseAccessGate,
) {
    /** The answer in force for each answered series, ordered by series key. */
    suspend fun effective(): List<RecurringFeedbackRow> = accessGate.withAccess { effective(database.userDecisionDao().listRecurring()) }

    /**
     * Appends [answer] and, once it is committed, runs [alongside] (an advisory write to another database). The two
     * files cannot commit together, so the answer goes first: a failed advisory write leaves an answer the next
     * refresh applies, never an advisory status without the answer that explains it.
     */
    suspend fun record(answer: RecurringFeedbackRow, alongside: suspend () -> Unit = {}) = accessGate.withAccess {
        database.userDecisionDao().insert(entity(answer.seriesKey, answer.kind, answer.payload, answer.decidedAt))
        alongside()
    }

    /**
     * When the answer in force for [seriesKey] is one of [answers], appends [UNDONE] at [at] and runs [alongside] before
     * the commit; returns whether it did. Here the advisory write goes first: if the commit then fails, the answer
     * still stands and still explains what the user sees, and the undo can be repeated.
     */
    suspend fun undo(seriesKey: String, answers: Set<String>, at: Long, alongside: suspend () -> Unit = {}): Boolean =
        accessGate.withAccess {
            database.withTransaction {
                val current = effective(database.userDecisionDao().listRecurring()).firstOrNull { it.seriesKey == seriesKey }
                if (current?.kind !in answers) return@withTransaction false
                database.userDecisionDao().insert(entity(seriesKey, UNDONE, null, at))
                alongside()
                true
            }
        }

    companion object {
        /** The answer that withdraws a series' earlier answer. */
        const val UNDONE = "UNDONE"

        private fun entity(seriesKey: String, answer: String, payload: String?, at: Long) = UserDecisionEntity(
            rawSmsId = null, kind = UserDecisionKind.RECURRING.name, decidedAtEpochMillis = at,
            subjectKey = seriesKey, answer = answer, payload = payload,
        )

        /** [rows] are `listRecurring` order (series, then sequence): each series' last row, unless it is [UNDONE]. */
        private fun effective(rows: List<UserDecisionEntity>): List<RecurringFeedbackRow> =
            rows.groupBy { it.subjectKey!! }.mapNotNull { (seriesKey, series) ->
                series.last().takeIf { it.answer != UNDONE }
                    ?.let { RecurringFeedbackRow(seriesKey, it.answer!!, it.payload!!, it.decidedAtEpochMillis) }
            }

        /**
         * The one-time copy of the analytics `recurring_feedback` rows into `user_decision`, run when the live database
         * opens, before anything can read an answer, in three steps: [readLegacy], [copyLegacy] (the only write to the
         * financial file) and [clearLegacy]. A crash before the copy's commit appends nothing; after it, the rerun finds
         * every series present, appends nothing and finishes the clear.
         */
        fun readLegacy(analytics: SupportSQLiteDatabase): List<RecurringFeedbackRow> =
            analytics.query("SELECT seriesKey, kind, payload, decidedAt FROM recurring_feedback ORDER BY seriesKey").use {
                buildList { while (it.moveToNext()) add(RecurringFeedbackRow(it.getString(0), it.getString(1), it.getString(2), it.getLong(3))) }
            }

        /**
         * Appends each of [legacy] verbatim (answer, payload, decidedAt) in one transaction of [db], except a series that
         * already has a row there: its answers came after the copy (a legacy row is older by construction). Then reads
         * every series back and throws unless it is there, a copied one exactly as [legacy] has it. Returns the count copied.
         */
        fun copyLegacy(db: SupportSQLiteDatabase, legacy: List<RecurringFeedbackRow>): Int {
            if (legacy.isEmpty()) return 0
            db.beginTransaction()
            val copied = try {
                val present = latest(db).keys
                legacy.filter { it.seriesKey !in present }.onEach {
                    db.execSQL(
                        "INSERT INTO user_decision (rawSmsId, kind, decidedAtEpochMillis, subjectKey, answer, payload) " +
                            "VALUES (NULL, 'RECURRING', ?, ?, ?, ?)",
                        arrayOf<Any>(it.decidedAt, it.seriesKey, it.kind, it.payload),
                    )
                }.toSet().also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
            val latest = latest(db)
            for (row in legacy) {
                val kept = checkNotNull(latest[row.seriesKey]) { "A recurring answer did not reach user_decision" }
                check(row !in copied || kept == listOf(row.kind, row.payload, row.decidedAt)) { "A copied recurring answer reads back differently" }
            }
            return copied.size
        }

        /** Deletes exactly the [legacy] rows [readLegacy] returned, once [copyLegacy] verified them. */
        fun clearLegacy(analytics: SupportSQLiteDatabase, legacy: List<RecurringFeedbackRow>) = legacy.forEach {
            analytics.execSQL(
                "DELETE FROM recurring_feedback WHERE seriesKey = ? AND kind = ? AND payload = ? AND decidedAt = ?",
                arrayOf<Any>(it.seriesKey, it.kind, it.payload, it.decidedAt),
            )
        }

        /** Each series' latest `RECURRING` row (an `UNDONE` one included) as (answer, payload, decidedAt). */
        private fun latest(db: SupportSQLiteDatabase): Map<String, List<Any?>> = db.query(
            "SELECT subjectKey, answer, payload, decidedAtEpochMillis FROM user_decision WHERE kind = 'RECURRING' ORDER BY sequence",
        ).use { buildMap { while (it.moveToNext()) put(it.getString(0), listOf(it.getString(1), it.getString(2), it.getLong(3))) } }
    }
}
