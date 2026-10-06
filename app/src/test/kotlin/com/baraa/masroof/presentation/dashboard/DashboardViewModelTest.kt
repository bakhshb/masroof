package com.baraa.masroof.presentation.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.commitment.CommitmentFromTransactionService
import com.baraa.masroof.application.dashboard.DashboardCommitmentsWorkflow
import com.baraa.masroof.application.dashboard.DashboardLayoutPreferencesRepository
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DashboardOverview
import com.baraa.masroof.application.dashboard.CreditFacilitiesOverview
import com.baraa.masroof.application.dashboard.CurrentAccountFlowDetailGrouping
import com.baraa.masroof.application.dashboard.CurrentAccountSummary
import com.baraa.masroof.application.dashboard.DashboardOverviewLoader
import com.baraa.masroof.application.dashboard.DashboardSectionId
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
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.application.onboarding.HistoricalImportResult
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
    fun refreshWithSmsImport_withoutPermission_skipsRescanAndMarksDeniedState() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "10.00"))
        var rescanCalls = 0
        val vm = viewModel(
            loader = loader,
            permissionGranted = false,
            rescanService = {
                rescanCalls++
                HistoricalImportResult()
            },
        )
        vm.refreshWithSmsImport()
        advanceUntilIdle()

        assertEquals(0, rescanCalls)
        assertFalse(vm.uiState.value.smsPermissionGranted)
    }

    @Test
    fun refreshWithSmsImport_withPermission_runsRescan() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "10.00"))
        var rescanCalls = 0
        val vm = viewModel(
            loader = loader,
            permissionGranted = true,
            rescanService = {
                rescanCalls++
                HistoricalImportResult(parsed = 1, scanned = 1, inserted = 1)
            },
        )
        vm.refreshWithSmsImport()
        advanceUntilIdle()

        assertEquals(1, rescanCalls)
        assertTrue(vm.uiState.value.smsPermissionGranted)
        assertEquals(SmsRescanStatus.OK, vm.uiState.value.rescanStatus)
    }

    @Test
    fun onAppResumed_afterPermissionGranted_triggersRescan() = runTest {
        val loader = FakeLoader()
        loader.put(currentPeriod, overview(currentPeriod, spending = "10.00"))
        var permissionGranted = false
        var rescanCalls = 0
        val vm = viewModel(
            loader = loader,
            permissionStateProvider = { permissionGranted },
            rescanService = {
                rescanCalls++
                HistoricalImportResult(parsed = 1, scanned = 1, inserted = 1)
            },
        )
        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.smsPermissionGranted)

        permissionGranted = true
        vm.onAppResumed()
        advanceUntilIdle()

        assertEquals(1, rescanCalls)
        assertTrue(vm.uiState.value.smsPermissionGranted)
    }

    private class FakeCardRegistry(
        vararg initial: CardRegistryEntry,
    ) : com.baraa.masroof.domain.repository.CardRegistryRepository {
        val entries = initial.toMutableList()

        override suspend fun observe(reference: CardReference, rawSmsId: String) = Unit

        override suspend fun setOwnership(reference: CardReference, status: OwnershipStatus) {
            val index = entries.indexOfFirst { it.bank == reference.bank && it.last4 == reference.last4 }
            if (index >= 0) {
                entries[index] = entries[index].copy(ownership = status)
            }
        }

        override suspend fun resolve(reference: CardReference): OwnershipStatus =
            entries.find { it.bank == reference.bank && it.last4 == reference.last4 }?.ownership
                ?: OwnershipStatus.UNKNOWN

        override suspend fun get(reference: CardReference) =
            entries.find { it.bank == reference.bank && it.last4 == reference.last4 }

        override suspend fun listAll(): List<CardRegistryEntry> = entries.toList()

        override suspend fun updateDisplayName(reference: CardReference, displayName: String?) = Unit

        override suspend fun updateCardNetwork(reference: CardReference, network: com.baraa.masroof.domain.model.CardNetwork?) = Unit

        override suspend fun updateCardType(reference: CardReference, cardType: com.baraa.masroof.domain.model.CardType?) = Unit

        override suspend fun linkDebitToAccount(card: CardReference, account: AccountReference) = Unit

        override suspend fun markAsDebit(reference: CardReference) = Unit

        override suspend fun setPrimaryCard(reference: CardReference) = Unit

        override suspend fun setSupplementaryCard(reference: CardReference, primaryLast4: String) = Unit

        override suspend fun clearCardRole(reference: CardReference) = Unit
    }

    private class FakeAccountRegistry(
        vararg initial: com.baraa.masroof.domain.model.AccountRegistryEntry,
    ) : com.baraa.masroof.domain.repository.AccountRegistryRepository {
        private val entries = initial.toMutableList()

        override suspend fun observe(reference: AccountReference, rawSmsId: String) = Unit

        override suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus) = Unit

        override suspend fun resolve(reference: AccountReference): OwnershipStatus =
            entries.find { it.bank == reference.bank && it.maskedNumber == reference.maskedNumber }?.ownership
                ?: OwnershipStatus.UNKNOWN

        override suspend fun get(reference: AccountReference) =
            entries.find { it.bank == reference.bank && it.maskedNumber == reference.maskedNumber }

        override suspend fun listAll() = entries.toList()
        override suspend fun updateDisplayName(reference: AccountReference, displayName: String?) = Unit
            override suspend fun updateAccountType(reference: com.baraa.masroof.domain.model.AccountReference, accountType: com.baraa.masroof.domain.model.AccountType) = Unit
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
        val layoutRepo = FakeLayoutPreferencesRepository()
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

    private fun viewModel(
        loader: FakeLoader,
        cardRegistry: com.baraa.masroof.domain.repository.CardRegistryRepository = FakeCardRegistry(),
        accountRegistry: com.baraa.masroof.domain.repository.AccountRegistryRepository = FakeAccountRegistry(),
        periodClock: java.time.Clock = clock,
        periodZoneId: java.time.ZoneId = zone,
        layoutPreferencesRepository: DashboardLayoutPreferencesRepository = FakeLayoutPreferencesRepository(),
        permissionGranted: Boolean = true,
        permissionStateProvider: () -> Boolean = { permissionGranted },
        rescanService: suspend () -> HistoricalImportResult = { HistoricalImportResult() },
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
            dashboardPeriodWorkflow = com.baraa.masroof.testsupport.PresentationWorkflowTestSupport
                .dashboardPeriodWorkflow(
                    now = periodClock.instant(),
                    zoneId = periodZoneId,
                ),
            layoutPreferencesRepository = layoutPreferencesRepository,
            rescanService = rescanService,
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
                reviewRepository = object : com.baraa.masroof.domain.repository.ReviewRepository {
                    override suspend fun getById(id: String) = null
                    override suspend fun findByRawSmsId(rawSmsId: String) = null
                    override suspend fun listRequired() = emptyList<com.baraa.masroof.domain.model.ReviewItem>()
                    override suspend fun listIgnored() = emptyList<com.baraa.masroof.domain.model.ReviewItem>()
                    override suspend fun listAll() = emptyList<com.baraa.masroof.domain.model.ReviewItem>()
                    override suspend fun upsertRequired(
                        rawSmsId: String,
                        kind: com.baraa.masroof.domain.model.ReviewKind,
                        reasons: List<String>,
                        now: java.time.Instant,
                    ) = error("unused")

                    override suspend fun markResolved(
                        id: String,
                        resolutionKind: com.baraa.masroof.domain.model.ReviewResolutionKind,
                        resolvedAt: java.time.Instant,
                        resolvedTransactionId: String?,
                    ) = null
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
        )

    private class FakeAppLocaleRepository : AppLocaleRepository {
        override fun getLanguageTag(): String = AppLocale.DEFAULT_TAG
        override fun setLanguageTag(languageTag: String) = Unit
    }

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
