package com.baraa.masroof.data.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.baraa.masroof.data.room.entity.ParsedEventEntity

@Dao
interface ParsedEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ParsedEventEntity)

    @Query("SELECT * FROM parsed_event WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ParsedEventEntity?

    @Query("SELECT * FROM parsed_event WHERE rawSmsId = :rawSmsId LIMIT 1")
    suspend fun findByRawSmsId(rawSmsId: String): ParsedEventEntity?

    @Query("DELETE FROM parsed_event WHERE rawSmsId = :rawSmsId")
    suspend fun deleteByRawSmsId(rawSmsId: String): Int

    @Query("SELECT COUNT(*) FROM parsed_event")
    suspend fun count(): Int

    @Query("SELECT * FROM parsed_event ORDER BY id")
    suspend fun listAll(): List<ParsedEventEntity>

    @Query(
        """
        SELECT pe.* FROM parsed_event pe
        INNER JOIN raw_sms rs ON pe.rawSmsId = rs.id
        WHERE rs.receivedAtEpochMillis >= :startInclusiveMillis
          AND rs.receivedAtEpochMillis < :endExclusiveMillis
        ORDER BY pe.id
        """,
    )
    suspend fun listReceivedBetween(
        startInclusiveMillis: Long,
        endExclusiveMillis: Long,
    ): List<ParsedEventEntity>

    @Query(
        """
        SELECT pe.* FROM parsed_event pe
        WHERE pe.messageFamily IN ('TRANSFER_IN', 'TRANSFER_OUT')
          AND NOT EXISTS (
            SELECT 1 FROM financial_transaction_raw_sms_link link
            WHERE link.rawSmsId = pe.rawSmsId
          )
        ORDER BY pe.id
        """,
    )
    suspend fun listUnlinkedTransfers(): List<ParsedEventEntity>

    @Query(
        """
        SELECT pe.* FROM parsed_event pe
        INNER JOIN raw_sms rs ON pe.rawSmsId = rs.id
        WHERE pe.messageFamily IN ('TRANSFER_IN', 'TRANSFER_OUT')
          AND rs.receivedAtEpochMillis >= :startInclusiveMillis
          AND rs.receivedAtEpochMillis < :endExclusiveMillis
          AND NOT EXISTS (
            SELECT 1 FROM financial_transaction_raw_sms_link link
            WHERE link.rawSmsId = pe.rawSmsId
          )
        ORDER BY pe.id
        """,
    )
    suspend fun listUnlinkedTransfersReceivedBetween(
        startInclusiveMillis: Long,
        endExclusiveMillis: Long,
    ): List<ParsedEventEntity>

    /**
     * [startInclusive] and [endExclusive] are ISO-8601 local date-times.
     * `datetime()` normalizes stored text that omits zero seconds.
     *
     * `datetime()` is not sargable, so there is no index on [occurredAtLocal].
     * The `messageFamily` index narrows the transfer families; the unlinked-link
     * probe uses the link primary key.
     */
    @Query(
        """
        SELECT pe.* FROM parsed_event pe
        WHERE pe.messageFamily IN ('TRANSFER_IN', 'TRANSFER_OUT')
          AND pe.occurredAtLocal IS NOT NULL
          AND datetime(pe.occurredAtLocal) >= datetime(:startInclusive)
          AND datetime(pe.occurredAtLocal) < datetime(:endExclusive)
          AND NOT EXISTS (
            SELECT 1 FROM financial_transaction_raw_sms_link link
            WHERE link.rawSmsId = pe.rawSmsId
          )
        ORDER BY pe.id
        """,
    )
    suspend fun listUnlinkedTransfersOccurredLocalBetween(
        startInclusive: String,
        endExclusive: String,
    ): List<ParsedEventEntity>

    /** Callers keep [rawSmsIds] under [RoomBatch.MAX_BIND_ARGS]. */
    @Query("SELECT * FROM parsed_event WHERE rawSmsId IN (:rawSmsIds)")
    suspend fun listByRawSmsIds(rawSmsIds: List<String>): List<ParsedEventEntity>

    @Query(
        """
        SELECT rawSmsId FROM parsed_event
        WHERE (sourceAccountBankId = :bankId AND sourceAccountMaskedNumber = :maskedNumber)
           OR (destinationAccountBankId = :bankId AND destinationAccountMaskedNumber = :maskedNumber)
        ORDER BY id
        """,
    )
    suspend fun listRawSmsIdsReferencingAccount(bankId: String, maskedNumber: String): List<String>

    @Query(
        """
        SELECT rawSmsId FROM parsed_event
        WHERE cardBankId = :bankId AND cardLast4 = :last4
        ORDER BY id
        """,
    )
    suspend fun listRawSmsIdsReferencingCard(bankId: String, last4: String): List<String>

    @Query(
        """
        SELECT rawSmsId FROM parsed_event
        WHERE bankId = :bankId AND loanType = :loanType
        ORDER BY id
        """,
    )
    suspend fun listRawSmsIdsReferencingLoan(bankId: String, loanType: String): List<String>

    @Query("SELECT * FROM parsed_event WHERE cardSmsChannel = 'STATEMENT' ORDER BY id")
    suspend fun listCardStatementFacts(): List<ParsedEventEntity>

    @Query(
        """
        SELECT * FROM parsed_event
        WHERE id IN (
            SELECT MAX(id) FROM parsed_event
            WHERE cardSmsChannel IN ('CREDIT', 'STATEMENT')
              AND cardLast4 IS NOT NULL
            GROUP BY cardBankId, cardLast4
        )
        ORDER BY id
        """,
    )
    suspend fun listLatestCreditCardRowFacts(): List<ParsedEventEntity>

    @Query(
        """
        SELECT pe.* FROM parsed_event pe
        INNER JOIN raw_sms rs ON rs.id = pe.rawSmsId
        INNER JOIN (
            SELECT p2.cardBankId AS cardBankId,
                   p2.cardLast4 AS cardLast4,
                   MAX(COALESCE(p2.occurredAtEpochMillis, r2.receivedAtEpochMillis)) AS latestAt
            FROM parsed_event p2
            INNER JOIN raw_sms r2 ON r2.id = p2.rawSmsId
            WHERE p2.cardSmsChannel = 'CREDIT'
              AND p2.availableBalanceDecimal IS NOT NULL
              AND p2.cardLast4 IS NOT NULL
              AND COALESCE(p2.occurredAtEpochMillis, r2.receivedAtEpochMillis) < :beforeExclusiveMillis
            GROUP BY p2.cardBankId, p2.cardLast4
        ) latest ON latest.cardBankId = pe.cardBankId AND latest.cardLast4 = pe.cardLast4
        WHERE pe.cardSmsChannel = 'CREDIT'
          AND pe.availableBalanceDecimal IS NOT NULL
          AND COALESCE(pe.occurredAtEpochMillis, rs.receivedAtEpochMillis) = latest.latestAt
        ORDER BY pe.id
        """,
    )
    suspend fun listLatestCreditCardAvailableBalanceFacts(beforeExclusiveMillis: Long): List<ParsedEventEntity>

    @Query(
        """
        SELECT * FROM parsed_event
        WHERE messageFamily = 'FINANCING_INSTALLMENT'
          AND loanType IS NOT NULL
        ORDER BY id
        """,
    )
    suspend fun listFinancingInstallmentFacts(): List<ParsedEventEntity>

    @Query(
        """
        SELECT * FROM parsed_event
        WHERE merchant IS NOT NULL
          AND exchangeRate IS NOT NULL
        ORDER BY id
        """,
    )
    suspend fun listExchangeRateFacts(): List<ParsedEventEntity>

    /** Callers keep [cardLast4s] under [RoomBatch.MAX_BIND_ARGS] / 2 (bound twice). */
    @Query(
        """
        SELECT * FROM parsed_event
        WHERE id IN (
            SELECT MIN(id) FROM parsed_event
            WHERE cardLast4 IN (:cardLast4s)
              AND cardSmsChannel = 'DEBIT'
            GROUP BY bankId, cardLast4
            UNION
            SELECT MIN(id) FROM parsed_event
            WHERE cardLast4 IN (:cardLast4s)
              AND (debitSourceAccountLast4 IS NOT NULL OR sourceAccountMaskedNumber IS NOT NULL)
            GROUP BY bankId, cardLast4
        )
        ORDER BY id
        """,
    )
    suspend fun listFirstDebitCardFacts(cardLast4s: List<String>): List<ParsedEventEntity>

    /**
     * Replace the current parse result for a RawSms (same or new event id).
     * Deletes any existing row for [entity.rawSmsId], then inserts [entity].
     */
    @Transaction
    suspend fun replaceForRawSms(entity: ParsedEventEntity) {
        deleteByRawSmsId(entity.rawSmsId)
        upsert(entity)
    }
}
