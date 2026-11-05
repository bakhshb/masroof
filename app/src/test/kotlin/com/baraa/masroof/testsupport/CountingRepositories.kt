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
    var globalUnlinkedCalls: Int = 0
    var boundedUnlinkedCalls: Int = 0
    var boundedLocalCalls: Int = 0
    var listByRawSmsIdsCalls: Int = 0
    val boundedWindows: MutableList<Pair<Instant, Instant>> = mutableListOf()
    val loadedRawSmsIdSets: MutableList<Collection<String>> = mutableListOf()

    fun reset() {
        listAllCalls = 0
        globalUnlinkedCalls = 0
        boundedUnlinkedCalls = 0
        boundedLocalCalls = 0
        listByRawSmsIdsCalls = 0
        boundedWindows.clear()
        loadedRawSmsIdSets.clear()
    }

    override suspend fun listAll(): List<ParsedEventRecord> {
        listAllCalls += 1
        return delegate.listAll()
    }

    override suspend fun listUnlinkedTransfers(): List<ParsedEventRecord> {
        globalUnlinkedCalls += 1
        return delegate.listUnlinkedTransfers()
    }

    override suspend fun listUnlinkedTransfersReceivedBetween(
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<ParsedEventRecord> {
        boundedUnlinkedCalls += 1
        boundedWindows += startInclusive to endExclusive
        return delegate.listUnlinkedTransfersReceivedBetween(startInclusive, endExclusive)
    }

    override suspend fun listUnlinkedTransfersOccurredLocalBetween(
        startInclusive: LocalDateTime,
        endExclusive: LocalDateTime,
    ): List<ParsedEventRecord> {
        boundedLocalCalls += 1
        return delegate.listUnlinkedTransfersOccurredLocalBetween(startInclusive, endExclusive)
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
    var boundedTypeCalls: Int = 0
    val boundedWindows: MutableList<Pair<Instant, Instant>> = mutableListOf()

    fun reset() {
        listAllCalls = 0
        listByTypesCalls = 0
        boundedTypeCalls = 0
        boundedWindows.clear()
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

    override suspend fun listByTypesOccurredBetween(
        types: Collection<FinancialTransactionType>,
        startInclusive: Instant,
        endExclusive: Instant,
    ): List<FinancialTransaction> {
        boundedTypeCalls += 1
        boundedWindows += startInclusive to endExclusive
        return delegate.listByTypesOccurredBetween(types, startInclusive, endExclusive)
    }
}
