package com.baraa.masroof.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDestinationNavigationTest {
    private val alJaziraCards = SettingsDestination.BankCards("BANK_ALJAZIRA")

    @Test
    fun encodeDecode_newDestinations_roundTrip() {
        assertEquals(SettingsDestination.MyAccounts, decodeSettingsDestination("my_accounts"))
        assertEquals(SettingsDestination.MyCards, decodeSettingsDestination("my_cards"))
        assertEquals(SettingsDestination.MyLoans, decodeSettingsDestination("my_loans"))
        assertEquals(SettingsDestination.MyCommitments, decodeSettingsDestination("my_commitments"))
        assertEquals(
            SettingsDestination.CommitmentDetail("cmt_abc"),
            decodeSettingsDestination("commitment:cmt_abc"),
        )
        assertEquals(SettingsDestination.App, decodeSettingsDestination("app"))
        assertEquals(SettingsDestination.DataBackup, decodeSettingsDestination("data_backup"))
        assertEquals(
            SettingsDestination.StatementReconciliation,
            decodeSettingsDestination("statement_reconciliation"),
        )
        assertEquals(SettingsDestination.DesignCatalog, decodeSettingsDestination("design_catalog"))
    }

    @Test
    fun pop_fromDeepLink_leavesSettings() {
        val fromCardsSummary = replaceSettingsStack(alJaziraCards)
        val fromDashboardAbout = replaceSettingsStack(SettingsDestination.About)
        val fromDataBackup = replaceSettingsStack(SettingsDestination.DataBackup)

        assertNull(popSettingsStack(fromCardsSummary))
        assertNull(popSettingsStack(fromDashboardAbout))
        assertNull(popSettingsStack(fromDataBackup))
    }

    @Test
    fun pop_fromHubChild_returnsToHub() {
        val hub = replaceSettingsStack(SettingsDestination.Hub)
        val app = pushSettingsDestination(hub, SettingsDestination.App)

        assertEquals(hub, popSettingsStack(app))
    }

    @Test
    fun pop_walksVisitHistory_notSyntheticParents() {
        val hub = replaceSettingsStack(SettingsDestination.Hub)
        val cardsList = pushSettingsDestination(hub, SettingsDestination.MyCards)
        val cards = pushSettingsDestination(cardsList, alJaziraCards)

        assertEquals(cardsList, popSettingsStack(cards))
        assertEquals(hub, popSettingsStack(cardsList))
        assertNull(popSettingsStack(hub))
    }

    @Test
    fun manageCardsThenOpenBank_backReturnsToCategoryListThenOrigin() {
        val fromCardsSummary = replaceSettingsStack(SettingsDestination.MyCards)
        val cards = pushSettingsDestination(fromCardsSummary, alJaziraCards)

        assertEquals(fromCardsSummary, popSettingsStack(cards))
        assertNull(popSettingsStack(fromCardsSummary))
    }
}
