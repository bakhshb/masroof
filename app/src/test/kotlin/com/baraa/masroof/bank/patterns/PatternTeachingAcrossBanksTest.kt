package com.baraa.masroof.bank.patterns

import com.baraa.masroof.bank.InterpretationDraftMapper
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.MoneyDirection
import com.baraa.masroof.engine.bank.ParseStatus
import com.baraa.masroof.engine.bank.patterns.FieldChoice
import com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration
import com.baraa.masroof.engine.bank.patterns.PatternAccountBinding
import com.baraa.masroof.engine.bank.patterns.PatternAccountRole
import com.baraa.masroof.engine.bank.patterns.PatternFactContract
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.engine.bank.patterns.SavedMessagePattern
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.finance.value.Currency
import com.baraa.masroof.engine.bank.BankNetwork
import com.baraa.masroof.parsing.model.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Every bundled bank lets the user teach a message its role profile does not recognize or cannot complete. */
class PatternTeachingAcrossBanksTest {
    private class Sample(val body: String, val family: MessageFamily, val reference: String, val fields: List<Pair<String, String>>)

    // Observed Barq layouts: a card-funded wallet top-up and internal transfers without a local account.
    private val samples = listOf(
        Sample("إضافة اموال لمحفظة برق\nالمبلغ: 400.0 SAR\nالبطاقة: **7271\nفي: 2026-10-22 20:31", MessageFamily.TRANSFER_IN,
            "funding_reference", listOf("amount" to "400.0 SAR", "funding_reference" to "**7271", "occurred_at" to "2026-10-22 20:31")),
        Sample("حوالة واردة داخلية\n100.00 SAR\nحساب المرسل: **1482\n2026-10-02 22:01", MessageFamily.TRANSFER_IN,
            "account_reference", listOf("amount" to "100.00 SAR", "account_reference" to "**1482", "occurred_at" to "2026-10-02 22:01")),
        Sample("حوالة صادرة داخلية\nالمبلغ: 250.00 SAR\nإلى : **0312\n2026-09-26 16:29", MessageFamily.TRANSFER_OUT,
            "account_reference", listOf("amount" to "250.00 SAR", "account_reference" to "**0312", "occurred_at" to "2026-09-26 16:29")),
    )
    private val bundled = File("src/main/assets/bank-patterns").listFiles()!!.sorted()
        .map { MessagePatternConfigurationCodec.decode(it.readText()) }

    private fun taught(base: MessagePatternConfiguration, sample: Sample, family: MessageFamily,
        contract: PatternFactContract = linkedContract(base, sample, family)): MessagePatternConfiguration {
        val bank = base.banks.single()
        val choices = sample.fields.map { (field, value) -> sample.body.indexOf(value).let { FieldChoice(field, it, it + value.length) } }
        val pattern = SavedMessagePattern.fromChoices("user.taught", bank.bankId, bank.senders.first(), family.name, sample.body, choices)
        return base.copy(banks = listOf(bank.copy(patterns = bank.patterns + pattern.copy(factContract = contract))))
    }

    private fun linkedContract(base: MessagePatternConfiguration, sample: Sample, family: MessageFamily): PatternFactContract {
        val incoming = family == MessageFamily.TRANSFER_IN || family == MessageFamily.REFUND
        return PatternFactContract(family, if (incoming) MoneyDirection.INCOMING else MoneyDirection.OUTGOING, version = 2,
            accountBindings = listOf(PatternAccountBinding(if (incoming) PatternAccountRole.DESTINATION else PatternAccountRole.SOURCE,
                BankId(base.banks.single().bankId), "9999")),
            contextReferences = setOf(sample.reference))
    }

    private fun input(base: MessagePatternConfiguration, sample: Sample) =
        MessageInput("taught", base.banks.single().senders.first(), sample.body, Instant.EPOCH, true)

    @Test fun everyBankAcceptsATaughtPatternThatMatchesOrCompletesItsProfile() {
        assertEquals(7, bundled.size)
        bundled.forEach { base -> samples.forEach { sample ->
            val bank = base.banks.single().bankId
            val input = input(base, sample)
            val interpretation = PatternFactInterpreter(taught(base, sample, sample.family)).interpretRouted(input, BankId(bank))
            val label = "$bank ${sample.body.lines().first()}: ${interpretation.diagnostics.map { it.code }}"
            assertEquals(label, ParseStatus.SUCCESS, interpretation.status)
            assertEquals(label, sample.family, interpretation.facts.family)
            assertEquals(label, "9999", (interpretation.facts.destinationAccount ?: interpretation.facts.sourceAccount)!!.maskedNumber)
            assertTrue(label, InterpretationDraftMapper().map(input, interpretation, Bank(bank)) is ParseResult.Success)
        } }
    }

    @Test fun aTaughtFamilyNeverOverridesADifferentRecognizedFamily() {
        bundled.forEach { base -> samples.forEach { sample ->
            val bank = base.banks.single().bankId
            val input = input(base, sample)
            val recognized = PatternFactInterpreter(base).interpretRouted(input, BankId(bank)).facts.family
            if (recognized == MessageFamily.UNKNOWN) return@forEach
            assertEquals("$bank", sample.family, recognized)
            val conflicting = PatternFactInterpreter(taught(base, sample, MessageFamily.REFUND)).interpretRouted(input, BankId(bank))
            assertEquals("$bank", listOf("PATTERN_CONFLICT"), conflicting.diagnostics.map { it.code })
        } }
    }

    @Test fun barqRecognizesTheseFamiliesButNeedsATaughtLocalAccount() {
        val barq = bundled.single { it.banks.single().bankId == "BANK_BARQ" }
        samples.forEach { sample ->
            val result = PatternFactInterpreter(barq).interpretRouted(input(barq, sample), BankId("BANK_BARQ"))
            assertEquals(sample.body, sample.family, result.facts.family)
            assertEquals(sample.body, ParseStatus.REVIEW_REQUIRED, result.status)
            assertTrue(sample.body, result.diagnostics.any { it.code.startsWith("MISSING_TRANSFER_") })
        }
        val topUp = PatternFactInterpreter(barq).interpretRouted(input(barq, samples.first()), BankId("BANK_BARQ"))
        assertEquals(0, topUp.facts.amount!!.amount.compareTo("400".toBigDecimal()))
        assertEquals(BankNetwork.INTER_BANK, topUp.facts.bankNetwork)
    }

    @Test fun aTaughtReadingThatAgreesWithACompleteBuiltInPostsInsteadOfConflicting() {
        val aljazira = bundled.single { it.banks.single().bankId == "BANK_ALJAZIRA" }
        fun purchase(amount: String) = Sample("شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR 60.99\nلدى: TEST\nفي: 2026-10-06 13:57",
            MessageFamily.PURCHASE, "card", listOf("card" to "7271", "amount" to amount, "merchant" to "TEST", "occurred_at" to "2026-10-06 13:57"))
        val input = input(aljazira, purchase("SAR 60.99"))
        val builtIn = PatternFactInterpreter(aljazira).interpretRouted(input, BankId("BANK_ALJAZIRA"))
        assertEquals(builtIn.diagnostics.toString(), ParseStatus.SUCCESS, builtIn.status)
        fun teach(amount: String) = taught(aljazira, purchase(amount), MessageFamily.PURCHASE,
            PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING, cardBank = BankId("BANK_ALJAZIRA")))

        val agreeing = PatternFactInterpreter(teach("SAR 60.99")).interpretRouted(input, BankId("BANK_ALJAZIRA"))
        assertEquals(agreeing.diagnostics.toString(), ParseStatus.SUCCESS, agreeing.status)
        assertEquals(builtIn.facts, agreeing.facts)

        // A different amount (here a slot ending early) is still a conflict for the user to settle.
        val disagreeing = PatternFactInterpreter(teach("SAR 60.9")).interpretRouted(input, BankId("BANK_ALJAZIRA"))
        assertEquals(listOf("PATTERN_CONFLICT"), disagreeing.diagnostics.map { it.code })
    }

    @Test fun aTaughtPatternCompletingABuiltInReadingMustReadTheSameAmount() {
        val barq = bundled.single { it.banks.single().bankId == "BANK_BARQ" }
        val sample = samples.first()
        val input = input(barq, sample)
        assertEquals(ParseStatus.REVIEW_REQUIRED, PatternFactInterpreter(barq).interpretRouted(input, BankId("BANK_BARQ")).status)
        // The slot reads 400.0 in the declared USD, so the amount differs from the built-in 400 SAR.
        val wrongAmount = Sample(sample.body, sample.family, sample.reference,
            sample.fields.map { if (it.first == "amount") "amount" to "400.0" else it })
        val config = taught(barq, wrongAmount, sample.family, linkedContract(barq, sample, sample.family).copy(currency = Currency.USD))
        val result = PatternFactInterpreter(config).interpretRouted(input, BankId("BANK_BARQ"))
        assertEquals(listOf("PATTERN_CONFLICT"), result.diagnostics.map { it.code })
    }
}
