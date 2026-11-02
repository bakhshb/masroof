package com.baraa.masroof.application.dashboard

import com.baraa.masroof.application.commitment.CommitmentPauseTransitions
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

enum class CommitmentPaymentStatus {
    PAID,
    UNPAID,
}

enum class CommitmentDashboardSource {
    USER,
    CREDIT_CARD,
    LOAN,
}

data class CommitmentDashboardRow(
    val key: String,
    val source: CommitmentDashboardSource,
    val displayName: String,
    val amount: Money,
    val expectedDate: LocalDate?,
    val status: CommitmentPaymentStatus,
    val userCommitmentId: String? = null,
    val creditFacilityKey: String? = null,
    val loanKey: String? = null,
    val paymentTransactionId: String? = null,
    val paymentMatchIssue: CommitmentMatchIssue? = null,
    /** Set for a loan without a user name, so screens show its localized type instead of [displayName]. */
    val loanTypeFallback: LoanType? = null,
)

data class CommitmentsOverview(
    val rows: List<CommitmentDashboardRow>,
    val total: Money,
    val paid: Money,
    val remaining: Money,
    val currency: Currency,
) {
    val hasContent: Boolean get() = rows.isNotEmpty()

    companion object {
        fun empty(currency: Currency = Currency.SAR): CommitmentsOverview {
            val zero = Money.zero(currency)
            return CommitmentsOverview(
                rows = emptyList(),
                total = zero,
                paid = zero,
                remaining = zero,
                currency = currency,
            )
        }
    }
}

object CommitmentsOverviewBuilder {
    fun build(
        salaryPeriod: FinancialPeriod,
        commitments: List<Commitment>,
        creditFacilities: CreditFacilitiesOverview,
        loansOverview: LoansOverview,
        transactions: List<FinancialTransaction>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        zoneId: ZoneId,
        paymentIdentities: Map<String, CommitmentPaymentIdentity> = emptyMap(),
    ): CommitmentsOverview {
        val sources = transactions.associateBy { it.id }
        val visibleCommitments = commitments.filter { isCommitmentVisibleInPeriod(it, salaryPeriod, zoneId) }
            .filter { occurrencesInPeriod(it, salaryPeriod).isNotEmpty() }.sortedBy { it.id }
        val start = FinancialPeriodPolicy.toInclusiveStartInstant(salaryPeriod.startDate, zoneId)
        val end = FinancialPeriodPolicy.toExclusiveEndInstant(salaryPeriod.endDateExclusive, zoneId)
        val owners = transactions.filter { it.occurredAt >= start && it.occurredAt < end }.associate { tx ->
            val matching = visibleCommitments.filter { c -> matchesCommitmentPayment(tx, c, primaryCurrency, sarEquivalents,
                source = sources[c.sourceTransactionId], identities = paymentIdentities) }
            val exact = matching.filter { it.sourceTransactionId == tx.id }
            tx.id to exact.ifEmpty { matching }
        }
        val rows = buildList {
            visibleCommitments.forEach { commitment ->
                val allowed = owners.filterValues { it.size == 1 && it.single().id == commitment.id }.keys
                val ambiguous = owners.values.any { it.size > 1 && it.any { c -> c.id == commitment.id } }
                addAll(buildUserRows(commitment, salaryPeriod, transactions, primaryCurrency, sarEquivalents, zoneId,
                    allowed, ambiguous, commitment.sourceTransactionId in sources))
            }
            addAll(
                buildCreditCardRows(
                    creditFacilities = creditFacilities,
                    salaryPeriod = salaryPeriod,
                    transactions = transactions,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                    zoneId = zoneId,
                ),
            )
            addAll(buildLoanRows(loansOverview))
        }.sortedBy { it.displayName.lowercase() }

        if (rows.isEmpty()) {
            return CommitmentsOverview.empty(primaryCurrency)
        }

        var total = BigDecimal.ZERO.setScale(Money.SCALE, RoundingMode.HALF_EVEN)
        var paid = BigDecimal.ZERO.setScale(Money.SCALE, RoundingMode.HALF_EVEN)
        rows.forEach { row ->
            total = total.add(row.amount.amount)
            if (row.status == CommitmentPaymentStatus.PAID) {
                paid = paid.add(row.amount.amount)
            }
        }
        val remaining = total.subtract(paid).setScale(Money.SCALE, RoundingMode.HALF_EVEN)
        val totalMoney = Money(total, primaryCurrency)
        val paidMoney = Money(paid, primaryCurrency)
        return CommitmentsOverview(
            rows = rows,
            total = totalMoney,
            paid = paidMoney,
            remaining = Money(remaining, primaryCurrency),
            currency = primaryCurrency,
        )
    }

    private fun buildUserRows(
        commitment: Commitment,
        salaryPeriod: FinancialPeriod,
        transactions: List<FinancialTransaction>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        zoneId: ZoneId,
        allowedPaymentIds: Set<String>,
        ambiguous: Boolean,
        hasSource: Boolean,
    ): List<CommitmentDashboardRow> {
        val occurrences = occurrencesInPeriod(commitment, salaryPeriod)
        if (occurrences.isEmpty()) return emptyList()
        val expectedAmount = resolveMoney(
            amount = commitment.amount,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            sourceTransactionId = commitment.sourceTransactionId,
            transactions = transactions,
        ) ?: return emptyList()
        val matchedPayments = collectMatchingPayments(
            salaryPeriod = salaryPeriod,
            transactions = transactions,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
            zoneId = zoneId,
            allowedPaymentIds = allowedPaymentIds,
        )
        val paidOccurrenceCount = matchedPayments.size
        // Recurring payments may exceed the scheduled occurrences within one salary period.
        val rowCount = if (commitment.recurrence == null) {
            occurrences.size
        } else {
            maxOf(occurrences.size, matchedPayments.size)
        }
        return List(rowCount) { index ->
            val occurrence = occurrences.getOrNull(index)
            val isPaid = index < paidOccurrenceCount
            val matchedPayment = matchedPayments.getOrNull(index)
            val rowAmount = if (isPaid && matchedPayment != null) {
                matchedPayment.amount
            } else {
                expectedAmount
            }
            val rowDate = if (isPaid && matchedPayment != null) {
                matchedPayment.occurredAt.atZone(zoneId).toLocalDate()
            } else {
                commitment.dueDate ?: occurrence
            }
            CommitmentDashboardRow(
                key = if (matchedPayment != null) {
                    "user:${commitment.id}:payment:${matchedPayment.transactionId}"
                } else {
                    "user:${commitment.id}:$occurrence"
                },
                source = CommitmentDashboardSource.USER,
                displayName = commitment.name,
                amount = rowAmount,
                expectedDate = rowDate,
                status = if (isPaid) {
                    CommitmentPaymentStatus.PAID
                } else {
                    CommitmentPaymentStatus.UNPAID
                },
                userCommitmentId = commitment.id,
                paymentTransactionId = matchedPayment?.transactionId,
                paymentMatchIssue = when {
                    isPaid -> null
                    ambiguous -> CommitmentMatchIssue.AMBIGUOUS_PAYMENT
                    !hasSource -> CommitmentMatchIssue.MISSING_SOURCE
                    else -> null
                },
            )
        }
    }

    private fun buildCreditCardRows(
        creditFacilities: CreditFacilitiesOverview,
        salaryPeriod: FinancialPeriod,
        transactions: List<FinancialTransaction>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        zoneId: ZoneId,
    ): List<CommitmentDashboardRow> =
        creditFacilities.facilities.mapNotNull { facility ->
            val due = facility.facilityDue ?: return@mapNotNull null
            if (!isStatementDueInPeriod(due, salaryPeriod, zoneId)) return@mapNotNull null
            val paid = hasSatisfiedFacilityDue(
                facility = facility,
                due = due,
                transactions = transactions,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
            )
            CommitmentDashboardRow(
                key = "credit:${facility.bank.id}:${facility.primaryLast4}",
                source = CommitmentDashboardSource.CREDIT_CARD,
                displayName = facility.primaryLast4,
                amount = due.amount,
                expectedDate = due.dueDate,
                status = if (paid) CommitmentPaymentStatus.PAID else CommitmentPaymentStatus.UNPAID,
                creditFacilityKey = "${facility.bank.id}:${facility.primaryLast4}",
            )
        }

    internal fun isStatementDueInPeriod(
        due: StatementDueSnapshot,
        salaryPeriod: FinancialPeriod,
        zoneId: ZoneId,
    ): Boolean {
        val dueDate = due.dueDate ?: due.updatedAt.atZone(zoneId).toLocalDate()
        return isDateInPeriod(dueDate, salaryPeriod)
    }

    private fun hasSatisfiedFacilityDue(
        facility: CreditFacilityOverview,
        due: StatementDueSnapshot,
        transactions: List<FinancialTransaction>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
    ): Boolean {
        val paid = transactions.fold(Money.zero(primaryCurrency)) { total, transaction ->
            if (!facility.isPaymentToward(due, transaction)) {
                total
            } else {
                val amount = TransactionAmountResolver.effectiveAmount(
                    tx = transaction,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                )
                if (amount == null) total else total + amount
            }
        }
        return paid.amount >= due.amount.amount
    }

    private fun buildLoanRows(
        loansOverview: LoansOverview,
    ): List<CommitmentDashboardRow> =
        loansOverview.loans.mapNotNull { loan ->
            val paid = loan.salaryPeriodPayment.amount.signum() > 0
            if (!paid && loan.remainingBalance?.amount?.signum() == 0) {
                return@mapNotNull null
            }
            val amount = when {
                paid -> Money(loan.salaryPeriodPayment.amount, loan.salaryPeriodPayment.currency)
                loan.latestInstallmentAmount != null -> loan.latestInstallmentAmount
                else -> return@mapNotNull null
            }
            CommitmentDashboardRow(
                key = "loan:${loan.bank.id}:${loan.loanType.name}",
                source = CommitmentDashboardSource.LOAN,
                displayName = loan.displayLabel,
                amount = amount,
                expectedDate = null,
                status = if (paid) CommitmentPaymentStatus.PAID else CommitmentPaymentStatus.UNPAID,
                loanKey = "${loan.bank.id}:${loan.loanType.name}",
                loanTypeFallback = loan.loanType.takeIf { loan.customName == null },
            )
        }

    internal fun isCommitmentVisibleInPeriod(
        commitment: Commitment,
        salaryPeriod: FinancialPeriod,
        zoneId: ZoneId,
    ): Boolean = CommitmentPauseTransitions.isVisibleInSalaryPeriod(commitment, salaryPeriod, zoneId)

    internal fun occurrencesInPeriod(
        commitment: Commitment,
        salaryPeriod: FinancialPeriod,
    ): List<LocalDate> {
        val anchor = commitment.transactionDate
        return when (commitment.recurrence) {
            null -> listOf(anchor).filter { isDateInPeriod(it, salaryPeriod) }
            CommitmentRecurrence.WEEKLY -> buildList {
                var occurrence = anchor
                while (occurrence.isBefore(salaryPeriod.startDate)) {
                    occurrence = occurrence.plusWeeks(1)
                }
                while (isDateInPeriod(occurrence, salaryPeriod)) {
                    add(occurrence)
                    occurrence = occurrence.plusWeeks(1)
                }
            }
            CommitmentRecurrence.MONTHLY -> occurrencesForMonths(
                anchor = anchor,
                start = salaryPeriod.startDate,
                endExclusive = salaryPeriod.endDateExclusive,
                monthStep = 1,
            )
            CommitmentRecurrence.YEARLY -> occurrencesForMonths(
                anchor = anchor,
                start = salaryPeriod.startDate,
                endExclusive = salaryPeriod.endDateExclusive,
                monthStep = 12,
            )
        }
    }

    private fun occurrencesForMonths(
        anchor: LocalDate,
        start: LocalDate,
        endExclusive: LocalDate,
        monthStep: Long,
    ): List<LocalDate> = buildList {
        var month = YearMonth.from(start)
        val endMonth = YearMonth.from(endExclusive.minusDays(1))
        while (!month.isAfter(endMonth)) {
            val monthOffset = (month.year - anchor.year) * 12L + month.monthValue - anchor.monthValue
            if (monthOffset >= 0 && monthOffset % monthStep == 0L) {
                val occurrence = month.atDay(minOf(anchor.dayOfMonth, month.lengthOfMonth()))
                if (isDateInPeriod(occurrence, FinancialPeriod(start, endExclusive))) {
                    add(occurrence)
                }
            }
            month = month.plusMonths(1)
        }
    }

    private fun isDateInPeriod(
        date: LocalDate,
        salaryPeriod: FinancialPeriod,
    ): Boolean {
        val anchorDate = date
        return !anchorDate.isBefore(salaryPeriod.startDate) &&
            anchorDate.isBefore(salaryPeriod.endDateExclusive)
    }

    private data class MatchedCommitmentPayment(
        val transactionId: String,
        val occurredAt: Instant,
        val amount: Money,
    )

    private fun collectMatchingPayments(
        salaryPeriod: FinancialPeriod,
        transactions: List<FinancialTransaction>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        zoneId: ZoneId,
        allowedPaymentIds: Set<String>,
    ): List<MatchedCommitmentPayment> {
        val periodStart = FinancialPeriodPolicy.toInclusiveStartInstant(salaryPeriod.startDate, zoneId)
        val periodEnd = FinancialPeriodPolicy.toExclusiveEndInstant(salaryPeriod.endDateExclusive, zoneId)
        val matched = mutableListOf<MatchedCommitmentPayment>()
        transactions.forEach { tx ->
            if (tx.occurredAt < periodStart || tx.occurredAt >= periodEnd) return@forEach
            if (tx.id !in allowedPaymentIds) return@forEach
            val paymentAmount = TransactionAmountResolver.effectiveAmount(
                tx = tx,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
            ) ?: return@forEach
            matched.add(MatchedCommitmentPayment(transactionId = tx.id, occurredAt = tx.occurredAt, amount = paymentAmount))
        }
        return matched.sortedBy { it.occurredAt }
    }

    internal fun matchesCommitmentPayment(
        transaction: FinancialTransaction,
        commitment: Commitment,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        source: FinancialTransaction? = null,
        identities: Map<String, CommitmentPaymentIdentity> = emptyMap(),
    ): Boolean {
        if (
            transaction.type != FinancialTransactionType.EXPENSE &&
            transaction.type != FinancialTransactionType.FEE &&
            transaction.type != FinancialTransactionType.BILL_PAYMENT
        ) {
            return false
        }
        val txAmount = TransactionAmountResolver.effectiveAmount(
            tx = transaction,
            primaryCurrency = primaryCurrency,
            sarEquivalents = sarEquivalents,
        ) ?: return false
        if (txAmount.amount.signum() <= 0) return false
        if (transaction.id == commitment.sourceTransactionId) return true
        val expected = identities[commitment.sourceTransactionId]
            ?: source?.let(CommitmentPaymentIdentity::from)
            ?: CommitmentPaymentIdentity(setOf(CommitmentPaymentIdentity.normalize(commitment.name)).filter(String::isNotEmpty).toSet())
        val actual = identities[transaction.id] ?: CommitmentPaymentIdentity.from(transaction)
        return expected.matches(actual)
    }

    private fun resolveMoney(
        amount: Money,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        sourceTransactionId: String,
        transactions: List<FinancialTransaction>,
    ): Money? {
        if (amount.currency == primaryCurrency) {
            return amount
        }
        val sourceTx = transactions.find { it.id == sourceTransactionId }
        if (sourceTx != null) {
            if (sourceTx.amount.currency != amount.currency) return null
            val sourceSar = TransactionAmountResolver.effectiveAmount(
                tx = sourceTx,
                primaryCurrency = primaryCurrency,
                sarEquivalents = sarEquivalents,
            ) ?: sarEquivalents[sourceTransactionId] ?: return null
            if (sourceTx.amount.amount.signum() == 0) return null
            val ratio = amount.amount.divide(
                sourceTx.amount.amount,
                Money.SCALE,
                RoundingMode.HALF_EVEN,
            )
            return Money(
                sourceSar.amount.multiply(ratio).setScale(Money.SCALE, RoundingMode.HALF_EVEN),
                primaryCurrency,
            )
        }
        return sarEquivalents[sourceTransactionId]
    }
}
