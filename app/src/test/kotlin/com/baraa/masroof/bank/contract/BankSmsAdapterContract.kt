package com.baraa.masroof.bank.contract

import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** One sample SMS fed through an adapter by the shared contract. */
data class ContractSms(
    val label: String,
    val sender: String,
    val body: String,
)

/**
 * Per-adapter evidence the shared contract runs against.
 *
 * Real adapters must provide fixture-backed [financial] and [nonFinancial] samples;
 * a superficially valid adapter with no fixtures cannot pass.
 */
data class BankSmsAdapterContractSamples(
    val positiveSenders: List<String>,
    val negativeSenders: List<String>,
    val financial: List<ContractSms>,
    val nonFinancial: List<ContractSms>,
    /** Unsupported / unknown / ambiguous formats from a recognized sender. */
    val noAutomaticFinancialOutput: List<ContractSms>,
)

data class BankSmsAdapterContractCase(
    val adapter: BankSmsAdapter,
    val samples: BankSmsAdapterContractSamples,
) {
    override fun toString(): String = adapter.bank.id
}

/**
 * Shared behavioral contract every [BankSmsAdapter] implementation must satisfy.
 */
object BankSmsAdapterContract {
    private val RECEIVED_AT: Instant = Instant.parse("2026-08-11T08:00:00Z")

    private val NON_FINANCIAL_FAMILIES = setOf(
        MessageFamily.OTP,
        MessageFamily.NON_FINANCIAL,
        MessageFamily.BALANCE_NOTICE,
        MessageFamily.UNKNOWN,
    )

    fun verify(case: BankSmsAdapterContractCase) {
        val adapter = case.adapter
        val samples = case.samples
        assertFalse(
            "Adapter bank must be a known registry identity",
            adapter.bank == Bank.UNKNOWN,
        )
        verifyFixtureCoverage(adapter, samples)
        samples.positiveSenders.forEach { verifyClaimsSender(adapter, it) }
        samples.negativeSenders.forEach { verifyDoesNotClaimSender(adapter, it) }
        samples.financial.forEach { verifyFinancial(adapter, it) }
        samples.nonFinancial.forEach { verifyNonFinancial(adapter, it) }
        samples.noAutomaticFinancialOutput.forEach { verifyNoAutomaticFinancialOutput(adapter, it) }
        (samples.financial + samples.nonFinancial).forEach { verifyParseTrustsRoute(adapter, it) }
        verifyArbitraryInputStaysInBank(adapter)
    }

    /** Routing is authoritative: parse output must not depend on a second sender check. */
    private fun verifyParseTrustsRoute(adapter: BankSmsAdapter, sms: ContractSms) {
        val routed = parse(adapter, sms)
        val unroutedSender = parse(adapter, sms.copy(sender = "unrouted-sender"))
        assertEquals("${sms.label}: parse must not re-detect the sender", routed, unroutedSender)
    }

    fun parse(adapter: BankSmsAdapter, sms: ContractSms): ParseResult =
        adapter.parse(
            SmsParseInput(
                rawSmsId = "contract-${sms.label}",
                sender = sms.sender,
                body = sms.body,
                receivedAt = RECEIVED_AT,
            ),
        )

    fun parsedEvent(result: ParseResult): ParsedEvent? =
        when (result) {
            is ParseResult.Success -> result.event
            is ParseResult.Partial -> result.event
            is ParseResult.ReviewRequired -> result.event
            is ParseResult.NonFinancial -> result.event
            is ParseResult.Unsupported,
            is ParseResult.Invalid,
            -> null
        }

    private fun verifyFixtureCoverage(adapter: BankSmsAdapter, samples: BankSmsAdapterContractSamples) {
        val bank = adapter.bank.id
        assertTrue("$bank: at least one positive sender sample", samples.positiveSenders.isNotEmpty())
        assertTrue("$bank: at least one known-negative sender sample", samples.negativeSenders.isNotEmpty())
        assertTrue("$bank: at least one valid financial fixture", samples.financial.isNotEmpty())
        assertTrue("$bank: at least one non-financial fixture", samples.nonFinancial.isNotEmpty())
    }

    private fun verifyClaimsSender(adapter: BankSmsAdapter, sender: String) {
        when (val detection = adapter.detect(sender, "contract body")) {
            is BankDetectionResult.Detected ->
                assertEquals("positive sender '$sender' must report adapter bank", adapter.bank, detection.bank)
            is BankDetectionResult.Unknown ->
                fail("${adapter.bank.id} must claim positive sender '$sender': ${detection.reasons}")
            is BankDetectionResult.Suspected ->
                fail("${adapter.bank.id} must claim positive sender '$sender', not merely suspect it")
        }
    }

    private fun verifyDoesNotClaimSender(adapter: BankSmsAdapter, sender: String) {
        when (val detection = adapter.detect(sender, "contract body")) {
            is BankDetectionResult.Detected ->
                fail("${adapter.bank.id} must not claim known-negative sender '$sender'")
            is BankDetectionResult.Unknown ->
                assertTrue("Unknown detection must explain why", detection.reasons.isNotEmpty())
            is BankDetectionResult.Suspected ->
                assertTrue("Suspicion must carry evidence and must not name an adapter", detection.evidence.isNotEmpty())
        }
    }

    private fun verifyFinancial(adapter: BankSmsAdapter, sms: ContractSms) {
        verifyClaimsSender(adapter, sms.sender)
        val result = parse(adapter, sms)
        assertTrue("${sms.label}: financial sample must parse SUCCESS, got $result", result is ParseResult.Success)
        val event = (result as ParseResult.Success).event
        assertEquals(sms.label, adapter.bank, event.bank)
        assertFalse(
            "${sms.label}: financial sample must have a financial family, got ${event.messageFamily}",
            event.messageFamily in NON_FINANCIAL_FAMILIES,
        )
        assertNotNull("${sms.label}: financial sample must carry an amount", event.amount)
    }

    private fun verifyNonFinancial(adapter: BankSmsAdapter, sms: ContractSms) {
        verifyClaimsSender(adapter, sms.sender)
        val result = parse(adapter, sms)
        assertTrue(
            "${sms.label}: non-financial sample must stay non-financial, got $result",
            result is ParseResult.NonFinancial,
        )
        parsedEvent(result)?.let { event ->
            assertEquals(sms.label, adapter.bank, event.bank)
            assertEquals(sms.label, ParseStatus.NON_FINANCIAL, event.parseStatus)
        }
    }

    private fun verifyNoAutomaticFinancialOutput(adapter: BankSmsAdapter, sms: ContractSms) {
        val result = parse(adapter, sms)
        assertFalse(
            "${sms.label}: unsupported/ambiguous sample must not produce automatic financial output, got $result",
            result is ParseResult.Success,
        )
        parsedEvent(result)?.let { event ->
            assertEquals(sms.label, adapter.bank, event.bank)
            assertTrue(
                "${sms.label}: event must not be automation-eligible, was ${event.parseStatus}",
                event.parseStatus != ParseStatus.SUCCESS,
            )
        }
    }

    private fun verifyArbitraryInputStaysInBank(adapter: BankSmsAdapter) {
        val result = parse(adapter, ContractSms("arbitrary", "contract-sender", "contract body"))
        parsedEvent(result)?.let { event ->
            assertEquals("parsed event bank must equal adapter bank", adapter.bank, event.bank)
        }
        assertFalse("arbitrary text must not parse SUCCESS", result is ParseResult.Success)
    }
}
