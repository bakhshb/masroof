package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.testsupport.parsedRecord
import com.baraa.masroof.testsupport.tx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class DebitCardOverviewBuilderTest {
    private val zoneId = ZoneId.of("Asia/Riyadh")
    private val salaryPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-11"))

    @Test
    fun madaPosAndCashWithdrawal_sumForLinkedDebitCard() {
        val owned = "account:bank_aljazira:3001"
        val cardId = "card:bank_aljazira:2210"
        val pos = tx(
            id = "pos-mada",
            type = FinancialTransactionType.EXPENSE,
            amount = "120.00",
            source = cardId,
            linked = listOf("evt-pos"),
        )
        val cash = tx(
            id = "cash-mada",
            type = FinancialTransactionType.CASH_WITHDRAWAL,
            amount = "200.00",
            source = owned,
            linked = listOf("evt-cash"),
        )
        val parsedRecords = listOf(
            parsedRecord(
                id = "evt-pos",
                family = MessageFamily.PURCHASE,
                sourceLast4 = "3001",
                cardLast4 = "2210",
                body = "شراء من نقاط البيع\nبطاقة مدى: 2210",
            ),
            parsedRecord(
                id = "evt-cash",
                family = MessageFamily.WITHDRAWAL,
                sourceLast4 = "3001",
                cardLast4 = "2210",
                body = "سحب نقدي\nبطاقة مدى: 2210",
            ),
        )
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "2210",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id,
            linkedAccountMaskedNumber = "3001",
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

        val result = DebitCardOverviewBuilder.buildSpendingByCardKey(
            salaryPeriod = salaryPeriod,
            debitCards = listOf(debit),
            transactions = listOf(pos, cash),
            parsedRecords = parsedRecords,
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            ownedAccountContainerIds = setOf(owned),
            zoneId = zoneId,
        )

        assertEquals(BigDecimal("320.00"), result.spendingByCardKey["BANK_ALJAZIRA:2210"]?.amount)
        assertEquals(setOf("pos-mada", "cash-mada"), result.transactionDebitSpendInvolvement.keys)
    }

    @Test
    fun madaRefund_reducesSalaryPeriodSpending() {
        val owned = "account:bank_aljazira:3001"
        val cardId = "card:bank_aljazira:2210"
        val pos = tx(
            id = "pos-mada",
            type = FinancialTransactionType.EXPENSE,
            amount = "120.00",
            source = cardId,
            linked = listOf("evt-pos"),
        )
        val refund = tx(
            id = "refund-mada",
            type = FinancialTransactionType.REFUND,
            amount = "20.00",
            source = null,
            dest = cardId,
            linked = listOf("evt-refund"),
        )
        val parsedRecords = listOf(
            parsedRecord(
                id = "evt-pos",
                family = MessageFamily.PURCHASE,
                sourceLast4 = "3001",
                cardLast4 = "2210",
                body = "شراء من نقاط البيع\nبطاقة مدى: 2210",
            ),
            parsedRecord(
                id = "evt-refund",
                family = MessageFamily.REFUND,
                sourceLast4 = "3001",
                cardLast4 = "2210",
                body = "استرداد\nبطاقة مدى: 2210",
            ),
        )
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "2210",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id,
            linkedAccountMaskedNumber = "3001",
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

        val result = DebitCardOverviewBuilder.buildSpendingByCardKey(
            salaryPeriod = salaryPeriod,
            debitCards = listOf(debit),
            transactions = listOf(pos, refund),
            parsedRecords = parsedRecords,
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            ownedAccountContainerIds = setOf(owned),
            zoneId = zoneId,
        )

        assertEquals(BigDecimal("100.00"), result.spendingByCardKey["BANK_ALJAZIRA:2210"]?.amount)
        assertEquals(setOf("pos-mada", "refund-mada"), result.transactionDebitSpendInvolvement.keys)
    }

    @Test
    fun internalAtmWithdrawal_hisabRaqam_attributesToLinkedDebitCard() {
        val owned = "account:bank_aljazira:3001"
        val body = """
            سحب نقدي داخلي صراف الي
            بطاقة 8219:مدى
            حساب رقم: 3001
            بمبلغ: SAR 2,200.00
            مكان السحب: جــدة - 7225
            في: 2026-08-02 17:41
        """.trimIndent()
        val cash = tx(
            id = "cash-atm",
            type = FinancialTransactionType.CASH_WITHDRAWAL,
            amount = "2200.00",
            source = owned,
            linked = listOf("evt-cash-atm"),
        )
        val parsedRecords = listOf(
            parsedRecord(
                id = "evt-cash-atm",
                family = MessageFamily.WITHDRAWAL,
                sourceLast4 = "3001",
                cardLast4 = "8219",
                body = body,
            ),
        )
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "8219",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id,
            linkedAccountMaskedNumber = "3001",
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

        val result = DebitCardOverviewBuilder.buildSpendingByCardKey(
            salaryPeriod = salaryPeriod,
            debitCards = listOf(debit),
            transactions = listOf(cash),
            parsedRecords = parsedRecords,
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            ownedAccountContainerIds = setOf(owned),
            zoneId = zoneId,
        )

        assertEquals(BigDecimal("2200.00"), result.spendingByCardKey["BANK_ALJAZIRA:8219"]?.amount)
        assertEquals(setOf("cash-atm"), result.transactionDebitSpendInvolvement.keys)
    }

    @Test
    fun billPaymentOnLinkedAccount_isExcludedFromMadaSpending() {
        val owned = "account:bank_aljazira:3001"
        val billPayment = tx(
            id = "bill-mada",
            type = FinancialTransactionType.BILL_PAYMENT,
            amount = "90.00",
            source = owned,
            linked = listOf("evt-bill"),
        )
        val parsedRecords = listOf(
            parsedRecord(
                id = "evt-bill",
                family = MessageFamily.BILL_PAYMENT,
                sourceLast4 = "3001",
                cardLast4 = "2210",
                body = "سداد فاتورة\nبطاقة مدى: 2210",
            ),
        )
        val debit = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "2210",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.DEBIT,
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id,
            linkedAccountMaskedNumber = "3001",
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

        val result = DebitCardOverviewBuilder.buildSpendingByCardKey(
            salaryPeriod = salaryPeriod,
            debitCards = listOf(debit),
            transactions = listOf(billPayment),
            parsedRecords = parsedRecords,
            primaryCurrency = Currency.SAR,
            sarEquivalents = emptyMap(),
            ownedAccountContainerIds = setOf(owned),
            zoneId = zoneId,
        )

        assertEquals(BigDecimal("0.00"), result.spendingByCardKey["BANK_ALJAZIRA:2210"]?.amount)
        assertTrue(result.transactionDebitSpendInvolvement.isEmpty())
    }
}
