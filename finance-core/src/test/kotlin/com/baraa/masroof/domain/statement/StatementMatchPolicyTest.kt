package com.baraa.masroof.domain.statement

import com.baraa.masroof.domain.model.Bank
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatementMatchPolicyTest {
    @Test
    fun clockWindowRemainsInclusiveAndDateOnlyHasNoClockConstraint() {
        val clock = LocalDateTime.parse("2026-10-23T12:00:00")
        assertTrue(StatementMatchPolicy.bookingTimesCompatible(clock, clock.plusMinutes(10)))
        assertTrue(StatementMatchPolicy.bookingTimesCompatible(clock, clock.minusMinutes(10)))
        assertFalse(StatementMatchPolicy.bookingTimesCompatible(clock, clock.plusMinutes(10).plusSeconds(1)))
        assertTrue(StatementMatchPolicy.bookingTimesCompatible(null, clock))
        assertFalse(StatementMatchPolicy.datesMatch(clock.toLocalDate(), clock.toLocalDate().plusDays(1)))
    }

    @Test
    fun suppliedReferenceNeedsExactlyOneComparableLedgerReference() {
        assertTrue(StatementMatchPolicy.referenceSupported(" ABC ", setOf("abc")))
        assertFalse(StatementMatchPolicy.referenceSupported("abc", emptySet()))
        assertFalse(StatementMatchPolicy.referenceSupported("abc", setOf("abc", "def")))
        assertTrue(StatementMatchPolicy.referenceSupported(null, emptySet()))
    }

    @Test
    fun accountIdentityRequiresCanonicalBankQualifiedAccount() {
        assertEquals(
            StatementMatchPolicy.QualifiedAccount(Bank("D360"), "3001"),
            StatementMatchPolicy.qualifiedAccount("account:D360:3001"),
        )
        listOf("account:3001", "card:D360:3001", "account:UNKNOWN:3001", "account:D360: 3001").forEach {
            assertNull(StatementMatchPolicy.qualifiedAccount(it), it)
        }
    }
}
