package com.baraa.masroof.engine.bank

import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.rules.RuleLibrary
import com.baraa.masroof.finance.value.BankId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class StatementAuthorityTest {
    private fun parse(body: String) = RuleInterpreter(RuleLibrary.current()).interpretRouted(MessageInput("m8", "Sender", body, Instant.EPOCH), BankId("TEST"))
    @Test fun colonStatementTitleHasOriginalStatementProofAndPartyTextCannotSupplyIt() {
        val body = "بطاقة إئتمانية: إصدار كشف حساب\nبطاقة: 7271 بطاقة إئتمانية\nإجمالي المبلغ المستحق: SAR 1250.00\nتاريخ الاستحقاق: 2026-08-25"
        val result = parse(body)
        assertEquals(ParseStatus.NON_FINANCIAL, result.status)
        assertEquals(CardChannel.STATEMENT, result.facts.cardChannel)
        val span = result.evidence.single { it.field == "observation.card.statement" }
        assertTrue(body.substring(span.start, span.end).contains("كشف"))
        val unbound = parse("Login notification\nMerchant: credit card statement\nCard: 7271")
        assertNull(unbound.facts.cardChannel)
    }
    @Test fun bareFeeDoesNotAcquireCurrencyFromForeignPurchaseOrBalance() {
        val base = "Credit card purchase\nCard: 7271\nAmount: USD 23\nDate: 2026-10-18 13:00\nAvailable balance: SAR 1000\nInternational fee: "
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(base + "1.99").status)
        val explicit = parse(base + "SAR 1.99")
        assertEquals(ParseStatus.SUCCESS, explicit.status)
        assertEquals("SAR", explicit.facts.internationalFee!!.currency.name)
        assertEquals("USD", explicit.facts.amount!!.currency.name)
    }
}
