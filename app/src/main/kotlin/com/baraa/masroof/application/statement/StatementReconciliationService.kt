package com.baraa.masroof.application.statement

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.rules.FinancialImpactCalculator
import com.baraa.masroof.domain.statement.BankStatementEntry
import com.baraa.masroof.domain.statement.ParsedBankStatement
import com.baraa.masroof.domain.statement.StatementComparisonStatus
import com.baraa.masroof.domain.statement.StatementDirection
import com.baraa.masroof.domain.statement.StatementMatchPolicy
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Read-only comparison of one parsed statement to posted [FinancialTransaction] rows.
 *
 * Calls [FinancialTransactionRepository.listOccurredBetween] only. It does not
 * post, hide, or alter transactions, SMS, parsed events, corrections, or reviews.
 */
class StatementReconciliationService(
    private val financialTransactionRepository: FinancialTransactionRepository,
) {
    suspend fun compare(statement: ParsedBankStatement): StatementReconciliationReport {
        if (statement.entries.isEmpty()) {
            return emptyReport(statement)
        }
        val periodByAccount = statement.entries.groupBy { accountKey(it.bank, it.accountMasked) }
            .mapValues { (_, lines) ->
                lines.minOf { it.bookedDate } to lines.maxOf { it.bookedDate }
            }
        val start = statement.entries.minOf { it.bookedDate }
            .minusDays(StatementMatchPolicy.BOOKING_WINDOW_DAYS + StatementMatchPolicy.QUERY_SUPERSET_PADDING_DAYS)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
        val end = statement.entries.maxOf { it.bookedDate }
            .plusDays(
                StatementMatchPolicy.BOOKING_WINDOW_DAYS + StatementMatchPolicy.QUERY_SUPERSET_PADDING_DAYS + 1,
            )
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
        val posted = financialTransactionRepository.listOccurredBetween(start, end)
        val storedZones = storedZonesByBank(posted)
        val statementZones = statement.entries.map { it.bank }.distinct().associateWith { bank ->
            StatementMatchPolicy.zoneForStatementBank(bank, storedZones[bank].orEmpty())
        }
        val ledgerSides = projectLedger(posted, statementZones, periodByAccount)
        val statementSides = statement.entries.map { entry ->
            StatementSide(
                entry = entry,
                comparable = statementZones[entry.bank] != null,
            )
        }
        val paired = pair(statementSides, ledgerSides)
        return buildReport(statement, paired, periodByAccount)
    }

    private fun emptyReport(statement: ParsedBankStatement): StatementReconciliationReport =
        StatementReconciliationReport(
            formatVersion = statement.formatVersion,
            counts = StatementReconciliationCounts(0, 0, 0, 0, 0),
            statementLines = emptyList(),
            ledgerLines = emptyList(),
            totals = emptyList(),
            balances = statement.balances,
            balanceCheck = balanceCheck(statement),
            matchedFleetPaymentCount = 0,
            matchedSelfTransferCount = 0,
        )

    private fun storedZonesByBank(posted: List<FinancialTransaction>): Map<Bank, Set<String?>> {
        val zones = linkedMapOf<Bank, MutableSet<String?>>()
        posted.forEach { transaction ->
            listOf(transaction.sourceContainerId, transaction.destinationContainerId).forEach container@{ containerId ->
                val account = StatementMatchPolicy.qualifiedAccount(containerId) ?: return@container
                zones.getOrPut(account.bank) { linkedSetOf() }.add(transaction.occurredAtZone)
            }
        }
        return zones
    }

    private fun projectLedger(
        posted: List<FinancialTransaction>,
        statementZones: Map<Bank, java.time.ZoneId?>,
        periodByAccount: Map<String, Pair<LocalDate, LocalDate>>,
    ): List<LedgerSide> {
        val sides = mutableListOf<LedgerSide>()
        posted.forEach { transaction ->
            sides += side(
                transaction = transaction,
                containerId = transaction.sourceContainerId,
                endpoint = StatementMatchPolicy.AccountEndpoint.SOURCE,
                statementZones = statementZones,
                periodByAccount = periodByAccount,
            )
            sides += side(
                transaction = transaction,
                containerId = transaction.destinationContainerId,
                endpoint = StatementMatchPolicy.AccountEndpoint.DESTINATION,
                statementZones = statementZones,
                periodByAccount = periodByAccount,
            )
        }
        return sides
    }

    private fun side(
        transaction: FinancialTransaction,
        containerId: String?,
        endpoint: StatementMatchPolicy.AccountEndpoint,
        statementZones: Map<Bank, java.time.ZoneId?>,
        periodByAccount: Map<String, Pair<LocalDate, LocalDate>>,
    ): List<LedgerSide> {
        val account = StatementMatchPolicy.qualifiedAccount(containerId) ?: return emptyList()
        val key = accountKey(account.bank, account.accountMasked)
        val period = periodByAccount[key] ?: return emptyList()
        val direction = StatementMatchPolicy.cashDirection(transaction.type, endpoint)
        val zone = StatementMatchPolicy.zoneForPostedMovement(account.bank, transaction.occurredAtZone)
        val bankComparable = statementZones[account.bank] != null
        if (zone == null || direction == null || !bankComparable) {
            val civilDate = zone?.let { transaction.occurredAt.atZone(it).toLocalDate() }
            if (civilDate != null && !inPeriod(civilDate, period)) return emptyList()
            return listOf(
                LedgerSide(
                    transaction = transaction,
                    bank = account.bank,
                    accountMasked = account.accountMasked,
                    direction = direction ?: StatementDirection.DEBIT,
                    civilDate = civilDate,
                    comparable = false,
                ),
            )
        }
        val civilDate = transaction.occurredAt.atZone(zone).toLocalDate()
        if (!inPeriod(civilDate, period)) return emptyList()
        return listOf(
            LedgerSide(
                transaction = transaction,
                bank = account.bank,
                accountMasked = account.accountMasked,
                direction = direction,
                civilDate = civilDate,
                comparable = true,
            ),
        )
    }

    private fun pair(statementSides: List<StatementSide>, ledgerSides: List<LedgerSide>): Paired {
        val edges = mutableListOf<Edge>()
        statementSides.forEachIndexed statement@{ statementIndex, statementSide ->
            if (!statementSide.comparable) return@statement
            ledgerSides.forEachIndexed ledger@{ ledgerIndex, ledgerSide ->
                if (!ledgerSide.comparable) return@ledger
                if (compatible(statementSide.entry, ledgerSide)) {
                    edges += Edge(statementIndex, ledgerIndex)
                }
            }
        }
        val ambiguousStatements = mutableSetOf<Int>()
        val ambiguousLedgers = mutableSetOf<Int>()
        val byStatement = edges.groupBy { it.statementIndex }
        val byLedger = edges.groupBy { it.ledgerIndex }
        val crowdedStatements = byStatement.filterValues { it.size > 1 }.keys
        val crowdedLedgers = byLedger.filterValues { it.size > 1 }.keys
        edges.forEach { edge ->
            if (edge.statementIndex in crowdedStatements || edge.ledgerIndex in crowdedLedgers) {
                ambiguousStatements += edge.statementIndex
                ambiguousLedgers += edge.ledgerIndex
            }
        }
        val matched = mutableMapOf<Int, Int>()
        byStatement.forEach { (statementIndex, statementEdges) ->
            if (statementIndex in ambiguousStatements || statementEdges.size != 1) return@forEach
            val ledgerIndex = statementEdges.single().ledgerIndex
            if (ledgerIndex !in ambiguousLedgers && byLedger[ledgerIndex]?.size == 1) {
                matched[statementIndex] = ledgerIndex
            }
        }
        return Paired(
            statementSides = statementSides,
            ledgerSides = ledgerSides,
            matched = matched,
            ambiguousStatements = ambiguousStatements,
            ambiguousLedgers = ambiguousLedgers,
        )
    }

    private fun compatible(entry: BankStatementEntry, side: LedgerSide): Boolean {
        val civilDate = side.civilDate ?: return false
        return entry.bank == side.bank &&
            entry.accountMasked == side.accountMasked &&
            entry.direction == side.direction &&
            entry.amount.currency == side.transaction.amount.currency &&
            entry.amount == side.transaction.amount &&
            StatementMatchPolicy.datesMatch(entry.bookedDate, civilDate)
    }

    private fun buildReport(
        statement: ParsedBankStatement,
        paired: Paired,
        periodByAccount: Map<String, Pair<LocalDate, LocalDate>>,
    ): StatementReconciliationReport {
        val statementLines = paired.statementSides.mapIndexed { index, side ->
            val ledgerIndex = paired.matched[index]
            val status = when {
                !side.comparable -> StatementComparisonStatus.UNSUPPORTED
                index in paired.ambiguousStatements -> StatementComparisonStatus.AMBIGUOUS
                ledgerIndex != null -> StatementComparisonStatus.MATCHED
                else -> StatementComparisonStatus.STATEMENT_ONLY
            }
            val ledger = ledgerIndex?.let { paired.ledgerSides[it] }
            StatementLineComparison(
                entry = side.entry,
                status = status,
                ledgerTransactionId = ledger?.transaction?.id,
                ledgerType = ledger?.transaction?.type,
            )
        }
        val matchedLedgerIndexes = paired.matched.values.toSet()
        val ledgerLines = paired.ledgerSides.mapIndexed { index, side ->
            val status = when {
                !side.comparable -> StatementComparisonStatus.UNSUPPORTED
                index in paired.ambiguousLedgers -> StatementComparisonStatus.AMBIGUOUS
                index in matchedLedgerIndexes -> StatementComparisonStatus.MATCHED
                else -> StatementComparisonStatus.LEDGER_ONLY
            }
            LedgerLineComparison(
                transactionId = side.transaction.id,
                type = side.transaction.type,
                bank = side.bank,
                accountMasked = side.accountMasked,
                direction = side.direction,
                amount = side.transaction.amount.amount,
                currency = side.transaction.amount.currency,
                civilDate = side.civilDate ?: side.transaction.occurredAt.atZone(ZoneOffset.UTC).toLocalDate(),
                status = status,
            )
        }
        val matchedTransactions = statementLines.mapNotNull { line ->
            if (line.status != StatementComparisonStatus.MATCHED) return@mapNotNull null
            val id = line.ledgerTransactionId ?: return@mapNotNull null
            id to line.ledgerType
        }.distinctBy { it.first }
        val fleet = matchedTransactions.count { (_, type) ->
            type != null && FinancialImpactCalculator.forType(type).countsAsExpense
        }
        val selfTransfers = matchedTransactions.count { it.second == FinancialTransactionType.SELF_TRANSFER }
        val counts = StatementReconciliationCounts(
            matched = statementLines.count { it.status == StatementComparisonStatus.MATCHED },
            statementOnly = statementLines.count { it.status == StatementComparisonStatus.STATEMENT_ONLY },
            ledgerOnly = ledgerLines.count { it.status == StatementComparisonStatus.LEDGER_ONLY },
            ambiguous = statementLines.count { it.status == StatementComparisonStatus.AMBIGUOUS } +
                ledgerLines.count { it.status == StatementComparisonStatus.AMBIGUOUS },
            unsupported = statementLines.count { it.status == StatementComparisonStatus.UNSUPPORTED } +
                ledgerLines.count { it.status == StatementComparisonStatus.UNSUPPORTED },
        )
        return StatementReconciliationReport(
            formatVersion = statement.formatVersion,
            counts = counts,
            statementLines = statementLines,
            ledgerLines = ledgerLines,
            totals = totals(statementLines, ledgerLines, periodByAccount),
            balances = statement.balances,
            balanceCheck = balanceCheck(statement),
            matchedFleetPaymentCount = fleet,
            matchedSelfTransferCount = selfTransfers,
        )
    }

    private fun totals(
        statementLines: List<StatementLineComparison>,
        ledgerLines: List<LedgerLineComparison>,
        periodByAccount: Map<String, Pair<LocalDate, LocalDate>>,
    ): List<StatementCurrencyTotal> {
        val keys = linkedSetOf<TotalKey>()
        statementLines.forEach { keys += TotalKey(it.entry.bank.id, it.entry.accountMasked, it.entry.amount.currency) }
        ledgerLines.forEach { keys += TotalKey(it.bank.id, it.accountMasked, it.currency) }
        return keys.map { key ->
            val period = periodByAccount.getValue(accountKey(Bank.fromId(key.bankId), key.accountMasked))
            val statementForKey = statementLines.filter {
                it.entry.bank.id == key.bankId &&
                    it.entry.accountMasked == key.accountMasked &&
                    it.entry.amount.currency == key.currency
            }
            val ledgerForKey = ledgerLines.filter {
                it.bank.id == key.bankId && it.accountMasked == key.accountMasked && it.currency == key.currency
            }
            StatementCurrencyTotal(
                bankId = key.bankId,
                accountMasked = key.accountMasked,
                periodStart = period.first,
                periodEnd = period.second,
                currency = key.currency,
                matchedCount = statementForKey.count { it.status == StatementComparisonStatus.MATCHED },
                matchedDebit = sum(statementForKey, StatementComparisonStatus.MATCHED, StatementDirection.DEBIT),
                matchedCredit = sum(statementForKey, StatementComparisonStatus.MATCHED, StatementDirection.CREDIT),
                statementOnlyCount = statementForKey.count { it.status == StatementComparisonStatus.STATEMENT_ONLY },
                statementOnlyDebit = sum(statementForKey, StatementComparisonStatus.STATEMENT_ONLY, StatementDirection.DEBIT),
                statementOnlyCredit = sum(statementForKey, StatementComparisonStatus.STATEMENT_ONLY, StatementDirection.CREDIT),
                ledgerOnlyCount = ledgerForKey.count { it.status == StatementComparisonStatus.LEDGER_ONLY },
                ledgerOnlyDebit = sumLedger(ledgerForKey, StatementComparisonStatus.LEDGER_ONLY, StatementDirection.DEBIT),
                ledgerOnlyCredit = sumLedger(ledgerForKey, StatementComparisonStatus.LEDGER_ONLY, StatementDirection.CREDIT),
                ambiguousStatementCount = statementForKey.count { it.status == StatementComparisonStatus.AMBIGUOUS },
                ambiguousStatementDebit = sum(statementForKey, StatementComparisonStatus.AMBIGUOUS, StatementDirection.DEBIT),
                ambiguousStatementCredit = sum(statementForKey, StatementComparisonStatus.AMBIGUOUS, StatementDirection.CREDIT),
                ambiguousLedgerCount = ledgerForKey.count { it.status == StatementComparisonStatus.AMBIGUOUS },
                ambiguousLedgerDebit = sumLedger(ledgerForKey, StatementComparisonStatus.AMBIGUOUS, StatementDirection.DEBIT),
                ambiguousLedgerCredit = sumLedger(ledgerForKey, StatementComparisonStatus.AMBIGUOUS, StatementDirection.CREDIT),
                unsupportedCount = statementForKey.count { it.status == StatementComparisonStatus.UNSUPPORTED } +
                    ledgerForKey.count { it.status == StatementComparisonStatus.UNSUPPORTED },
            )
        }.sortedWith(compareBy({ it.bankId }, { it.accountMasked }, { it.currency.name }))
    }

    private fun sum(
        lines: List<StatementLineComparison>,
        status: StatementComparisonStatus,
        direction: StatementDirection,
    ): BigDecimal {
        val selected = lines.filter { it.status == status && it.entry.direction == direction }
        if (selected.isEmpty()) return scaledZero()
        return selected.fold(Money.zero(selected.first().entry.amount.currency)) { acc, line ->
            acc + line.entry.amount
        }.amount
    }

    private fun sumLedger(
        lines: List<LedgerLineComparison>,
        status: StatementComparisonStatus,
        direction: StatementDirection,
    ): BigDecimal {
        val selected = lines.filter { it.status == status && it.direction == direction }
        if (selected.isEmpty()) return scaledZero()
        return selected.fold(Money.zero(selected.first().currency)) { acc, line ->
            acc + Money.of(line.amount, line.currency)
        }.amount
    }

    private fun scaledZero(): BigDecimal = BigDecimal.ZERO.setScale(Money.SCALE)

    private fun balanceCheck(statement: ParsedBankStatement): StatementComparisonStatus? =
        if (statement.balances.any { it.opening != null || it.closing != null }) {
            StatementComparisonStatus.UNSUPPORTED
        } else {
            null
        }

    private fun inPeriod(date: LocalDate, period: Pair<LocalDate, LocalDate>): Boolean =
        !date.isBefore(period.first) && !date.isAfter(period.second)

    private fun accountKey(bank: Bank, accountMasked: String): String =
        StatementMatchPolicy.accountKey(bank, accountMasked)

    private data class StatementSide(
        val entry: BankStatementEntry,
        val comparable: Boolean,
    )

    private data class LedgerSide(
        val transaction: FinancialTransaction,
        val bank: Bank,
        val accountMasked: String,
        val direction: StatementDirection,
        val civilDate: LocalDate?,
        val comparable: Boolean,
    )

    private data class Edge(
        val statementIndex: Int,
        val ledgerIndex: Int,
    )

    private data class Paired(
        val statementSides: List<StatementSide>,
        val ledgerSides: List<LedgerSide>,
        val matched: Map<Int, Int>,
        val ambiguousStatements: Set<Int>,
        val ambiguousLedgers: Set<Int>,
    )

    private data class TotalKey(
        val bankId: String,
        val accountMasked: String,
        val currency: Currency,
    )
}
