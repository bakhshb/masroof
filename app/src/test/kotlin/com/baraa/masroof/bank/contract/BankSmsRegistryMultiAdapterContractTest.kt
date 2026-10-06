package com.baraa.masroof.bank.contract

import com.baraa.masroof.bank.BankRoutingResult
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.BankSmsRegistry
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BankSmsRegistryMultiAdapterContractTest {
    private val cases = BankSmsAdapterContractTest.cases()

    @Test
    fun registry_routesStubAndProductionAdaptersIndependently() {
        val registry = BankSmsRegistry(adapters = cases.map { it.adapter })

        val alJazira = registry.route("AlJazira", "purchase body")
        val stub = registry.route(StubBankSmsAdapter.SENDER, "any body")

        assertTrue(alJazira is BankRoutingResult.Matched)
        assertTrue(stub is BankRoutingResult.Matched)
    }

    @Test
    fun crossAdapterSenderSamples_areClaimedByExactlyOneAdapter() {
        for (case in cases) {
            for (sms in case.allSenderSamples()) {
                val claimants = cases
                    .filter { it.adapter.detect(sms.sender, sms.body) is BankDetectionResult.Detected }
                    .map { it.adapter.bank }
                assertEquals(
                    "sample '${sms.label}' (${sms.sender}) must be claimed only by ${case.adapter.bank.id}",
                    listOf(case.adapter.bank),
                    claimants,
                )
            }
        }
    }

    @Test
    fun registrationOrder_doesNotChangeRoutedBank() {
        val forward = BankSmsRegistry(cases.map { it.adapter })
        val reversed = BankSmsRegistry(cases.map { it.adapter }.reversed())
        for (case in cases) {
            for (sms in case.allSenderSamples()) {
                val a = forward.route(sms.sender, sms.body)
                val b = reversed.route(sms.sender, sms.body)
                assertTrue("${sms.label}: forward route $a", a is BankRoutingResult.Matched)
                assertTrue("${sms.label}: reversed route $b", b is BankRoutingResult.Matched)
                assertEquals(sms.label, case.adapter.bank, (a as BankRoutingResult.Matched).adapter.bank)
                assertEquals(sms.label, case.adapter.bank, (b as BankRoutingResult.Matched).adapter.bank)
            }
        }
    }

    @Test
    fun knownNegativeSenders_areNotClaimedByAnyAdapterOutsideTheirBank() {
        val registry = BankSmsRegistry(cases.map { it.adapter })
        for (case in cases) {
            for (sender in case.samples.negativeSenders) {
                when (val route = registry.route(sender, "contract body")) {
                    is BankRoutingResult.Matched ->
                        assertTrue(
                            "negative sender '$sender' for ${case.adapter.bank.id} routed to its own bank",
                            route.adapter.bank != case.adapter.bank,
                        )
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun overlappingAdapters_routeAmbiguous_inEveryRegistrationOrder() {
        val alJazira = cases.first { it.adapter.bank == Bank.BANK_ALJAZIRA }
        val lookalike = OverlappingAdapter(claimedSender = "AlJazira")
        val adapters = cases.map { it.adapter } + lookalike
        val orders = listOf(adapters, adapters.reversed(), listOf(lookalike) + cases.map { it.adapter })
        val sms = alJazira.samples.financial.first()

        val routes = orders.map { BankSmsRegistry(it).route(sms.sender, sms.body) }

        routes.forEach { route ->
            assertTrue("expected Ambiguous, got $route", route is BankRoutingResult.Ambiguous)
            assertEquals(
                listOf(Bank.BANK_ALJAZIRA, lookalike.bank).sortedBy { it.id },
                (route as BankRoutingResult.Ambiguous).banks,
            )
        }
        assertEquals(1, routes.toSet().size)
        assertEquals(0, lookalike.parseCalls)
    }

    private class OverlappingAdapter(private val claimedSender: String) : BankSmsAdapter {
        override val bank: Bank = Bank("LOOKALIKE_BANK")
        var parseCalls = 0
            private set

        override fun detect(sender: String, body: String): BankDetectionResult =
            if (sender == claimedSender) {
                BankDetectionResult.Detected(bank, Confidence(score = 1.0), listOf("sender:$sender"))
            } else {
                BankDetectionResult.Unknown(listOf("sender_not_recognized_as_lookalike"))
            }

        override fun parse(input: SmsParseInput): ParseResult {
            parseCalls++
            return ParseResult.Unsupported("lookalike")
        }
    }

    private fun BankSmsAdapterContractCase.allSenderSamples(): List<ContractSms> =
        samples.positiveSenders.map { ContractSms("sender:$it", it, "contract body") } +
            samples.financial + samples.nonFinancial + samples.noAutomaticFinancialOutput
}
