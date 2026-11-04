package com.baraa.masroof.engine.bank

import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.rules.RuleLibrary
import com.baraa.masroof.finance.value.BankId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class MessageLayoutCoverageTest {
    private val bank = BankId("UNRELATED_BANK")
    private fun parse(body: String): Interpretation = RuleInterpreter(RuleLibrary.current()).interpretRouted(
        MessageInput("layout", "OtherSender", body, Instant.parse("2026-10-18T00:00:00Z")), bank)

    @Test fun atmWithdrawalKeepsParenthesizedMadaCardSeparateFromAccount() {
        val body = "سحب نقدي داخلي صراف الي\nمكان السحب: JEDDAH-بنك الرياض\nبطاقة (مدى):8219 \nحساب رقم: 3001\nبمبلغ: SAR 100.00\nفي: 2026-10-22 05:26"
        for (variant in listOf(body, body.replace("8219", "٨٢١٩").replace("3001", "٣٠٠١"),
            body.replace("بطاقة (مدى):8219 \nحساب رقم: 3001", "حساب رقم: 3001\nبطاقة (مدى):8219"))) {
            val r = parse(variant)
            assertEquals(ParseStatus.SUCCESS, r.status, r.diagnostics.toString())
            assertEquals(MessageFamily.WITHDRAWAL, r.facts.family)
            assertEquals(MoneyDirection.OUTGOING, r.facts.direction)
            assertEquals("8219", r.facts.card?.last4)
            assertEquals("3001", r.facts.sourceAccount?.maskedNumber)
            assertEquals(bank, r.facts.sourceAccount?.bank)
            assertEquals(CardChannel.DEBIT, r.facts.cardChannel)
            assertEquals("100.00", r.facts.amount?.amount?.toPlainString())
            assertEquals("2026-10-22T05:26", r.facts.occurredAtLocal.toString())
            assertEquals(1, r.evidence.count { it.field == "CARD" })
            assertTrue(r.evidence.filter { it.field == "CARD" }.all {
                variant.substring(it.start, it.end) in listOf("8219", "٨٢١٩")
            })
        }
        val accountOnly = parse(body.replace("بطاقة (مدى):8219 \n", ""))
        assertEquals(ParseStatus.SUCCESS, accountOnly.status)
        assertEquals("3001", accountOnly.facts.sourceAccount?.maskedNumber)
        assertNull(accountOnly.facts.card)
        assertNull(accountOnly.facts.cardChannel)
        val conflicting = parse("$body\nبطاقة: 9999")
        assertEquals(ParseStatus.REVIEW_REQUIRED, conflicting.status)
        assertTrue(Blocker.CONFLICTING_FACTS in conflicting.blockers)
        assertNull(conflicting.facts.card)
    }

    @Test fun timeFirstAndInlineClausesWorkAtAnyBankWithOriginalEvidence() {
        val ar = "شراء عبر نقاط البيع\nبطاقة مدى: ٤٥٦٧\nلدى: متجر مستقل 😀\nبمبلغ: SAR ٣٢.٥٠\nخصمت من حساب: ٨٩٠١\nفي: 14:32 03-08-2026"
        val r = parse(ar)
        assertEquals(ParseStatus.SUCCESS, r.status); assertEquals(bank, r.bank)
        assertEquals("8901", r.facts.debitSourceAccountLast4)
        assertEquals("2026-08-03T14:32", r.facts.occurredAtLocal.toString())
        assertTrue(r.evidence.any { it.field == "CARD" && ar.substring(it.start, it.end) == "٤٥٦٧" })
        val inline = "Online Purchase Apple Pay Credit Card: 4567 at :Other Shop of : 32.50 SAR on : 2026-08-03 14:32 Available Balance is: 900 SAR Due Amount: 20 SAR"
        val e = parse(inline)
        assertEquals(ParseStatus.SUCCESS, e.status, e.diagnostics.toString())
        assertEquals("Other Shop", e.facts.merchant); assertEquals(CardChannel.CREDIT, e.facts.cardChannel)
        assertEquals("4567", e.facts.card!!.last4)
        assertTrue(e.evidence.all { it.start >= 0 && it.end <= inline.length && it.end > it.start })
    }

    @Test fun compoundRefundConsumesOnlyItsOwnPurchaseWords() {
        val body = "استرداد مبلغ عملية شراء عبر نقاط البيع\nبطاقة: 4567\nبمبلغ: SAR 32.50\nفي: 2026-08-03 14:32"
        assertEquals(ParseStatus.SUCCESS, parse(body).status)
        assertEquals(MessageFamily.REFUND, parse(body).facts.family)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$body\nPurchase").status)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body.replace("32.50", "-32.50")).status)
    }

    @Test fun invalidDatesAndRoleInjectionRemainReviewWithoutReceiptFallback() {
        val body = "Purchase\nCard: 4567\nAmount: SAR 32.50\nTime: 14:32 30-02-2026"
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body).status)
        assertNull(parse(body).facts.occurredAtLocal)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse(body.replace("Time:", "Merchant:")).status)
        val quoted = "> Online Purchase Credit Card: 4567 of: 32 SAR on: 2026-08-03 14:32"
        assertNotEquals(ParseStatus.SUCCESS, parse(quoted).status)
        assertEquals(ParseStatus.NON_FINANCIAL, parse("OTP 987654 $body").status)
    }

    @Test fun partyDirectoryAndStatementsNeverCreatePrincipalOrTime() {
        val directory = "اسم المستفيد: Synthetic Person\nالاسم المختصر: Test\nحالة: غير نشط"
        assertEquals(ParseStatus.NON_FINANCIAL, parse(directory).status)
        assertNull(parse(directory).facts.amount)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$directory\nPurchase\nAmount: 42 SAR").status)
        val statement = parse("إصدار كشف حساب\nالمبلغ المستحق: 100 SAR\nتاريخ الاستحقاق: 25/08/2026")
        assertEquals(ParseStatus.NON_FINANCIAL, statement.status)
        assertNull(statement.facts.amount); assertNull(statement.facts.occurredAtLocal)
        assertEquals("2026-08-25", statement.facts.paymentDueDate.toString())
    }
    @Test fun sameLineCardNumberAndDirectoryMetadataStayRoleBound() {
        val body = "Credit Card: Refund\nCard: Credit Number:4567\nFrom: Synthetic Shop\nAmount: 12 SAR\nDate: 2026-08-03 14:32"
        val result = parse(body)
        assertEquals(ParseStatus.SUCCESS, result.status, result.diagnostics.toString())
        assertEquals("4567", result.facts.card!!.last4)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$body\nCard: 9999").status)
        assertEquals("Synthetic Shop", result.facts.merchant)
        assertEquals(ParseStatus.NON_FINANCIAL, parse("كلمة مرور صالحة لمرة واحدة\n$body").status)
        val directory = "اسم المستفيد: Synthetic Person\nالاسم المختصر: Test\nحالة: غير نشط\nحساب: SA00001234\nبنك: Remote Bank\nفي: 2026-08-03 14:32"
        val notice = parse(directory)
        assertEquals(ParseStatus.NON_FINANCIAL, notice.status)
        assertNull(notice.facts.amount); assertNull(notice.facts.sourceAccount); assertNull(notice.facts.occurredAtLocal)
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$directory\nAmount: 12 SAR").status)
        // A custom longer PURCHASE phrase cannot consume an independent refund cue.
        val schema = RuleLibrary.current().let { base -> base.copy(rules = base.rules.map {
            if (it.family == MessageFamily.PURCHASE) it.copy(phrases = it.phrases + "purchase refund") else it
        }) }
        val conflict = RuleInterpreter(schema).interpretRouted(MessageInput("conflict", "OtherSender",
            "purchase refund\nCard:4567\nAmount:12 SAR\nDate:2026-08-03 14:32", Instant.EPOCH), bank)
        assertEquals(ParseStatus.REVIEW_REQUIRED, conflict.status)
    }

}
