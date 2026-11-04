package com.baraa.masroof.presentation.onboarding

import com.baraa.masroof.testsupport.EmptyReviewRepository
import com.baraa.masroof.application.onboarding.ImportDatePolicy
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.onboarding.HistoricalImportGateway
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.testsupport.SettingsViewModelTestSupport
import kotlinx.coroutines.CompletableDeferred
import com.baraa.masroof.application.sms.SmsScanFailure
import com.baraa.masroof.application.sms.SmsScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startWithoutBankSelection_waitsForExplicitBanks_andDoesNotScan() = runTest {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.prefs.draft = null
        val vm = fixture.newViewModel()
        advanceUntilIdle()
        vm.onStartClicked()
        vm.startImport()
        advanceUntilIdle()
        assertEquals(OnboardingStep.BANKS, vm.uiState.value.step)
        assertEquals(LocalDate.parse("2026-07-12"), vm.uiState.value.selectedImportDate)
        assertEquals(LocalDate.parse("2026-08-11"), vm.uiState.value.importThroughDate)
        assertEquals(0, fixture.gateway.calls)
    }

    @Test
    fun selectedBanksAndInclusivePeriod_surviveRestart_andReachSharedImport() = runTest {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.toggleBank(Bank.BANK_ALJAZIRA.id)
        fixture.vm.toggleBank("stc_bank")
        fixture.vm.continueFromBanks()
        fixture.vm.selectCustomDate(LocalDate.parse("2026-08-02"))
        fixture.vm.selectImportThroughDate(LocalDate.parse("2026-08-09"))
        val restarted = fixture.newViewModel()
        advanceUntilIdle()
        assertEquals(setOf("stc_bank"), restarted.uiState.value.selectedBankIds)
        assertEquals(LocalDate.parse("2026-08-09"), restarted.uiState.value.importThroughDate)
        assertEquals(0, fixture.gateway.calls)
        restarted.startImport()
        advanceUntilIdle()
        assertEquals(setOf("stc_bank"), fixture.gateway.lastRequest?.bankIds)
        assertEquals(LocalDate.parse("2026-08-02"), fixture.gateway.lastRequest?.from)
        assertEquals(LocalDate.parse("2026-08-09"), fixture.gateway.lastRequest?.through)
    }

    @Test
    fun last27th_beforeAndAfter27() {
        assertEquals(
            LocalDate.parse("2026-07-27"),
            ImportDatePolicy.last27th(LocalDate.parse("2026-08-11")),
        )
        assertEquals(
            LocalDate.parse("2026-08-27"),
            ImportDatePolicy.last27th(LocalDate.parse("2026-08-29")),
        )
    }

    @Test
    fun requiredPermissions_doNotRequestSendSms() {
        assertTrue(OnboardingPermissionPolicy.REQUIRED_SMS_PERMISSIONS.contains(android.Manifest.permission.READ_SMS))
        assertTrue(OnboardingPermissionPolicy.REQUIRED_SMS_PERMISSIONS.contains(android.Manifest.permission.RECEIVE_SMS))
        assertFalse(OnboardingPermissionPolicy.REQUIRED_SMS_PERMISSIONS.contains(android.Manifest.permission.SEND_SMS))
    }

    @Test
    fun unselectedUnknownAccounts_doNotBlockSetup_butOwnedCardWithoutTypeDoes() = runTest {
        val cardRows = mutableListOf(CardRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "8219", OwnershipStatus.OWNED,
            firstSeenRawSmsId = null, lastSeenRawSmsId = null))
        val fixture = Fixture(cards = cardRows, accounts = mutableListOf(AccountRegistryEntry.forTest(
            Bank("stc_bank"), "3001", OwnershipStatus.UNKNOWN, firstSeenRawSmsId = null, lastSeenRawSmsId = null)))
        advanceUntilIdle()
        fixture.vm.onStartClicked(); fixture.vm.startImport()
        advanceUntilIdle()
        assertTrue(fixture.vm.uiState.value.accounts.isEmpty())
        fixture.vm.finalizeOnboarding()
        advanceUntilIdle()
        assertFalse(fixture.prefs.isOnboardingCompleted())
        cardRows[0] = cardRows[0].copy(cardType = CardType.CREDIT)
        fixture.vm.finalizeOnboarding()
        advanceUntilIdle()
        assertTrue(fixture.prefs.isOnboardingCompleted())
    }

    @Test
    fun freshInstall_permissionDenied_stillWelcome() = runTest {
        val fixture = Fixture(permissionGranted = false)
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, fixture.vm.uiState.value.step)
    }

    @Test
    fun freshInstall_permissionGranted_stillWelcome() = runTest {
        val fixture = Fixture(permissionGranted = true)
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, fixture.vm.uiState.value.step)
    }

    @Test
    fun startClicked_permissionDenied_routesBanks() = runTest {
        val fixture = Fixture(permissionGranted = false)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        assertEquals(OnboardingStep.BANKS, fixture.vm.uiState.value.step)
        assertTrue(fixture.prefs.isOnboardingStarted())
    }

    @Test
    fun startClicked_permissionGranted_routesBanks() = runTest {
        val fixture = Fixture(permissionGranted = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        assertEquals(OnboardingStep.BANKS, fixture.vm.uiState.value.step)
        assertTrue(fixture.prefs.isOnboardingStarted())
    }

    @Test
    fun restartAfterStartBeforePermission_routesPermission() = runTest {
        val fixture = Fixture(permissionGranted = false)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PERMISSION, restartVm.uiState.value.step)
    }

    @Test
    fun restartAfterPermissionGranted_routesImportDate() = runTest {
        val fixture = Fixture(permissionGranted = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        assertEquals(OnboardingStep.IMPORT_DATE, restartVm.uiState.value.step)
    }

    @Test
    fun completedOnboarding_routesHome() = runTest {
        val fixture = Fixture(permissionGranted = false)
        fixture.prefs.setOnboardingCompleted(true)
        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        assertEquals(OnboardingStep.HOME, restartVm.uiState.value.step)
    }

    @Test
    fun permissionRefreshWhileWelcome_doesNotSkipWelcome() = runTest {
        val fixture = Fixture(permissionGranted = false)
        advanceUntilIdle()
        fixture.vm.onPermissionResult(true)
        advanceUntilIdle()
        assertEquals(OnboardingStep.WELCOME, fixture.vm.uiState.value.step)
    }

    @Test
    fun permissionRefreshWhileHome_doesNotRestartOnboarding() = runTest {
        val fixture = Fixture(permissionGranted = true)
        fixture.prefs.setOnboardingCompleted(true)
        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        restartVm.onPermissionResult(false)
        advanceUntilIdle()
        assertEquals(OnboardingStep.HOME, restartVm.uiState.value.step)
    }

    @Test
    fun importDate_permissionRevoked_routesPermission() = runTest {
        val fixture = Fixture(permissionGranted = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.continueFromBanks()
        advanceUntilIdle()
        fixture.vm.onPermissionResult(false)
        advanceUntilIdle()
        assertEquals(OnboardingStep.PERMISSION, fixture.vm.uiState.value.step)
    }

    @Test
    fun selectedDate_reachesTheImportRequest() = runTest {
        val fixture = Fixture(permissionGranted = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.selectCustomDate(LocalDate.parse("2026-08-03"))
        fixture.vm.startImport()
        advanceUntilIdle()
        assertEquals(LocalDate.parse("2026-08-03"), fixture.gateway.lastRequest?.from)
    }

    @Test
    fun futureCustomDate_rejected() = runTest {
        val fixture = Fixture(now = Instant.parse("2026-08-11T08:00:00Z"), permissionGranted = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.selectCustomDate(LocalDate.parse("2026-08-12"))
        advanceUntilIdle()
        assertEquals(OnboardingError.INVALID_FUTURE_DATE, fixture.vm.uiState.value.error)
    }

    @Test
    fun persistedIncompleteImport_routesToImportDateNotEndlessImporting() = runTest {
        val fixture = Fixture(permissionGranted = true)
        fixture.prefs.setOnboardingStarted(true)
        fixture.prefs.setHistoricalImportStartEpochMillis(LocalDate.parse("2026-08-03").atStartOfDay(fixture.zone).toInstant().toEpochMilli())
        fixture.prefs.setHistoricalImportCompleted(false)
        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        assertEquals(OnboardingStep.IMPORT_DATE, restartVm.uiState.value.step)
        assertTrue(restartVm.uiState.value.importState is ImportState.Idle)
    }

    @Test
    fun persistedIncompleteImport_restoresSavedDate() = runTest {
        val fixture = Fixture(permissionGranted = true)
        fixture.prefs.draft = null // Previous installations have only the original start boundary.
        val savedDate = LocalDate.parse("2026-08-03")
        fixture.prefs.setOnboardingStarted(true)
        fixture.prefs.setHistoricalImportStartEpochMillis(savedDate.atStartOfDay(fixture.zone).toInstant().toEpochMilli())
        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        assertEquals(savedDate, restartVm.uiState.value.selectedImportDate)
    }

    @Test
    fun explicitRetryAfterRestart_startsScan() = runTest {
        val fixture = Fixture(permissionGranted = true)
        fixture.prefs.setOnboardingStarted(true)
        fixture.prefs.setHistoricalImportStartEpochMillis(LocalDate.parse("2026-08-03").atStartOfDay(fixture.zone).toInstant().toEpochMilli())
        fixture.prefs.setHistoricalImportCompleted(false)
        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        restartVm.startImport()
        advanceUntilIdle()
        assertEquals(1, fixture.gateway.calls)
    }

    @Test
    fun doubleStartWhileScanning_onlyOneGatewayCall() = runTest {
        val fixture = Fixture(permissionGranted = true, gateway = FakeImportGateway(blockFirstCall = true))
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        fixture.vm.startImport()
        fixture.vm.startImport()
        advanceUntilIdle()
        assertEquals(1, fixture.gateway.calls)
        fixture.gateway.completeBlockedCall()
        advanceUntilIdle()
    }

    @Test
    fun retryAfterProviderError_allowsSecondGatewayCall() = runTest {
        val gateway = FakeImportGateway(
            initialResult = com.baraa.masroof.application.onboarding.HistoricalImportResult(
                failure = com.baraa.masroof.application.onboarding.HistoricalImportFailure.ProviderError("x"),
            ),
            nextResult = com.baraa.masroof.application.onboarding.HistoricalImportResult(
                scanned = 1, parsed = 1, duplicates = 0,
            ),
        )
        val fixture = Fixture(permissionGranted = true, gateway = gateway)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        fixture.vm.startImport()
        advanceUntilIdle()
        fixture.vm.startImport()
        advanceUntilIdle()
        assertEquals(2, fixture.gateway.calls)
    }

    @Test
    fun candidateLoadFailureAfterScanSuccess_doesNotPersistImportCompleted() = runTest {
        val fixture = Fixture(permissionGranted = true, candidateLoadShouldFail = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        fixture.vm.startImport()
        advanceUntilIdle()
        assertFalse(fixture.prefs.isHistoricalImportCompleted())
        assertEquals(OnboardingError.IMPORT_FAILED, fixture.vm.uiState.value.error)
    }

    @Test
    fun retryAfterCandidateLoadFailure_succeedsSafely() = runTest {
        val fixture = Fixture(permissionGranted = true, candidateLoadShouldFail = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        fixture.vm.startImport()
        advanceUntilIdle()
        fixture.candidateLoadShouldFail = false
        fixture.vm.startImport()
        advanceUntilIdle()
        assertTrue(fixture.prefs.isHistoricalImportCompleted())
        assertEquals(OnboardingStep.OWNERSHIP, fixture.vm.uiState.value.step)
    }

    @Test
    fun importSuccess_advancesToOwnership() = runTest {
        val fixture = Fixture(permissionGranted = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        assertEquals(OnboardingStep.OWNERSHIP, fixture.vm.uiState.value.step)
        assertTrue(fixture.prefs.isHistoricalImportCompleted())
    }

    @Test
    fun providerFailure_showsRetryState() = runTest {
        val fixture = Fixture(
            permissionGranted = true,
            gateway = FakeImportGateway(initialResult = com.baraa.masroof.application.onboarding.HistoricalImportResult(
                failure = com.baraa.masroof.application.onboarding.HistoricalImportFailure.ProviderError("x"),
            )),
        )
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        assertTrue(fixture.vm.uiState.value.importState is ImportState.ProviderError)
        assertEquals(OnboardingError.SMS_PROVIDER_ERROR, fixture.vm.uiState.value.error)
    }

    @Test
    fun permissionFailure_returnsPermissionStep() = runTest {
        val fixture = Fixture(
            permissionGranted = true,
            gateway = FakeImportGateway(initialResult = com.baraa.masroof.application.onboarding.HistoricalImportResult(
                failure = com.baraa.masroof.application.onboarding.HistoricalImportFailure.PermissionDenied,
            )),
        )
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        assertEquals(OnboardingStep.PERMISSION, fixture.vm.uiState.value.step)
    }

    @Test
    fun unknownCandidatesShown_bankUnknownHidden() = runTest {
        val fixture = Fixture(
            permissionGranted = true,
            accounts = mutableListOf(
AccountRegistryEntry.forTest(
                    Bank.BANK_ALJAZIRA,
                    "3001",
                    OwnershipStatus.UNKNOWN,
                    firstSeenRawSmsId = null,
                    lastSeenRawSmsId = null,
                ),
                AccountRegistryEntry.forTest(
                    Bank.UNKNOWN,
                    "9999",
                    OwnershipStatus.UNKNOWN,
                    firstSeenRawSmsId = null,
                    lastSeenRawSmsId = null,
                ),
            ),
            cards = mutableListOf(
                CardRegistryEntry.forTest(
                    Bank.BANK_ALJAZIRA,
                    "7271",
                    OwnershipStatus.UNKNOWN,
                    firstSeenRawSmsId = null,
                    lastSeenRawSmsId = null,
                ),
            ),
        )
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        assertEquals(1, fixture.vm.uiState.value.accounts.size)
        assertEquals("3001", fixture.vm.uiState.value.accounts.single().suffix)
        assertEquals(1, fixture.vm.uiState.value.cards.size)
    }

    @Test
    fun ownershipActions_callP7AndCanRevise() = runTest {
        val fixture = Fixture(
            permissionGranted = true,
            accounts = mutableListOf(
                AccountRegistryEntry.forTest(
                    Bank.BANK_ALJAZIRA,
                    "3001",
                    OwnershipStatus.UNKNOWN,
                    firstSeenRawSmsId = null,
                    lastSeenRawSmsId = null,
                ),
            ),
        )
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        val account = fixture.vm.uiState.value.accounts.single()
        fixture.vm.setAccountOwnership(account, owned = true)
        advanceUntilIdle()
        assertEquals(OwnershipStatus.OWNED, fixture.accountRepo.resolve(AccountReference(Bank.BANK_ALJAZIRA, "3001")))
        fixture.vm.setAccountOwnership(account.copy(ownership = OwnershipStatus.OWNED), owned = false)
        advanceUntilIdle()
        assertEquals(OwnershipStatus.EXTERNAL, fixture.accountRepo.resolve(AccountReference(Bank.BANK_ALJAZIRA, "3001")))
    }

    @Test
    fun unresolvedUnknown_blocksFinalize_zeroCandidatesAllows() = runTest {
        val fixture = Fixture(
            permissionGranted = true,
            accounts = mutableListOf(
                AccountRegistryEntry.forTest(
                    Bank.BANK_ALJAZIRA,
                    "3001",
                    OwnershipStatus.UNKNOWN,
                    firstSeenRawSmsId = null,
                    lastSeenRawSmsId = null,
                ),
            ),
        )
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        fixture.vm.finalizeOnboarding()
        advanceUntilIdle()
        assertFalse(fixture.prefs.isOnboardingCompleted())

        val noCandidates = Fixture(permissionGranted = true, accounts = mutableListOf(), cards = mutableListOf())
        advanceUntilIdle()
        noCandidates.vm.onStartClicked()
        noCandidates.vm.startImport()
        advanceUntilIdle()
        noCandidates.vm.finalizeOnboarding()
        advanceUntilIdle()
        assertTrue(noCandidates.prefs.isOnboardingCompleted())
    }

    @Test
    fun finalization_refreshesAndPersistsCompletion_andRestartRoutesHome() = runTest {
        val fixture = Fixture(permissionGranted = true, accounts = mutableListOf())
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        fixture.vm.finalizeOnboarding()
        advanceUntilIdle()
        assertTrue(fixture.refreshed)
        assertTrue(fixture.prefs.isOnboardingCompleted())
        assertEquals(OnboardingStep.FINALIZE, fixture.vm.uiState.value.step)

        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        assertEquals(OnboardingStep.HOME, restartVm.uiState.value.step)
    }

    @Test
    fun finalizeRechecksRepositoryState_beforeCompleting() = runTest {
        val fixture = Fixture(permissionGranted = true, accounts = mutableListOf())
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        fixture.accountRepo.addUnknown("3001")
        fixture.vm.finalizeOnboarding()
        advanceUntilIdle()
        assertFalse(fixture.prefs.isOnboardingCompleted())
    }

    @Test
    fun importStartDate_persistsAcrossRestart() = runTest {
        val fixture = Fixture(permissionGranted = true)
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.selectCustomDate(LocalDate.parse("2026-08-03"))
        fixture.vm.startImport()
        advanceUntilIdle()
        val restartVm = fixture.newViewModel()
        restartVm.reloadFromCurrentState()
        advanceUntilIdle()
        assertEquals(LocalDate.parse("2026-08-03"), restartVm.uiState.value.selectedImportDate)
    }

    @Test
    fun resumeDuringRetainedImport_preservesScanningAndDoesNotLaunchADuplicate() = runTest {
        val fixture = Fixture(permissionGranted = true, gateway = FakeImportGateway(blockFirstCall = true))
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        runCurrent()
        assertEquals(OnboardingStep.IMPORTING, fixture.vm.uiState.value.step)
        fixture.vm.reloadFromCurrentState()
        runCurrent()
        assertEquals(OnboardingStep.IMPORTING, fixture.vm.uiState.value.step)
        assertEquals(ImportState.Scanning, fixture.vm.uiState.value.importState)
        fixture.vm.startImport()
        assertEquals(1, fixture.gateway.calls)
        fixture.gateway.completeBlockedCall()
        advanceUntilIdle()
        assertEquals(OnboardingStep.OWNERSHIP, fixture.vm.uiState.value.step)
    }

    @Test
    fun resumeWhileCatalogLoads_doesNotOverwriteAnImportStartedMeanwhile() = runTest {
        val fixture = Fixture(permissionGranted = true, gateway = FakeImportGateway(blockFirstCall = true))
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fixture.catalogGate = gate
        fixture.vm.reloadFromCurrentState()
        runCurrent()
        fixture.vm.startImport()
        runCurrent()
        assertEquals(OnboardingStep.IMPORTING, fixture.vm.uiState.value.step)
        gate.complete(Unit)
        runCurrent()
        assertEquals(OnboardingStep.IMPORTING, fixture.vm.uiState.value.step)
        assertEquals(ImportState.Scanning, fixture.vm.uiState.value.importState)
        fixture.gateway.completeBlockedCall()
        advanceUntilIdle()
    }

    @Test
    fun partialParseFailures_doNotCompleteHistoricalOnboardingImport() = runTest {
        val fixture = Fixture(permissionGranted = true, gateway = FakeImportGateway(
            initialResult = com.baraa.masroof.application.onboarding.HistoricalImportResult(scanned = 2, parsed = 1, failed = 1),
        ))
        advanceUntilIdle()
        fixture.vm.onStartClicked()
        fixture.vm.startImport()
        advanceUntilIdle()
        assertFalse(fixture.prefs.isHistoricalImportCompleted())
        assertEquals(OnboardingError.IMPORT_FAILED, fixture.vm.uiState.value.error)
        assertTrue(fixture.vm.uiState.value.importState is ImportState.ProviderError)
    }

    @Test
    fun restoreBackup_invalidPackage_setsError() = runTest {
        val fixture = Fixture(permissionGranted = false)
        advanceUntilIdle()
        fixture.vm.restoreBackup(android.net.Uri.parse("content://backup/invalid.masroof"))
        advanceUntilIdle()
        assertEquals(OnboardingError.BACKUP_RESTORE_INVALID, fixture.vm.uiState.value.error)
        assertFalse(fixture.vm.uiState.value.restoringBackup)
    }

    private class Fixture(
        now: Instant = Instant.parse("2026-08-11T08:00:00Z"),
        permissionGranted: Boolean = true,
        gateway: FakeImportGateway = FakeImportGateway(),
        candidateLoadShouldFail: Boolean = false,
        accounts: MutableList<AccountRegistryEntry> = mutableListOf(),
        cards: MutableList<CardRegistryEntry> = mutableListOf(),
    ) {
        val zone: ZoneId = ZoneId.systemDefault()
        val prefs = FakeOnboardingPrefs()
        val accountRepo = FakeAccountRepo(accounts) { this.candidateLoadShouldFail }
        val cardRepo = FakeCardRepo(cards)
        val reviewRepo = FakeReviewRepo()
        val gateway = gateway
        val backupGateway = FakeDatabaseBackupGateway()
        var permissionGranted: Boolean = permissionGranted
        var candidateLoadShouldFail: Boolean = candidateLoadShouldFail
        var refreshed: Boolean = false
        var catalogGate: CompletableDeferred<Unit>? = null

        val vm: OnboardingViewModel = newViewModel(now)

        fun newViewModel(now: Instant = Instant.parse("2026-08-11T08:00:00Z")): OnboardingViewModel {
            return OnboardingViewModel(
                onboardingPrefs = prefs,
                historicalImportGateway = gateway,
                onboardingOwnershipWorkflow = com.baraa.masroof.testsupport.PresentationWorkflowTestSupport
                    .onboardingOwnershipWorkflow(
                        accounts = accountRepo,
                        cards = cardRepo,
                        reviewRepository = reviewRepo,
                    ),
                refreshReviewQueue = { refreshed = true },
                databaseBackupService = backupGateway,
                permissionStateProvider = { permissionGranted },
                zoneId = zone,
                clock = Clock.fixed(now, zone),
                loadBankCatalog = { catalogGate?.await(); com.baraa.masroof.application.patterns.PatternCatalog(0, listOf(
                    com.baraa.masroof.application.patterns.PatternBankView(Bank.BANK_ALJAZIRA.id, "AlJazira", listOf("AlJazira"), emptyList()),
                    com.baraa.masroof.application.patterns.PatternBankView("stc_bank", "STC", listOf("STCBank"), emptyList()),
                )) },
            )
        }
    }

    private class FakeDatabaseBackupGateway(
        var outcome: BackupImportOutcome = BackupImportOutcome.InvalidPackage,
    ) : DatabaseBackupGateway {
        override suspend fun exportTo(destination: android.net.Uri, passphrase: CharArray): Result<Unit> =
            Result.success(Unit)

        override suspend fun inspect(source: android.net.Uri): BackupPackageKind = BackupPackageKind.UNRECOGNIZED

        override suspend fun importFrom(
            source: android.net.Uri,
            passphrase: CharArray,
            confirmLegacyPlaintext: Boolean,
        ): BackupImportOutcome = outcome
    }

    private class FakeOnboardingPrefs : OnboardingPreferencesRepository {
        var draft: com.baraa.masroof.application.onboarding.OnboardingSetupDraft? = com.baraa.masroof.application.onboarding.OnboardingSetupDraft(
            setOf(Bank.BANK_ALJAZIRA.id), "2026-07-27", "2026-08-11", true)
        override fun getSetupDraft() = draft
        override fun setSetupDraft(draft: com.baraa.masroof.application.onboarding.OnboardingSetupDraft?) { this.draft = draft }
        var started = false
        var done = false
        var start: Long? = null
        var imported = false
        override fun isOnboardingStarted(): Boolean = started
        override fun setOnboardingStarted(started: Boolean) {
            this.started = started
        }
        override fun isOnboardingCompleted(): Boolean = done
        override fun setOnboardingCompleted(completed: Boolean) {
            done = completed
        }
        override fun getHistoricalImportStartEpochMillis(): Long? = start
        override fun setHistoricalImportStartEpochMillis(epochMillis: Long?) {
            start = epochMillis
        }
        override fun isHistoricalImportCompleted(): Boolean = imported
        override fun setHistoricalImportCompleted(completed: Boolean) {
            imported = completed
        }
    }

    private class FakeImportGateway(
        initialResult: com.baraa.masroof.application.onboarding.HistoricalImportResult =
            com.baraa.masroof.application.onboarding.HistoricalImportResult(scanned = 10, parsed = 6, duplicates = 2),
        private val nextResult: com.baraa.masroof.application.onboarding.HistoricalImportResult? = null,
        private val blockFirstCall: Boolean = false,
    ) : HistoricalImportGateway {
        private var result: com.baraa.masroof.application.onboarding.HistoricalImportResult = initialResult
        private var blocked: CompletableDeferred<Unit>? = null
        var lastRequest: com.baraa.masroof.application.onboarding.OnboardingImportRequest? = null
        var calls: Int = 0
        override suspend fun scan(request: com.baraa.masroof.application.onboarding.OnboardingImportRequest): com.baraa.masroof.application.onboarding.HistoricalImportResult {
            calls++
            lastRequest = request
            if (blockFirstCall && calls == 1) {
                blocked = CompletableDeferred()
                blocked?.await()
            }
            val current = result
            if (nextResult != null) {
                result = nextResult
            }
            return current
        }
        fun completeBlockedCall() {
            blocked?.complete(Unit)
        }
    }

    private class FakeAccountRepo(
        private val entries: MutableList<AccountRegistryEntry>,
        private val failLoad: () -> Boolean = { false },
    ) : AccountRegistryRepository {
        override suspend fun observe(reference: AccountReference, rawSmsId: String) = Unit
        override suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus) {
            val idx = entries.indexOfFirst { it.bank == reference.bank && it.maskedNumber == reference.maskedNumber }
            if (idx >= 0) entries[idx] = entries[idx].copy(ownership = status)
            else entries += AccountRegistryEntry.forTest(
                reference.bank,
                reference.maskedNumber ?: "",
                status,
                firstSeenRawSmsId = null,
                lastSeenRawSmsId = null,
            )
        }
        override suspend fun resolve(reference: AccountReference): OwnershipStatus =
            entries.firstOrNull { it.bank == reference.bank && it.maskedNumber == reference.maskedNumber }?.ownership
                ?: OwnershipStatus.UNKNOWN
        override suspend fun get(reference: AccountReference): AccountRegistryEntry? =
            entries.firstOrNull { it.bank == reference.bank && it.maskedNumber == reference.maskedNumber }
        override suspend fun listAll(): List<AccountRegistryEntry> { check(!failLoad()) { "candidate read failed" }; return entries.toList() }
        override suspend fun updateDisplayName(reference: AccountReference, displayName: String?) = Unit
            override suspend fun updateAccountType(reference: com.baraa.masroof.domain.model.AccountReference, accountType: com.baraa.masroof.domain.model.AccountType) = Unit
        fun addUnknown(maskedNumber: String) {
            entries += AccountRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                maskedNumber,
                OwnershipStatus.UNKNOWN,
                firstSeenRawSmsId = null,
                lastSeenRawSmsId = null,
            )
        }
    }

    private class FakeCardRepo(
        private val entries: MutableList<CardRegistryEntry>,
    ) : CardRegistryRepository {
    override suspend fun configure(reference: com.baraa.masroof.domain.model.CardReference, configuration: com.baraa.masroof.domain.model.CardConfiguration) { error("configure not used by this test double") }

        override suspend fun observe(reference: CardReference, rawSmsId: String) = Unit
        override suspend fun setOwnership(reference: CardReference, status: OwnershipStatus) {
            val idx = entries.indexOfFirst { it.bank == reference.bank && it.last4 == reference.last4 }
            if (idx >= 0) entries[idx] = entries[idx].copy(ownership = status)
            else entries += CardRegistryEntry.forTest(
                reference.bank,
                reference.last4 ?: "",
                status,
                firstSeenRawSmsId = null,
                lastSeenRawSmsId = null,
            )
        }
        override suspend fun resolve(reference: CardReference): OwnershipStatus =
            entries.firstOrNull { it.bank == reference.bank && it.last4 == reference.last4 }?.ownership
                ?: OwnershipStatus.UNKNOWN
        override suspend fun get(reference: CardReference): CardRegistryEntry? =
            entries.firstOrNull { it.bank == reference.bank && it.last4 == reference.last4 }
        override suspend fun listAll(): List<CardRegistryEntry> = entries.toList()

        override suspend fun updateDisplayName(reference: CardReference, displayName: String?) = Unit

        override suspend fun updateCardNetwork(reference: CardReference, network: CardNetwork?) = Unit

        override suspend fun updateCardType(reference: CardReference, cardType: CardType?) = Unit
    }

    private class FakeReviewRepo : EmptyReviewRepository() {
        override suspend fun upsertRequired(
            rawSmsId: String,
            kind: ReviewKind,
            reasons: List<String>,
            now: Instant,
        ): ReviewItem {
            return ReviewItem(
                id = "review:$rawSmsId",
                rawSmsId = rawSmsId,
                kind = kind,
                status = ReviewStatus.REQUIRED,
                reasons = reasons,
                createdAt = now,
                updatedAt = now,
                resolvedAt = null,
                resolutionKind = null,
                resolvedTransactionId = null,
            )
        }
    }
}
