package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import java.time.Instant
import java.time.LocalDate

data class CreditCardBalanceSnapshot(
    val availableBalance: Money?,
    val dueAmount: Money?,
    val dueDate: LocalDate?,
    val statementIssuedAt: Instant?,
    val updatedAt: Instant,
    /** When [availableBalance] was read; null when no available-balance message exists. */
    val availableBalanceAt: Instant? = null,
)

data class CreditCardDashboardRow(
    val bank: Bank,
    val last4: String,
    val statementSpendingNet: SignedMoneyAmount,
    val salaryPeriodSpendingNet: SignedMoneyAmount,
    val statementPeriodLabel: String?,
    val snapshot: CreditCardBalanceSnapshot?,
)

/**
 * All credit cards with statement-cycle and salary-period spending.
 */
data class CreditCardsOverview(
    val cards: List<CreditCardDashboardRow>,
    val aggregateStatementPeriodLabel: String?,
    val salaryPeriodLabel: String?,
    val currency: Currency,
) {
    val hasContent: Boolean
        get() = cards.isNotEmpty()
}

/**
 * Latest statement due among [cards] — one value for linked cards on the same credit facility.
 * Ignores purchase/refund SMS outstanding balances.
 */
fun resolveLatestStatementDue(cards: List<CreditCardDashboardRow>): StatementDueSnapshot? =
    cards.mapNotNull { row ->
        val snap = row.snapshot ?: return@mapNotNull null
        val amount = snap.dueAmount ?: return@mapNotNull null
        val issuedAt = snap.statementIssuedAt ?: return@mapNotNull null
        StatementDueSnapshot(amount = amount, updatedAt = issuedAt, dueDate = snap.dueDate)
    }.maxByOrNull { it.updatedAt }

data class StatementDueSnapshot(
    val amount: Money,
    val updatedAt: Instant,
    val dueDate: LocalDate?,
)

/** Keep the amount and date from one statement; never borrow an older date. */
fun CreditCardDashboardRow.statementDueFor(facility: CreditFacilityOverview? = null): StatementDueSnapshot? {
    val matchingFacility = facility?.takeIf { candidate ->
        candidate.bank == bank && candidate.allCards.any { it.bank == bank && it.last4 == last4 }
    }
    return matchingFacility?.facilityDue ?: resolveLatestStatementDue(listOf(this))
}

internal fun sumCreditCardSpending(
    rows: List<CreditCardDashboardRow>,
    selector: (CreditCardDashboardRow) -> SignedMoneyAmount,
): SignedMoneyAmount =
    if (rows.isEmpty()) SignedMoneyAmount.zero(Currency.SAR) else SignedMoneyAmount(
        rows.sumOf { selector(it).amount }.setScale(Money.SCALE, java.math.RoundingMode.HALF_EVEN),
        rows.first().statementSpendingNet.currency,
    )
