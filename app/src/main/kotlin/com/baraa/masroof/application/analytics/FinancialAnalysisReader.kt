package com.baraa.masroof.application.analytics

import androidx.room.withTransaction
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.dashboard.*
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.repository.*
import java.time.Instant
import java.time.ZoneId
import java.time.Clock

internal data class FinancialAnalysisRead(val service: DashboardService, val rows: List<FinancialTransaction>,
    val committed: Set<String>, val retry: Boolean, val incompleteDays: Set<Long>, val committedCadences: Map<String, String>)

/**
 * Copy only approved facts and the existing bounded dashboard evidence in a short read.
 * All projections, currency conversion and model work happen AFTER releasing Room's
 * transaction. The detached facades reuse the existing purchase/commitment calculators.
 */
internal object FinancialAnalysisReader {
    suspend fun capture(c: AppContainer, start: Instant, end: Instant, zone: ZoneId, clock: Clock = Clock.system(zone)): FinancialAnalysisRead =
        // One transaction: rows and evidence must come from the same commit, or a stored snapshot and its
        // phone alert could mix two states (invalidations reach the generation check asynchronously).
        c.databaseAccessGate.withAccess { c.database.withTransaction {
            val accounts = c.accountRegistryRepository.listAll()
            val cards = c.cardRegistryRepository.listAll()
            val loans = c.loanRegistryRepository.listAll()
            val commitments = c.commitmentRepository.listAll()
            val reviews = c.reviewRepository.listRequired()
            val scope = DashboardEvidenceScope(c.financialTransactionRepository, c.parsedEventRepository, c.rawSmsRepository)
            val periodRows = c.financialTransactionRepository.listOccurredBetween(start, end)
            var evidence = scope.load(periodRows, cards, end)
            val statementStart = CreditCardOverviewBuilder.resolveStatementSpendingStart(evidence.parsedRecords, evidence.rawSmsById, zone, end)
            val cardRows = if (statementStart < start) c.financialTransactionRepository.listOccurredBetween(statementStart, end) else emptyList()
            val sources = c.financialTransactionRepository.getByIds(commitments.map { it.sourceTransactionId })
            val payments = c.financialTransactionRepository.listByTypes(listOf(FinancialTransactionType.CREDIT_CARD_PAYMENT))
            val rows = (periodRows + cardRows + sources + payments).distinctBy { it.id }.sortedWith(compareBy({ it.occurredAt }, { it.id }))
            evidence = scope.extend(evidence, rows)
            val copiedEvidence = evidence
            val detachedEvidence = object : DashboardEvidenceSource {
                override suspend fun load(transactions: Collection<FinancialTransaction>, registryCards: List<CardRegistryEntry>, periodEndExclusive: Instant) = copiedEvidence
                override suspend fun extend(evidence: DashboardEvidence, transactions: Collection<FinancialTransaction>) = copiedEvidence
            }
            val rowsById = rows.associateBy { it.id }
            val financial = object : FinancialTransactionRepository by c.financialTransactionRepository {
                override suspend fun getById(id: String) = rowsById[id]
                override suspend fun getByIds(ids: Collection<String>) = ids.distinct().mapNotNull(rowsById::get)
                override suspend fun listAll() = rows
                override suspend fun listOccurredBetween(startInclusive: Instant, endExclusive: Instant) = rows.filter { it.occurredAt >= startInclusive && it.occurredAt < endExclusive }.asReversed()
                override suspend fun listByTypes(types: Collection<FinancialTransactionType>) = rows.filter { it.type in types }
                override suspend fun listByTypesOccurredSince(types: Collection<FinancialTransactionType>, startInclusive: Instant) = rows.filter { it.type in types && it.occurredAt >= startInclusive }
                override suspend fun listByTypesOccurredBetween(types: Collection<FinancialTransactionType>, startInclusive: Instant, endExclusive: Instant) = rows.filter { it.type in types && it.occurredAt >= startInclusive && it.occurredAt < endExclusive }
            }
            val service = DashboardService(financial,
                object : ReviewRepository by c.reviewRepository {
                    // Detached reads must never reach the live database, which a restore may already have retired.
                    override suspend fun listRequired() = reviews
                    override suspend fun countRequired() = reviews.size
                },
                c.parsedEventRepository, c.rawSmsRepository, c.appLocaleRepository,
                object : AccountRegistryRepository by c.accountRegistryRepository { override suspend fun listAll() = accounts },
                object : CardRegistryRepository by c.cardRegistryRepository { override suspend fun listAll() = cards },
                object : LoanRegistryRepository by c.loanRegistryRepository { override suspend fun listAll() = loans },
                object : CommitmentRepository by c.commitmentRepository { override suspend fun listAll() = commitments },
                // Analytics never requests a new rate. Enrichment remains the financial authority.
                TransactionSarEquivalentResolver(ForeignSarMarketRateProvider { _, _ -> null }), zoneId = zone, clock = clock, evidenceSource = detachedEvidence)
            val retry = c.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM processing_retry").use { it.moveToFirst(); it.getInt(0) > 0 }
            val incompleteIds = (reviews.map { it.rawSmsId } + c.processingRetryRepository.listRetryableRawSmsIds()).toSet()
            val incompleteRaw = c.rawSmsRepository.getByIds(incompleteIds).associateBy { it.id }
            val incompleteEvents = c.parsedEventRepository.listByRawSmsIds(incompleteIds).associateBy { it.event.rawSmsId }
            val incompleteDays = incompleteIds.flatMap { id ->
                val rawSms = incompleteRaw[id] ?: return@flatMap emptyList()
                val record = incompleteEvents[id]
                val receiptDay = rawSms.receivedAt.atZone(zone).toLocalDate().toEpochDay()
                val eventDay = record?.let {
                    com.baraa.masroof.domain.assembly.TransactionTiming.effectiveOccurredAt(it.event, it.details.occurredAtLocal,
                        rawSms.receivedAt, zone).atZone(zone).toLocalDate().toEpochDay()
                }
                listOfNotNull(receiptDay, eventDay)
            }.toSet()
            FinancialAnalysisRead(service, rows, commitments.map { it.sourceTransactionId }.toSet(), retry, incompleteDays,
                commitments.mapNotNull { it.recurrence?.name?.let { recurrence -> it.sourceTransactionId to recurrence } }.toMap())
        } }
}
