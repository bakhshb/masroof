package com.baraa.masroof.lab.beta.application

import java.time.ZoneId
import java.util.Locale

/** Source settings list ordering: SQLite NOCASE names, then latest disabled calendar day. */
object BetaCommitmentLists {
    fun rows(commitments: List<BetaRecurringCommitment>,active: Boolean,zone: ZoneId): List<BetaRecurringCommitment> {
        val named=ordered(commitments.filter {it.active==active})
        return if(active) named else named.sortedByDescending {row ->
            row.pauseIntervals.lastOrNull()?.pausedAt?.atZone(zone)?.toLocalDate() ?: row.anchorDate
        }
    }
    internal fun ordered(commitments: List<BetaRecurringCommitment>)=commitments.sortedWith {a,b ->
        val name=sqliteNoCase(a.label,b.label)
        if(name!=0) name else a.id.compareTo(b.id)
    }
    fun history(entries: List<BetaCommitmentHistoryEntry>): List<BetaCommitmentHistoryEntry> = entries.sortedWith(
        compareByDescending<BetaCommitmentHistoryEntry> {it.at}.thenBy {it.name.lowercase(Locale.ROOT)}.thenBy {it.event.ordinal})
    private fun sqliteNoCase(first: String,second: String): Int {
        fun bytes(value: String)=value.map {if(it in 'A'..'Z') it.lowercaseChar() else it}.joinToString("").toByteArray(Charsets.UTF_8)
        val a=bytes(first);val b=bytes(second)
        for(index in 0 until minOf(a.size,b.size)) {
            val difference=(a[index].toInt() and 255)-(b[index].toInt() and 255)
            if(difference!=0) return difference
        }
        return a.size-b.size
    }
}
