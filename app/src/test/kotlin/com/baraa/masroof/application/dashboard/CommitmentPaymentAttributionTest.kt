package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.testsupport.tx
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CommitmentPaymentAttributionTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val old = FinancialPeriod(LocalDate.parse("2026-07-27"), LocalDate.parse("2026-08-27"))
    private val current = FinancialPeriod(LocalDate.parse("2026-08-27"), LocalDate.parse("2026-09-27"))
    private val future = FinancialPeriod(LocalDate.parse("2026-09-27"), LocalDate.parse("2026-10-27"))
    private fun day(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant()
    private fun commitment(name: String) = Commitment("commitment", name, Money.of("100", Currency.SAR),
        LocalDate.parse("2026-07-28"), CommitmentRecurrence.MONTHLY, null, true, sourceTransactionId = "source",
        createdAt = Instant.parse("2026-07-28T00:00:00Z"), updatedAt = Instant.parse("2026-07-28T00:00:00Z"))
    private fun overview(period: FinancialPeriod, c: Commitment, rows: List<FinancialTransaction>) = CommitmentsOverviewBuilder.build(
        period, listOf(c), CreditFacilitiesOverview(emptyList(), emptyList(), Currency.SAR),
        LoansOverview(emptyList(), null, Currency.SAR), rows, Currency.SAR, emptyMap(), zone)

    @Test fun largerNewPeriodPayment_matchesSourceIdentityAfterDisplayNameEdit_andKeepsHistoryAndFutureExpectation() {
        val source = tx("source", occurredAt = day("2026-07-28"), amount = "100", merchant = "STC")
        val payment = tx("new-payment", occurredAt = day("2026-08-28"), amount = "130", merchant = "STC")
        val c = commitment("فاتورة الهاتف")
        val rows = listOf(source, payment)
        val now = overview(current, c, rows)
        assertEquals(CommitmentPaymentStatus.PAID, now.rows.single().status)
        assertEquals(Money.of("130", Currency.SAR), now.paid)
        assertEquals(payment.id, now.rows.single().paymentTransactionId)
        assertEquals(Money.of("100", Currency.SAR), overview(old, c, rows).paid)
        val next = overview(future, c, rows)
        assertEquals(CommitmentPaymentStatus.UNPAID, next.rows.single().status)
        assertEquals(Money.of("100", Currency.SAR), next.remaining)
        assertEquals(Money.of("100", Currency.SAR), c.amount)
    }

    @Test fun blankMerchant_usesTheNonblankCounterpartyLikeCommitmentCreation() {
        val rows = listOf(tx("source", occurredAt = day("2026-07-28"), amount = "100", merchant = "", counterparty = "Provider"),
            tx("new-payment", occurredAt = day("2026-08-28"), amount = "130", merchant = " ", counterparty = "Provider"))
        assertEquals(CommitmentPaymentStatus.PAID, overview(current, commitment("Provider"), rows).rows.single().status)
    }

    @Test fun oldPaymentNeverPaysTheNewPeriodEvenWhenExpectedAmountChanges() {
        val source = tx("source", occurredAt = day("2026-07-28"), amount = "100", merchant = "STC")
        val c = commitment("STC").copy(amount = Money.of("130", Currency.SAR))
        assertEquals(CommitmentPaymentStatus.UNPAID, overview(current, c, listOf(source)).rows.single().status)
        assertEquals(Money.of("100", Currency.SAR), overview(old, c, listOf(source)).paid)
    }
    @Test fun providerCodesAreAuthoritativeAndDoNotCollapseDifferentBillers() {
        val a = CommitmentPaymentIdentity(setOf("provider"), setOf("001"))
        assertTrue(a.matches(CommitmentPaymentIdentity(setOf("renamed"), setOf("001"))))
        assertFalse(a.matches(CommitmentPaymentIdentity(setOf("provider"), setOf("002"))))
        assertFalse(a.matches(CommitmentPaymentIdentity(setOf("provider"), setOf("001", "002"))))
    }

    @Test fun refundIncomeAndTransfersCannotPayAnExpenseCommitment() {
        val source = tx("source", occurredAt = day("2026-07-28"), amount = "100", merchant = "STC")
        val payment = tx("new-payment", occurredAt = day("2026-08-28"), amount = "130", merchant = "STC")
        for (type in listOf(FinancialTransactionType.INCOME, FinancialTransactionType.REFUND, FinancialTransactionType.SELF_TRANSFER)) {
            assertEquals(CommitmentPaymentStatus.UNPAID,
                overview(current, commitment("STC"), listOf(source, payment.copy(type = type))).rows.single().status)
        }
    }

    /** A commitment groups a merchant the same way the dashboard does, including spacing around punctuation. */
    @Test fun merchantSpacingAroundPunctuation_isTheSameMerchant() {
        val samples = listOf("STC - Pay", "stc-pay", " STC -PAY ", "STC  -  Pay")
        assertEquals(1, samples.map(CommitmentPaymentIdentity::normalize).distinct().size)
        samples.forEach { assertEquals(MerchantNameNormalizer.key(it), CommitmentPaymentIdentity.normalize(it)) }
        assertTrue(CommitmentPaymentIdentity(setOf(CommitmentPaymentIdentity.normalize("STC - Pay")))
            .matches(CommitmentPaymentIdentity(setOf(CommitmentPaymentIdentity.normalize("stc-pay")))))
    }
}
