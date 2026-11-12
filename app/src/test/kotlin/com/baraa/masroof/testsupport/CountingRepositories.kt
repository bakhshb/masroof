package com.baraa.masroof.testsupport

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import java.time.Instant
import java.time.LocalDateTime

/** Counts whole-history and scoped reads made through the wrapped parsed-event repository. */
class CountingParsedEventRepository(
    private val delegate: ParsedEventRepository,
) : ParsedEventRepository by delegate {
    var listAllCalls: Int = 0
    var listByRawSmsIdsCalls: Int = 0
    var transferCandidateCalls: Int = 0
    val boundedWindows: MutableList<Pair<Instant, Instant>> = mutableListOf()
    val loadedRawSmsIdSets: MutableList<Collection<String>> = mutableListOf()

    fun reset() {
        listAllCalls = 0
        listByRawSmsIdsCalls = 0
        transferCandidateCalls = 0
        boundedWindows.clear()
        loadedRawSmsIdSets.clear()
    }

    override suspend fun listAll(): List<ParsedEventRecord> {
        listAllCalls += 1
        return delegate.listAll()
    }

    /** The ledger writer's one bounded transfer-candidate query, with its receipt window. */
    override suspend fun listTransferCandidates(
        receivedAt: ClosedRange<Instant>,
        occurredAt: ClosedRange<Instant>,
        occurredAtLocal: ClosedRange<LocalDateTime>,
    ): List<ParsedEventRecord> {
        transferCandidateCalls += 1
        boundedWindows += receivedAt.start to receivedAt.endInclusive
        return delegate.listTransferCandidates(receivedAt, occurredAt, occurredAtLocal)
    }

    override suspend fun listByRawSmsIds(rawSmsIds: Collection<String>): List<ParsedEventRecord> {
        listByRawSmsIdsCalls += 1
        loadedRawSmsIdSets += rawSmsIds
        return delegate.listByRawSmsIds(rawSmsIds)
    }
}

/** Counts whole-history and typed reads made through the wrapped ledger repository. */
class CountingFinancialTransactionRepository(
    private val delegate: FinancialTransactionRepository,
) : FinancialTransactionRepository by delegate {
    var listAllCalls: Int = 0
    var listByTypesCalls: Int = 0

    fun reset() {
        listAllCalls = 0
        listByTypesCalls = 0
    }

    override suspend fun listAll(): List<FinancialTransaction> {
        listAllCalls += 1
        return delegate.listAll()
    }

    override suspend fun listByTypes(
        types: Collection<FinancialTransactionType>,
    ): List<FinancialTransaction> {
        listByTypesCalls += 1
        return delegate.listByTypes(types)
    }
}
