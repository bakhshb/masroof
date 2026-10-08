package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistence for [com.baraa.masroof.domain.model.ParsedEvent] plus
 * [com.baraa.masroof.parsing.model.ParsedEventDetails] as dedicated nullable columns.
 *
 * Single-table shape chosen for simplicity and transactional atomicity.
 * Domain/parsing models remain separate; mappers reconstitute both types.
 *
 * FK to [RawSmsEntity] uses NO ACTION / RESTRICT semantics: deleting a parsed
 * row must never cascade-delete raw SMS evidence. Unique [rawSmsId] supports
 * one current parse result per SMS (replaceable on reprocess).
 */
@Entity(
    tableName = "parsed_event",
    foreignKeys = [
        ForeignKey(
            entity = RawSmsEntity::class,
            parentColumns = ["id"],
            childColumns = ["rawSmsId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["rawSmsId"], unique = true),
        Index(value = ["cardBankId", "cardLast4"]),
        Index(value = ["cardSmsChannel", "cardLast4"]),
    ],
)
data class ParsedEventEntity(
    @PrimaryKey val id: String,
    val rawSmsId: String,
    val bankId: String,
    val messageFamily: String,
    val direction: String?,
    val amountDecimal: String?,
    val amountCurrency: String?,
    val purchaseChannel: String?,
    val sourceAccountBankId: String?,
    val sourceAccountMaskedNumber: String?,
    val destinationAccountBankId: String?,
    val destinationAccountMaskedNumber: String?,
    val cardBankId: String?,
    val cardLast4: String?,
    val merchant: String?,
    val counterparty: String?,
    /** Instant epoch millis; independent of [occurredAtLocal]. */
    val occurredAtEpochMillis: Long?,
    val bankNetworkType: String?,
    val confidenceScore: Double,
    /** Reasons joined with [CONFIDENCE_REASON_SEPARATOR]; empty string when none. */
    val confidenceReasons: String,
    val parseStatus: String,
    // --- ParsedEventDetails columns (not conflated with ParsedEvent fields) ---
    val transactionReference: String?,
    val availableBalanceDecimal: String?,
    val availableBalanceCurrency: String?,
    val outstandingBalanceDecimal: String?,
    val outstandingBalanceCurrency: String?,
    val biller: String?,
    val billerCode: String?,
    /** ISO-8601 local date-time text; no timezone conversion. */
    val occurredAtLocal: String?,
    val cardSmsChannel: String?,
    /** ISO-8601 local date text. */
    val paymentDueDate: String?,
    val exchangeRate: String?,
    val internationalFeeDecimal: String?,
    val internationalFeeCurrency: String?,
    val labeledForeignAmountDecimal: String?,
    val labeledForeignAmountCurrency: String?,
    val loanType: String?,
    val debitSourceAccountLast4: String?,
    val salaryIncomeWording: Int?,
) {
    companion object {
        const val CONFIDENCE_REASON_SEPARATOR: Char = '\u001e'
    }
}
