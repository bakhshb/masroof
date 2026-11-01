package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.testsupport.FakeAppLocaleRepository
import com.baraa.masroof.testsupport.MutableCardRegistryRepository as FakeCardRegistry
import com.baraa.masroof.testsupport.StubAccountRegistryRepository as FakeAccountRegistry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.maintenance.MaintenanceCompletionSignal
import com.baraa.masroof.application.commitment.CommitmentFromTransactionService
import com.baraa.masroof.application.dashboard.DashboardCommitmentsWorkflow
import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DashboardOverview
import com.baraa.masroof.application.dashboard.CreditFacilitiesOverview
import com.baraa.masroof.application.dashboard.CurrentAccountFlowDetailGrouping
import com.baraa.masroof.application.dashboard.CurrentAccountSummary
import com.baraa.masroof.application.dashboard.DashboardOverviewLoader
import com.baraa.masroof.application.dashboard.DashboardSectionSize
import com.baraa.masroof.application.dashboard.DashboardSectionId
import com.baraa.masroof.application.dashboard.DashboardTransactionFacts
import com.baraa.masroof.application.dashboard.DashboardTransactionFactsBuilder
import com.baraa.masroof.application.dashboard.TransactionSmsEvidenceLoader
import com.baraa.masroof.application.dashboard.MonthlyFinancialSummary
import com.baraa.masroof.application.dashboard.SpendingSplitSummary
import com.baraa.masroof.application.dashboard.SignedMoneyAmount
import com.baraa.masroof.application.transaction.TransactionIgnoreService
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.testsupport.NoOpCardRegistryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Asia/Riyadh")
    private val clock = Clock.fixed(Instant.parse("2026-08-11T08:00:00Z"), zone)
    private val currentPeriod = FinancialPeriodPolicy.periodContaining(LocalDate.parse("2026-08-11"))
    private val previousPeriod = FinancialPeriodPolicy.previous(currentPeriod)
    private val previous2Period = FinancialPeriodPolicy.previous(previousPeriod)

    @Test
    fun transactionListQueryIsReappliedWhenDashboardRowsRefresh() = runTest {
        val loader = FakeLoader()
        val transaction = foreignTransaction().copy(amount = Money.of("15", Currency.SAR))
        loader.put(currentPeriod, overview(currentPeriod, "15").copy(transactions = listOf(transaction)))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(Money.of("15", Currency.SAR), vm.uiState.value.transactionListProjection.result.totalAmount)

        vm.setTransactionListQuery(TransactionListFilterState(searchQuery = "no match"))
        assertTrue(vm.uiState.value.transactionListProjection.result.transactions.isEmpty())
        loader.put(currentPeriod, overview(currentPeriod, "20").copy(
            transactions = listOf(transaction.copy(amount = Money.of("20", Currency.SAR)))))
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.uiState.value.transactionListProjection.result.totalAmount)
        vm.setTransactionListQuery(TransactionListFilterState())
        assertEquals(Money.of("20", Currency.SAR), vm.uiState.value.transactionListProjection.result.totalAmount)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun constructingViewModel_doesNotLoadUntilExplicitRefresh() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "100.00"))
        val vm = viewModel(loader)
        advanceUntilIdle()

        assertTrue(loader.calls.isEmpty())
        assertNull(vm.uiState.value.summary)
        assertNull(vm.uiState.value.period)
    }

    @Test
    fun maintenanceCompletion_reloadsLoadedDashboardOnly() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "100.00"))
        val signal = MaintenanceCompletionSignal()
        val vm = viewModel(loader, maintenanceCompletions = signal.completions)
        advanceUntilIdle()

        signal.notifyCompleted()
        advanceUntilIdle()
        assertTrue(loader.calls.isEmpty())
        assertNull(vm.uiState.value.period)

        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, loader.calls.size)

        loader.put(currentPeriod, overview(currentPeriod, spending = "140.00"))
        signal.notifyCompleted()
        advanceUntilIdle()

        assertEquals(listOf(currentPeriod, currentPeriod), loader.calls)
        assertEquals(Money.of("140.00", Currency.SAR), vm.uiState.value.summary!!.spendingGross)
    }

    @Test
    fun refreshCompletingAfterOpeningDetail_keepsTheUsersNewSelection() = runTest {
        val loader=FakeLoader()
        val row=foreignTransaction()
        loader.put(currentPeriod,overview(currentPeriod,spending="100.00").copy(transactions=listOf(row)))
        val vm=viewModel(loader)
        vm.refresh();advanceUntilIdle()
        val gate=loader.enqueueGate(currentPeriod)
        vm.refresh();advanceUntilIdle()
        vm.openTransactionDetail(row.id);advanceUntilIdle()
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(row.id,vm.uiState.value.selectedTransactionId)
    }

    @Test
    fun refreshCompletingAfterClosingDetail_doesNotReopenIt() = runTest {
        val loader=FakeLoader()
        val row=foreignTransaction()
        loader.put(currentPeriod,overview(currentPeriod,spending="100.00").copy(transactions=listOf(row)))
        val vm=viewModel(loader)
        vm.refresh();advanceUntilIdle()
        vm.openTransactionDetail(row.id);advanceUntilIdle()
        val gate=loader.enqueueGate(currentPeriod)
        vm.refresh();advanceUntilIdle()
        vm.closeTransactionDetail()
        gate.complete(Unit);advanceUntilIdle()
        assertNull(vm.uiState.value.selectedTransactionId)
    }

    @Test
    fun refreshCompletingAfterSwitchingDetail_keepsTheLatestSelection() = runTest {
        val loader=FakeLoader()
        val first=foreignTransaction()
        val second=first.copy(id="second")
        loader.put(currentPeriod,overview(currentPeriod,spending="100.00").copy(transactions=listOf(first,second)))
        val vm=viewModel(loader)
        vm.refresh();advanceUntilIdle()
        vm.openTransactionDetail(first.id);advanceUntilIdle()
        val gate=loader.enqueueGate(currentPeriod)
        vm.refresh();advanceUntilIdle()
        vm.openTransactionDetail(second.id);advanceUntilIdle()
        gate.complete(Unit);advanceUntilIdle()
        assertEquals(second.id,vm.uiState.value.selectedTransactionId)
    }

    @Test
    fun changingPeriod_clearsThePreviousDetailBeforeLoading() = runTest {
        val loader=FakeLoader()
        val row=foreignTransaction()
        loader.put(currentPeriod,overview(currentPeriod,spending="100.00").copy(transactions=listOf(row)))
        loader.put(previousPeriod,overview(previousPeriod,spending="50.00"))
        val vm=viewModel(loader)
        vm.refresh();advanceUntilIdle()
        vm.openTransactionDetail(row.id);advanceUntilIdle()
        val gate=loader.enqueueGate(previousPeriod)
        vm.goToPreviousPeriod()
        assertNull(vm.uiState.value.selectedTransactionId)
        gate.complete(Unit);advanceUntilIdle()
        assertNull(vm.uiState.value.selectedTransactionId)
    }

    @Test
    fun previews_renderApplicationFactsWithoutReinterpreting() = runTest {
        val tx = foreignTransaction()
        val loader = FakeLoader()
        loader.put(
            currentPeriod,
            overview(currentPeriod, spending = "100.00").copy(
                transactions = listOf(tx),
                transactionCardInvolvement = mapOf(tx.id to setOf("${Bank.BANK_ALJAZIRA.id}:2210")),
                transactionFacts = mapOf(
                    tx.id to DashboardTransactionFacts(
                        primaryCardLast4 = "9999",
                        effectiveType = FinancialTransactionType.LOAN_REPAYMENT,
                        sarEquivalent = Money.of("1.00", Currency.SAR),
                    ),
                ),
                ownedAccountContainerIds = setOf("account:aljazira:3001"),
            ),
        )
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        val preview = vm.uiState.value.allTransactions.single()
        assertEquals("9999", preview.cardLast4)
        assertEquals(FinancialTransactionType.EXPENSE, preview.type)
        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, preview.typeLabelResHint)
        assertEquals(TransactionTypePresentation.direction(FinancialTransactionType.LOAN_REPAYMENT), preview.direction)
        assertEquals(Money.of("1.00", Currency.SAR), preview.sarEquivalent)
        assertEquals(setOf("account:aljazira:3001"), vm.uiState.value.ownedAccountContainerIds)
    }

    @Test
    fun previews_fromOverviewInvolvement_keepFormerRowFacts() = runTest {
        val tx = foreignTransaction()
        val loader = FakeLoader()
        loader.put(
            currentPeriod,
            overview(currentPeriod, spending = "100.00").copy(
                transactions = listOf(tx),
                transactionCardInvolvement = mapOf(tx.id to setOf("${Bank.BANK_ALJAZIRA.id}:2210")),
                transactionLoanInvolvement = mapOf(tx.id to setOf("loan:aljazira:PERSONAL")),
            ).let {
                it.copy(
                    transactionFacts = DashboardTransactionFactsBuilder.build(
                        transactions = it.transactions,
                        cardInvolvement = it.transactionCardInvolvement,
                        loanInvolvement = it.transactionLoanInvolvement,
                    ),
                )
            },
        )
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        val preview = vm.uiState.value.allTransactions.single()
        assertEquals("2210", preview.cardLast4)
        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, preview.typeLabelResHint)
        assertEquals(Money.of("37.50", Currency.SAR), preview.sarEquivalent)
        assertEquals(java.math.BigDecimal("3.75"), preview.appliedExchangeRate)
    }

    @Test
    fun successfulCurrentPeriodLoad() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "100.00"))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(currentPeriod, state.period)
        assertNotNull(state.summary)
        assertEquals(currentPeriod, state.summary!!.period)
        assertEquals(Money.of("100.00", Currency.SAR), state.summary!!.spendingGross)
        assertTrue(state.isCurrentPeriod)
    }

    @Test
    fun firstVisibleRefresh_loadsPostOnboardingDataNotStaleEmpty() = runTest {
        val loader = FakeLoader()
        // Simulated pre-onboarding empty overview available if eagerly queried.
        loader.put(currentPeriod, overview(currentPeriod, spending = "0.00", transactionCount = 0))
        val vm = viewModel(loader)
        advanceUntilIdle()
        assertTrue(loader.calls.isEmpty())
        assertNull(vm.uiState.value.summary)

        // After import/finalization, overview changes before dashboard visibility.
        loader.put(currentPeriod, overview(currentPeriod, spending = "100.00"))
        vm.refresh() // first dashboard-visible refresh
        advanceUntilIdle()

        assertEquals(1, loader.calls.size)
        assertEquals(Money.of("100.00", Currency.SAR), vm.uiState.value.summary!!.spendingGross)
        assertPeriodSummaryInvariant(vm.uiState.value)
    }

    @Test
    fun navigatingToAnotherPeriod_clearsOldSummaryUnderNewLabel() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "100.00"))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        val gate = loader.enqueueGate(previousPeriod)
        loader.put(previousPeriod, overview(previousPeriod, spending = "40.00"))
        vm.goToPreviousPeriod()
        advanceUntilIdle()

        val loadingState = vm.uiState.value
        assertEquals(previousPeriod, loadingState.period)
        assertEquals(
            FinancialPeriodUiFormatter.formatSalaryPeriodTitle(appContext, previousPeriod),
            loadingState.periodLabel,
        )
        assertTrue(loadingState.loading)
        assertNull(loadingState.summary)
        assertTrue(loadingState.recentTransactions.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertPeriodSummaryInvariant(vm.uiState.value)
        assertEquals(Money.of("40.00", Currency.SAR), vm.uiState.value.summary!!.spendingGross)
    }

    @Test
    fun newPeriodLoadFailure_doesNotShowOldSummaryUnderNewLabel() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "100.00"))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        loader.failNextPeriod = previousPeriod
        vm.goToPreviousPeriod()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(previousPeriod, state.period)
        assertEquals(
            FinancialPeriodUiFormatter.formatSalaryPeriodTitle(appContext, previousPeriod),
            state.periodLabel,
        )
        assertNull(state.summary)
        assertEquals(DashboardError.LOAD_FAILED, state.error)
        assertFalse(state.loading)
    }

    @Test
    fun retryAfterFailure_retriesSelectedPeriod() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "100.00"))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        loader.failNextPeriod = previousPeriod
        vm.goToPreviousPeriod()
        advanceUntilIdle()
        assertEquals(DashboardError.LOAD_FAILED, vm.uiState.value.error)

        loader.put(previousPeriod, overview(previousPeriod, spending = "55.00"))
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(previousPeriod, state.period)
        assertNull(state.error)
        assertEquals(Money.of("55.00", Currency.SAR), state.summary!!.spendingGross)
        assertPeriodSummaryInvariant(state)
        assertTrue(loader.calls.count { it == previousPeriod } >= 2)
    }

    @Test
    fun rapidPreviousPrevious_onlyLatestRequestWins() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "10.00"))
        loader.put(previousPeriod, overview(previousPeriod, spending = "20.00"))
        loader.put(previous2Period, overview(previous2Period, spending = "30.00"))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        val gateP1 = loader.enqueueGate(previousPeriod)
        val gateP2 = loader.enqueueGate(previous2Period)
        vm.goToPreviousPeriod() // P1
        vm.goToPreviousPeriod() // P2, cancels P1
        advanceUntilIdle()

        gateP2.complete(Unit)
        gateP1.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(previous2Period, state.period)
        assertEquals(Money.of("30.00", Currency.SAR), state.summary!!.spendingGross)
        assertPeriodSummaryInvariant(state)
        assertNull(state.error)
    }

    @Test
    fun previousThenCurrentBeforePreviousReturns_currentRemainsFinal() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "10.00"))
        loader.put(previousPeriod, overview(previousPeriod, spending = "20.00"))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        val gatePrevious = loader.enqueueGate(previousPeriod)
        val gateCurrent = loader.enqueueGate(currentPeriod)
        vm.goToPreviousPeriod()
        vm.goToCurrentPeriod()
        advanceUntilIdle()

        gateCurrent.complete(Unit)
        gatePrevious.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(currentPeriod, state.period)
        assertEquals(Money.of("10.00", Currency.SAR), state.summary!!.spendingGross)
        assertTrue(state.isCurrentPeriod)
        assertPeriodSummaryInvariant(state)
    }

    @Test
    fun cancelledObsoleteLoad_doesNotSetLoadFailed() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "10.00"))
        loader.put(previous2Period, overview(previous2Period, spending = "30.00"))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()

        loader.failNextPeriod = previousPeriod
        val gateP1 = loader.enqueueGate(previousPeriod)
        val gateP2 = loader.enqueueGate(previous2Period)
        vm.goToPreviousPeriod()
        vm.goToPreviousPeriod()
        advanceUntilIdle()

        gateP1.complete(Unit)
        gateP2.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(previous2Period, state.period)
        assertNull(state.error)
        assertNotNull(state.summary)
        assertPeriodSummaryInvariant(state)
    }

    @Test
    fun summaryPeriodMatchesStatePeriodWheneverSummaryPresent() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "12.00"))
        loader.put(previousPeriod, overview(previousPeriod, spending = "8.00"))
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()
        assertPeriodSummaryInvariant(vm.uiState.value)

        vm.goToPreviousPeriod()
        advanceUntilIdle()
        assertPeriodSummaryInvariant(vm.uiState.value)

        vm.goToCurrentPeriod()
        advanceUntilIdle()
        assertPeriodSummaryInvariant(vm.uiState.value)
    }

    @Test
    fun previewTitleFallsBackWithoutEnumName() = runTest {
        val loader = FakeLoader()
        val tx = FinancialTransaction(
            id = "tx1",
            type = FinancialTransactionType.CREDIT_CARD_PAYMENT,
            amount = Money.of("100.00", Currency.SAR),
            occurredAt = Instant.parse("2026-08-01T10:00:00Z"),
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = null,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = emptyList(),
        )
        loader.put(
            currentPeriod,
            DashboardOverview(
                period = currentPeriod,
                summary = MonthlyFinancialSummary.empty(currentPeriod, Currency.SAR),
                currentAccount = emptyCurrentAccount(),
                spendingSplit = emptySpendingSplit(),
                transactions = listOf(tx),
                creditFacilities = emptyCreditFacilities(),
                ownedAccountPeriodSummaries = emptyList(),
                flowDetailGrouping = CurrentAccountFlowDetailGrouping.empty(),
                isCurrentPeriod = true,
            ),
        )
        val vm = viewModel(loader)
        vm.refresh()
        advanceUntilIdle()
        val preview = vm.uiState.value.recentTransactions.single()
        assertNull(preview.title)
        assertEquals(FinancialTransactionType.CREDIT_CARD_PAYMENT, preview.type)
        assertFalse(preview.amountLabel.contains("CREDIT_CARD_PAYMENT"))
    }

    @Test
    fun refresh_loadsUnknownCardsFromRegistry() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "100.00"))
        val registry = FakeCardRegistry(
            CardRegistryEntry.forTest(
                bank = Bank.BANK_ALJAZIRA,
                last4 = "5123",
                ownership = OwnershipStatus.UNKNOWN,
                firstSeenRawSmsId = "sms-1",
                lastSeenRawSmsId = "sms-1",
            ),
        )
        val vm = viewModel(loader, registry)
        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(UnknownCardCandidateUi(Bank.BANK_ALJAZIRA, "5123")), vm.uiState.value.unknownCards)
    }

    @Test
    fun refresh_loadsExistingDataWithOrWithoutSmsPermission() = runTest {
        for (permissionGranted in listOf(false, true)) {
            val loader = FakeLoader()
            loader.put(currentPeriod, overview(currentPeriod, spending = "10.00"))
            val vm = viewModel(loader, permissionGranted = permissionGranted)

            vm.refresh()
            advanceUntilIdle()

            assertEquals(permissionGranted, vm.uiState.value.smsPermissionGranted)
            assertEquals(listOf(currentPeriod), loader.calls)
            assertEquals(1, vm.uiState.value.summary!!.transactionCount)
            assertNull(vm.uiState.value.error)
        }
    }

    @Test
    fun onAppResumed_afterPermissionGrantedRefreshesExistingData() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "10.00"))
        var permissionGranted = false
        val vm = viewModel(loader, permissionStateProvider = { permissionGranted })
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.smsPermissionGranted)

        permissionGranted = true
        vm.onAppResumed()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.smsPermissionGranted)
        assertEquals(listOf(currentPeriod, currentPeriod), loader.calls)
        assertEquals(1, vm.uiState.value.summary!!.transactionCount)
    }

    private fun assertPeriodSummaryInvariant(state: DashboardUiState) {
        val summary = state.summary
        if (summary != null) {
            assertEquals(state.period, summary.period)
        }
    }

    private fun overview(
        period: FinancialPeriod,
        spending: String,
        transactionCount: Int = 1,
    ): DashboardOverview {
        val base = MonthlyFinancialSummary.empty(period, Currency.SAR)
        val amount = Money.of(spending, Currency.SAR)
        return DashboardOverview(
            period = period,
            summary = base.copy(
                spendingGross = amount,
                spendingNet = SignedMoneyAmount.of(amount),
                transactionCount = transactionCount,
            ),
            currentAccount = emptyCurrentAccount(),
            spendingSplit = emptySpendingSplit(),
            transactions = emptyList(),
            creditFacilities = emptyCreditFacilities(),
            ownedAccountPeriodSummaries = emptyList(),
            flowDetailGrouping = CurrentAccountFlowDetailGrouping.empty(),
            isCurrentPeriod = period == currentPeriod,
        )
    }

    private fun foreignTransaction() = FinancialTransaction(
        id = "tx-usd",
        type = FinancialTransactionType.EXPENSE,
        amount = Money.of("10.00", Currency.USD),
        occurredAt = Instant.parse("2026-08-10T09:00:00Z"),
        sourceContainerId = "account:aljazira:3001",
        destinationContainerId = null,
        merchant = "SPOTIFY",
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = emptyList(),
        appliedExchangeRate = java.math.BigDecimal("3.75"),
        exchangeRateSource = com.baraa.masroof.domain.model.ExchangeRateSource.SMS,
    )

    private fun emptyCreditFacilities(): CreditFacilitiesOverview =
        CreditFacilitiesOverview(
            facilities = emptyList(),
            debitCards = emptyList(),
            currency = Currency.SAR,
        )

    companion object {
        fun emptyCurrentAccount(currency: Currency = Currency.SAR): CurrentAccountSummary =
            CurrentAccountSummary.zero(currency)

        fun emptySpendingSplit(currency: Currency = Currency.SAR): SpendingSplitSummary =
            SpendingSplitSummary(
                currency = currency,
                totalSpending = Money.zero(currency),
                creditCardPurchases = SignedMoneyAmount.zero(currency),
            )
    }

    @Test
    fun customizeLayout_openToggleSave_persistsAndClosesSheet() = runTest {
        val savedLayout = DashboardLayoutSnapshot.default().let { layout ->
            layout.copy(sections = layout.sections.map { it.copy(size = DashboardSectionSize.SMALL) })
        }
        val layoutRepo = FakeLayoutPreferencesRepository(savedLayout)
        val vm = viewModel(FakeLoader(), layoutPreferencesRepository = layoutRepo)
        advanceUntilIdle()

        vm.openCustomizeSheet()
        assertTrue(vm.uiState.value.customizeSheetOpen)
        assertNotNull(vm.uiState.value.customizeDraft)

        vm.toggleCustomizeSection(DashboardSectionId.CARDS)
        assertFalse(
            vm.uiState.value.customizeDraft!!.entry(DashboardSectionId.CARDS)!!.visible,
        )

        vm.saveCustomizeLayout()
        assertFalse(vm.uiState.value.customizeSheetOpen)
        assertNull(vm.uiState.value.customizeDraft)
        assertFalse(vm.uiState.value.dashboardLayout.entry(DashboardSectionId.CARDS)!!.visible)
        assertFalse(layoutRepo.saved!!.entry(DashboardSectionId.CARDS)!!.visible)
        assertEquals(savedLayout.sections.map { it.size }, layoutRepo.saved!!.sections.map { it.size })
        val recreated = viewModel(FakeLoader(), layoutPreferencesRepository = layoutRepo)
        assertEquals(layoutRepo.saved, recreated.uiState.value.dashboardLayout)
    }

    @Test
    fun customizeLayout_toggleLoans_persistsVisibility() = runTest {
        val layoutRepo = FakeLayoutPreferencesRepository()
        val vm = viewModel(FakeLoader(), layoutPreferencesRepository = layoutRepo)
        advanceUntilIdle()

        vm.openCustomizeSheet()
        vm.toggleCustomizeSection(DashboardSectionId.LOANS)
        vm.saveCustomizeLayout()

        assertFalse(vm.uiState.value.dashboardLayout.entry(DashboardSectionId.LOANS)!!.visible)
        assertFalse(layoutRepo.saved!!.entry(DashboardSectionId.LOANS)!!.visible)
    }

    @Test
    fun customizeLayout_moveLoans_persistsOrder() = runTest {
        val layoutRepo = FakeLayoutPreferencesRepository()
        val vm = viewModel(FakeLoader(), layoutPreferencesRepository = layoutRepo)
        advanceUntilIdle()

        vm.openCustomizeSheet()
        val initialIndex = vm.uiState.value.customizeDraft!!.sections
            .indexOfFirst { it.id == DashboardSectionId.LOANS }
        repeat(initialIndex) {
            vm.moveCustomizeSection(DashboardSectionId.LOANS, direction = -1)
        }
        vm.saveCustomizeLayout()

        assertEquals(
            DashboardSectionId.LOANS,
            vm.uiState.value.dashboardLayout.sections.first().id,
        )
        assertEquals(
            DashboardSectionId.LOANS,
            layoutRepo.saved!!.sections.first().id,
        )
    }

    @Test
    fun customizeLayout_dismissWithoutSave_discardsDraft() = runTest {
        val layoutRepo = FakeLayoutPreferencesRepository()
        val vm = viewModel(FakeLoader(), layoutPreferencesRepository = layoutRepo)
        advanceUntilIdle()

        vm.openCustomizeSheet()
        vm.toggleCustomizeSection(DashboardSectionId.HERO)
        vm.dismissCustomizeSheet()

        assertFalse(vm.uiState.value.customizeSheetOpen)
        assertNull(vm.uiState.value.customizeDraft)
        assertTrue(vm.uiState.value.dashboardLayout.entry(DashboardSectionId.HERO)!!.visible)
        assertNull(layoutRepo.saved)
    }

    @Test
    fun resumedCurrentPeriod_followsTheClockAcrossASalaryBoundary() = runTest {
        val movingClock = MovingClock(clock.instant(), zone)
        val loader = FakeLoader()
        val next = FinancialPeriodPolicy.next(currentPeriod)
        loader.put(currentPeriod, overview(currentPeriod, "100.00"))
        loader.put(next, overview(next, "130.00"))
        val vm = viewModel(loader, periodClock = movingClock)
        vm.refresh(); advanceUntilIdle()
        movingClock.now = next.startDate.atStartOfDay(zone).toInstant()
        vm.onAppResumed(); advanceUntilIdle()
        assertEquals(next, vm.uiState.value.period)
    }

    @Test
    fun resumedHistoricalSelection_staysSelectedUntilUserReturnsToCurrent() = runTest {
        val movingClock = MovingClock(clock.instant(), zone)
        val loader = FakeLoader()
        val next = FinancialPeriodPolicy.next(currentPeriod)
        for (p in listOf(previousPeriod, currentPeriod, next)) loader.put(p, overview(p, "100.00"))
        val vm = viewModel(loader, periodClock = movingClock)
        vm.refresh(); advanceUntilIdle()
        vm.goToPreviousPeriod(); advanceUntilIdle()
        movingClock.now = next.startDate.atStartOfDay(zone).toInstant()
        vm.onAppResumed(); advanceUntilIdle()
        assertEquals(previousPeriod, vm.uiState.value.period)
        vm.goToCurrentPeriod(); advanceUntilIdle()
        assertEquals(next, vm.uiState.value.period)
    }

    private class MovingClock(var now: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }

    private fun viewModel(
        loader: FakeLoader,
        cardRegistry: com.baraa.masroof.domain.repository.CardRegistryRepository = FakeCardRegistry(),
        accountRegistry: com.baraa.masroof.domain.repository.AccountRegistryRepository = FakeAccountRegistry(),
        periodClock: java.time.Clock = clock,
        periodZoneId: java.time.ZoneId = zone,
        layoutPreferencesRepository: DashboardLayoutPreferencesRepository = FakeLayoutPreferencesRepository(),
        permissionGranted: Boolean = true,
        permissionStateProvider: () -> Boolean = { permissionGranted },
        maintenanceCompletions: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
        smsEvidenceLoader: TransactionSmsEvidenceLoader = TransactionSmsEvidenceLoader(
            financialTransactionRepository = object : com.baraa.masroof.domain.repository.FinancialTransactionRepository {
                override suspend fun save(
                    transaction: FinancialTransaction,
                    rawSmsIds: Collection<String>,
                ) = com.baraa.masroof.domain.repository.FinancialTransactionSaveResult.Saved

                override suspend fun getById(id: String) = null
                override suspend fun findByRawSmsId(rawSmsId: String) = null
                override suspend fun listAll() = emptyList<FinancialTransaction>()
                override suspend fun listOccurredBetween(
                    startInclusive: java.time.Instant,
                    endExclusive: java.time.Instant,
                ) = emptyList<FinancialTransaction>()

                override suspend fun isRawSmsLinked(rawSmsId: String) = false
                override suspend fun listRawSmsIds(transactionId: String) = emptyList<String>()
                override suspend fun update(transaction: FinancialTransaction) = false
                override suspend fun updateAppliedExchangeRate(
                    id: String,
                    exchangeRate: java.math.BigDecimal,
                    source: com.baraa.masroof.domain.model.ExchangeRateSource,
                ) = false
                override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String) = false
                override suspend fun unlinkRawSms(rawSmsId: String) = false

                override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String) = false
            },
            rawSmsRepository = object : com.baraa.masroof.domain.repository.RawSmsRepository {
                override suspend fun insertIfAbsent(rawSms: com.baraa.masroof.domain.model.RawSms) =
                    com.baraa.masroof.domain.repository.RawSmsInsertResult.Inserted

                override suspend fun getById(id: String) = null
                override suspend fun existsById(id: String) = false
                override suspend fun findByDeviceMessageId(deviceMessageId: String) = null
                override suspend fun listIdsByReceivedAt(): List<String> = emptyList()
                override suspend fun findCrossSourceNearDuplicate(
                    sender: String,
                    bodyHash: String,
                    fromInclusive: java.time.Instant,
                    toInclusive: java.time.Instant,
                    lookingForLiveRow: Boolean,
                ) = null
            },
        ),
    ): DashboardViewModel =
        DashboardViewModel(
            overviewLoader = loader,
            dashboardRegistryWorkflow = com.baraa.masroof.testsupport.PresentationWorkflowTestSupport
                .dashboardRegistryWorkflow(cards = cardRegistry, accounts = accountRegistry),
            dashboardPeriodWorkflow = com.baraa.masroof.application.dashboard.DashboardPeriodWorkflow(
                clock = object : com.baraa.masroof.sms.time.InstantClock { override fun now() = periodClock.instant() },
                zoneId = periodZoneId,
            ),
            layoutPreferencesRepository = layoutPreferencesRepository,
            smsEvidenceLoader = smsEvidenceLoader,
            permissionStateProvider = permissionStateProvider,
            reclassificationService = TransactionReclassificationService(
                financialTransactionRepository = object : com.baraa.masroof.domain.repository.FinancialTransactionRepository {
                    override suspend fun save(
                        transaction: FinancialTransaction,
                        rawSmsIds: Collection<String>,
                    ) = com.baraa.masroof.domain.repository.FinancialTransactionSaveResult.Saved

                    override suspend fun getById(id: String) = null
                    override suspend fun findByRawSmsId(rawSmsId: String) = null
                    override suspend fun listAll() = emptyList<FinancialTransaction>()
                    override suspend fun listOccurredBetween(
                        startInclusive: java.time.Instant,
                        endExclusive: java.time.Instant,
                    ) = emptyList<FinancialTransaction>()

                    override suspend fun isRawSmsLinked(rawSmsId: String) = false
                    override suspend fun listRawSmsIds(transactionId: String) = emptyList<String>()
                    override suspend fun update(transaction: FinancialTransaction) = false
                    override suspend fun updateAppliedExchangeRate(
                        id: String,
                        exchangeRate: java.math.BigDecimal,
                        source: com.baraa.masroof.domain.model.ExchangeRateSource,
                    ) = false
                    override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String) = false
                override suspend fun unlinkRawSms(rawSmsId: String) = false

                override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String) = false
                },
                effectiveParsedEventProvider = com.baraa.masroof.application.review.EffectiveParsedEventProvider(
                    object : com.baraa.masroof.parsing.repository.ParsedEventRepository {
                        override suspend fun save(
                            event: com.baraa.masroof.domain.model.ParsedEvent,
                            details: com.baraa.masroof.parsing.model.ParsedEventDetails,
                        ) = Unit

                        override suspend fun getById(id: String) = null
                        override suspend fun findByRawSmsId(rawSmsId: String) = null
                        override suspend fun deleteByRawSmsId(rawSmsId: String) = Unit
                        override suspend fun listAll() = emptyList<com.baraa.masroof.parsing.repository.ParsedEventRecord>()

                        override suspend fun listReceivedBetween(
                            startInclusive: java.time.Instant,
                            endExclusive: java.time.Instant,
                        ) = emptyList<com.baraa.masroof.parsing.repository.ParsedEventRecord>()
                    },
                    object : com.baraa.masroof.domain.repository.UserCorrectionRepository {
                        override suspend fun save(correction: com.baraa.masroof.domain.model.UserCorrection) = Unit
                        override suspend fun latestForRawSmsId(rawSmsId: String) = null
                        override suspend fun listForRawSmsId(rawSmsId: String) = emptyList<com.baraa.masroof.domain.model.UserCorrection>()
                        override suspend fun listForRawSmsIds(rawSmsIds: Collection<String>) =
                            emptyList<com.baraa.masroof.domain.model.UserCorrection>()
                    },
                ),
                ownershipResolver = com.baraa.masroof.domain.ownership.OwnershipResolver(
                    object : com.baraa.masroof.domain.repository.AccountRegistryRepository {
                        override suspend fun observe(reference: AccountReference, rawSmsId: String) = Unit
                        override suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus) = Unit
                        override suspend fun resolve(reference: AccountReference) = OwnershipStatus.UNKNOWN
                        override suspend fun get(ref: com.baraa.masroof.domain.model.AccountReference) = null
                        override suspend fun listAll() = emptyList<com.baraa.masroof.domain.model.AccountRegistryEntry>()
                        override suspend fun updateDisplayName(
                            reference: com.baraa.masroof.domain.model.AccountReference,
                            displayName: String?,
                        ) = Unit
                        override suspend fun updateAccountType(
                            reference: com.baraa.masroof.domain.model.AccountReference,
                            accountType: com.baraa.masroof.domain.model.AccountType,
                        ) = Unit
                    },
                    NoOpCardRegistryRepository(),
                    com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository,
                ),
            ),
            ignoreService = TransactionIgnoreService(
                persistence = com.baraa.masroof.domain.repository.TransactionIgnoreRepository { _, _ ->
                    com.baraa.masroof.domain.repository.TransactionIgnoreOutcome.Rejected("transaction_not_found")
                },
                clock = com.baraa.masroof.sms.time.InstantClock.System,
            ),
            dashboardCommitmentsWorkflow = DashboardCommitmentsWorkflow(
                commitmentFromTransactionService = CommitmentFromTransactionService(
                    commitmentRepository = com.baraa.masroof.testsupport.NoOpCommitmentRepository(),
                    financialTransactionRepository = object : com.baraa.masroof.domain.repository.FinancialTransactionRepository {
                        override suspend fun save(
                            transaction: FinancialTransaction,
                            rawSmsIds: Collection<String>,
                        ) = com.baraa.masroof.domain.repository.FinancialTransactionSaveResult.Saved

                        override suspend fun getById(id: String) = null
                        override suspend fun findByRawSmsId(rawSmsId: String) = null
                        override suspend fun listAll() = emptyList<FinancialTransaction>()
                        override suspend fun listOccurredBetween(
                            startInclusive: java.time.Instant,
                            endExclusive: java.time.Instant,
                        ) = emptyList<FinancialTransaction>()

                        override suspend fun isRawSmsLinked(rawSmsId: String) = false
                        override suspend fun listRawSmsIds(transactionId: String) = emptyList<String>()
                        override suspend fun update(transaction: FinancialTransaction) = false
                        override suspend fun updateAppliedExchangeRate(
                            id: String,
                            exchangeRate: java.math.BigDecimal,
                            source: com.baraa.masroof.domain.model.ExchangeRateSource,
                        ) = false
                        override suspend fun deleteIfExclusiveRawSmsLink(rawSmsId: String) = false
                        override suspend fun unlinkRawSms(rawSmsId: String) = false
                        override suspend fun linkRawSmsIfAbsent(transactionId: String, rawSmsId: String) = false
                    },
                ),
                commitmentRepository = com.baraa.masroof.testsupport.NoOpCommitmentRepository(),
            ),
            appContext = appContext,
            appLocaleRepository = FakeAppLocaleRepository(),
            zoneId = zone,
            maintenanceCompletions = maintenanceCompletions,
        )

    private class FakeLayoutPreferencesRepository(
        private var snapshot: DashboardLayoutSnapshot = DashboardLayoutSnapshot.default(),
    ) : DashboardLayoutPreferencesRepository {
        var saved: DashboardLayoutSnapshot? = null

        override fun load(): DashboardLayoutSnapshot = snapshot

        override fun save(snapshot: DashboardLayoutSnapshot) {
            this.snapshot = snapshot
            saved = snapshot
        }
    }

    private class FakeLoader : DashboardOverviewLoader {
        private val overviews = mutableMapOf<FinancialPeriod, DashboardOverview>()
        private val gates = mutableMapOf<FinancialPeriod, CompletableDeferred<Unit>>()
        val calls = mutableListOf<FinancialPeriod>()
        var failNextPeriod: FinancialPeriod? = null

        fun put(period: FinancialPeriod, overview: DashboardOverview) {
            overviews[period] = overview
        }

        fun enqueueGate(period: FinancialPeriod): CompletableDeferred<Unit> {
            val gate = CompletableDeferred<Unit>()
            gates[period] = gate
            return gate
        }

        override suspend fun loadOverview(period: FinancialPeriod): DashboardOverview {
            calls += period
            gates.remove(period)?.await()
            if (failNextPeriod == period) {
                failNextPeriod = null
                error("load failed for $period")
            }
            return overviews[period]
                ?: DashboardOverview(
                    period = period,
                    summary = MonthlyFinancialSummary.empty(period, Currency.SAR),
                    currentAccount = DashboardViewModelTest.emptyCurrentAccount(),
                    spendingSplit = DashboardViewModelTest.emptySpendingSplit(),
                    transactions = emptyList(),
                    creditFacilities = CreditFacilitiesOverview(
                        facilities = emptyList(),
                        debitCards = emptyList(),
                        currency = Currency.SAR,
                    ),
                    ownedAccountPeriodSummaries = emptyList(),
                    flowDetailGrouping = CurrentAccountFlowDetailGrouping.empty(),
                    isCurrentPeriod = false,
                )
        }
    }
}
