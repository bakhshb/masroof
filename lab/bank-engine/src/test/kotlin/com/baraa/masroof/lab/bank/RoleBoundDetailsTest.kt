package com.baraa.masroof.lab.bank

import com.baraa.masroof.lab.bank.interpretation.RuleInterpreter
import com.baraa.masroof.lab.bank.rules.RuleLibrary
import com.baraa.masroof.lab.finance.value.BankId
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Annotated synthetic development examples; no parser oracle or general accuracy claim. */
class RoleBoundDetailsTest {
    private val catalog = BankCatalog(listOf(BankDefinition(BankId("SYNTHETIC"), setOf("SyntheticBank"))))
    private val at = "2026-10-17 13:00"
    private val purchase = "Purchase\nAmount: USD 23.00\nCard: 1234\n$at"
    private val cardPayment = "Card payment\nAmount: SAR 100\nCard: 5678\nFrom account: 1234\n$at"
    private val bill = "Bill payment\nAmount: SAR 50\nFrom account: 1234\nBiller: Synthetic Utilities\nBiller code: 001\n$at"
    private val loan = "Loan repayment\nAmount: SAR 500\nFrom account: 1234\nCounterparty: Synthetic Finance\n$at"
    private fun input(body: String) = MessageInput("role-fixture", "SyntheticBank", body, Instant.parse("2026-10-17T10:00:00Z"))
    private fun parse(body: String, schema: BankRuleSchema = RuleLibrary.current()) = RuleInterpreter(schema).interpret(input(body), catalog)

    @Test fun configuredBareSarFeesAreRoleBoundAndNeverBorrowPrincipalCurrency() {
        val bare="$purchase\nرسوم العمليات الدولية: 1.40"
        val r=parse(bare)
        assertEquals(ParseStatus.SUCCESS,r.status)
        assertEquals("SAR",r.facts.internationalFee!!.currency.name)
        assertEquals("USD",r.facts.amount!!.currency.name)
        assertEquals("1.40",r.facts.internationalFee!!.amount.toPlainString())
        val noDefault=RuleLibrary.current().let {it.copy(rules=it.rules.map {rule ->rule.copy(observations=rule.observations-"fee.sar_unlabeled")})}
        assertEquals(ParseStatus.REVIEW_REQUIRED,parse(bare,noDefault).status)
        for(value in listOf("-1.40","1.4001","1.40 USD SAR","1.40 junk")) assertEquals(ParseStatus.REVIEW_REQUIRED,parse("$purchase\nرسوم العمليات الدولية: $value").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED,parse("$purchase\nInternational fee: 1.40").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED,parse("$bare\nرسوم العمليات الدولية: 2.00").status)
    }

    @Test fun secondaryCurrenciesAndExplicitRateNeverConvertOrReplacePrincipal() {
        val body = "$purchase\nDue amount: SAR 900\nForeign amount: USD 23\nInternational fee: SAR 1.99\nExchange rate: 3.756957"
        val r = parse(body)
        assertEquals(ParseStatus.SUCCESS, r.status)
        assertEquals("23.00", r.facts.amount!!.amount.toPlainString())
        assertEquals("USD", r.facts.amount!!.currency.name)
        assertEquals("SAR", r.facts.outstandingBalance!!.currency.name)
        assertEquals("900.00", r.facts.outstandingBalance!!.amount.toPlainString())
        assertEquals("USD", r.facts.labeledForeignAmount!!.currency.name)
        assertEquals("SAR", r.facts.internationalFee!!.currency.name)
        assertEquals("1.99", r.facts.internationalFee!!.amount.toPlainString())
        assertEquals(BigDecimal("3.756957"), r.facts.exchangeRate)
        listOf("dueAmount", "foreignAmount", "internationalFee", "exchangeRate").forEach { field ->
            val span = r.evidence.single { it.field == field }
            assertTrue(span.end <= body.length); assertFalse(span.slice(input(body)).isBlank())
        }
    }

    @Test fun zeroSecondaryMoneyIsKnownButZeroRateAndZeroPrincipalAreRejected() {
        val r = parse("$purchase\nDue amount: SAR 0\nInternational fee: SAR 0")
        assertEquals(ParseStatus.SUCCESS, r.status)
        assertEquals(0, r.facts.outstandingBalance!!.amount.signum())
        assertEquals(0, r.facts.internationalFee!!.amount.signum())
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$purchase\nExchange rate: 0").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(purchase.replace("23.00", "0.00")).status)
        assertNull(parse(purchase).facts.exchangeRate)
        assertNull(parse(purchase).facts.internationalFee)
    }

    @Test fun cardBillAndLoanPaymentsRequireTheirExplicitRoles() {
        listOf(cardPayment to MessageFamily.CARD_PAYMENT, bill to MessageFamily.BILL_PAYMENT,
            loan to MessageFamily.FINANCING_INSTALLMENT).forEach { (body, family) ->
            val r = parse(body)
            assertEquals(body, ParseStatus.SUCCESS, r.status); assertEquals(family, r.facts.family)
            assertEquals("1234", r.facts.sourceAccount!!.maskedNumber)
            assertEquals(MoneyDirection.OUTGOING, r.facts.direction)
            assertNull(r.facts.destinationAccount); assertNull(r.facts.loanType)
        }
        assertEquals("5678", parse(cardPayment).facts.card!!.last4)
        assertEquals("Synthetic Utilities", parse(bill).facts.biller)
        assertEquals("001", parse(bill).facts.billerCode)
        assertEquals("AbC-007", parse(bill.replace("001", "AbC-007")).facts.billerCode)
        assertEquals("Synthetic Finance", parse(loan).facts.counterparty)
        listOf(cardPayment.replace("Card: 5678", ""), cardPayment.replace("From account: 1234", ""),
            bill.replace("Biller: Synthetic Utilities", ""),
            loan.replace("Counterparty: Synthetic Finance", ""), loan.replace("From account: 1234", ""))
            .forEach { assertEquals(it, ParseStatus.REVIEW_REQUIRED, parse(it).status) }
        val noCode = parse(bill.replace("Biller code: 001", ""))
        assertEquals(ParseStatus.SUCCESS, noCode.status)
        assertNull(noCode.facts.billerCode)
    }

    @Test fun dueDateIsInformationalAndNeverSubstitutesForMovementTime() {
        val body = "Balance notification\nDue amount: SAR 900\nPayment due date: 2026-11-01"
        val r = parse(body)
        assertEquals(ParseStatus.NON_FINANCIAL, r.status)
        assertEquals(LocalDate.of(2026, 11, 1), r.facts.paymentDueDate)
        assertNull(r.facts.amount); assertNull(r.facts.occurredAtLocal)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body.replace("2026-11-01", "2026-02-30")).status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$body\nPayment due date: 2026-11-02").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(cardPayment.replace(at, "Payment due date: 2026-11-01")).status)
        // A statement due date beside a quoted card payment is not proof of an executed payment.
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$cardPayment\nPayment due date: 2026-11-01").status)
    }

    @Test fun conflictingOrMalformedSecondaryProofNeverBecomesSuccessfulMoney() {
        listOf("Due amount: SAR 50\nDue amount: USD 50", "Foreign amount: SAR 23",
            "Foreign amount: USD 24", "International fee: SAR 1.99\nFee amount: SAR 2.00",
            "International fee: JPY 20", "International fee: SAR -1", "International fee: SAR 1-",
            "International fee: SAR 1.999", "Due amount: SAR 1,23", "Foreign amount: USD 23 plus tax",
            "Exchange rate: -3.75", "Exchange rate: NaN", "Exchange rate: 3e2",
            "Exchange rate: 1.12345678901", "Exchange rate: 3.75\nExchange rate: 3.76",
            "Due amount:", "Biller code: 1e+2", "Exchange rate=0", "Foreign amount=USD 23",
            "Due amount", "International fee=NaN").forEach { detail ->
            val r = parse("$purchase\n$detail")
            assertEquals(detail, ParseStatus.REVIEW_REQUIRED, r.status)
            assertNull(r.facts.executionState)
        }
    }

    @Test fun roleLinesAndSecondaryReservationPreventPrincipalInjection() {
        val noPrincipal = purchase.replace("Amount: USD 23.00", "")
        listOf("Foreign amount: USD 23", "Due amount: SAR 23", "Fee amount: SAR 23",
            "Merchant: Amount: USD 23", "Transaction reference: Amount: USD 23").forEach { detail ->
            val r = parse("$noPrincipal\n$detail")
            assertEquals(detail, ParseStatus.REVIEW_REQUIRED, r.status)
            assertNull(r.facts.amount)
        }
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$noPrincipal\nForeign amount: USD 23", RuleLibrary.initial()).status)
        val r = parse("$purchase\nMerchant: Due amount: SAR 999")
        assertEquals(ParseStatus.SUCCESS, r.status)
        assertNull(r.facts.outstandingBalance)
        assertEquals("Due amount: SAR 999", r.facts.merchant)
    }

    @Test fun referenceAndPartyFactsPreserveOriginalUnicodeEvidence() {
        val body = "😀\r\nسداد فاتورة\r\nبمبلغ: ٥٠ SAR\r\nمن حساب: ١٢٣٤\r\nالمفوتر: شركة أصليّة\r\nرمز المفوتر: ٠٠١\r\nرقم المعاملة: REF-007\r\n٢٠٢٦-١٠-١٧ ١٣:٠٠"
        val r = parse(body)
        assertEquals(ParseStatus.SUCCESS, r.status)
        assertEquals("شركة أصليّة", r.facts.biller)
        assertEquals("001", r.facts.billerCode)
        assertEquals("REF-007", r.facts.transactionReference)
        assertTrue(r.evidence.any { it.field == "biller" && it.slice(input(body)) == "المفوتر: شركة أصليّة" })
        assertTrue(r.evidence.any { it.field == "billerCode" && it.slice(input(body)) == "رمز المفوتر: ٠٠١" })
        assertTrue(r.evidence.all { it.start >= 0 && it.end <= body.length })
    }

    @Test fun duplicateEquivalentFactsAreAcceptedAndDifferentPartiesAreHeld() {
        val r = parse("$purchase\nExchange rate: 3.750\nExchange rate: 3.75\nDue amount: SAR 10\nDue amount: 10.00 SAR")
        assertEquals(ParseStatus.SUCCESS, r.status)
        assertEquals(BigDecimal("3.75"), r.facts.exchangeRate)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$bill\nBiller: Another Utility").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$loan\nCounterparty: Other Finance").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$bill\nTransaction reference: A\nTransaction reference: B").status)
    }

    @Test fun executionSecuritySenderAndQuoteGuardsApplyToNewFamilies() {
        listOf(cardPayment, bill, loan).forEach { body ->
            assertEquals(ParseStatus.NON_FINANCIAL, parse("OTP 123456\n$body").status)
            assertEquals(ParseStatus.REVIEW_REQUIRED, parse("Declined\n$body").status)
            assertEquals(ParseStatus.REVIEW_REQUIRED, parse("```text\n$body\n```").status)
            val r = RuleInterpreter(RuleLibrary.current()).interpret(input(body).copy(sender = "Unrecognized"), catalog)
            assertTrue(Blocker.UNKNOWN_BANK in r.blockers)
        }
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$bill\nLoan repayment").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$purchase\nIncoming transfer").status)
        val unusedNetwork = parse(purchase, RuleLibrary.current().copy(network = NetworkRules(networkLabels = listOf("network"))))
        assertEquals(ParseStatus.SUCCESS, unusedNetwork.status)
        assertNull(unusedNetwork.facts.bankNetwork)
    }

    @Test fun aConfiguredLabelCannotAssignOneSpanToDifferentFieldRoles() {
        val current = RuleLibrary.current()
        val aliased = current.copy(rules = current.rules.map { it.copy(detailLabels = it.detailLabels + mapOf(
            "dueAmount" to listOf("secondary"), "foreignAmount" to listOf("secondary"))) })
        val r = parse("$purchase\nSecondary: USD 23", aliased)
        assertEquals(ParseStatus.REVIEW_REQUIRED, r.status)
        assertTrue(Blocker.CONFLICTING_FACTS in r.blockers)
        val financialRole = current.copy(rules = current.rules.map { it.copy(detailLabels = it.detailLabels +
            ("counterparty" to listOf("amount"))) })
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(loan.replace("Counterparty: Synthetic Finance", ""), financialRole).status)
    }
}
