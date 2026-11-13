package com.baraa.masroof.integration.replica

import com.baraa.masroof.testsupport.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.dashboard.DashboardOverview
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.parsing.model.*
import com.baraa.masroof.sms.datasource.*
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.testsupport.engineFixtureAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.*

/** Rules-only and assisted adapters traverse the same Room/reconciliation/dashboard implementation. */
@RunWith(RobolectricTestRunner::class)
class ReplicaFinancialParityTest {
    private val host: Context get() = ApplicationProvider.getApplicationContext()
    private val storage = java.util.UUID.randomUUID().toString()
    private fun referenceContext() = TestStorageContext(host, "parity-reference-$storage")
    private fun actualContext() = TestStorageContext(host, "parity-actual-$storage")
    @Test fun importsOwnershipLedgerDashboardReplayAndReopenMatchProduction() = runBlocking { exercise(ReferenceHistory.names) }
    @Test fun fiveReportedMessagesPostWithoutRepeatedReviews() = runBlocking { exercise(ReferenceHistory.reported) }
    @Test fun supportedFormatsPostInTheImportPassWithoutLearningOrAManualRefresh() = runBlocking {
        val context = referenceContext()
        val names = listOf("personal","vehicle","auto","house","mortgage","generic").map { "supported_loan_$it" } +
            listOf("supported_withdrawal_en","supported_fee_title") +
            listOf("same_bank_other_out","same_bank_other_in","same_bank_own","other_bank_same_suffix").map { "supported_transfer_$it" }
        val source = ReferenceHistory.source(host,names)
        val reference = AppContainer(context,bankAdapterOverride = engineFixtureAdapter(profile = com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile.current()),smsDataSourceOverride = source)
        var actual = AppContainer(actualContext(),smsDataSourceOverride = source)
        try {
            for (c in listOf(reference,actual)) {
                // Existing confirmed ownership, including the same suffix in a different bank.
                for (suffix in listOf("3001","3002","5001")) c.review.onboardingOwnershipWorkflow.confirmAccountOwned(AccountReference(Bank.BANK_ALJAZIRA,suffix))
                c.review.onboardingOwnershipWorkflow.markAccountExternal(AccountReference(Bank.BANK_ALJAZIRA,"5002"))
                val result = c.sms.historicalSmsScanner.scan(Instant.EPOCH)
                assertEquals(result.toString(),0,result.failed)
                assertTrue("The import pass itself must post every supported message", c.storage.financialTransactionRepository.listAll().size == names.size)
                assertTrue("No additional classification or model refresh",c.storage.reviewRepository.listRequired().isEmpty())
                assertTrue(c.storage.parsedEventRepository.listAll().all { it.event.confidence.reasons.none { reason -> reason.startsWith("family_origin:") } })
            }
            val rows = actual.storage.financialTransactionRepository.listAll().sortedBy { it.id }
            val referenceRows = reference.storage.financialTransactionRepository.listAll().sortedBy { it.id }
            // Reviewed engine improvement: explicit external bank identity, never local suffix ownership.
            val external = rows.single { it.amount.amount.toPlainString() == "64.00" }
            assertEquals("account:BANK_D360:5001", external.destinationContainerId)
            assertEquals(referenceRows, rows)
            val expectedTypes = mapOf("61.00" to FinancialTransactionType.EXTERNAL_TRANSFER_OUT,"62.00" to FinancialTransactionType.EXTERNAL_TRANSFER_IN,
                "63.00" to FinancialTransactionType.SELF_TRANSFER,"64.00" to FinancialTransactionType.EXTERNAL_TRANSFER_OUT)
            expectedTypes.forEach { (amount,type) -> assertEquals(type,rows.single { it.amount.amount.toPlainString()==amount }.type) }
            assertEquals(6,rows.count { it.type == FinancialTransactionType.LOAN_REPAYMENT })
            val overview = reference.dashboard.dashboardService.loadOverview(ReferenceHistory.period())
            assertOverview(overview,actual.dashboard.dashboardService.loadOverview(ReferenceHistory.period()))
            val replay = actual.sms.historicalSmsScanner.scan(Instant.EPOCH)
            assertEquals(names.size,replay.duplicates)
            assertEquals(rows,actual.storage.financialTransactionRepository.listAll().sortedBy { it.id })
            actual.close()
            actual = AppContainer(actualContext(),smsDataSourceOverride = source)
            assertEquals(rows,actual.storage.financialTransactionRepository.listAll().sortedBy { it.id })
            assertOverview(overview,actual.dashboard.dashboardService.loadOverview(ReferenceHistory.period()))
        } finally { reference.close(); actual.close() }
    }
    private suspend fun exercise(names: List<String>) {
        val referenceContext = referenceContext()
        val source = ReferenceHistory.source(host,names)
        val expected = AppContainer(referenceContext,bankAdapterOverride = engineFixtureAdapter(profile = com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile.current()),smsDataSourceOverride = source)
        var actual = AppContainer(actualContext(),smsDataSourceOverride = source)
        try {
            for (container in listOf(expected,actual)) {
                val scan = container.sms.historicalSmsScanner.scan(Instant.EPOCH)
                assertEquals(scan.toString(),0,scan.failed)
                assertEquals(names.size,scan.scanned)
                confirmOwnership(container)
            }
            val expectedRows = expected.storage.financialTransactionRepository.listAll().sortedBy { it.id }
            val actualRows = actual.storage.financialTransactionRepository.listAll().sortedBy { it.id }
            assertEquals("Ledger rows",expectedRows,actualRows)
            assertTrue("Financial data must be posted",actualRows.size == names.size)
            val period = ReferenceHistory.period()
            val baseline = expected.dashboard.dashboardService.loadOverview(period)
            assertOverview(baseline,actual.dashboard.dashboardService.loadOverview(period))
            assertTrue(actual.dashboard.dashboardService.loadOverview(period).creditFacilities.hasContent)
            assertFalse(actual.storage.loanRegistryRepository.listAll().isEmpty())
            val reviews = actual.storage.reviewRepository.listRequired()
            assertEquals("All known fixture messages should be automatic after ownership",0,reviews.size)
            val replay = actual.sms.historicalSmsScanner.scan(Instant.EPOCH)
            assertEquals(0,replay.inserted)
            assertEquals(names.size,replay.duplicates)
            assertEquals(actualRows,actual.storage.financialTransactionRepository.listAll().sortedBy { it.id })
            actual.close()
            actual = AppContainer(actualContext(),smsDataSourceOverride = source)
            assertEquals(actualRows,actual.storage.financialTransactionRepository.listAll().sortedBy { it.id })
            assertOverview(baseline,actual.dashboard.dashboardService.loadOverview(period))
        } finally { expected.close(); actual.close() }
    }
    private suspend fun confirmOwnership(container: AppContainer) {
        container.storage.accountRegistryRepository.listAll().forEach {
            val ref = AccountReference(it.bank,it.maskedNumber)
            if (it.bank == Bank.BANK_ALJAZIRA) container.review.onboardingOwnershipWorkflow.confirmAccountOwned(ref)
            else container.review.onboardingOwnershipWorkflow.markAccountExternal(ref)
        }
        container.storage.cardRegistryRepository.listAll().forEach { container.review.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(it.bank,it.last4)) }
        container.review.transactionReconciliationService.reconcileStoredEvents()
        container.review.reviewWorkflowService.refreshReviewQueue()
    }
    private fun assertOverview(expected: DashboardOverview, actual: DashboardOverview) {
        assertEquals("Summary",expected.summary,actual.summary)
        assertEquals("Accounts",expected.currentAccount,actual.currentAccount)
        assertEquals("Cards",expected.creditFacilities,actual.creditFacilities)
        assertEquals("Loans",expected.loansOverview,actual.loansOverview)
        assertEquals("Commitments",expected.commitmentsOverview,actual.commitmentsOverview)
        assertEquals("Merchants",expected.merchantSpending,actual.merchantSpending)
        assertEquals("Daily trend",expected.dailySpendingTrend,actual.dailySpendingTrend)
        assertEquals("Transaction facts",expected.transactionFacts,actual.transactionFacts)
        assertEquals("Owned accounts",expected.ownedAccountContainerIds,actual.ownedAccountContainerIds)
    }
}

