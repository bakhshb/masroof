package com.baraa.masroof.application.dashboard

import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.assembly.TransactionTiming
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object LoanOverviewBuilder {
    fun build(
        salaryPeriod: FinancialPeriod,
        loans: List<LoanRegistryEntry>,
        transactions: List<FinancialTransaction>,
        parsedRecords: List<ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
        primaryCurrency: Currency,
        sarEquivalents: Map<String, Money>,
        zoneId: ZoneId,
        displayLocale: Locale = Locale.forLanguageTag(AppLocale.TAG_AR),
    ): LoansOverview {
        val ownedLoans = loans.filter { it.ownership == OwnershipStatus.OWNED }
        if (ownedLoans.isEmpty()) {
            return LoansOverview(
                loans = emptyList(),
                salaryPeriodLabel = null,
                currency = primaryCurrency,
            )
        }

        val salaryPeriodStart = FinancialPeriodPolicy.toInclusiveStartInstant(salaryPeriod.startDate, zoneId)
        val salaryPeriodEnd = FinancialPeriodPolicy.toExclusiveEndInstant(salaryPeriod.endDateExclusive, zoneId)
        val salaryPeriodLabel = DateTimeFormatter.ofPattern("d MMMM", displayLocale)
            .format(salaryPeriod.startDate)
        val remainingByLoanKey = latestRemainingBalances(
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            zoneId = zoneId,
            asOfExclusive = salaryPeriodEnd,
        )
        val installmentByLoanKey = latestInstallmentAmounts(
            parsedRecords = parsedRecords,
            rawSmsById = rawSmsById,
            zoneId = zoneId,
            asOfExclusive = salaryPeriodEnd,
        )
        val parsedRecordsById = parsedRecords.associateBy { it.event.id }

        val overviews = ownedLoans.map { loan ->
            val loanKey = loanKey(loan.bank.id, loan.loanType)
            var periodPaymentTotal = Money.zero(primaryCurrency)
            transactions.forEach { tx ->
                if (!LoanRepaymentAttribution.matchesLoan(tx, parsedRecordsById, loan.bank, loan.loanType)) {
                    return@forEach
                }
                if (tx.occurredAt.isBefore(salaryPeriodStart) || !tx.occurredAt.isBefore(salaryPeriodEnd)) {
                    return@forEach
                }
                val amount = TransactionAmountResolver.effectiveAmount(
                    tx = tx,
                    primaryCurrency = primaryCurrency,
                    sarEquivalents = sarEquivalents,
                ) ?: return@forEach
                periodPaymentTotal += amount
            }
            val periodPayment = SignedMoneyAmount.of(periodPaymentTotal)
            val remaining = remainingByLoanKey[loanKey]
            val installment = installmentByLoanKey[loanKey]
            LoanOverview(
                bank = loan.bank,
                loanType = loan.loanType,
                displayLabel = loan.displayName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: RegistryDisplayLabels.loanLabel(loan),
                remainingBalance = remaining?.first,
                remainingBalanceAsOf = remaining?.second,
                latestInstallmentAmount = installment?.first,
                latestInstallmentAsOf = installment?.second,
                salaryPeriodPayment = periodPayment,
                salaryPeriodLabel = salaryPeriodLabel,
                customName = loan.displayName?.trim()?.takeIf { it.isNotEmpty() },
            )
        }.sortedBy { it.displayLabel }

        return LoansOverview(
            loans = overviews,
            salaryPeriodLabel = salaryPeriodLabel,
            currency = primaryCurrency,
        )
    }

    private fun loanKey(bankId: String, loanType: LoanType): String =
        "$bankId:${loanType.name}"

    private fun latestRemainingBalances(
        parsedRecords: List<ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
        zoneId: ZoneId,
        asOfExclusive: Instant,
    ): Map<String, Pair<Money, Instant>> {
        val latest = mutableMapOf<String, Pair<Money, Instant>>()
        parsedRecords.forEach { record ->
            val event = record.event
            if (event.messageFamily != MessageFamily.FINANCING_INSTALLMENT) return@forEach
            val loanType = record.details.loanType ?: return@forEach
            val balance = record.details.outstandingBalance ?: return@forEach
            val raw = rawSmsById[event.rawSmsId] ?: return@forEach
            val occurredAt = TransactionTiming.effectiveOccurredAt(
                event = event,
                occurredAtLocal = record.details.occurredAtLocal,
                receivedAt = raw.receivedAt,
                zoneId = zoneId,
            )
            if (!occurredAt.isBefore(asOfExclusive)) return@forEach
            val key = loanKey(event.bank.id, loanType)
            val existing = latest[key]
            if (existing == null || occurredAt.isAfter(existing.second)) {
                latest[key] = balance to occurredAt
            }
        }
        return latest
    }

    private fun latestInstallmentAmounts(
        parsedRecords: List<ParsedEventRecord>,
        rawSmsById: Map<String, RawSms>,
        zoneId: ZoneId,
        asOfExclusive: Instant,
    ): Map<String, Pair<Money, Instant>> {
        val latest = mutableMapOf<String, Pair<Money, Instant>>()
        parsedRecords.forEach { record ->
            val event = record.event
            if (event.messageFamily != MessageFamily.FINANCING_INSTALLMENT) return@forEach
            val loanType = record.details.loanType ?: return@forEach
            val amount = event.amount ?: return@forEach
            val raw = rawSmsById[event.rawSmsId] ?: return@forEach
            val occurredAt = TransactionTiming.effectiveOccurredAt(
                event = event,
                occurredAtLocal = record.details.occurredAtLocal,
                receivedAt = raw.receivedAt,
                zoneId = zoneId,
            )
            if (!occurredAt.isBefore(asOfExclusive)) return@forEach
            val key = loanKey(event.bank.id, loanType)
            val existing = latest[key]
            if (existing == null || occurredAt.isAfter(existing.second)) {
                latest[key] = amount to occurredAt
            }
        }
        return latest
    }
}
