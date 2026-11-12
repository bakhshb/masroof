package com.baraa.masroof.ledger

import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.ReviewKind
import java.time.Instant
import java.time.LocalDateTime

/**
 * The effective evidence of one raw SMS: its parse with the user's corrections applied, the receipt time of the
 * raw SMS, and the parse details the ledger reads. [automationConfirmed] is true when a correction confirms the
 * family or amount ([com.baraa.masroof.domain.assembly.TransactionAssembler.isAutomationEligible]).
 */
data class LedgerEvidence(
    val event: ParsedEvent,
    val receivedAt: Instant,
    val transactionReference: String?,
    val occurredAtLocal: LocalDateTime?,
    val loanType: LoanType?,
    val automationConfirmed: Boolean,
)

/** Registry ownership already resolved for the references the evidence names; a reference not listed is UNKNOWN. */
data class OwnershipFacts(
    val accounts: Map<AccountReference, OwnershipStatus> = emptyMap(),
    val cards: Map<CardReference, OwnershipStatus> = emptyMap(),
    val loans: Map<LoanReference, OwnershipStatus> = emptyMap(),
) {
    fun account(reference: AccountReference?): OwnershipStatus = reference?.let(accounts::get) ?: OwnershipStatus.UNKNOWN
    fun card(reference: CardReference?): OwnershipStatus = reference?.let(cards::get) ?: OwnershipStatus.UNKNOWN
    fun loan(reference: LoanReference): OwnershipStatus = loans[reference] ?: OwnershipStatus.UNKNOWN
}

/**
 * Stored state the projection takes as input. [rows] are the stored ledger rows with the raw SMS they link: their
 * zone and exchange-rate pair are inputs to derived rows, and a row the keep rule names is carried verbatim
 * (docs/ARCHITECTURE.md, "Derived ledger"). [correctionClosedRawSmsIds] are the raw SMS whose review a
 * correction's own pass closed as `USER_CORRECTION` (the app's `ReviewCorrectionClosure`), which protects them
 * like an outcome decision.
 */
data class StoredFacts(
    val rows: List<LedgerRow> = emptyList(),
    val correctionClosedRawSmsIds: Set<String> = emptySet(),
)

/** One derived ledger row and the raw SMS it links, sorted. */
data class LedgerRow(
    val transaction: FinancialTransaction,
    val rawSmsIds: List<String>,
)

data class LedgerReviewCandidate(
    val rawSmsId: String,
    val kind: ReviewKind,
    val reasons: List<String>,
)

/**
 * The derived ledger. [settledRawSmsIds] need no review; [retryRawSmsIds] could not be projected and stay
 * incomplete; [confirmedLoans] are the loans the pre-pass found owned, which the caller records in the registry.
 */
data class LedgerProjection(
    val rows: List<LedgerRow>,
    val reviewCandidates: List<LedgerReviewCandidate>,
    val settledRawSmsIds: Set<String>,
    val retryRawSmsIds: Set<String>,
    val confirmedLoans: Set<LoanReference>,
)
