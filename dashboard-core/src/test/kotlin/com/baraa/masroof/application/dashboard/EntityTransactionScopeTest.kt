package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.Bank
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntityTransactionScopeTest {
    @Test
    fun matchesAccount_sameLast4AtAnotherBankIsNotThisAccount() {
        val account = EntityTransactionScope.account(Bank.BANK_ALJAZIRA, "1234")

        assertTrue(EntityTransactionScope.matchesAccount("account:BANK_ALJAZIRA:1234", account))
        assertFalse(EntityTransactionScope.matchesAccount("account:BANK_STC:1234", account))
        // A legacy id with no bank segment keeps the last-four fallback.
        assertTrue(EntityTransactionScope.matchesAccount("account:1234", account))
    }
}
