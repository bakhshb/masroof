package com.baraa.masroof.ledger

import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.ReviewKind
import java.math.BigDecimal
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
    val transactionReference: String? = null,
    val occurredAtLocal: LocalDateTime? = null,
    val loanType: LoanType? = null,
    val automationConfirmed: Boolean = false,
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

/** One row of the stored ledger, named by the raw SMS it links. Only its zone and exchange-rate pair are read. */
data class StoredLedgerRow(
    val rawSmsIds: Set<String>,
    val occurredAtZone: String?,
    val appliedExchangeRate: BigDecimal? = null,
    val exchangeRateSource: ExchangeRateSource? = null,
)

/**
 * Stored state the projection takes as input. [correctionClosedRawSmsIds] are the raw SMS whose review a
 * correction's own pass closed as `USER_CORRECTION` (the app's `ReviewCorrectionClosure`), which protects them
 * like an outcome decision.
 */
data class StoredFacts(
    val rows: List<StoredLedgerRow> = emptyList(),
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
