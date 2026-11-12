package com.baraa.masroof.testsupport

import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.repository.RawSmsInsertResult
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import java.math.BigDecimal
import java.time.Instant

/*
 * Empty repositories. Tests subclass them and override only what the scenario needs.
 * Subclass instead of `by` delegation: interface default methods (countRequired, getByIds, ...)
 * must keep calling the overridden members.
 */

internal open class EmptyRawSmsRepository : RawSmsRepository {
    override suspend fun insertIfAbsent(rawSms: RawSms): RawSmsInsertResult = RawSmsInsertResult.Inserted
    override suspend fun getById(id: String): RawSms? = null
    override suspend fun findByDeviceMessageId(deviceMessageId: String): RawSms? = null
    override suspend fun listIdsByReceivedAt(): List<String> = emptyList()
    override suspend fun listCrossSourceNearDuplicates(
        sender: String,
        bodyHash: String,
        fromInclusive: Instant,
        toInclusive: Instant,
        lookingForLiveRow: Boolean,
    ): List<RawSms> = emptyList()
}

internal open class EmptyReviewRepository : ReviewRepository {
    override suspend fun getById(id: String): ReviewItem? = null
    override suspend fun findByRawSmsId(rawSmsId: String): ReviewItem? = null
    override suspend fun listRequired(): List<ReviewItem> = emptyList()
    override suspend fun listIgnored(): List<ReviewItem> = emptyList()
    override suspend fun listAll(): List<ReviewItem> = emptyList()
    override suspend fun upsertRequired(
        rawSmsId: String,
        kind: ReviewKind,
        reasons: List<String>,
        now: Instant,
    ): ReviewItem = error("unused")
    override suspend fun markResolved(
        id: String,
        resolutionKind: ReviewResolutionKind,
        resolvedAt: Instant,
        resolvedTransactionId: String?,
    ): ReviewItem? = null
}

internal open class EmptyFinancialTransactionRepository : FinancialTransactionRepository {
    override suspend fun getById(id: String): FinancialTransaction? = null
    override suspend fun findByRawSmsId(rawSmsId: String): FinancialTransaction? = null
    override suspend fun listAll(): List<FinancialTransaction> = emptyList()
    override suspend fun listOccurredBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<FinancialTransaction> = emptyList()
    override suspend fun save(
        transaction: FinancialTransaction,
        rawSmsIds: Collection<String>,
    ): FinancialTransactionSaveResult = FinancialTransactionSaveResult.Saved
    override suspend fun update(transaction: FinancialTransaction): Boolean = false
    override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String): Boolean = false
    override suspend fun unlinkRawSms(rawSmsId: String): Boolean = false
    override suspend fun listRawSmsIds(transactionId: String): List<String> = emptyList()
    override suspend fun isRawSmsLinked(rawSmsId: String): Boolean = false
    override suspend fun updateAppliedExchangeRate(
        id: String,
        exchangeRate: BigDecimal,
        source: ExchangeRateSource,
    ): Boolean = false
}

internal open class EmptyParsedEventRepository : ParsedEventRepository {
    override suspend fun save(event: ParsedEvent, details: ParsedEventDetails) = Unit
    override suspend fun getById(id: String): ParsedEventRecord? = null
    override suspend fun findByRawSmsId(rawSmsId: String): ParsedEventRecord? = null
    override suspend fun deleteByRawSmsId(rawSmsId: String) = Unit
    override suspend fun listAll(): List<ParsedEventRecord> = emptyList()
}
