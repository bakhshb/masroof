package com.baraa.masroof.engine.bank

import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InterpretationContractTest {
    private val version = InterpretationVersion(1, "shadow-adapter-test", "legacy-fixture-1")

    @Test fun originalEvidenceOffsetsDoNotUseFoldedArabicText() {
        val input = MessageInput("sms-1", "BANK", "شراء بمبلغ 10.00 SAR", Instant.EPOCH)
        val start = input.body.indexOf("10.00")
        assertEquals("10.00", Evidence("amount-label", "amount", start, start + 5).slice(input))
    }

    @Test fun futureContractIsNotSilentlySupported() {
        assertTrue(version.isSupported())
        assertFalse(version.copy(contract = 2).isSupported())
    }

    @Test fun unknownRoutingAndFamilyRequireExplicitBlockers() {
        assertThrows(IllegalArgumentException::class.java) {
            Interpretation("sms-1", version, null, Facts(MessageFamily.UNKNOWN), ParseStatus.REVIEW_REQUIRED,
                null, emptyList(), emptySet())
        }
        val unresolved = Interpretation("sms-1", version, null, Facts(MessageFamily.UNKNOWN),
            ParseStatus.REVIEW_REQUIRED, null, emptyList(),
            setOf(Blocker.AMBIGUOUS_BANK, Blocker.UNKNOWN_FAMILY))
        assertEquals(2, unresolved.blockers.size)
    }
}
