package com.baraa.masroof.application.analytics

import com.baraa.masroof.data.analytics.AnalysisAlertRow
import com.baraa.masroof.data.analytics.AnalysisPreferencesRow

internal object AnalysisAlertPolicy {
    fun eligibleForPhone(row: AnalysisAlertRow, snapshot: AnalysisSnapshot, prefs: AnalysisPreferencesRow, now: Long): Boolean =
        prefs.phone && !snapshot.quality.blocked && !row.resolved && row.confirmed && !row.read && row.lastNotified == 0L &&
            row.snoozedUntil <= now && row.sourceHash == snapshot.sourceHash && now - snapshot.issuedAt in 0..(6 * 3600_000L) &&
            (row.kind != "BUDGET" || (prefs.enabledAt > 0 && now - prefs.enabledAt >= 7 * 24 * 3600_000L))
    fun evaluate(snapshot: AnalysisSnapshot, candidates: List<AnalysisCandidate>, previous: List<AnalysisAlertRow>,
        prefs: AnalysisPreferencesRow, today: Long): List<AnalysisAlertRow> {
        val now = snapshot.issuedAt
        val current = mutableListOf<AnalysisAlertRow>()
        fun add(id: String, kind: String, title: String, amount: Long, confirmed: Boolean, evidence: String? = null) {
            val old = previous.firstOrNull { it.id == id }
            val continuous = old != null && !old.resolved && now - old.observedAt in 0..(12 * 3600_000L)
            current += (old ?: AnalysisAlertRow(id, kind, title, amount, now, now)).copy(title = title, amount = amount,
                firstSeen = if (kind == "BUDGET" && !continuous) now else minOf(old?.firstSeen ?: now, now),
                observedAt = now, confirmed = confirmed || (kind == "BUDGET" && continuous && now - old.firstSeen >= 6 * 3600_000L),
                resolved = false, sourceHash = snapshot.sourceHash, evidenceId = evidence)
        }
        if (!snapshot.quality.blocked) {
            prefs.budget?.takeIf { prefs.budgetAlerts && snapshot.actual > it }?.let {
                add("budget-actual:${snapshot.periodStart}:$it", "ACTUAL", "", snapshot.actual, true)
            }
            prefs.budget?.takeIf { prefs.budgetAlerts && (snapshot.budgetLower ?: snapshot.lower) != null && (snapshot.budgetLower ?: snapshot.lower)!! > it && snapshot.actual <= it }?.let {
                add("budget:${snapshot.periodStart}:$it", "BUDGET", "", snapshot.total!!, false)
            }
            if (prefs.dueAlerts) snapshot.dues.filter { it.confirmed && it.day != null && it.day in today..(today + prefs.reminderDays) }
                .forEach { add(it.alertId, "DUE", it.name, it.amount, true) }
            if (prefs.paymentAlerts) candidates.filter { it.increased }.forEach {
                add("payment:${it.key}:${it.sourceIds.last()}", "PAYMENT", it.name, it.latestAmount, true, it.sourceIds.last())
            }
        }
        return current + previous.filter { old -> current.none { it.id == old.id } }.map { it.copy(resolved = true) }
    }
}
