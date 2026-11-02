package com.baraa.masroof.application.dashboard

import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.period.CreditCardStatementPolicy
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.model.isCreditCardSms
import com.baraa.masroof.parsing.model.isStatementSms
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds all credit-card rows with statement-cycle and salary-period spending per card.
 */
object CreditCardOverviewBuilder {
    private fun dayMonthFormatter(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMMM", locale)

    fun resolveStatementSpendingStart(
        parsedRecords: List<ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
        zoneId: ZoneId,
        periodEndExclusive: Instant,
    ): Instant = statementWindows(parsedRecords, rawSmsById, zoneId, periodEndExclusive).earliestStart

    fun build(
        salaryPeriod: FinancialPeriod,
        cardTransactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
        zoneId: ZoneId,
        primaryCurrency: Currency = Currency.SAR,
        sarEquivalents: Map<String, Money> = emptyMap(),
        displayLocale: Locale = Locale.forLanguageTag(AppLocale.TAG_AR),
    ): CreditCardsOverview {
        val dayMonth = dayMonthFormatter(displayLocale)
        val creditCardMeta = mutableMapOf<String, CardReference>()
        val snapshotCandidates = mutableListOf<SnapshotCandidate>()

        for (record in parsedRecords) {
            val event = record.event
            val cardRef = event.cardRef ?: continue
            val cardId = FinancialContainerIdFactory.cardId(cardRef) ?: continue
            val raw = rawSmsById[event.rawSmsId] ?: continue
            val details = record.details
            if (!details.isCreditCardSms()) continue

            creditCardMeta[cardId] = cardRef
            val at = event.occurredAt ?: raw.receivedAt
            val isStatement = details.isStatementSms()
            if (
                isStatement ||
                details.availableBalance != null ||
                details.outstandingBalance != null
            ) {
                snapshotCandidates += SnapshotCandidate(
                    cardId = cardId,
                    cardRef = cardRef,
                    updatedAt = at,
                    details = details,
                    isStatement = isStatement,
                    dueDate = details.paymentDueDate,
                )
            }
        }

        val periodEndExclusive = FinancialPeriodPolicy.toExclusiveEndInstant(
            salaryPeriod.endDateExclusive,
            zoneId,
        )
        val windows = statementWindows(parsedRecords, rawSmsById, zoneId, periodEndExclusive)
        val salaryPeriodStart = FinancialPeriodPolicy.toInclusiveStartInstant(salaryPeriod.startDate, zoneId)
        val salaryPeriodLabel = dayMonth.format(salaryPeriod.startDate)

        val latestStatementByCard = snapshotCandidates
            .filter { it.isStatement && it.updatedAt.isBefore(periodEndExclusive) }
            .groupBy { it.cardId }
            .mapValues { (_, candidates) -> candidates.maxBy { it.updatedAt } }

        val latestAvailableByCard = snapshotCandidates
            .filter {
                !it.isStatement &&
                    it.details.availableBalance != null &&
                    it.updatedAt.isBefore(periodEndExclusive)
            }
            .groupBy { it.cardId }
            .mapValues { (_, candidates) -> candidates.maxBy { it.updatedAt } }

        val cardIds = creditCardMeta.keys.toSet()

        val rows = cardIds.map { cardId ->
            val ref = creditCardMeta[cardId]
                ?: latestStatementByCard[cardId]?.cardRef
                ?: latestAvailableByCard[cardId]?.cardRef
                ?: parseCardId(cardId)
            val cardStatementStart = windows.start(ref.bank, latestStatementByCard[cardId]?.updatedAt)
            val statementLabel = dayMonth.format(cardStatementStart.atZone(zoneId).toLocalDate())

            val statementNet = netSpending(
                transactions = cardTransactions,
                cardId = cardId,
                startInclusive = cardStatementStart,
                endExclusive = periodEndExclusive,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
            )
            val salaryNet = netSpending(
                transactions = cardTransactions,
                cardId = cardId,
                startInclusive = salaryPeriodStart,
                endExclusive = periodEndExclusive,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
            )

            CreditCardDashboardRow(
                bank = ref.bank,
                last4 = ref.last4.orEmpty(),
                statementSpendingNet = statementNet,
                salaryPeriodSpendingNet = salaryNet,
                statementPeriodLabel = statementLabel,
                snapshot = buildCardSnapshot(
                    latestAvailable = latestAvailableByCard[cardId],
                    latestStatement = latestStatementByCard[cardId],
                ),
            )
        }.sortedBy { it.last4 }

        val latestStatement = snapshotCandidates
            .filter { it.isStatement && it.updatedAt.isBefore(periodEndExclusive) }
            .maxByOrNull { it.updatedAt }

        val aggregateStatementLabel = latestStatement?.updatedAt?.let {
            dayMonth.format(it.atZone(zoneId).toLocalDate())
        } ?: dayMonth.format((windows.byBank.values.maxOrNull() ?: windows.cycleStart).atZone(zoneId).toLocalDate())

        return CreditCardsOverview(
            cards = rows,
            aggregateStatementPeriodLabel = aggregateStatementLabel,
            salaryPeriodLabel = salaryPeriodLabel,
            currency = primaryCurrency,
        )
    }

    /**
     * Statement window starts. A card uses its own latest statement, else its bank's latest statement
     * (a statement may name no card), else the policy cycle; never another bank's. Statements older than
     * [STALE_STATEMENT_CYCLES] cycles before the current one are stale (a closed or replaced card) and are ignored,
     * which also bounds the dashboard's card-window read.
     */
    private class StatementWindows(val cycleStart: Instant, val floor: Instant, val byBank: Map<Bank, Instant>,
        val byCard: Collection<Instant>) {
        fun start(bank: Bank, ownStatement: Instant?): Instant =
            ownStatement?.takeIf { !it.isBefore(floor) } ?: byBank[bank] ?: cycleStart
        val earliestStart: Instant get() = (byBank.values + byCard + cycleStart).min()
    }

    private const val STALE_STATEMENT_CYCLES = 3L

    private fun statementWindows(
        parsedRecords: List<ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
        zoneId: ZoneId,
        periodEndExclusive: Instant,
    ): StatementWindows {
        val cycle = CreditCardStatementPolicy.statementCycleStartOnOrBefore(
            periodEndExclusive.atZone(zoneId).toLocalDate().minusDays(1))
        val floor = cycle.minusMonths(STALE_STATEMENT_CYCLES).atStartOfDay(zoneId).toInstant()
        val statements = parsedRecords.mapNotNull { record ->
            if (!record.details.isStatementSms()) return@mapNotNull null
            val raw = rawSmsById[record.event.rawSmsId] ?: return@mapNotNull null
            val at = record.event.occurredAt ?: raw.receivedAt
            if (!at.isBefore(periodEndExclusive) || at.isBefore(floor)) return@mapNotNull null
            record.event to at
        }
        return StatementWindows(cycle.atStartOfDay(zoneId).toInstant(), floor,
            statements.groupBy({ it.first.bank }, { it.second }).mapValues { it.value.max() },
            statements.filter { it.first.cardRef != null }.groupBy({ it.first.cardRef }, { it.second }).values.map { it.max() })
    }

    private fun netSpending(
        transactions: List<FinancialTransaction>,
        cardId: String,
        startInclusive: Instant,
        endExclusive: Instant,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
    ): SignedMoneyAmount {
        var gross = Money.zero(primaryCurrency)
        var refund = Money.zero(primaryCurrency)
        for (tx in transactions) {
            if (tx.occurredAt.isBefore(startInclusive)) continue
            if (!tx.occurredAt.isBefore(endExclusive)) continue
            val amount = when {
                tx.amount.currency == primaryCurrency -> tx.amount
                else -> sarEquivalents[tx.id] ?: continue
            }
            when (tx.type) {
                FinancialTransactionType.EXPENSE,
                FinancialTransactionType.FEE,
                -> {
                    if (tx.sourceContainerId != cardId) continue
                    gross += amount
                }

                FinancialTransactionType.REFUND -> {
                    if (tx.destinationContainerId != cardId) continue
                    refund += amount
                }

                else -> Unit
            }
        }
        return SignedMoneyAmount.difference(gross, refund)
    }

    private fun buildCardSnapshot(
        latestAvailable: SnapshotCandidate?,
        latestStatement: SnapshotCandidate?,
    ): CreditCardBalanceSnapshot? {
        if (latestAvailable == null && latestStatement == null) return null

        val updatedAt = listOfNotNull(
            latestAvailable?.updatedAt,
            latestStatement?.updatedAt,
        ).maxOrNull() ?: return null

        return CreditCardBalanceSnapshot(
            availableBalance = latestAvailable?.details?.availableBalance,
            // Statement SMS only — purchase/refund "إجمالي المبلغ المستحق" is account-level
            // and repeats across linked cards on the same credit facility.
            dueAmount = latestStatement?.details?.outstandingBalance,
            dueDate = latestStatement?.dueDate,
            statementIssuedAt = latestStatement?.updatedAt,
            updatedAt = updatedAt,
        )
    }

    private fun parseCardId(cardId: String): CardReference {
        val parts = cardId.split(":")
        val last4 = parts.getOrNull(2).orEmpty()
        val bankId = parts.getOrNull(1).orEmpty()
        val bank = Bank.fromId(bankId)
        return CardReference(bank = bank, last4 = last4)
    }

    private data class SnapshotCandidate(
        val cardId: String,
        val cardRef: CardReference,
        val updatedAt: Instant,
        val details: ParsedEventDetails,
        val isStatement: Boolean,
        val dueDate: LocalDate?,
    )
}
