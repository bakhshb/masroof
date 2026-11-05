package com.baraa.masroof.application.patterns

import com.baraa.masroof.application.interpretation.ConfiguredPatternAdapters

import com.baraa.masroof.bank.*
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.parsing.model.*
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MoneyDirection
import com.baraa.masroof.engine.bank.patterns.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class MessagePatternRoutingTest {
    private val body = "Pay qattah\nAmount: 10SAR\nTo: TEST\nAt: 15/10/26 12:30"
    private fun definition(sender: String = "STC Bank"): BankMessagePatterns {
        val fields = listOf("amount" to "10SAR", "counterparty" to "TEST", "occurred_at" to "15/10/26 12:30")
        val pattern = SavedMessagePattern.fromChoices("qattah", "BANK_STC", sender, "TRANSFER_OUT", body,
            fields.map { (role, value) -> FieldChoice(role, body.indexOf(value), body.indexOf(value) + value.length) })
        return BankMessagePatterns("BANK_STC", listOf(sender), patterns = listOf(pattern))
    }

    @Test fun savedBanksBecomeRoutableWithoutRecreatingTheRegistryAndDisabledPatternsStayReviewable() {
        val directory = Files.createTempDirectory("pattern-routing-").toFile()
        try {
            val store = MessagePatternStore(directory.resolve("patterns.json"))
            val provider = ConfiguredPatternAdapters(store)
            val registry = BankSmsRegistry(emptyList(), provider::snapshot)
            assertTrue(registry.route("STC Bank", body) is BankRoutingResult.NotMatched)
            val bank = definition()
            store.saveBank(0, bank)
            val matched = registry.route("STC Bank", body) as BankRoutingResult.Matched
            assertEquals(Bank("BANK_STC"), matched.adapter.bank)
            val input = SmsParseInput("route", "STC Bank", body, Instant.parse("2026-10-15T10:00:00Z"))
            assertTrue(matched.adapter.parse(input) is ParseResult.ReviewRequired)
            val approved = bank.patterns.single().copy(revision = 2,
                factContract = PatternFactContract(MessageFamily.TRANSFER_OUT, MoneyDirection.OUTGOING))
            store.saveBank(1, bank.copy(patterns = listOf(approved)))
            val adapter = requireNotNull(registry.adapterFor(Bank("BANK_STC")))
            assertTrue(adapter.parse(input) is ParseResult.Success)
            assertEquals(adapter.parse(input), adapter.parse(input.copy(sender = "stored explicit choice")))
            store.saveBank(2, bank.copy(patterns = listOf(approved.copy(revision = 3, enabled = false))))
            assertTrue(registry.adapterFor(Bank("BANK_STC"))!!.parse(input) is ParseResult.ReviewRequired)
            assertTrue(registry.route("STC Bank", body) is BankRoutingResult.Matched)
            // A sender containing a configured name is held for review, never claimed or dropped.
            assertTrue(registry.route("STC BankX", body) is BankRoutingResult.SuspectedBank)
        } finally { directory.deleteRecursively() }
    }

    @Test fun configuredSendersPreserveBankCollisions() {
        val directory = Files.createTempDirectory("pattern-collision-").toFile()
        try {
            val store = MessagePatternStore(directory.resolve("patterns.json"))
            val first = definition("Shared")
            store.saveBank(0, first)
            store.saveBank(1, BankMessagePatterns("OTHER", listOf("Shared")))
            val provider = ConfiguredPatternAdapters(store)
            val registry = BankSmsRegistry(emptyList(), provider::snapshot)
            assertEquals(listOf("BANK_STC", "OTHER"), (registry.route("Shared", body) as BankRoutingResult.Ambiguous).banks.map { it.id })
        } finally { directory.deleteRecursively() }
    }
}
