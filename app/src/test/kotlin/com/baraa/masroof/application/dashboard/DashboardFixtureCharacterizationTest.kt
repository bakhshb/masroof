package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.parsing.fixtures.AlJaziraFixtureParseHarness
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.testsupport.CountingParsedEventRepository
import com.baraa.masroof.testsupport.CountingRawSmsRepository
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import com.baraa.masroof.testsupport.WriteRejectingFinancialTransactionRepository
import com.baraa.masroof.testsupport.WholeHistoryDashboardEvidenceSource
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * End-to-end characterization: on-disk Bank AlJazira fixtures → parse facts → dashboard helpers,
 * and scoped dashboard evidence ([DashboardEvidenceScope]) versus the former whole-history load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardFixtureCharacterizationTest {
    @Test
    fun scopedEvidence_matchesWholeHistoryProjection_forFixtureCorpus() = runBlocking<Unit> {
        DashboardLedgerWorld(context()).use { world ->
            world.importFixtureCorpus()
            assertScopedEqualsWholeHistory(world, months(YearMonth.of(2026, 4), YearMonth.of(2026, 10)))
        }
    }

    @Test
    fun scopedEvidence_matchesWholeHistoryProjection_acrossLongHistory() = runBlocking<Unit> {
        DashboardLedgerWorld(context()).use { world ->
            world.importFixtureCorpus()
            world.seedSyntheticHistory(DashboardLedgerWorld.SYNTHETIC_MONTHS)
            val projections = assertScopedEqualsWholeHistory(
                world,
                months(YearMonth.of(2024, 12), YearMonth.of(2026, 12)),
            )

            // The scenario must exercise every history fact the scope bounds.
            val facilities = projections.flatMap { it.creditFacilities.facilities }
            assertTrue(facilities.any { it.facilityDue != null })
            assertTrue(facilities.flatMap { it.allCards }.any { it.snapshot?.availableBalance != null })
            // Card seen only in one old plain credit SMS: a real row, not a registry placeholder.
            assertTrue(facilities.any { it.primary.last4 == "3333" && it.primary.statementPeriodLabel != null })
            val debitCards = projections.flatMap { it.creditFacilities.debitCards }
            assertTrue(debitCards.any { it.last4 == "2210" && it.linkedAccountMaskedNumber == "3001" })
            assertTrue(debitCards.any { it.last4 == "6666" && it.linkedAccountMaskedNumber == "3002" })
            val loans = projections.flatMap { it.loansOverview.loans }
            assertTrue(loans.any { it.loanType == LoanType.PERSONAL && it.remainingBalance != null })
            assertTrue(loans.any { it.loanType == LoanType.AUTO && it.latestInstallmentAmount != null })
            assertTrue(projections.any { it.commitmentsOverview.hasContent })
            assertTrue(
                projections.flatMap { it.transactions }.any {
                    it.merchant == "SPOTIFY" && it.exchangeRateSource == ExchangeRateSource.HISTORICAL_MERCHANT
                },
            )
            assertTrue(
                projections.any { projection ->
                    projection.transactions.any { it.amount.currency == Currency.USD } &&
                        projection.summary.excludedOtherCurrencyCount == 0
                },
            )
        }
    }

    @Test
    fun scopedLoad_readsBoundedEvidence_withoutWholeHistoryOrPerRowLookups() = runBlocking<Unit> {
        DashboardLedgerWorld(context()).use { world ->
            world.importFixtureCorpus()
            world.seedSyntheticHistory(DashboardLedgerWorld.SYNTHETIC_MONTHS)
            val parsed = CountingParsedEventRepository(world.parsedRepo)
            val raw = CountingRawSmsRepository(world.rawRepo)
            val recorder = RecordingEvidenceSource(
                DashboardEvidenceScope(
                    financialTransactionRepository = WriteRejectingFinancialTransactionRepository(world.ftRepo),
                    parsedEventRepository = parsed,
                    rawSmsRepository = raw,
                ),
            )
            val service = world.dashboardService(
                evidenceSource = recorder,
                parsedEventRepository = parsed,
                rawSmsRepository = raw,
            )

            val projection = service.loadProjection(FinancialPeriodPolicy.periodContaining(LocalDate.parse("2025-11-10")))

            assertTrue(projection.transactions.isNotEmpty())
            assertEquals(0, parsed.listAllCalls)
            assertEquals(0, raw.getByIdCalls)
            assertTrue(raw.getByIdsCalls > 0)
            val history = world.parsedRepo.listAll().size
            assertTrue(
                "scoped ${recorder.largest} of $history parsed rows",
                recorder.largest * 2 < history,
            )
        }
    }

    private suspend fun assertScopedEqualsWholeHistory(
        world: DashboardLedgerWorld,
        months: List<YearMonth>,
    ): List<DashboardProjection> {
        val wholeHistory = world.dashboardService(
            evidenceSource = WholeHistoryDashboardEvidenceSource(world.parsedRepo, world.rawRepo),
        )
        val scoped = world.dashboardService()
        val periods = months.map { FinancialPeriodPolicy.periodContaining(it.atDay(10)) }.distinct()
        return periods.map { period ->
            val expected = wholeHistory.loadProjection(period)
            assertEquals("period ${period.startDate}", expected, scoped.loadProjection(period))
            expected
        }
    }

    private fun months(from: YearMonth, toInclusive: YearMonth): List<YearMonth> =
        generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(toInclusive) }.toList()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private class RecordingEvidenceSource(private val delegate: DashboardEvidenceSource) : DashboardEvidenceSource {
        var largest = 0
            private set

        override suspend fun load(
            transactions: Collection<FinancialTransaction>,
            registryCards: List<CardRegistryEntry>,
            periodEndExclusive: Instant,
        ): DashboardEvidence = delegate.load(transactions, registryCards, periodEndExclusive).also(::record)

        override suspend fun extend(
            evidence: DashboardEvidence,
            transactions: Collection<FinancialTransaction>,
        ): DashboardEvidence = delegate.extend(evidence, transactions).also(::record)

        private fun record(evidence: DashboardEvidence) {
            largest = maxOf(largest, evidence.parsedRecords.size)
        }
    }

    @Test
    fun salaryTransferFixture_detectedAsSalaryIncome() {
        val record = AlJaziraFixtureParseHarness.parseRecord("transfer_in_salary_ar_001")
        assertEquals(true, record.details.salaryIncomeWording)

        val tx = FinancialTransaction(
            id = "tx-salary",
            type = FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            amount = Money.of("3191.68", Currency.SAR),
            occurredAt = Instant.parse("2026-07-27T01:12:00Z"),
            sourceContainerId = null,
            destinationContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf(record.event.id),
        )

        assertTrue(SalaryIncomeHeuristics.isSalaryIncome(tx, mapOf(record.event.id to record)))
    }

    @Test
    fun debitPurchaseFixture_infersLinkedAccountFromParseFact() {
        val record = AlJaziraFixtureParseHarness.parseRecord("purchase_pos_ar_debit_001")
        assertEquals(CardSmsChannel.DEBIT, record.details.cardSmsChannel)
        assertEquals("3001", record.details.debitSourceAccountLast4)

        assertEquals(
            "3001",
            DebitLinkedAccountInferrer.inferAccountLast4(
                bank = Bank.BANK_ALJAZIRA,
                cardLast4 = "2210",
                parsedRecords = listOf(record),
            ),
        )
    }

    @Test
    fun financingInstallmentFixture_resolvesLoanRepaymentContainer() {
        val record = AlJaziraFixtureParseHarness.parseRecord("financing_installment_ar_001")
        assertEquals(LoanType.PERSONAL, record.details.loanType)

        val tx = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf(record.event.rawSmsId)),
            type = FinancialTransactionType.FEE,
            amount = Money.of("3036.11", Currency.SAR),
            occurredAt = Instant.parse("2026-08-27T01:10:00Z"),
            sourceContainerId = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, "3001"),
            destinationContainerId = null,
            merchant = null,
            counterparty = record.event.counterparty,
            categoryId = null,
            linkedParsedEventIds = listOf(record.event.id),
        )

        val loanId = FinancialContainerIdFactory.loanId(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)
        assertEquals(loanId, LoanRepaymentAttribution.loanContainerId(tx, mapOf(record.event.id to record)))
        assertTrue(LoanRepaymentAttribution.isLoanRepayment(tx, mapOf(record.event.id to record)))
    }

    @Test
    fun googlePayDebitFixture_classifiesRegistryEntryAsDebit() {
        val record = AlJaziraFixtureParseHarness.parseRecord("purchase_pos_ar_debit_googlepay_001")
        assertEquals(CardSmsChannel.DEBIT, record.details.cardSmsChannel)

        val entry = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "8219",
            ownership = OwnershipStatus.OWNED,
            cardType = null,
            firstSeenRawSmsId = record.event.rawSmsId,
            lastSeenRawSmsId = record.event.rawSmsId,
        )

        assertTrue(
            CardRegistryDebitClassifier.isDebitRegistryEntry(
                entry = entry,
                parsedRecords = listOf(record),
            ),
        )
    }

    @Test
    fun creditPurchaseFixture_isNotDebitChannel() {
        val record = AlJaziraFixtureParseHarness.parseRecord("purchase_pos_ar_cc_001")
        assertEquals(CardSmsChannel.CREDIT, record.details.cardSmsChannel)

        val entry = CardRegistryEntry.forTest(
            bank = Bank.BANK_ALJAZIRA,
            last4 = "7271",
            ownership = OwnershipStatus.OWNED,
            cardType = CardType.CREDIT,
            firstSeenRawSmsId = record.event.rawSmsId,
            lastSeenRawSmsId = record.event.rawSmsId,
        )

        assertFalse(
            CardRegistryDebitClassifier.isDebitRegistryEntry(
                entry = entry,
                parsedRecords = listOf(record),
            ),
        )
    }
}
