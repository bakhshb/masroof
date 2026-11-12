package com.baraa.masroof.parsing.model

import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.LoanType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Parse-time structured facts that are not ownership or financial-treatment
 * conclusions, and that [com.baraa.masroof.domain.model.ParsedEvent] does not
 * currently carry.
 *
 * Kept as a narrowly typed parsing-layer companion to [ParseResult] /
 * [ParsedEventDraft] so fixture fields (reference, balances, biller, local
 * timestamp) are not silently discarded or conflated with merchant/amount.
 *
 * [occurredAtLocal] holds the SMS local date-time without inventing a timezone.
 * [com.baraa.masroof.domain.model.ParsedEvent.occurredAt] ([java.time.Instant])
 * remains unset until a timezone policy is defined.
 */
data class ParsedEventDetails(
    val transactionReference: String? = null,
    val availableBalance: Money? = null,
    val outstandingBalance: Money? = null,
    val biller: String? = null,
    val billerCode: String? = null,
    val occurredAtLocal: LocalDateTime? = null,
    val cardSmsChannel: CardSmsChannel? = null,
    val paymentDueDate: LocalDate? = null,
    val exchangeRate: BigDecimal? = null,
    val internationalFee: Money? = null,
    /** Foreign amount label when the transaction amount alone is insufficient (e.g. SAR charge line). */
    val labeledForeignAmount: Money? = null,
    /** Resolved at parse time from financing SMS labels (لـ: …). */
    val loanType: LoanType? = null,
    /** Debit-card purchase source account suffix when the SMS states it explicitly. */
    val debitSourceAccountLast4: String? = null,
    /** Transfer-in SMS mentions salary-like wording at parse time. */
    val salaryIncomeWording: Boolean? = null,
)
