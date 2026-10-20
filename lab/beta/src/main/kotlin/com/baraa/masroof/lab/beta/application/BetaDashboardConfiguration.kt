package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.value.Money
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Explicit Beta user settings. These grant no parsing, ledger or payment-matching authority. */
data class BetaSalaryCycle(val day: Int, val zone: ZoneId = ZoneId.of("Asia/Riyadh"))
data class BetaFacilityCycle(val facilityId: AssetId, val closingDay: Int)
enum class BetaCommitmentRecurrence { WEEKLY, MONTHLY, YEARLY }
data class BetaCommitmentPause(val pausedAt: Instant,val resumedAt: Instant?=null)

data class BetaRecurringCommitment(
    val id: String,
    val label: String,
    val amount: Money,
    val dueDay: Int,
    val starts: YearMonth,
    val ends: YearMonth? = null,
    /** Explicit manual occurrence decisions, keyed by the due-date month. Never a ledger write. */
    val paidMonths: Set<YearMonth> = emptySet(),
    val recurrence: BetaCommitmentRecurrence? = BetaCommitmentRecurrence.MONTHLY,
    val transactionDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val active: Boolean = true,
    val pauseIntervals: List<BetaCommitmentPause> = emptyList(),
    val sourceTransactionId: String? = null,
) {
    /** Old explicit month/day settings keep their anchor without claiming a bank transaction. */
    val anchorDate: LocalDate get() = transactionDate ?: starts.atDay(minOf(dueDay,starts.lengthOfMonth()))
}
data class BetaDashboardConfigurationDraft(
    val salary: BetaSalaryCycle? = null,
    val statements: List<BetaFacilityCycle> = emptyList(),
    val commitments: List<BetaRecurringCommitment> = emptyList(),
)
data class BetaDashboardConfiguration(
    val revision: Long,
    val draft: BetaDashboardConfigurationDraft,
    /** False on the independent UI branch's explicit fallback provider. */
    val available: Boolean = true,
)
sealed interface BetaDashboardConfigurationWrite {
    data class Saved(val configuration: BetaDashboardConfiguration) : BetaDashboardConfigurationWrite
    data class Conflict(val current: BetaDashboardConfiguration) : BetaDashboardConfigurationWrite
    data class Invalid(val reason: String) : BetaDashboardConfigurationWrite
    data object Unavailable : BetaDashboardConfigurationWrite
}
interface BetaDashboardControls {
    suspend fun read(): BetaDashboardConfiguration
    /** Atomically validates and replaces the complete draft only if the revision still matches. */
    suspend fun save(expectedRevision: Long, draft: BetaDashboardConfigurationDraft): BetaDashboardConfigurationWrite
}
object UnavailableBetaDashboardControls : BetaDashboardControls {
    override suspend fun read() = BetaDashboardConfiguration(0, BetaDashboardConfigurationDraft(), available = false)
    override suspend fun save(expectedRevision: Long, draft: BetaDashboardConfigurationDraft) = BetaDashboardConfigurationWrite.Unavailable
}
