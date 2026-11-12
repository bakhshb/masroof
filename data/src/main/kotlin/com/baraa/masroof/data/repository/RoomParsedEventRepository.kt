package com.baraa.masroof.data.repository

import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.dao.ParsedEventDao
import com.baraa.masroof.data.room.dao.RoomBatch
import com.baraa.masroof.data.room.mapper.ParsedEventMapper
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository

class RoomParsedEventRepository(
    private val dao: ParsedEventDao,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : ParsedEventRepository {
    override suspend fun save(event: ParsedEvent, details: ParsedEventDetails): Unit = accessGate.withAccess<Unit> {
        val entity = ParsedEventMapper.toEntity(event, details)
        dao.replaceForRawSms(entity)
    }

    override suspend fun saveForHistoricalBatch(
        event: ParsedEvent,
        details: ParsedEventDetails,
        requiredAt: java.time.Instant,
    ): Unit = accessGate.withAccess<Unit> {
        dao.replaceForHistoricalBatch(ParsedEventMapper.toEntity(event, details), requiredAt.toEpochMilli())
    }

    override suspend fun getById(id: String): ParsedEventRecord? = accessGate.withAccess {
        dao.getById(id)?.let(ParsedEventMapper::toRecord)
    }

    override suspend fun findByRawSmsId(rawSmsId: String): ParsedEventRecord? = accessGate.withAccess {
        dao.findByRawSmsId(rawSmsId)?.let(ParsedEventMapper::toRecord)
    }

    override suspend fun deleteByRawSmsId(rawSmsId: String): Unit = accessGate.withAccess<Unit> {
        dao.deleteByRawSmsId(rawSmsId)
    }

    override suspend fun listAll(): List<ParsedEventRecord> = accessGate.withAccess {
        dao.listAll().map(ParsedEventMapper::toRecord)
    }

    override suspend fun listTransferCandidates(
        receivedAt: ClosedRange<java.time.Instant>,
        occurredAt: ClosedRange<java.time.Instant>,
        occurredAtLocal: ClosedRange<java.time.LocalDateTime>,
    ): List<ParsedEventRecord> = accessGate.withAccess {
        // Whole seconds, widened outward, because datetime() drops fractions.
        dao.listTransferCandidates(
            receivedFrom = receivedAt.start.toEpochMilli(),
            receivedTo = receivedAt.endInclusive.toEpochMilli(),
            occurredFrom = occurredAt.start.toEpochMilli(),
            occurredTo = occurredAt.endInclusive.toEpochMilli(),
            localFrom = occurredAtLocal.start.withNano(0).toString(),
            localTo = occurredAtLocal.endInclusive.withNano(0).plusSeconds(1).toString(),
        ).map(ParsedEventMapper::toRecord)
    }

    override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> = accessGate.withAccess {
        RoomBatch.query(rawSmsIds) { chunk -> dao.listByRawSmsIds(chunk) }
            .sortedBy { it.id }
            .map(ParsedEventMapper::toRecord)
    }

    override suspend fun listByIds(ids: Collection<String>): List<ParsedEventRecord> = accessGate.withAccess {
        RoomBatch.query(ids) { chunk -> dao.listByIds(chunk) }
            .sortedBy { it.id }
            .map(ParsedEventMapper::toRecord)
    }

    override suspend fun listRawSmsIdsReferencingAccount(account: AccountReference): List<String> = accessGate.withAccess {
        val masked = account.maskedNumber ?: return@withAccess emptyList()
        return@withAccess dao.listRawSmsIdsReferencingAccount(account.bank.id, masked)
    }

    override suspend fun listRawSmsIdsReferencingCard(card: CardReference): List<String> = accessGate.withAccess {
        val last4 = card.last4 ?: return@withAccess emptyList()
        return@withAccess dao.listRawSmsIdsReferencingCard(card.bank.id, last4)
    }

    override suspend fun listRawSmsIdsReferencingLoan(loan: LoanReference): List<String> = accessGate.withAccess {
        dao.listRawSmsIdsReferencingLoan(loan.bank.id, loan.loanType.name)
    }

    override suspend fun listRememberedNonFinancial(): List<ParsedEventRecord> = accessGate.withAccess {
        dao.listRememberedNonFinancial().map(ParsedEventMapper::toRecord)
    }

    override suspend fun listCardStatementFacts(): List<ParsedEventRecord> = accessGate.withAccess {
        dao.listCardStatementFacts().map(ParsedEventMapper::toRecord)
    }

    override suspend fun listLatestCreditCardRowFacts(): List<ParsedEventRecord> = accessGate.withAccess {
        dao.listLatestCreditCardRowFacts().map(ParsedEventMapper::toRecord)
    }

    override suspend fun listLatestCreditCardAvailableBalanceFacts(
        beforeExclusive: java.time.Instant,
    ): List<ParsedEventRecord> = accessGate.withAccess {
        dao.listLatestCreditCardAvailableBalanceFacts(beforeExclusive.toEpochMilli())
            .map(ParsedEventMapper::toRecord)
    }

    override suspend fun listFinancingInstallmentFacts(): List<ParsedEventRecord> = accessGate.withAccess {
        dao.listFinancingInstallmentFacts().map(ParsedEventMapper::toRecord)
    }

    override suspend fun listExchangeRateFacts(): List<ParsedEventRecord> = accessGate.withAccess {
        dao.listExchangeRateFacts().map(ParsedEventMapper::toRecord)
    }

    override suspend fun listFirstDebitCardFacts(cardLast4s: Collection<String>): List<ParsedEventRecord> = accessGate.withAccess {
        RoomBatch.query(cardLast4s, chunkSize = RoomBatch.MAX_BIND_ARGS / 2) { chunk ->
            dao.listFirstDebitCardFacts(chunk)
        }
            .sortedBy { it.id }
            .map(ParsedEventMapper::toRecord)
    }
}
