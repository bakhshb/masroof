package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.OwnershipStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BankHierarchyBuilderTest {
    @Test
    fun debitLinkMatchesFullNumberSuffixOrNothing() {
        assertTrue(debitLinkMatchesAccount("1234", "1234"))
        assertTrue(debitLinkMatchesAccount("1234", "SA00001234"))
        assertTrue(debitLinkMatchesAccount("SA00001234", "1234"))
        assertFalse(debitLinkMatchesAccount("9999", "1234"))
        assertFalse(debitLinkMatchesAccount(null, "1234"))
    }

    @Test
    fun nestsLinkedMadaUnderCurrentAccountAndLeavesUnlinkedDebitAtBankLevel() {
        val bank = Bank.BANK_ALJAZIRA
        val ownedAccounts = listOf(
            account(masked = "****3001"),
        )
        val accountsFleet = AccountsSummary(
            accounts = listOf(
                OwnedAccount.from(
                    bank = bank,
                    maskedNumber = "****3001",
                    summary = CurrentAccountSummary.zero(Currency.SAR),
                ),
            ),
        )
        val creditFacilities = CreditFacilitiesOverview(
            facilities = emptyList(),
            debitCards = listOf(
                debitOverview(last4 = "5555", linkedMask = "3001"),
                debitOverview(last4 = "8888", linkedMask = null),
            ),
            currency = Currency.SAR,
        )

        val hierarchy = BankHierarchyBuilder.build(
            ownedAccounts = ownedAccounts,
            accountsFleet = accountsFleet,
            creditFacilities = creditFacilities,
        )

        val tree = hierarchy.banks.single()
        assertEquals(bank, tree.bank)
        assertEquals(1, tree.currentAccounts.size)
        assertEquals("****3001", tree.currentAccounts.single().maskedNumber)
        assertEquals(listOf("5555"), tree.currentAccounts.single().debitCards.map { it.last4 })
        assertEquals(listOf("8888"), tree.unlinkedDebitCards.map { it.last4 })
    }

    private fun account(masked: String): AccountRegistryEntry =
        AccountRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            maskedNumber = masked,
            ownership = OwnershipStatus.OWNED,
            accountType = AccountType.CURRENT,
            firstSeenRawSmsId = "sms",
            lastSeenRawSmsId = "sms",
        )

    private fun debitOverview(last4: String, linkedMask: String?): DebitCardOverview =
        DebitCardOverview(
            bank = Bank.BANK_ALJAZIRA,
            last4 = last4,
            displayLabel = "Mada ••$last4",
            linkedAccountLabel = linkedMask,
            linkedAccountMaskedNumber = linkedMask,
            network = null,
            salaryPeriodSpendingNet = SignedMoneyAmount(BigDecimal("10.00"), Currency.SAR),
            salaryPeriodLabel = "Aug",
        )
}
