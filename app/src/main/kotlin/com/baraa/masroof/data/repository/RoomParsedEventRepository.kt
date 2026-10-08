package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.dao.ParsedEventDao
import com.baraa.masroof.data.room.dao.RoomBatch
import com.baraa.masroof.data.room.mapper.ParsedEventMapper
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository

class RoomParsedEventRepository(
    private val dao: ParsedEventDao,
) : ParsedEventRepository {
    override suspend fun save(event: ParsedEvent, details: ParsedEventDetails) {
        val entity = ParsedEventMapper.toEntity(event, details)
        dao.replaceForRawSms(entity)
    }

    override suspend fun getById(id: String): ParsedEventRecord? =
        dao.getById(id)?.let(ParsedEventMapper::toRecord)

    override suspend fun findByRawSmsId(rawSmsId: String): ParsedEventRecord? =
        dao.findByRawSmsId(rawSmsId)?.let(ParsedEventMapper::toRecord)

    override suspend fun deleteByRawSmsId(rawSmsId: String) {
        dao.deleteByRawSmsId(rawSmsId)
    }

    override suspend fun listAll(): List<ParsedEventRecord> =
        dao.listAll().map(ParsedEventMapper::toRecord)

    override suspend fun listReceivedBetween(
        startInclusive: java.time.Instant,
        endExclusive: java.time.Instant,
    ): List<ParsedEventRecord> =
        dao.listReceivedBetween(
            startInclusiveMillis = startInclusive.toEpochMilli(),
            endExclusiveMillis = endExclusive.toEpochMilli(),
        ).map(ParsedEventMapper::toRecord)

    override suspend fun listUnlinkedTransfers(): List<ParsedEventRecord> =
        dao.listUnlinkedTransfers().map(ParsedEventMapper::toRecord)

    override suspend fun listUnlinkedTransfersReceivedBetween(
        startInclusive: java.time.Instant,
        endExclusive: java.time.Instant,
    ): List<ParsedEventRecord> {
        if (!startInclusive.isBefore(endExclusive)) return emptyList()
        return dao.listUnlinkedTransfersReceivedBetween(
            startInclusiveMillis = startInclusive.toEpochMilli(),
            endExclusiveMillis = endExclusive.toEpochMilli(),
        ).map(ParsedEventMapper::toRecord)
    }

    override suspend fun listUnlinkedTransfersOccurredLocalBetween(
        startInclusive: java.time.LocalDateTime,
        endExclusive: java.time.LocalDateTime,
    ): List<ParsedEventRecord> {
        if (!startInclusive.isBefore(endExclusive)) return emptyList()
        return dao.listUnlinkedTransfersOccurredLocalBetween(
            startInclusive = startInclusive.toString(),
            endExclusive = endExclusive.toString(),
        ).map(ParsedEventMapper::toRecord)
    }

    override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> =
        RoomBatch.query(rawSmsIds) { chunk -> dao.listByRawSmsIds(chunk) }
            .sortedBy { it.id }
            .map(ParsedEventMapper::toRecord)

    override suspend fun listCardStatementFacts(): List<ParsedEventRecord> =
        dao.listCardStatementFacts().map(ParsedEventMapper::toRecord)

    override suspend fun listLatestCreditCardRowFacts(): List<ParsedEventRecord> =
        dao.listLatestCreditCardRowFacts().map(ParsedEventMapper::toRecord)

    override suspend fun listLatestCreditCardAvailableBalanceFacts(
        beforeExclusive: java.time.Instant,
    ): List<ParsedEventRecord> =
        dao.listLatestCreditCardAvailableBalanceFacts(beforeExclusive.toEpochMilli())
            .map(ParsedEventMapper::toRecord)

    override suspend fun listFinancingInstallmentFacts(): List<ParsedEventRecord> =
        dao.listFinancingInstallmentFacts().map(ParsedEventMapper::toRecord)

    override suspend fun listExchangeRateFacts(): List<ParsedEventRecord> =
        dao.listExchangeRateFacts().map(ParsedEventMapper::toRecord)

    override suspend fun listFirstDebitCardFacts(cardLast4s: Collection<String>): List<ParsedEventRecord> =
        RoomBatch.query(cardLast4s, chunkSize = RoomBatch.MAX_BIND_ARGS / 2) { chunk ->
            dao.listFirstDebitCardFacts(chunk)
        }
            .sortedBy { it.id }
            .map(ParsedEventMapper::toRecord)
}
