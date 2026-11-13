package com.baraa.masroof.application.analytics

import com.baraa.masroof.data.analytics.AnalysisCoverageRow

/** Coverage is evidence about a day, not an inference from an empty transaction list. */
object AnalysisCoveragePolicy {
    fun recover(rows: List<AnalysisCoverageRow>, incompleteDays: Set<Long>, at: Long): List<AnalysisCoverageRow> {
        val receipts = rows.filter { it.reason == "SCAN_RECEIPT" }
        return rows.flatMap { gap ->
            if(gap.reason in setOf("SCAN_RECEIPT", "CHECKPOINT") || gap.repairedAt != null) listOf(gap)
            else {
                // A scan may prove yesterday complete while today is still unfinished.
                // Preserve the remaining gap rather than withholding the repaired history.
                val segments = mutableListOf<AnalysisCoverageRow>()
                var first = gap.fromDay
                fun repaired(day: Long) = day !in incompleteDays && (gap.reason == "PROCESSING" || receipts.any {
                    day in it.fromDay..it.throughDay && it.observedAt >= gap.observedAt
                })
                var closed = repaired(first)
                fun add(through: Long) {
                    segments += gap.copy(id = if(segments.isEmpty()) gap.id else "${gap.id}:$first",
                        fromDay=first,throughDay=through,repairedAt=if(closed) at else null)
                }
                for(day in gap.fromDay+1..gap.throughDay) {
                    val next=repaired(day)
                    if(next != closed) { add(day-1);first=day;closed=next }
                }
                add(gap.throughDay)
                segments
            }
        }
    }
    fun gapDays(rows: List<AnalysisCoverageRow>): Set<Long> = rows.filter {
        it.repairedAt == null && it.reason !in setOf("SCAN_RECEIPT", "CHECKPOINT")
    }.flatMap { (it.fromDay..it.throughDay).toList() }.toSet()

    /**
     * The first day of an open gap that only an inbox re-read can prove, or null. A scan certifies whole days
     * through yesterday, so a gap that is only today waits; a processing gap closes when its retry does.
     */
    fun rescanFrom(rows: List<AnalysisCoverageRow>, today: Long): Long? = rows.filter {
        it.repairedAt == null && it.reason !in setOf("SCAN_RECEIPT", "CHECKPOINT", "PROCESSING") && it.fromDay < today
    }.minOfOrNull { it.fromDay }
}
