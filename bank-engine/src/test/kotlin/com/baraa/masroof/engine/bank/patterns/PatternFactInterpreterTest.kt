package com.baraa.masroof.engine.bank.patterns

import com.baraa.masroof.engine.bank.*
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.finance.value.Currency
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class PatternFactInterpreterTest {
    @Test fun deletedPatternMarkersChangeTheFingerprintAndAreCopiedIndependentlyOfSetOrder() {
        val original = configuration(pattern())
        val ids = linkedSetOf("deleted-a", "deleted-b")
        val deleted = original.copy(banks = original.banks.map { it.copy(deletedPatternIds = ids) })
        val interpreter = PatternFactInterpreter(deleted)
        val fingerprint = interpreter.fingerprint
        assertNotEquals(PatternFactInterpreter(original).fingerprint, fingerprint)
        assertEquals(fingerprint, PatternFactInterpreter(deleted.copy(banks = deleted.banks.map {
            it.copy(deletedPatternIds = linkedSetOf("deleted-b", "deleted-a"))
        })).fingerprint)
        ids.clear()
        assertEquals(fingerprint, interpreter.fingerprint)
    }

    @Test fun absentTextAccountReferenceNeverSatisfiesAnExactAccountBinding() {
        val text = "Incoming\nAmount:10SAR\nAccount:321"
        val p = SavedMessagePattern("optional-account", "BANK_STC", "STC Bank", "TRANSFER_IN", parts = listOf(
            StaticText("Incoming\nAmount:"), FieldSlot("amount", FieldKind.MONEY, 0, false),
            StaticText("\nAccount:"), FieldSlot("account_reference", FieldKind.TEXT, 0, false), StaticText("")))
            .copy(factContract = PatternFactContract(MessageFamily.TRANSFER_IN, MoneyDirection.INCOMING, version = 2,
                accountBindings = listOf(PatternAccountBinding(PatternAccountRole.DESTINATION, BankId("BANK_STC"),
                    "4321", "account_reference", "321"))))
        val engine = PatternFactInterpreter(configuration(p))
        assertEquals(ParseStatus.SUCCESS, engine.interpretRouted(input.copy(body = text), bank).status)
        val missing = engine.interpretRouted(input.copy(body = text.replace("321", "")), bank)
        assertEquals(ParseStatus.REVIEW_REQUIRED, missing.status)
        assertNull(missing.facts.destinationAccount)
        assertTrue(missing.diagnostics.any { it.code == "PATTERN_ACCOUNT_BINDING_MISMATCH" })
        assertTrue(missing.evidence.none { it.field == "ACCOUNT_REFERENCE" })
    }

    @Test fun shortReferencesUseOnlyExplicitBankAndRoleQualifiedBindings() {
        val body = "Incoming\nAmount:10SAR\nAccount:321"
        val p = SavedMessagePattern.fromChoices("bound", "BANK_STC", "STC Bank", "TRANSFER_IN", body,
            listOf(FieldChoice("amount", 16, 21), FieldChoice("account_reference", 30, 33)))
        val binding = PatternAccountBinding(PatternAccountRole.DESTINATION, BankId("BANK_STC"), "4321", "account_reference", "321")
        val approved = p.copy(factContract = PatternFactContract(MessageFamily.TRANSFER_IN, MoneyDirection.INCOMING,
            version = 2, accountBindings = listOf(binding)))
        val config = MessagePatternConfiguration(banks = listOf(BankMessagePatterns("BANK_STC", listOf("STC Bank"), patterns = listOf(approved))))
        val input = MessageInput("bound", "STC Bank", body, Instant.EPOCH, true)
        val engine = PatternFactInterpreter(config)
        val result = engine.interpretRouted(input, BankId("BANK_STC"))
        assertEquals(ParseStatus.SUCCESS, result.status)
        assertEquals("4321", result.facts.destinationAccount!!.maskedNumber)
        assertEquals("321", result.evidence.single { it.field == "ACCOUNT_REFERENCE" }.slice(input))
        assertEquals(BankId("BANK_STC"), result.facts.destinationAccount.bank)
        assertNull(result.facts.sourceAccount)
        val different = engine.interpretRouted(input.copy(body = body.replace("321", "322")), BankId("BANK_STC"))
        assertEquals(ParseStatus.REVIEW_REQUIRED, different.status)
        assertNull(different.facts.destinationAccount)
        assertNull(engine.interpretRouted(input, BankId("OTHER")).facts.destinationAccount)
    }

    private val bank = BankId("BANK_STC")
    private val body = "Internal incoming transfer\nAmount:١٢٫٥٠SR\nAcc:٣٢١\nAt:15/10/26 12:30"
    private val input = MessageInput("sms", "STC Bank", body, Instant.parse("2026-10-15T10:00:00Z"), true)
    private val contract = PatternFactContract(MessageFamily.TRANSFER_IN, MoneyDirection.INCOMING)
    private fun pattern() = SavedMessagePattern.fromChoices("in", bank.value, input.sender, "PURCHASE", body,
        listOf("amount" to "١٢٫٥٠SR", "account_reference" to "٣٢١", "occurred_at" to "15/10/26 12:30").map { (field, value) ->
            val start = body.indexOf(value)
            FieldChoice(field, start, start + value.length)
        })
    private fun configuration(vararg patterns: SavedMessagePattern) = MessagePatternConfiguration(1,
        listOf(BankMessagePatterns(bank.value, listOf(input.sender), patterns = patterns.toList())))

    @Test fun matchingAndOperationLabelAloneNeverAuthorizeFinancialFacts() {
        val result = PatternFactInterpreter(configuration(pattern())).interpretRouted(input, bank)
        assertEquals(ParseStatus.REVIEW_REQUIRED, result.status)
        assertEquals(MessageFamily.UNKNOWN, result.facts.family)
        assertNull(result.facts.amount)
        assertTrue(result.diagnostics.any { it.code == "PATTERN_CONTRACT_MISSING" })
    }

    @Test fun explicitContractKeepsOriginalEvidenceLocalTimeAndNeutralShortReferences() {
        val interpreter = PatternFactInterpreter(configuration(pattern().copy(factContract = contract)))
        val result = interpreter.interpretRouted(input, bank)
        assertEquals(MessageFamily.TRANSFER_IN, result.facts.family)
        assertEquals(MoneyDirection.INCOMING, result.facts.direction)
        assertEquals("12.50", result.facts.amount!!.amount.toPlainString())
        assertEquals(Currency.SAR, result.facts.amount.currency)
        assertEquals(LocalDateTime.parse("2026-10-15T12:30"), result.facts.occurredAtLocal)
        assertNull(result.facts.occurredAt)
        assertNull(result.facts.sourceAccount)
        assertNull(result.facts.destinationAccount)
        assertNull(result.facts.bankNetwork)
        assertEquals(ParseStatus.REVIEW_REQUIRED, result.status)
        assertEquals("٣٢١", result.evidence.single { it.field == "ACCOUNT_REFERENCE" }.slice(input))
        assertEquals("١٢٫٥٠SR", result.evidence.single { it.field == "AMOUNT" }.slice(input))
        assertEquals(interpreter.fingerprint, result.patternProvenance!!.configurationFingerprint)
    }

    @Test fun conflictingApprovedPatternsNeverChooseAFirstWinner() {
        val first = pattern().copy(factContract = contract)
        val second = pattern().copy(id = "other", factContract = contract)
        listOf(listOf(first, second), listOf(second, first)).forEach {
            val result = PatternFactInterpreter(configuration(*it.toTypedArray())).interpretRouted(input, bank)
            assertEquals(MessageFamily.UNKNOWN, result.facts.family)
            assertTrue(Blocker.CONFLICTING_FACTS in result.blockers)
        }
    }

    @Test fun anUnapprovedOverlapNeverBlocksTheApprovedPattern() {
        val approved = pattern().copy(factContract = contract)
        listOf(listOf(approved, pattern().copy(id = "other")), listOf(pattern().copy(id = "other"), approved)).forEach {
            assertEquals(MessageFamily.TRANSFER_IN, PatternFactInterpreter(configuration(*it.toTypedArray())).interpretRouted(input, bank).facts.family)
        }
    }

    @Test fun routedInterpretationDoesNotRepeatSenderDetection() {
        val result = PatternFactInterpreter(configuration(pattern().copy(factContract = contract)))
            .interpretRouted(input.copy(sender = "Explicit stored bank choice"), bank)
        assertEquals(MessageFamily.TRANSFER_IN, result.facts.family)
    }

    @Test fun arabicCurrencyWordsTeachAndInterpretAsSar() {
        listOf("10 ريال", "10 ر.س", "10 ريال سعودي").forEach { amount ->
            val text = "Payment\nAmount:$amount\nShop:MARKET"
            val p = SavedMessagePattern.fromChoices("payment", bank.value, input.sender, "PURCHASE", text,
                listOf(FieldChoice("amount", 15, 15 + amount.length)))
                .copy(factContract = PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING))
            assertEquals(FieldKind.MONEY, p.parts.filterIsInstance<FieldSlot>().single().kind)
            val result = PatternFactInterpreter(configuration(p)).interpretRouted(input.copy(body = text), bank)
            assertEquals("10.00", result.facts.amount!!.amount.toPlainString(), amount)
            assertEquals(Currency.SAR, result.facts.amount.currency)
            assertEquals("25.50", PatternFactInterpreter(configuration(p)).interpretRouted(
                input.copy(body = text.replace("10", "25.50")), bank).facts.amount!!.amount.toPlainString())
        }
    }

    @Test fun bareAmountsNeedAnExplicitCurrencyAndCannotBorrowAnAccountNumber() {
        val text = "Payment\nAmount:10\nAccount:1234"
        val p = SavedMessagePattern.fromChoices("payment", bank.value, input.sender, "PURCHASE", text,
            listOf(FieldChoice("amount", 15, 17), FieldChoice("reference", 26, 30)))
            .copy(factContract = PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING))
        val message = input.copy(body = text)
        val blocked = PatternFactInterpreter(configuration(p)).interpretRouted(message, bank)
        assertNull(blocked.facts.amount)
        assertEquals(ParseStatus.REVIEW_REQUIRED, blocked.status)
        val explicit = PatternFactInterpreter(configuration(p.copy(factContract = p.factContract!!.copy(currency = Currency.SAR))))
            .interpretRouted(message, bank)
        assertEquals("10.00", explicit.facts.amount!!.amount.toPlainString())
        assertTrue("AMOUNT_CURRENCY" in explicit.patternProvenance!!.definitionFacts)
    }

    @Test fun contradictoryCurrencyAndUnknownRolesBlockAutomaticUse() {
        val p = pattern().copy(factContract = contract.copy(currency = Currency.USD))
        val result = PatternFactInterpreter(configuration(p)).interpretRouted(input, bank)
        assertTrue(result.diagnostics.any { it.code == "PATTERN_CURRENCY_CONFLICT" })
        val unknown = p.copy(parts = p.parts.map { if (it is FieldSlot && it.field == "account_reference") it.copy(field = "unexpected") else it })
        val unsupported = PatternFactInterpreter(configuration(unknown)).interpretRouted(input, bank)
        assertTrue(unsupported.diagnostics.any { it.code == "PATTERN_ROLE_UNSUPPORTED" })
    }

    @Test fun securityAndNonExecutionGatesCannotBeTaughtAwayInVariableText() {
        val text = "Payment\nAmount:10SAR\nMerchant:Shop"
        val p = SavedMessagePattern.fromChoices("payment", bank.value, input.sender, "PURCHASE", text,
            listOf(FieldChoice("amount", 15, 20), FieldChoice("merchant", 30, 34)))
            .copy(factContract = PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING))
        val interpreter = PatternFactInterpreter(configuration(p))
        val otp = interpreter.interpretRouted(input.copy(body = text.replace("Shop", "OTP")), bank)
        assertEquals(MessageFamily.OTP, otp.facts.family)
        assertNull(otp.facts.amount)
        val pending = interpreter.interpretRouted(input.copy(body = text.replace("Shop", "pending")), bank)
        assertEquals(ParseStatus.REVIEW_REQUIRED, pending.status)
        assertTrue(pending.diagnostics.any { it.code == "PATTERN_EXECUTION_NOT_PROVEN" })
    }

    @Test fun fingerprintsAreOrderIndependentButChangeOnDisableContractSenderAndRestore() {
        val p = pattern().copy(factContract = contract)
        val config = configuration(p, p.copy(id = "second"))
        val fingerprint = PatternFactInterpreter(config).fingerprint
        assertEquals(fingerprint, PatternFactInterpreter(config.copy(banks = config.banks.map { it.copy(patterns = it.patterns.reversed()) })).fingerprint)
        listOf(config.copy(banks = config.banks.map { it.copy(patterns = listOf(p.copy(enabled = false), p.copy(id = "second"))) }),
            config.copy(banks = config.banks.map { it.copy(patterns = listOf(p.copy(factContract = contract.copy(direction = MoneyDirection.OUTGOING)), p.copy(id = "second"))) }),
            config.copy(generation = 2), config.copy(banks = config.banks.map { it.copy(senders = listOf("other")) })).forEach {
            assertNotEquals(fingerprint, PatternFactInterpreter(it).fingerprint)
        }
        val mutable = config.banks.toMutableList()
        val snapshot = PatternFactInterpreter(config.copy(banks = mutable))
        mutable.clear()
        assertEquals(fingerprint, snapshot.fingerprint)
        assertEquals(MessageFamily.UNKNOWN, snapshot.interpretRouted(input, bank).facts.family) // Both patterns still conflict.
    }

    @Test fun transactionCurrencyDoesNotLeakIntoBalancesAndAccountBanksAreExplicit() {
        val text = "Purchase\nAmount:10USD\nBalance:100SAR\nAccount:1234"
        val p = SavedMessagePattern.fromChoices("purchase", bank.value, input.sender, "PURCHASE", text,
            listOf("amount" to "10USD", "available_balance" to "100SAR", "source_account" to "1234").map { (field, value) ->
                FieldChoice(field, text.indexOf(value), text.indexOf(value) + value.length)
            }).copy(factContract = PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING,
                Currency.USD, sourceAccountBank = bank))
        val result = PatternFactInterpreter(configuration(p)).interpretRouted(input.copy(body = text), bank)
        assertEquals(ParseStatus.SUCCESS, result.status)
        assertEquals(Currency.USD, result.facts.amount!!.currency)
        assertEquals(Currency.SAR, result.facts.availableBalance!!.currency)
        assertEquals(bank, result.facts.sourceAccount!!.bank)
        assertEquals("1234", result.facts.sourceAccount.maskedNumber)
        val unresolved = PatternFactInterpreter(configuration(p.copy(factContract = p.factContract!!.copy(sourceAccountBank = null))))
            .interpretRouted(input.copy(body = text), bank)
        assertEquals(ParseStatus.REVIEW_REQUIRED, unresolved.status)
        assertNull(unresolved.facts.sourceAccount)
    }
}
