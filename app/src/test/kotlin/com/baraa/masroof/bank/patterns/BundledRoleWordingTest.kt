package com.baraa.masroof.bank.patterns

import com.baraa.masroof.testsupport.bundledPatterns
import com.baraa.masroof.engine.bank.Interpretation
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MessageInput
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.finance.value.BankId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Bundled role wording; fictional values in production layouts. */
class BundledRoleWordingTest {
    @Test fun barqAccountLinesAreAccountsNotParties() {
        val incoming = parse("BANK_BARQ", "barq", "Credit transfer Local\nAmount: 43.25 SAR\nFrom: Test Sender\nFrom a/c: **8765\nBank: NCBKSARI\n2026-10-20 12:30")
        assertEquals(MessageFamily.TRANSFER_IN, incoming.facts.family)
        assertEquals("Test Sender", incoming.facts.counterparty)
        assertEquals("8765", incoming.facts.sourceAccount?.maskedNumber)
        assertFalse(incoming.diagnostics.any { it.code == "CONFLICTING_counterparty" })
        val outgoing = parse("BANK_BARQ", "barq", "Debit Transfer Internal\nAmount: 43.25 SAR\nTo: Test Receiver\nTo A/C:**8765\n2026-10-20 12:30")
        assertEquals(MessageFamily.TRANSFER_OUT, outgoing.facts.family)
        assertEquals("Test Receiver", outgoing.facts.counterparty)
        assertEquals("8765", outgoing.facts.destinationAccount?.maskedNumber)
        assertFalse(outgoing.diagnostics.any { it.code == "CONFLICTING_counterparty" })
    }

    @Test fun snbCompactRecipientLineIsTheDestinationAccount() {
        val result = parse("BANK_SNB", "SNB-AlAhli", "حوالة صادرة داخلية ب43.25 SAR\nل8765* مستفيد تجريبي\n2026-10-20 12:30")
        assertEquals(MessageFamily.TRANSFER_OUT, result.facts.family)
        assertEquals("8765", result.facts.destinationAccount?.maskedNumber)
        val merchant = parse("BANK_SNB", "SNB-AlAhli", "حوالة صادرة داخلية ب43.25 SAR\nلدى متجر 8765\n2026-10-20 12:30")
        assertNull(merchant.facts.destinationAccount)
    }

    private fun parse(bank: String, sender: String, body: String): Interpretation {
        val config = bundledPatterns(bank)
        return PatternFactInterpreter(config).interpretRouted(
            MessageInput("wording", sender, body, Instant.parse("2026-10-20T12:00:00Z"), true), BankId(bank))
    }
}
