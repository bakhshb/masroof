package com.baraa.masroof.data.repository

import androidx.room.withTransaction
import com.baraa.masroof.data.analytics.RecurringFeedbackRow
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import com.baraa.masroof.domain.model.UserDecisionKind

/**
 * The user's answers about recurring-payment series, kept as `RECURRING` rows of `user_decision` (plan 5.2). Append-only:
 * the answer in force for a series is its latest row, and [UNDONE] makes the series behave as never answered. An answer
 * is a [RecurringFeedbackRow] (series key, answer kind, payload, decidedAt).
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
    }
}
