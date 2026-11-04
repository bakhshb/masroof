package com.baraa.masroof.bank.patterns

import com.baraa.masroof.testsupport.bundledPatterns
import com.baraa.masroof.bank.InterpretationDraftMapper
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.engine.bank.Interpretation
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.MoneyDirection
import com.baraa.masroof.engine.bank.ParseStatus
import com.baraa.masroof.engine.bank.patterns.FieldKind
import com.baraa.masroof.engine.bank.patterns.FieldSlot
import com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration
import com.baraa.masroof.engine.bank.patterns.PatternAccountBinding
import com.baraa.masroof.engine.bank.patterns.PatternAccountRole
import com.baraa.masroof.engine.bank.patterns.PatternFactContract
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.engine.bank.patterns.SavedMessagePattern
import com.baraa.masroof.engine.bank.patterns.StaticText
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.parsing.model.ParseResult
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Approved account/card links complete only the role a built-in or taught message leaves empty. Fictional values. */
class BundledPatternLinksTest {
    private val barq = BankId("BANK_BARQ")
    private val incoming = "حوالة واردة داخلية\n100.00 SAR\nحساب المرسل: **5555\n2026-10-02 22:01"
    private val purchase = "شراء\nمبلغ 25 SAR\nلدى TEST\n2026-09-15 12:30"

    @Test fun incomingTransferWithoutOwnAccount_postsOnlyToTheLinkedAccount() {
        val linked = run("barq.transfer-in", incoming, link(PatternAccountRole.DESTINATION, "1234"))
        assertEquals(ParseStatus.SUCCESS, linked.status)
        assertEquals("1234", linked.facts.destinationAccount!!.maskedNumber)
        assertEquals("5555", linked.facts.sourceAccount!!.maskedNumber)
        assertEquals("100.00", linked.facts.amount!!.amount.toPlainString())
        assertTrue(mapped(incoming, linked) is ParseResult.Success)

        val unlinked = run("barq.transfer-in", incoming)
        assertEquals(ParseStatus.REVIEW_REQUIRED, unlinked.status)
        assertTrue(unlinked.diagnostics.any { it.code == "MISSING_TRANSFER_DESTINATION" })

        val named = "حوالة واردة داخلية\n100.00 SAR\nحساب المرسل: **5555\nلحساب: **9999\n2026-10-02 22:01"
        // A destination the message names stays the message's; the link only fills a missing one.
        val other = run("barq.transfer-in", named, link(PatternAccountRole.DESTINATION, "1234"))
        assertEquals(ParseStatus.SUCCESS, other.status)
        assertEquals("9999", other.facts.destinationAccount!!.maskedNumber)
    }

    @Test fun purchaseWithoutCard_takesOnlyALinkedCard() {
        val carded = run("barq.purchase", purchase, link(PatternAccountRole.CARD, "4321"))
        assertEquals(ParseStatus.SUCCESS, carded.status)
        assertEquals("4321", carded.facts.card!!.last4)
        assertNull(carded.facts.sourceAccount)
        assertTrue(mapped(purchase, carded) is ParseResult.Success)

        assertEquals(ParseStatus.REVIEW_REQUIRED, run("barq.purchase", purchase).status)
        // A funding account would turn a credit purchase into an account debit.
        val accountOnly = run("barq.purchase", purchase, link(PatternAccountRole.SOURCE, "1234"))
        assertEquals(ParseStatus.REVIEW_REQUIRED, accountOnly.status)
        assertNull(accountOnly.facts.sourceAccount)

        val named = run("barq.purchase", "شراء\nمبلغ 25 SAR\nبطاقة مدى: **1111\nلدى TEST\n2026-09-15 12:30",
            link(PatternAccountRole.CARD, "4321"))
        assertEquals(ParseStatus.SUCCESS, named.status)
        assertEquals("1111", named.facts.card!!.last4)
    }

    @Test fun eachFamily_isCompletedOnlyByTheRoleItIsMissing() {
        val source = link(PatternAccountRole.SOURCE, "1234")
        fun assertPosts(id: String, body: String, vararg links: PatternAccountBinding) =
            assertEquals("$id: ${run(id, body, *links).diagnostics}", ParseStatus.SUCCESS, run(id, body, *links).status)
        fun assertReviews(id: String, body: String, vararg links: PatternAccountBinding) =
            assertEquals(id, ParseStatus.REVIEW_REQUIRED, run(id, body, *links).status)
        val transferOut = "حوالة صادرة داخلية\nالمبلغ: 100 SAR\nإلى : TEST PERSON\n2026-09-15 12:30"
        assertReviews("barq.transfer-out", transferOut)
        assertPosts("barq.transfer-out", transferOut, source)
        val withdrawal = "سحب نقدي\nمبلغ 200 SAR\n2026-09-15 12:30"
        assertReviews("barq.withdrawal", withdrawal)
        assertPosts("barq.withdrawal", withdrawal, source)
        // Card payment needs the paying account and the card it settles.
        val cardPayment = "سداد بطاقة ائتمانية\nمبلغ 300 SAR\n2026-09-15 12:30"
        assertReviews("barq.card-payment", cardPayment, source)
        assertPosts("barq.card-payment", cardPayment, source, link(PatternAccountRole.CARD, "4321"))
        val fee = run("barq.fee", "خصم رسوم\nمبلغ 5 SAR\n2026-09-15 12:30", source)
        assertEquals(fee.diagnostics.toString(), ParseStatus.SUCCESS, fee.status)
        assertEquals("1234", fee.facts.sourceAccount!!.maskedNumber)
        // A cash withdrawal from a named credit card is the card's, never the linked account's.
        val cardCash = run("barq.withdrawal", "سحب نقدي\nبطاقة ائتمانية: **4321\nمبلغ 500 SAR\n2026-09-15 12:30", source)
        assertEquals(ParseStatus.REVIEW_REQUIRED, cardCash.status)
        assertNull(cardCash.facts.sourceAccount)
        // A missing biller is not something an account link can supply.
        assertReviews("barq.bill", "سداد فاتورة\nمبلغ 50 SAR\n2026-09-15 12:30", source)
        // Links never fill another family's role: an incoming transfer has no own source account.
        assertReviews("barq.transfer-in", incoming, source)
    }

    @Test fun taughtTemplateWithoutCardField_usesTheLinkedCard() {
        val body = "POS\nAmount:25SAR\nShop:TEST"
        val pattern = SavedMessagePattern("taught-purchase", "BANK_STC", "STC Bank", "PURCHASE", parts = listOf(
            StaticText("POS\nAmount:"), FieldSlot("amount", FieldKind.MONEY, 0, false),
            StaticText("\nShop:"), FieldSlot("merchant", FieldKind.TEXT, 0, false), StaticText("")))
            .copy(factContract = PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING, version = 2,
                accountBindings = listOf(PatternAccountBinding(PatternAccountRole.CARD, BankId("BANK_STC"), "4321"))))
        val config = MessagePatternConfiguration(banks = listOf(
            com.baraa.masroof.engine.bank.patterns.BankMessagePatterns("BANK_STC", listOf("STC Bank"), patterns = listOf(pattern))))
        val result = PatternFactInterpreter(config).interpretRouted(
            MessageInput("taught", "STC Bank", body, Instant.parse("2026-09-15T09:30:00Z"), true), BankId("BANK_STC"))
        assertEquals(result.diagnostics.toString(), ParseStatus.SUCCESS, result.status)
        assertEquals("4321", result.facts.card!!.last4)
    }

    private fun link(role: PatternAccountRole, last4: String) = PatternAccountBinding(role, barq, last4)

    private fun run(patternId: String, body: String, vararg links: PatternAccountBinding): Interpretation {
        val base = bundledPatterns("BANK_BARQ")
        val config = base.copy(banks = base.banks.map { bank -> bank.copy(patterns = bank.patterns.map { pattern ->
            if (pattern.id != patternId || links.isEmpty()) pattern
            else pattern.copy(factContract = pattern.factContract!!.copy(version = 2, accountBindings = links.toList()))
        }) })
        return PatternFactInterpreter(config).interpretRouted(input(body), barq)
    }

    private fun input(body: String) = MessageInput("links", "barq app", body, Instant.parse("2026-10-02T19:01:00Z"), true)
    private fun mapped(body: String, result: Interpretation) = InterpretationDraftMapper().map(input(body), result, Bank("BANK_BARQ"))
}
