package com.baraa.masroof.parsing.repository

import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.isCreditCardSms
import com.baraa.masroof.parsing.model.isStatementSms
import java.time.Instant

/**
 * Parsing-facing persistence for structured parse output.
 *
 * Lives under parsing (not domain) because [ParsedEventDetails] is a parsing-layer
 * type. Implementations live in the data layer.
 *
 * Supports replace-by-rawSmsId for future reprocessing without event history.
 *
 * ### Fact lookups
 * The `list*Facts` operations serve read models that need history facts which are not
 * linked to the transactions they display (statement cycles, balances, loan installments,
 * exchange rates, card evidence). Each returns **at least** the rows its KDoc describes,
 * ordered by event id. Implementations may return a superset (the in-memory defaults do);
 * callers always apply their exact selection rule to the returned rows.
 */
interface ParsedEventRepository {
    suspend fun save(event: ParsedEvent, details: ParsedEventDetails = ParsedEventDetails())

    suspend fun getById(id: String): ParsedEventRecord?

    suspend fun findByRawSmsId(rawSmsId: String): ParsedEventRecord?

    /**
     * Removes the parsed result row(s) for [rawSmsId] only.
     * Never deletes the related [com.baraa.masroof.domain.model.RawSms] evidence.
     */
    suspend fun deleteByRawSmsId(rawSmsId: String)

    /** All persisted parse results (for ownership discovery backlog). */
    suspend fun listAll(): List<ParsedEventRecord>

    /** Current parse rows for [rawSmsIds] in one batch lookup, ordered by event id. */
    suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> =
        rawSmsIds.distinct().mapNotNull { findByRawSmsId(it) }.sortedBy { it.event.id }

    /**
     * Parse results whose [com.baraa.masroof.domain.model.RawSms.receivedAt] falls in
     * `[startInclusive, endExclusive)` — used for incremental reconciliation windows.
     */
    suspend fun listReceivedBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<ParsedEventRecord>

    /**
     * Transfer parse rows with no financial-transaction link yet — used to bound
     * incremental reconciliation without scanning the full backlog.
     */
    suspend fun listUnlinkedTransfers(): List<ParsedEventRecord> =
        listAll().filter { record ->
            record.event.messageFamily == MessageFamily.TRANSFER_IN ||
                record.event.messageFamily == MessageFamily.TRANSFER_OUT
        }

    /** Every [CardSmsChannel.STATEMENT] row. */
    suspend fun listCardStatementFacts(): List<ParsedEventRecord> =
        listAll().filter { it.details.isStatementSms() }

    /**
     * The newest row (highest event id) per card reference among credit/statement-channel
     * rows that carry a card last4 — identifies every credit card seen in SMS history.
     */
    suspend fun listLatestCreditCardRowFacts(): List<ParsedEventRecord> =
        listAll().filter { it.details.isCreditCardSms() && it.event.cardRef?.last4 != null }

    /**
     * Per card reference: the [CardSmsChannel.CREDIT] rows with an available balance whose
     * effective instant (`occurredAt ?: RawSms.receivedAt`) is the latest one strictly
     * before [beforeExclusive]. Rows tied on that instant are all returned.
     */
    suspend fun listLatestCreditCardAvailableBalanceFacts(beforeExclusive: Instant): List<ParsedEventRecord> =
        listAll().filter { record ->
            record.details.cardSmsChannel == CardSmsChannel.CREDIT &&
                record.details.availableBalance != null &&
                record.event.cardRef?.last4 != null
        }

    /** [MessageFamily.FINANCING_INSTALLMENT] rows with a loan type. */
    suspend fun listFinancingInstallmentFacts(): List<ParsedEventRecord> =
        listAll().filter { record ->
            record.event.messageFamily == MessageFamily.FINANCING_INSTALLMENT &&
                record.details.loanType != null
        }

    /** Rows carrying both a merchant and an SMS-quoted exchange rate. */
    suspend fun listExchangeRateFacts(): List<ParsedEventRecord> =
        listAll().filter { it.event.merchant != null && it.details.exchangeRate != null }

    /**
     * For each (event bank, card last4) with last4 in [cardLast4s]: the first row by event id
     * on the [CardSmsChannel.DEBIT] channel, and the first row by event id carrying a debit
     * source account (`debitSourceAccountLast4` or a source account reference).
     */
    suspend fun listFirstDebitCardFacts(cardLast4s: Collection<String>): List<ParsedEventRecord> {
        if (cardLast4s.isEmpty()) return emptyList()
        val wanted = cardLast4s.toSet()
        return listAll().filter { it.event.cardRef?.last4 in wanted }
    }
}

/**
 * Reconstructed parse output: domain [ParsedEvent] plus parse-time [ParsedEventDetails].
 *
 * [userCorrected] is true only on effective projections that overlay at least one
 * explicit user correction; stored rows are always false.
 */
data class ParsedEventRecord(
    val event: ParsedEvent,
    val details: ParsedEventDetails,
    val userCorrected: Boolean = false,
)
