package com.baraa.masroof.engine.bank

import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.rules.RuleLibrary
import com.baraa.masroof.finance.value.BankId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ObservationRulesTest {
    private val purchase = "Purchase\nAmount: SAR 50\nCard: 1234\n2026-10-18 13:00"
    private val loan = "Loan repayment\nAmount: SAR 50\nFrom account: 1234\nCounterparty: Synthetic Finance\n2026-10-18 13:00"
    private val incoming = "Incoming transfer\nAmount: SAR 50\nFrom account: 1234\nTo account: 5678\nNetwork: intra-bank\n2026-10-18 13:00"
    private fun input(body: String) = MessageInput("observation-fixture", "Synthetic", body, Instant.parse("2026-10-18T10:00:00Z"))
    private fun parse(body: String, schema: BankRuleSchema = RuleLibrary.current()) = RuleInterpreter(schema).interpretRouted(input(body), BankId("LOCAL"))

    @Test fun balanceEvidenceRetainsAllMatchesOfTheFirstConfiguredPhrase() {
        val body = "$purchase\nAvailable balance: SAR 500\nالرصيد المتاح: SAR 500\nAvailable balance: SAR 500"
        val result = parse(body)
        assertEquals(ParseStatus.SUCCESS, result.status)
        assertEquals(listOf("Available balance", "Available balance"), result.evidence
            .filter { it.field == "observation.card.available_balance" }.map { it.slice(input(body)) })
    }

    @Test fun cardChannelsUseTypedRolesAndConfiguredCreditBalanceProof() {
        val r = parse("$purchase\nCard type: credit\nPurchase channel: online")
        assertEquals(ParseStatus.SUCCESS, r.status); assertEquals(CardChannel.CREDIT, r.facts.cardChannel)
        assertEquals(PurchaseChannel.ONLINE, r.facts.purchaseChannel)
        val title = parse(purchase.replace("Purchase", "POS purchase").replace("Card: 1234", "Debit card: 1234"))
        assertEquals(ParseStatus.SUCCESS, title.status); assertEquals(CardChannel.DEBIT, title.facts.cardChannel)
        assertEquals(PurchaseChannel.POS, title.facts.purchaseChannel)
        val balance = parse("$purchase\nAvailable balance: SAR 500")
        assertEquals(ParseStatus.SUCCESS, balance.status); assertEquals(CardChannel.CREDIT,balance.facts.cardChannel)
        assertNull(balance.facts.purchaseChannel)
        assertTrue(balance.evidence.any { it.field == "observation.card.available_balance" })
        assertEquals(CardChannel.STATEMENT, parse("Balance notification\nCard type: statement").facts.cardChannel)
    }

    @Test fun allLoanKindsAndSalaryWordingRemainObservedFactsNotFinancialClassification() {
        mapOf("personal" to LoanType.PERSONAL, "auto" to LoanType.AUTO, "mortgage" to LoanType.MORTGAGE).forEach { (label, expected) ->
            val r = parse("$loan\nLoan type: $label")
            assertEquals(ParseStatus.SUCCESS, r.status); assertEquals(expected, r.facts.loanType)
        }
        val salary = parse("$incoming\nIncome type: salary")
        assertEquals(ParseStatus.SUCCESS, salary.status); assertEquals(true, salary.facts.salaryIncomeWording)
        assertEquals(true, parse(incoming.replace("Incoming transfer", "Salary credited")).facts.salaryIncomeWording)
        assertEquals(false, parse(incoming).facts.salaryIncomeWording)
        assertNull(parse(loan).facts.loanType)
    }

    @Test fun namesReferencesAndUnlabeledWordsCannotInjectObservations() {
        val r = parse("$purchase\nMerchant: Credit Personal Salary Online Corp\nTransaction reference: POS-123")
        assertEquals(ParseStatus.SUCCESS, r.status)
        assertNull(r.facts.cardChannel); assertNull(r.facts.purchaseChannel); assertNull(r.facts.salaryIncomeWording); assertNull(r.facts.loanType)
        assertNull(parse("$loan\nNote: Loan type: auto").facts.loanType)
        assertEquals(false, parse("$incoming\nCounterparty: Salary Corporation").facts.salaryIncomeWording)
    }

    @Test fun unknownConflictingAndWrongFamilyRolesCannotBeSuccessful() {
        listOf("Card type: credit\nCard type: debit", "Card type: unknown", "Purchase channel: online\nChannel: pos",
            "Channel: unsupported", "Loan type: auto", "Income type: salary", "Card type=credit").forEach { role ->
            val r = parse("$purchase\n$role")
            assertEquals(ParseStatus.REVIEW_REQUIRED, r.status, role); assertNull(r.facts.executionState)
        }
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$loan\nLoan type: auto\nLoan type: personal").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$incoming\nCard type: credit").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$purchase\nCard type: statement").status)
    }

    @Test fun arabicRangesAndLiteralConfigurableVocabularyStayReproducible() {
        val body = "😀\r\nشراء\r\nبمبلغ: ٥٠ SAR\r\nبطاقة: ١٢٣٤\r\nنوع البطاقة: ائتمانية\r\nقناة الشراء: عبر الإنترنت\r\n٢٠٢٦-١٠-١٨ ١٣:٠٠"
        val r = parse(body)
        assertEquals(ParseStatus.SUCCESS, r.status); assertEquals(CardChannel.CREDIT, r.facts.cardChannel)
        assertEquals(PurchaseChannel.ONLINE, r.facts.purchaseChannel)
        assertTrue(r.evidence.any { it.field == "observation.purchaseChannel" && it.slice(input(body)) == "قناة الشراء: عبر الإنترنت" })
        val custom = RuleLibrary.current().let { schema -> schema.copy(rules = schema.rules.map { rule ->
            if (rule.family == MessageFamily.PURCHASE) rule.copy(observations = rule.observations + ("purchase.online" to listOf("e-commerce"))) else rule
        }) }
        assertEquals(PurchaseChannel.ONLINE, parse("$purchase\nChannel: e-commerce", custom).facts.purchaseChannel)
        val conflicting = custom.copy(rules = custom.rules.map { it.copy(observations = it.observations + ("purchase.pos" to listOf("e-commerce"))) })
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$purchase\nChannel: e-commerce", conflicting).status)
        assertNotEquals(RuleInterpreter(custom).version, RuleInterpreter(conflicting).version)
    }

    @Test fun securityNonExecutionAndCurrencyGuardsStillDominateObservations() {
        val body = "$purchase\nCard type: credit\nChannel: online"
        assertEquals(ParseStatus.NON_FINANCIAL, parse("OTP 123456\n$body").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("Declined\n$body").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$body\nAmount: USD 50").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("```\n$body\n```").status)
    }
}
