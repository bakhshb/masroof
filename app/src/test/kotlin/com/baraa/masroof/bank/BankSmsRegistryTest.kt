package com.baraa.masroof.bank

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.parsing.model.BankDetectionResult
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.model.SmsParseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankSmsRegistryTest {

    @Test
    fun emptyRegistry_returnsNotMatched() {
        val registry = BankSmsRegistry(emptyList())

        val result = registry.route("AlJazira", "body")

        assertTrue(result is BankRoutingResult.NotMatched)
        assertEquals("sender_not_in_scope", (result as BankRoutingResult.NotMatched).reason)
    }

    @Test
    fun alJaziraSender_returnsMatchedAdapter() {
        val adapter = FakeBankSmsAdapter(
            bank = Bank.BANK_ALJAZIRA,
            detection = BankDetectionResult.Detected(
                bank = Bank.BANK_ALJAZIRA,
                confidence = Confidence(score = 1.0),
                evidence = emptyList(),
            ),
        )
        val registry = BankSmsRegistry(listOf(adapter))

        val result = registry.route("AlJazira", "body")

        assertTrue(result is BankRoutingResult.Matched)
        val matched = result as BankRoutingResult.Matched
        assertEquals(adapter, matched.adapter)
        assertEquals(Bank.BANK_ALJAZIRA, matched.detection.bank)
    }

    @Test
    fun nearMissSender_preservesUnknownReason() {
        val adapter = FakeBankSmsAdapter(
            bank = Bank.BANK_ALJAZIRA,
            detection = BankDetectionResult.Unknown(
                reasons = listOf("sender_not_recognized_as_bank_aljazira"),
            ),
        )
        val registry = BankSmsRegistry(listOf(adapter))

        val result = registry.route("OtherBank", "body")

        assertTrue(result is BankRoutingResult.NotMatched)
        assertEquals(
            "sender_not_recognized_as_bank_aljazira",
            (result as BankRoutingResult.NotMatched).reason,
        )
    }

    @Test
    fun unknownThenDetected_selectsSecondAdapter() {
        val first = FakeBankSmsAdapter(
            bank = Bank("FIRST"),
            detection = BankDetectionResult.Unknown(reasons = listOf("first_unknown")),
        )
        val second = FakeBankSmsAdapter(
            bank = Bank("SECOND"),
            detection = BankDetectionResult.Detected(
                bank = Bank("SECOND"),
                confidence = Confidence(score = 1.0),
                evidence = emptyList(),
            ),
        )
        val registry = BankSmsRegistry(listOf(first, second))

        val result = registry.route("sender", "body")

        assertTrue(result is BankRoutingResult.Matched)
        assertEquals(second, (result as BankRoutingResult.Matched).adapter)
    }

    @Test
    fun detectedThenDetected_isAmbiguousRegardlessOfOrder() {
        val first = detectingAdapter("FIRST")
        val second = detectingAdapter("SECOND")

        val forward = BankSmsRegistry(listOf(first, second)).route("sender", "body")
        val reversed = BankSmsRegistry(listOf(second, first)).route("sender", "body")

        assertTrue(forward is BankRoutingResult.Ambiguous)
        assertEquals(forward, reversed)
        val ambiguous = forward as BankRoutingResult.Ambiguous
        assertEquals(listOf(Bank("FIRST"), Bank("SECOND")), ambiguous.banks)
        assertEquals("ambiguous_bank_route", ambiguous.reason)
    }

    @Test
    fun singleCredibleAdapter_winsRegardlessOfRegistrationOrder() {
        val unknown = FakeBankSmsAdapter(
            bank = Bank("FIRST"),
            detection = BankDetectionResult.Unknown(reasons = listOf("first_unknown")),
        )
        val detected = detectingAdapter("SECOND")

        for (order in listOf(listOf(unknown, detected), listOf(detected, unknown))) {
            val result = BankSmsRegistry(order).route("sender", "body")
            assertTrue(result is BankRoutingResult.Matched)
            assertEquals(detected, (result as BankRoutingResult.Matched).adapter)
        }
    }

    @Test
    fun threeAdaptersTwoDetected_isAmbiguousWithOnlyClaimants() {
        val unknown = FakeBankSmsAdapter(
            bank = Bank("A_UNKNOWN"),
            detection = BankDetectionResult.Unknown(reasons = listOf("nope")),
        )
        val registry = BankSmsRegistry(listOf(detectingAdapter("Z_BANK"), unknown, detectingAdapter("M_BANK")))

        val result = registry.route("sender", "body")

        assertTrue(result is BankRoutingResult.Ambiguous)
        assertEquals(listOf(Bank("M_BANK"), Bank("Z_BANK")), (result as BankRoutingResult.Ambiguous).banks)
    }

    private fun detectingAdapter(id: String) = FakeBankSmsAdapter(
        bank = Bank(id),
        detection = BankDetectionResult.Detected(
            bank = Bank(id),
            confidence = Confidence(score = 1.0),
            evidence = emptyList(),
        ),
    )

    @Test
    fun adapterFor_returnsRegisteredAdapterByBank() {
        val first = FakeBankSmsAdapter(
            bank = Bank("FIRST"),
            detection = BankDetectionResult.Unknown(reasons = listOf("first_unknown")),
        )
        val second = FakeBankSmsAdapter(
            bank = Bank.BANK_ALJAZIRA,
            detection = BankDetectionResult.Unknown(reasons = listOf("aljazira_unknown")),
        )
        val registry = BankSmsRegistry(listOf(first, second))

        assertEquals(second, registry.adapterFor(Bank.BANK_ALJAZIRA))
        assertNull(registry.adapterFor(Bank("MISSING")))
    }

    @Test
    fun singleAdapterOrNull_onlyWhenExactlyOneAdapter() {
        val only = FakeBankSmsAdapter(
            bank = Bank.BANK_ALJAZIRA,
            detection = BankDetectionResult.Unknown(reasons = listOf("unused")),
        )
        val other = FakeBankSmsAdapter(
            bank = Bank("OTHER"),
            detection = BankDetectionResult.Unknown(reasons = listOf("unused")),
        )

        assertEquals(only, BankSmsRegistry(listOf(only)).singleAdapterOrNull())
        assertNull(BankSmsRegistry(emptyList()).singleAdapterOrNull())
        assertNull(BankSmsRegistry(listOf(only, other)).singleAdapterOrNull())
    }

    private class FakeBankSmsAdapter(
        override val bank: Bank,
        private val detection: BankDetectionResult,
    ) : BankSmsAdapter {
        override fun detect(sender: String, body: String): BankDetectionResult = detection

        override fun parse(input: SmsParseInput): ParseResult =
            ParseResult.Unsupported(reason = "fake_adapter")
    }
}
