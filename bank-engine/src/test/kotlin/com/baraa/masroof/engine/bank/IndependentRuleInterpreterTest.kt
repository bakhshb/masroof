package com.baraa.masroof.engine.bank

import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.engine.bank.rules.RuleLibrary
import com.baraa.masroof.finance.value.BankId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class IndependentRuleInterpreterTest {
    private val bank = BankId("TEST_BANK")
    private val catalog = BankCatalog(listOf(BankDefinition(bank, setOf("SyntheticBank"))))
    private fun input(body: String, sender: String = "SyntheticBank") =
        MessageInput("fixture", sender, body, Instant.parse("2026-10-17T11:00:00Z"))
    private fun parse(body: String) = RuleInterpreter(RuleLibrary.initial()).interpret(input(body), catalog)
    private val purchase = "Purchase\nAmount: SAR 42.35\nCard: **1234\nMerchant: Synthetic Shop\n2026-10-17 13:00"

    @Test fun independentEngineExtractsOriginalEvidenceAndNeverGuessesOwnership() {
        val result = parse(purchase + "\nAvailable balance: USD 120.50")
        assertEquals(ParseStatus.SUCCESS, result.status)
        assertEquals("42.35", result.facts.amount!!.amount.toPlainString())
        assertEquals("SAR", result.facts.amount.currency.name)
        assertEquals("USD", result.facts.availableBalance!!.currency.name)
        assertEquals("1234", result.facts.card!!.last4)
        assertNull(result.facts.sourceAccount)
        assertNull(result.facts.occurredAt)
        assertEquals("Synthetic Shop", result.facts.merchant)
        assertEquals("EXECUTED", result.facts.executionState)
        assertTrue(result.evidence.all { it.end <= purchase.plus("\nAvailable balance: USD 120.50").length })
        assertTrue(result.evidence.any { it.field == "AMOUNT" && it.slice(input(purchase)) == "Amount: SAR 42.35" })
        assertEquals(RuleInterpreter.VERSION, result.version.engine)
        assertTrue(result.version.rules.contains("literal-v1-r1-"))
        assertNull(result.confidence)
    }

    @Test fun maskWhoseDigitsAreAPrefix_isNeverTakenAsTheLastFour() {
        val result = parse("Purchase\nAmount: SAR 42.35\nCard: 4567****1234\nMerchant: Synthetic Shop\n2026-10-17 13:00")
        assertNotEquals("4567", result.facts.card?.last4)
        assertEquals("1234", parse(purchase.replace("**1234", "1234**")).facts.card!!.last4)
    }

    @Test fun utf16EvidenceSurvivesEmojiArabicDigitsMarksCrLfAndCompatibilityCharacters() {
        val body = "😀\r\nشِراء\r\nبمبلغ： ٤٢٫٣٥ SAR\r\nبطاقة: **١٢٣٤\r\nلدى: متجر أصلي\r\n٢٠٢٦-١٠-١٧ ١٣:٠٠"
        val result = parse(body)
        assertEquals(ParseStatus.SUCCESS, result.status)
        assertEquals("42.35", result.facts.amount!!.amount.toPlainString())
        assertEquals("متجر أصلي", result.facts.merchant)
        assertTrue(result.evidence.any { it.field == "CARD" && it.slice(input(body)) == "**١٢٣٤" })
        assertTrue(result.evidence.any { it.field == "AMOUNT" && it.slice(input(body)) == "بمبلغ： ٤٢٫٣٥ SAR" })
        assertEquals(13, result.facts.occurredAtLocal!!.hour)
        result.evidence.forEach { assertFalse(it.slice(input(body)).contains("😀")) }
    }

    @Test fun commonSaudiOneTimeCodeWordingIsClassifiedAsOtp() {
        listOf("الرقم السري لمرة واحدة", "الرقم السري لمره واحده", "الرمز السري", "رمز التأكيد", "رمز الدخول", "رمز المرور",
            "كلمة السر", "كود التحقق", "كود التفعيل", "Activation code", "Do not share this code", "لا تشارك الرمز").forEach { term ->
            val r = parse("$term: 567890\n$purchase")
            assertEquals(MessageFamily.OTP, r.facts.family, term)
            assertEquals(ParseStatus.NON_FINANCIAL, r.status, term)
            assertNull(r.facts.amount, term)
        }
        assertEquals(MessageFamily.OTP, parse("OTP1234 is your code\n$purchase").facts.family)
        // An approval code on a real purchase is not a one-time password.
        assertEquals(ParseStatus.SUCCESS, parse("$purchase\nApproval code: 123456").status)
    }

    @Test fun currencyMayTouchTheAmountInEitherOrder() {
        mapOf("SAR42.35" to "SAR", "42.35SAR" to "SAR", "SAR 42.35" to "SAR", "SR42.35" to "SAR", "42.35 SR" to "SAR",
            "ر.س42.35" to "SAR", "42.35ريال" to "SAR", "USD42.35" to "USD").forEach { (value, code) ->
            val r = parse("Purchase\nAmount: $value\nCard: **1234\nMerchant: Synthetic Shop\n2026-10-17 13:00")
            assertEquals(ParseStatus.SUCCESS, r.status, value)
            assertEquals("42.35", r.facts.amount!!.amount.toPlainString(), value)
            assertEquals(code, r.facts.amount.currency.name, value)
        }
        assertEquals(1000, parse("Purchase\nAmount: 1,000.00SAR\nCard: **1234\nMerchant: Shop\n2026-10-17 13:00").facts.amount!!.amount.toInt())
        listOf("42.35SARX", "42.355SAR").forEach { value ->
            val r = parse("Purchase\nAmount: $value\nCard: **1234\nMerchant: Synthetic Shop\n2026-10-17 13:00")
            assertEquals(ParseStatus.REVIEW_REQUIRED, r.status, value)
            assertNull(r.facts.amount, value)
        }
    }

    @Test fun securityAndUnexecutedWordingCannotBecomeSuccessfulMoney() {
        listOf("OTP", "verification code", "one-time password", "PIN", "رمز التحقق", "رمز التوثيق", "رمز تأكيد").forEach { term ->
            val r = parse("$term: 567890\n$purchase")
            assertEquals(ParseStatus.NON_FINANCIAL, r.status, term)
            assertEquals(MessageFamily.OTP, r.facts.family)
            assertNull(r.facts.amount); assertNull(r.facts.card)
        }
        listOf("declined", "rejected", "pending", "request", "cancelled", "not completed", "not executed", "not yet debited", "never executed", "لم تتم", "مرفوضة", "قيد التنفيذ", "غير منفذة", "لم يخصم").forEach { term ->
            val r = parse("$term\n$purchase")
            assertEquals(ParseStatus.REVIEW_REQUIRED, r.status, term)
            assertNull(r.facts.executionState)
        }
        listOf("offer", "advertisement", "statement", "payment due date").forEach { term ->
            assertEquals(ParseStatus.REVIEW_REQUIRED, parse("$term\n$purchase").status, term)
        }
    }

    @Test fun conflictsAndMissingProofStayReviewWithoutInventingZerosOrReceiptTime() {
        val cases = listOf(purchase + "\nAmount: USD 42.35", purchase + "\nCard: 9999", purchase + "\nRefund",
            purchase.replace("Amount: SAR 42.35", "Available balance: SAR 42.35"),
            purchase.replace("Amount: SAR 42.35", "Due amount: SAR 42.35"),
            purchase.replace("42.35", "0.00"), purchase.replace("42.35", "-42.35"),
            purchase.replace("42.35", "42.35-"), purchase.replace("42.35", "42.35−"),
            purchase.replace("SAR", "JPY"), purchase.replace("42.35", "42.355"),
            purchase.replace("2026-10-17 13:00", "2026-02-30 13:00"),
            purchase.replace("2026-10-17 13:00", "Expires: 2026-10-17 13:00"),
            purchase.replace("2026-10-17 13:00", ""), purchase.replace("Card: **1234", ""),
            purchase + "\n2026-10-18 13:00", purchase + "\nMerchant: Another Shop",
            purchase + "\nAmount: JPY 100", purchase + "\nAmount: SAR -50", purchase + "\nIncoming transfer")
        cases.forEach { body ->
            val r = parse(body)
            assertEquals(ParseStatus.REVIEW_REQUIRED, r.status, body)
            assertTrue(r.blockers.isNotEmpty()); assertNull(r.facts.executionState)
        }
        assertEquals(MessageFamily.UNKNOWN, parse("repurchase amount SAR 42.35").facts.family)
    }

    @Test fun catalogAmbiguityCannotBeOverriddenByBodyOrRuleOrder() {
        val engine = RuleInterpreter(RuleLibrary.initial())
        val other = BankDefinition(BankId("OTHER"), setOf("SyntheticBank"))
        val r = engine.interpret(input(purchase), BankCatalog(listOf(BankDefinition(bank, setOf("SyntheticBank")), other)))
        assertEquals(setOf(Blocker.AMBIGUOUS_BANK, Blocker.UNKNOWN_FAMILY), r.blockers)
        assertNull(r.bank); assertTrue(r.evidence.isEmpty())
        assertTrue(Blocker.UNKNOWN_BANK in engine.interpret(input(purchase, "syntheticbank"), catalog).blockers)
    }

    @Test fun ruleSnapshotsAreImmutableVersionedAndUnsupportedCapabilitiesFailClosed() {
        val phrases = mutableListOf("purchase")
        val rule = RuleLibrary.initial().rules.first().copy(phrases = phrases)
        val schema = BankRuleSchema(revision = 7, rules = listOf(rule))
        val engine = RuleInterpreter(schema)
        phrases.clear()
        assertEquals(ParseStatus.SUCCESS, engine.interpret(input(purchase), catalog).status)
        val changed = RuleInterpreter(schema.copy(rules = listOf(rule.copy(phrases = listOf("buy")))))
        assertNotEquals(engine.version, changed.version)
        val configured = rule.copy(phrases = listOf("purchase"))
        val valid = schema.copy(rules = listOf(configured))
        listOf(valid.copy(schema = 2), valid.copy(network = NetworkRules(bankAliases = mapOf("UNKNOWN" to listOf("bad")))),
            schema.copy(rules = listOf(configured.copy(observations = mapOf("ownership.mine" to listOf("credit"))))),
            schema.copy(rules = listOf(configured.copy(direction = MoneyDirection.INCOMING))))
            .forEach { invalid -> assertEquals(ParseStatus.REVIEW_REQUIRED, RuleInterpreter(invalid).interpret(input(purchase), catalog).status) }
        assertEquals(ParseStatus.REVIEW_REQUIRED, parse("x".repeat(16001)).status)
    }

    @Test fun unsupportedSchemaKeepsOtpPriorityAcrossRepeatedMessages() {
        val invalidSchemas = listOf(
            RuleLibrary.initial().copy(schema = 2),
            RuleLibrary.initial().let { schema -> schema.copy(rules = schema.rules.map { it.copy(phrases = listOf("ـ")) }) },
        )
        invalidSchemas.forEach { schema ->
            val engine = RuleInterpreter(schema)
            repeat(2) {
                val financial = engine.interpret(input(purchase), catalog)
                assertEquals(ParseStatus.REVIEW_REQUIRED, financial.status)
                assertTrue(Blocker.UNSUPPORTED_RULE_SCHEMA in financial.blockers)
                assertTrue(financial.evidence.isEmpty())
                val otp = engine.interpret(input("OTP 123456\n$purchase"), catalog)
                assertEquals(ParseStatus.NON_FINANCIAL, otp.status)
                assertEquals(MessageFamily.OTP, otp.facts.family)
                assertTrue(otp.blockers.isEmpty())
            }
        }
    }

    @Test fun aSingleIdentifierCannotServeTwoAssetRoles() {
        val rule = RuleLibrary.initial().rules.first().copy(labels = RuleLibrary.initial().rules.first().labels +
            (ExtractionField.SOURCE_ACCOUNT to listOf("card")))
        val r = RuleInterpreter(BankRuleSchema(revision = 1, rules = listOf(rule))).interpret(input(purchase), catalog)
        assertEquals(ParseStatus.REVIEW_REQUIRED, r.status)
        assertTrue(Blocker.CONFLICTING_FACTS in r.blockers)
    }

    @Test fun initialFamiliesAreBankAgnosticButIncompleteTransfersStayReview() {
        listOf("Refund\nAmount: USD 10.00\nCard: 1234" to MessageFamily.REFUND,
            "Cash withdrawal\nAmount: EUR 15.00\nFrom account: 5678" to MessageFamily.WITHDRAWAL,
            "Fee charged\nAmount: GBP 1.00" to MessageFamily.FEE).forEach { (body, family) ->
            val r = parse(body + "\n2026-10-17T13:00")
            assertEquals(ParseStatus.SUCCESS, r.status); assertEquals(family, r.facts.family)
        }
        assertEquals(ParseStatus.NON_FINANCIAL, parse("Balance notification\nAvailable balance: SAR 20").status)
        assertEquals(ParseStatus.NON_FINANCIAL, parse("Login notification").status)
        val otherBank = BankId("SECOND_BANK")
        val r = RuleInterpreter(RuleLibrary.initial()).interpret(input(purchase, "OtherSender"),
            BankCatalog(listOf(BankDefinition(otherBank, setOf("OtherSender")))))
        assertEquals(ParseStatus.SUCCESS, r.status); assertEquals(otherBank, r.facts.card!!.bank)
        val transfer = RuleLibrary.initial().rules.first().copy(family = MessageFamily.TRANSFER_OUT)
        val unsupported = RuleInterpreter(BankRuleSchema(revision = 1, rules = listOf(transfer))).interpret(input(purchase), catalog)
        assertEquals(ParseStatus.REVIEW_REQUIRED, unsupported.status)
        assertTrue(Blocker.VALIDATION_ERROR in unsupported.blockers)
    }
}
