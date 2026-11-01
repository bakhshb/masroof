package com.baraa.masroof.presentation.settings

import com.baraa.masroof.testsupport.FakeAppLocaleRepository
import com.baraa.masroof.testsupport.MutableCardRegistryRepository
import com.baraa.masroof.testsupport.MutableCardRegistryRepository as TrackingCardRegistry
import com.baraa.masroof.testsupport.StubAccountRegistryRepository

import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.theme.ThemePreferencesRepository
import com.baraa.masroof.application.update.ApkIntegrityVerifier
import com.baraa.masroof.application.update.UpdateChannel
import com.baraa.masroof.application.update.UpdateManifest
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.sms.time.InstantClock
import com.baraa.masroof.testsupport.SettingsViewModelTestSupport
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import java.time.LocalDate
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @Test
    fun manualAccountRemainsVisibleWhenReconciliationFailsAfterSavingTheDecision() = runTest {
        val accounts = FakeAccountRegistry()
        val vm = viewModel(accounts = accounts, onReconcileOwnership = { error("retry required") })
        vm.addManualAccount("BANK_ALJAZIRA", "3001", "My account") {}
        advanceUntilIdle()
        assertEquals(SettingsError.UPDATE_FAILED, vm.uiState.value.error)
        assertEquals("3001", vm.uiState.value.followedAccounts.single().maskedNumber)
        assertFalse(vm.uiState.value.updating)
    }
    @Test fun confirmingNewCardOpensSetupAfterOwnershipReconciliation() = runTest {
        val cards = FakeCardRegistry(CardRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "1234", ownership = OwnershipStatus.UNKNOWN))
        var reconciles = 0
        val vm = viewModel(cards = cards, onReconcileOwnership = { reconciles++ })
        vm.refresh(); advanceUntilIdle()
        vm.confirmCardOwned(vm.uiState.value.unregisteredCards.single()); advanceUntilIdle()
        assertEquals(1, reconciles)
        assertEquals(OwnershipStatus.OWNED, vm.uiState.value.cardNetworkTarget?.ownership)
        assertEquals("1234", vm.uiState.value.cardNetworkTarget?.last4)
        assertNull(vm.uiState.value.cardNetworkTarget?.cardType)
    }

    @Test fun confirmedSetupUpdatesOnlyCardMetadataWithoutReconciliationOrReparse() = runTest {
        val cards = FakeCardRegistry(CardRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "1234",
            cardType = com.baraa.masroof.domain.model.CardType.DEBIT,
            cardNetwork = com.baraa.masroof.domain.model.CardNetwork.MADA,
            linkedAccountBankId = Bank.BANK_ALJAZIRA.id, linkedAccountMaskedNumber = "3001"))
        var reconciles = 0
        var refreshes = 0
        val vm = viewModel(cards = cards, onRefreshReviewQueue = { refreshes++ }, onReconcileOwnership = { reconciles++ })
        vm.refresh(); advanceUntilIdle()
        vm.openCardNetworkPicker(vm.uiState.value.followedCards.single()); advanceUntilIdle()
        val baseline = refreshes to reconciles
        vm.saveCardSetup(com.baraa.masroof.domain.model.CardConfiguration(com.baraa.masroof.domain.model.CardNetwork.VISA,
            com.baraa.masroof.domain.model.CardType.CREDIT, com.baraa.masroof.domain.model.CardRole.PRIMARY))
        advanceUntilIdle()
        assertEquals(baseline, refreshes to reconciles)
        assertNull(vm.uiState.value.cardNetworkTarget)
        assertEquals(com.baraa.masroof.domain.model.CardType.CREDIT, vm.uiState.value.followedCards.single().cardType)
        assertNull(vm.uiState.value.followedCards.single().linkedAccountMaskedNumber)
    }

    @Test fun failedSetupKeepsEditorOpenAndDoesNotReconcile() = runTest {
        val cards = TrackingCardRegistry(CardRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "1234"))
        var reconciles = 0
        val vm = viewModel(cards = cards, onReconcileOwnership = { reconciles++ })
        vm.refresh(); advanceUntilIdle()
        vm.openCardNetworkPicker(vm.uiState.value.followedCards.single()); advanceUntilIdle()
        vm.saveCardSetup(com.baraa.masroof.domain.model.CardConfiguration(com.baraa.masroof.domain.model.CardNetwork.VISA,
            com.baraa.masroof.domain.model.CardType.CREDIT, com.baraa.masroof.domain.model.CardRole.PRIMARY))
        advanceUntilIdle()
        assertEquals(SettingsError.UPDATE_FAILED, vm.uiState.value.error)
        assertEquals("1234", vm.uiState.value.cardNetworkTarget?.last4)
        assertFalse(vm.uiState.value.updating)
        assertEquals(0, reconciles)
    }

    @Test
    fun renamingOwnedEntitiesOnlyUpdatesTheirLabels() = runTest {
        val cards = FakeCardRegistry(CardRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "1234"))
        val accounts = FakeAccountRegistry(AccountRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "3001"))
        val vm = viewModel(cards = cards, accounts = accounts,
            onRefreshReviewQueue = { error("Renaming must not reconcile financial history") })
        vm.refresh()
        advanceUntilIdle()
        vm.openRenameCard(vm.uiState.value.followedCards.single())
        vm.saveCardDisplayName("  Travel  ")
        advanceUntilIdle()
        vm.openRenameAccount(vm.uiState.value.followedAccounts.single())
        vm.saveAccountDisplayName("  Salary  ")
        advanceUntilIdle()
        assertEquals("Travel", vm.uiState.value.followedCards.single().displayName)
        assertEquals("Salary", vm.uiState.value.followedAccounts.single().displayName)
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.updating)
    }

    @Test
    fun completedReparseReloadsRegistriesWithoutAnotherFinancialPass() = runTest {
        val vm = viewModel(onRefreshReviewQueue = { error("Reparse already completed derived work") })
        vm.reparseStoredMessages()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.reparsingStored)
    }

    @Test
    fun refreshingSmsPermissionDoesNotStartImportOrFinancialWork() = runTest {
        var granted = false
        val vm = viewModel(
            permissionStateProvider = { granted },
            onRefreshReviewQueue = { error("Permission changes must not reconcile") },
        )
        vm.refreshSmsPermissionState()
        assertFalse(vm.uiState.value.smsPermissionGranted)

        granted = true
        vm.refreshSmsPermissionState()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.smsPermissionGranted)
        assertFalse(vm.uiState.value.importingSms)
        assertNull(vm.uiState.value.scopedSmsImportResult)
        assertFalse(vm.uiState.value.scopedSmsImportFailed)
    }

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
    fun refresh_groupsCardsByOwnership() = runTest {
        val cards = FakeCardRegistry(
            CardRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "7271",
                OwnershipStatus.OWNED,
                firstSeenRawSmsId = "1",
                lastSeenRawSmsId = "1",
            ),
            CardRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "5123",
                OwnershipStatus.UNKNOWN,
                firstSeenRawSmsId = "2",
                lastSeenRawSmsId = "2",
            ),
            CardRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "9999",
                OwnershipStatus.EXTERNAL,
                firstSeenRawSmsId = "3",
                lastSeenRawSmsId = "3",
            ),
        )
        val vm = viewModel(cards = cards)
        vm.refresh()
        advanceUntilIdle()

        assertEquals("7271", vm.uiState.value.followedCards.single().last4)
        assertEquals("5123", vm.uiState.value.unregisteredCards.single().last4)
        assertEquals("9999", vm.uiState.value.stoppedCards.single().last4)
        assertEquals(SettingsViewModelTestFixtures.APP_VERSION, vm.uiState.value.appVersion)
    }

    @Test
    fun setThemeMode_updatesState() = runTest {
        val vm = viewModel(themeMode = ThemeMode.SYSTEM)
        vm.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, vm.uiState.value.themeMode)
    }

    @Test
    fun setUpdateChannel_updatesState() = runTest {
        val vm = viewModel()
        vm.setUpdateChannel(UpdateChannel.NIGHTLY)
        advanceUntilIdle()
        assertEquals(UpdateChannel.NIGHTLY, vm.uiState.value.updateChannel)
    }

    @Test
    fun refresh_groupsAccountsByOwnership() = runTest {
        val accounts = FakeAccountRegistry(
            AccountRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "3001",
                OwnershipStatus.OWNED,
                firstSeenRawSmsId = "1",
                lastSeenRawSmsId = "1",
            ),
            AccountRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "3002",
                OwnershipStatus.UNKNOWN,
                firstSeenRawSmsId = "2",
                lastSeenRawSmsId = "2",
            ),
            AccountRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "3003",
                OwnershipStatus.EXTERNAL,
                firstSeenRawSmsId = "3",
                lastSeenRawSmsId = "3",
            ),
        )
        val vm = viewModel(accounts = accounts)
        vm.refresh()
        advanceUntilIdle()

        assertEquals("3001", vm.uiState.value.followedAccounts.single().maskedNumber)
        assertEquals("3002", vm.uiState.value.unregisteredAccounts.single().maskedNumber)
        assertEquals("3003", vm.uiState.value.stoppedAccounts.single().maskedNumber)
    }

    @Test
    fun resumeTracking_marksCardOwned() = runTest {
        val cards = FakeCardRegistry(
            CardRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "9999",
                OwnershipStatus.EXTERNAL,
                firstSeenRawSmsId = "3",
                lastSeenRawSmsId = "3",
            ),
        )
        var refreshCalls = 0
        val ownershipChanges = mutableListOf<com.baraa.masroof.application.review.ReviewWorkflowService.OwnershipChange>()
        val vm = viewModel(
            cards = cards,
            onRefreshReviewQueue = { refreshCalls++ },
            onReconcileOwnership = { ownershipChanges += it },
        )
        vm.refresh()
        advanceUntilIdle()
        vm.resumeTracking(
            ManagedCardUi(
                id = com.baraa.masroof.domain.ids.RegistryEntityIdFactory.stableCardId(
                    Bank.BANK_ALJAZIRA.id,
                    "9999",
                ),
                bank = Bank.BANK_ALJAZIRA,
                last4 = "9999",
                ownership = OwnershipStatus.EXTERNAL,
            ),
        )
        advanceUntilIdle()

        assertEquals(OwnershipStatus.OWNED, cards.entries.single().ownership)
        assertEquals(0, refreshCalls)
        assertEquals(
            listOf(
                com.baraa.masroof.application.review.ReviewWorkflowService.OwnershipChange.Card(
                    CardReference(Bank.BANK_ALJAZIRA, "9999"),
                ),
            ),
            ownershipChanges,
        )
        assertTrue(vm.uiState.value.followedCards.any { it.last4 == "9999" })
    }

    @Test
    fun resumeAccountTracking_marksAccountOwned() = runTest {
        val accounts = FakeAccountRegistry(
            AccountRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "3003",
                OwnershipStatus.EXTERNAL,
                firstSeenRawSmsId = "3",
                lastSeenRawSmsId = "3",
            ),
        )
        val vm = viewModel(accounts = accounts)
        vm.refresh()
        advanceUntilIdle()
        vm.resumeAccountTracking(
            ManagedAccountUi(
                id = com.baraa.masroof.domain.ids.RegistryEntityIdFactory.stableAccountId(
                    Bank.BANK_ALJAZIRA.id,
                    "3003",
                ),
                bank = Bank.BANK_ALJAZIRA,
                maskedNumber = "3003",
                ownership = OwnershipStatus.EXTERNAL,
            ),
        )
        advanceUntilIdle()

        assertEquals(OwnershipStatus.OWNED, accounts.entries.single().ownership)
        assertTrue(vm.uiState.value.followedAccounts.any { it.maskedNumber == "3003" })
    }

    @Test
    fun setAccountType_reconcilesOnlyThatAccount() = runTest {
        val accounts = FakeAccountRegistry(
            AccountRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "3003", OwnershipStatus.OWNED, firstSeenRawSmsId = "3", lastSeenRawSmsId = "3"),
        )
        val ownershipChanges = mutableListOf<com.baraa.masroof.application.review.ReviewWorkflowService.OwnershipChange>()
        val vm = viewModel(
            accounts = accounts,
            onRefreshReviewQueue = { error("An account type must not reconcile the whole history") },
            onReconcileOwnership = { ownershipChanges += it },
        )
        vm.refresh()
        advanceUntilIdle()

        vm.setAccountType(
            ManagedAccountUi(
                id = com.baraa.masroof.domain.ids.RegistryEntityIdFactory.stableAccountId(Bank.BANK_ALJAZIRA.id, "3003"),
                bank = Bank.BANK_ALJAZIRA,
                maskedNumber = "3003",
                ownership = OwnershipStatus.OWNED,
            ),
            com.baraa.masroof.domain.model.AccountType.SAVINGS,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(com.baraa.masroof.application.review.ReviewWorkflowService.OwnershipChange.Account(AccountReference(Bank.BANK_ALJAZIRA, "3003"))),
            ownershipChanges,
        )
    }

    @Test
    fun confirmStopTracking_marksCardExternal() = runTest {
        val cards = FakeCardRegistry(
            CardRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "7271",
                OwnershipStatus.OWNED,
                firstSeenRawSmsId = "1",
                lastSeenRawSmsId = "1",
            ),
        )
        val vm = viewModel(cards = cards)
        vm.refresh()
        advanceUntilIdle()
        val card = vm.uiState.value.followedCards.single()
        vm.requestStopTracking(card)
        vm.confirmStopTracking()
        advanceUntilIdle()

        assertEquals(OwnershipStatus.EXTERNAL, cards.entries.single().ownership)
        assertNull(vm.uiState.value.stopConfirmCardTarget)
        assertTrue(vm.uiState.value.stoppedCards.any { it.last4 == "7271" })
    }

    @Test
    fun deleteUnusedAccount_needsConfirmationThenRemovesIt() = runTest {
        val accounts = FakeAccountRegistry(AccountRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "3001", OwnershipStatus.OWNED,
            firstSeenRawSmsId = null, lastSeenRawSmsId = null))
        val vm = viewModel(accounts = accounts, transactions = SettingsViewModelTestSupport.emptyFinancialTransactionRepository())
        vm.refresh()
        advanceUntilIdle()
        vm.requestDelete(StopTrackingTarget.Account(vm.uiState.value.followedAccounts.single()))
        advanceUntilIdle()
        assertNull(vm.uiState.value.deleteRequest!!.blocker)
        assertEquals(1, accounts.entries.size)

        vm.confirmDelete()
        advanceUntilIdle()
        assertNull(vm.uiState.value.deleteRequest)
        assertTrue(accounts.entries.isEmpty())
        assertTrue(vm.uiState.value.followedAccounts.isEmpty())
    }

    @Test
    fun deleteLinkedAccount_isBlockedListsTransactionsAndOffersStopTracking() = runTest {
        val accounts = FakeAccountRegistry(AccountRegistryEntry.forTest(Bank.BANK_ALJAZIRA, "3001", OwnershipStatus.OWNED,
            firstSeenRawSmsId = null, lastSeenRawSmsId = null))
        val posted = com.baraa.masroof.domain.model.FinancialTransaction(id = "tx-1",
            type = com.baraa.masroof.domain.model.FinancialTransactionType.EXPENSE, amount = Money.of("25.00", Currency.SAR),
            occurredAt = Instant.parse("2026-09-10T10:00:00Z"), sourceContainerId = "account:BANK_ALJAZIRA:3001",
            destinationContainerId = null, merchant = "TEST SHOP", counterparty = null, categoryId = null,
            linkedParsedEventIds = emptyList(), occurredAtZone = "Asia/Riyadh")
        val ledger = object : com.baraa.masroof.domain.repository.FinancialTransactionRepository by
            SettingsViewModelTestSupport.emptyFinancialTransactionRepository() {
            override suspend fun listAll() = listOf(posted)
        }
        val vm = viewModel(accounts = accounts, transactions = ledger)
        vm.refresh()
        advanceUntilIdle()
        vm.requestDelete(StopTrackingTarget.Account(vm.uiState.value.followedAccounts.single()))
        advanceUntilIdle()
        val request = vm.uiState.value.deleteRequest!!
        assertEquals(com.baraa.masroof.application.settings.DeleteBlocker.LINKED_TRANSACTIONS, request.blocker)
        assertEquals(1, request.linkedCount)
        assertEquals("TEST SHOP", request.linked.single().title)
        assertEquals(java.time.LocalDate.of(2026, 9, 10), request.linked.single().occurredOn)

        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(1, accounts.entries.size)

        vm.stopTrackingInsteadOfDelete()
        assertNull(vm.uiState.value.deleteRequest)
        assertEquals("3001", vm.uiState.value.stopConfirmAccountTarget?.maskedNumber)
    }

    @Test
    fun confirmStopAccountTracking_marksAccountExternal() = runTest {
        val accounts = FakeAccountRegistry(
            AccountRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "3001",
                OwnershipStatus.OWNED,
                firstSeenRawSmsId = "1",
                lastSeenRawSmsId = "1",
            ),
        )
        val vm = viewModel(accounts = accounts)
        vm.refresh()
        advanceUntilIdle()
        val account = vm.uiState.value.followedAccounts.single()
        vm.requestStopAccountTracking(account)
        vm.confirmStopAccountTracking()
        advanceUntilIdle()

        assertEquals(OwnershipStatus.EXTERNAL, accounts.entries.single().ownership)
        assertNull(vm.uiState.value.stopConfirmAccountTarget)
        assertTrue(vm.uiState.value.stoppedAccounts.any { it.maskedNumber == "3001" })
    }

    @Test
    fun debitSetupClearsPriorCreditRole() = runTest {
        val cards = FakeCardRegistry(CardRegistryEntry.forTest(
            Bank.BANK_ALJAZIRA, "7271", OwnershipStatus.OWNED,
            cardRole = com.baraa.masroof.domain.model.CardRole.PRIMARY,
        ))
        val vm = viewModel(cards = cards)
        vm.refresh()
        advanceUntilIdle()
        vm.openCardNetworkPicker(vm.uiState.value.followedCards.single())
        advanceUntilIdle()
        vm.saveCardSetup(com.baraa.masroof.domain.model.CardConfiguration(
            com.baraa.masroof.domain.model.CardNetwork.MADA,
            com.baraa.masroof.domain.model.CardType.DEBIT,
        ))
        advanceUntilIdle()

        val card = vm.uiState.value.followedCards.single()
        assertEquals(com.baraa.masroof.domain.model.CardType.DEBIT, card.cardType)
        assertNull(card.cardRole)
        assertNull(card.parentCardLast4)
    }

    @Test
    fun clearGithubToken_clearsTokenPendingUpdateAndResetsUi() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pendingUpdateStore = com.baraa.masroof.application.update.PendingUpdateStore(
            context.getSharedPreferences("pending_update_prefs_vm_test", android.content.Context.MODE_PRIVATE),
        ).also { it.clear() }
        val manifest = UpdateManifest(
            versionCode = 99,
            versionName = "9.9.0",
            apkFileName = "masroof.apk",
            sha256 = "abc",
            releaseNotes = null,
        )
        pendingUpdateStore.saveAvailable(manifest)
        val appUpdateService = SettingsViewModelTestFixtures.appUpdateService(token = "secret-token")
        val updateCheckCoordinator = SettingsViewModelTestFixtures.updateCheckCoordinator(
            appUpdateService = appUpdateService,
            pendingUpdateStore = pendingUpdateStore,
        )
        val vm = viewModel(
            appUpdateService = appUpdateService,
            updateCheckCoordinator = updateCheckCoordinator,
        )
        vm.refresh()
        advanceUntilIdle()

        vm.clearGithubToken()

        assertFalse(vm.uiState.value.githubTokenConfigured)
        assertEquals(AppUpdateUiState.Idle, vm.uiState.value.updateState)
        assertNull(updateCheckCoordinator.restorePendingUpdate())
        assertFalse(appUpdateService.hasConfiguredToken())
    }

    @Test
    fun saveGithubToken_failedPersistence_isNotReportedAsSaved() = runTest {
        val appUpdateService = SettingsViewModelTestFixtures.appUpdateService(persistToken = false)
        val vm = viewModel(
            appUpdateService = appUpdateService,
            updateCheckCoordinator = SettingsViewModelTestFixtures.updateCheckCoordinator(
                appUpdateService = appUpdateService,
            ),
        )

        vm.saveGithubToken("ghp_secret")

        assertEquals(AppUpdateMessage.TOKEN_SAVE_FAILED, vm.uiState.value.updateMessage)
        assertFalse(vm.uiState.value.githubTokenConfigured)
        assertFalse(appUpdateService.hasConfiguredToken())
    }

    @Test
    fun refresh_clearsStalePendingUpdateAfterInstall() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pendingUpdateStore = com.baraa.masroof.application.update.PendingUpdateStore(
            context.getSharedPreferences("pending_update_prefs_vm_stale_test", android.content.Context.MODE_PRIVATE),
        ).also { it.clear() }
        val installedManifest = UpdateManifest(
            versionCode = 4,
            versionName = "0.2.1",
            apkFileName = "masroof.apk",
            sha256 = "abc",
            releaseNotes = null,
        )
        pendingUpdateStore.saveAvailable(installedManifest)
        val appUpdateService = SettingsViewModelTestFixtures.appUpdateService()
        val updateCheckCoordinator = SettingsViewModelTestFixtures.updateCheckCoordinator(
            appUpdateService = appUpdateService,
            pendingUpdateStore = pendingUpdateStore,
        )
        val vm = viewModel(
            appUpdateService = appUpdateService,
            updateCheckCoordinator = updateCheckCoordinator,
        )

        vm.refresh()
        advanceUntilIdle()

        assertEquals(AppUpdateUiState.Idle, vm.uiState.value.updateState)
        assertNull(updateCheckCoordinator.restorePendingUpdate())
    }

    @Test
    fun installPendingUpdate_clearsUpdateUiAfterLaunchingInstaller() = runTest {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pendingUpdateStore = com.baraa.masroof.application.update.PendingUpdateStore(
            context.getSharedPreferences("pending_update_prefs_vm_install_test", android.content.Context.MODE_PRIVATE),
        ).also { it.clear() }
        val apkBytes = "fake-apk".toByteArray()
        val sha256 = ApkIntegrityVerifier.sha256Hex(
            java.io.File(context.cacheDir, "sha-temp").apply {
                writeBytes(apkBytes)
            },
        )
        val manifest = UpdateManifest(
            versionCode = 99,
            versionName = "9.9.0",
            apkFileName = "masroof.apk",
            sha256 = sha256,
            releaseNotes = null,
        )
        pendingUpdateStore.saveAvailable(manifest)
        val appUpdateService = SettingsViewModelTestFixtures.appUpdateService()
        val apkFile = appUpdateService.updateApkFile(manifest)
        apkFile.parentFile?.mkdirs()
        apkFile.writeBytes(apkBytes)
        val updateCheckCoordinator = SettingsViewModelTestFixtures.updateCheckCoordinator(
            appUpdateService = appUpdateService,
            pendingUpdateStore = pendingUpdateStore,
        )
        val vm = viewModel(
            appUpdateService = appUpdateService,
            updateCheckCoordinator = updateCheckCoordinator,
        )

        vm.refresh()
        advanceUntilIdle()
        // The APK hash check runs on the IO dispatcher, outside the test scheduler.
        vm.uiState.first { it.updateState is AppUpdateUiState.ReadyToInstall }

        vm.installPendingUpdate()
        advanceUntilIdle()

        assertEquals(AppUpdateUiState.Idle, vm.uiState.value.updateState)
        assertEquals(manifest, pendingUpdateStore.readAvailable())
    }

    @Test
    fun refresh_buildsBankSummariesAndLoanTrees() = runTest {
        val loans = FakeLoanRegistry(
            LoanRegistryEntry(
                id = "lreg_1",
                bank = Bank.BANK_ALJAZIRA,
                loanType = LoanType.PERSONAL,
                ownership = OwnershipStatus.OWNED,
                firstSeenRawSmsId = "1",
                lastSeenRawSmsId = "1",
            ),
        )
        val cards = FakeCardRegistry(
            CardRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "7271",
                OwnershipStatus.OWNED,
                firstSeenRawSmsId = "1",
                lastSeenRawSmsId = "1",
            ),
        )
        val accounts = FakeAccountRegistry(
            AccountRegistryEntry.forTest(
                Bank.BANK_ALJAZIRA,
                "3001",
                OwnershipStatus.UNKNOWN,
                firstSeenRawSmsId = "2",
                lastSeenRawSmsId = "2",
            ),
        )
        val vm = viewModel(cards = cards, accounts = accounts, loans = loans)
        vm.refresh()
        advanceUntilIdle()

        val summary = vm.uiState.value.bankSummaries.single()
        assertEquals(Bank.BANK_ALJAZIRA, summary.bank)
        assertEquals(0, summary.followedAccountCount)
        assertEquals(1, summary.unregisteredAccountCount)
        assertEquals(1, summary.followedCardCount)
        assertEquals(1, summary.accountCount)
        assertEquals(1, summary.cardCount)
        assertEquals(1, summary.loanCount)

        val tree = vm.uiState.value.bankTrees.single()
        assertEquals(1, tree.loans.size)
    }

    @Test
    fun refresh_loadsActiveAndDisabledCommitments() = runTest {
        val commitment = sampleCommitment(active = true)
        val disabled = sampleCommitment(id = "cmt_disabled", active = false)
        val vm = viewModel(
            commitments = listOf(commitment, disabled),
        )
        vm.refresh()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.activeCommitments.size)
        assertEquals(commitment.id, vm.uiState.value.activeCommitments.single().id)
        assertEquals(1, vm.uiState.value.disabledCommitments.size)
        assertEquals(disabled.id, vm.uiState.value.disabledCommitments.single().id)
        assertTrue(vm.uiState.value.commitmentsLoaded)
        assertEquals(commitment.id, vm.commitmentById(commitment.id)?.id)
    }

    @Test
    fun saveCommitment_updatesStoredCommitment() = runTest {
        val commitment = sampleCommitment()
        val repo = MutableCommitmentRepository(listOf(commitment))
        val vm = viewModel(commitmentsWorkflow = commitmentsWorkflow(repo))
        vm.refresh()
        advanceUntilIdle()

        vm.saveCommitment(
            commitment.id,
            SettingsCommitmentsWorkflow.CommitmentEditorDraft(
                name = "Updated name",
                amount = Money.of("99.00", Currency.SAR),
                transactionDate = LocalDate.parse("2026-08-15"),
                recurrence = CommitmentRecurrence.YEARLY,
                dueDate = LocalDate.parse("2026-08-20"),
            ),
        )
        advanceUntilIdle()

        val updated = repo.get(commitment.id)!!
        assertEquals("Updated name", updated.name)
        assertEquals(Money.of("99.00", Currency.SAR), updated.amount)
        assertEquals(CommitmentRecurrence.YEARLY, updated.recurrence)
        assertEquals("Updated name", vm.uiState.value.activeCommitments.single().name)
    }

    @Test
    fun saveCommitment_rejectsNonPositiveAmount() = runTest {
        val commitment = sampleCommitment()
        val repo = MutableCommitmentRepository(listOf(commitment))
        val vm = viewModel(commitmentsWorkflow = commitmentsWorkflow(repo))
        vm.refresh()
        advanceUntilIdle()

        vm.saveCommitment(
            commitment.id,
            SettingsCommitmentsWorkflow.CommitmentEditorDraft(
                name = commitment.name,
                amount = Money.of("0.00", Currency.SAR),
                transactionDate = commitment.transactionDate,
                recurrence = commitment.recurrence,
                dueDate = commitment.dueDate,
            ),
        )
        advanceUntilIdle()

        assertEquals(Money.of("71.00", Currency.SAR), repo.get(commitment.id)!!.amount)
    }

    @Test
    fun toggleCommitmentActive_movesBetweenLists() = runTest {
        val commitment = sampleCommitment(active = true)
        val repo = MutableCommitmentRepository(listOf(commitment))
        val vm = viewModel(commitmentsWorkflow = commitmentsWorkflow(repo))
        vm.refresh()
        advanceUntilIdle()

        vm.toggleCommitmentActive(commitment.id)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.activeCommitments.isEmpty())
        assertEquals(commitment.id, vm.uiState.value.disabledCommitments.single().id)
        val stored = repo.get(commitment.id)!!
        assertFalse(stored.active)
        assertEquals(1, stored.pauseIntervals.size)
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), stored.pauseIntervals.single().pausedAt)
        assertTrue(vm.uiState.value.disabledCommitments.single().pauseIntervals.isNotEmpty())
        assertTrue(vm.uiState.value.commitmentRecordEntries.any { it.event == com.baraa.masroof.application.settings.CommitmentRecordEvent.PAUSED })
    }

    @Test
    fun deleteCommitment_removesFromUiState() = runTest {
        val commitment = sampleCommitment()
        val repo = MutableCommitmentRepository(listOf(commitment))
        val vm = viewModel(commitmentsWorkflow = commitmentsWorkflow(repo))
        vm.refresh()
        advanceUntilIdle()

        vm.deleteCommitment(commitment.id)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.activeCommitments.isEmpty())
        assertNull(repo.get(commitment.id))
    }

    private fun sampleCommitment(
        id: String = "cmt_1",
        active: Boolean = true,
    ): Commitment {
        val now = Instant.parse("2026-08-01T00:00:00Z")
        return Commitment(
            id = id,
            name = "Netflix",
            amount = Money.of("71.00", Currency.SAR),
            transactionDate = LocalDate.parse("2026-08-01"),
            recurrence = CommitmentRecurrence.MONTHLY,
            dueDate = LocalDate.parse("2026-08-05"),
            active = active,
            sourceTransactionId = "tx-$id",
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun commitmentsWorkflow(
        repo: CommitmentRepository,
    ): SettingsCommitmentsWorkflow =
        SettingsCommitmentsWorkflow(
            commitmentRepository = repo,
            clock = InstantClock { Instant.parse("2026-09-01T00:00:00Z") },
        )

    private class MutableCommitmentRepository(
        initial: List<Commitment> = emptyList(),
    ) : CommitmentRepository {
        private val items = initial.associateBy { it.id }.toMutableMap()

        override suspend fun create(commitment: Commitment) {
            items[commitment.id] = commitment
        }

        override suspend fun update(commitment: Commitment) {
            items[commitment.id] = commitment
        }

        override suspend fun delete(id: String) {
            items.remove(id)
        }

        override suspend fun get(id: String): Commitment? = items[id]

        override suspend fun getBySourceTransactionId(sourceTransactionId: String): Commitment? =
            items.values.find { it.sourceTransactionId == sourceTransactionId }

        override suspend fun listAll(): List<Commitment> = items.values.sortedBy { it.name }

        override suspend fun listActive(): List<Commitment> = listAll().filter { it.active }
    }

    @Test
    fun incompleteManualReparse_releasesBusyStateAndShowsFailure() = runTest {
        val vm = viewModel(reparseResult = com.baraa.masroof.application.maintenance.ReparseAllStoredEventsResult(3, 1))
        vm.reparseStoredMessages()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.reparsingStored)
        assertEquals(SettingsError.UPDATE_FAILED, vm.uiState.value.error)
    }

    private fun viewModel(
        cards: CardRegistryRepository = FakeCardRegistry(),
        accounts: AccountRegistryRepository = FakeAccountRegistry(),
        loans: LoanRegistryRepository = FakeLoanRegistry(),
        transactions: com.baraa.masroof.domain.repository.FinancialTransactionRepository? = null,
        permissionStateProvider: () -> Boolean = { true },
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        reparseResult: com.baraa.masroof.application.maintenance.ReparseAllStoredEventsResult =
            com.baraa.masroof.application.maintenance.ReparseAllStoredEventsResult(0, 0),
        onRefreshReviewQueue: () -> Unit = {},
        onReconcileOwnership: (com.baraa.masroof.application.review.ReviewWorkflowService.OwnershipChange) -> Unit = {},
        appUpdateService: com.baraa.masroof.application.update.AppUpdateService =
            SettingsViewModelTestFixtures.appUpdateService(),
        updateCheckCoordinator: com.baraa.masroof.application.update.UpdateCheckCoordinator =
            SettingsViewModelTestFixtures.updateCheckCoordinator(appUpdateService = appUpdateService),
        commitments: List<Commitment> = emptyList(),
        commitmentsWorkflow: SettingsCommitmentsWorkflow =
            SettingsCommitmentsWorkflow(
                commitmentRepository = MutableCommitmentRepository(commitments),
                clock = InstantClock { Instant.parse("2026-09-01T00:00:00Z") },
            ),
    ): SettingsViewModel =
        SettingsViewModel(
            settingsRegistryWorkflow = SettingsViewModelTestSupport.settingsRegistryWorkflow(
                cards = cards,
                accounts = accounts,
                loans = loans,
                transactions = transactions,
            ),
            settingsCommitmentsWorkflow = commitmentsWorkflow,
            appLocaleRepository = FakeAppLocaleRepository(),
            themePreferencesRepository = FakeThemePreferencesRepository(themeMode),
            databaseBackupService = FakeDatabaseBackupGateway(),
            refreshReviewQueue = { onRefreshReviewQueue() },
            reconcileOwnershipChange = { onReconcileOwnership(it) },
            reparseStoredEvents = { reparseResult },
            permissionStateProvider = permissionStateProvider,
            appVersion = SettingsViewModelTestFixtures.APP_VERSION,
            appUpdateService = appUpdateService,
            updateCheckCoordinator = updateCheckCoordinator,
            appLogService = SettingsViewModelTestFixtures.appLogService(),
            apkInstaller = SettingsViewModelTestFixtures.apkInstaller(),
            canInstallPackages = { true },
        )

    private class FakeThemePreferencesRepository(
        private var mode: ThemeMode = ThemeMode.SYSTEM,
    ) : ThemePreferencesRepository {
        override fun getThemeMode(): ThemeMode = mode

        override fun setThemeMode(mode: ThemeMode) {
            this.mode = mode
        }
    }

    private class FakeDatabaseBackupGateway : DatabaseBackupGateway {
        override suspend fun exportTo(destination: android.net.Uri, passphrase: CharArray): Result<Unit> =
            Result.success(Unit)

        override suspend fun inspect(source: android.net.Uri): BackupPackageKind = BackupPackageKind.UNRECOGNIZED

        override suspend fun importFrom(
            source: android.net.Uri,
            passphrase: CharArray,
            confirmLegacyPlaintext: Boolean,
        ): BackupImportOutcome = BackupImportOutcome.Failed
    }

    private class FakeCardRegistry(
        vararg initial: CardRegistryEntry,
    ) : MutableCardRegistryRepository(*initial) {
        override suspend fun configure(reference: CardReference, configuration: com.baraa.masroof.domain.model.CardConfiguration) {
            val index = entries.indexOfFirst { it.bank == reference.bank && it.last4 == reference.last4 }
            require(index >= 0)
            entries[index] = entries[index].copy(cardNetwork = configuration.network, cardType = configuration.type,
                cardRole = configuration.role, parentCardLast4 = configuration.primaryLast4,
                linkedAccountBankId = configuration.linkedAccount?.bank?.id,
                linkedAccountMaskedNumber = configuration.linkedAccount?.maskedNumber)
        }

        override suspend fun updateDisplayName(reference: CardReference, displayName: String?) {
            val index = entries.indexOfFirst { it.bank == reference.bank && it.last4 == reference.last4 }
            if (index >= 0) entries[index] = entries[index].copy(displayName = displayName)
        }
    }

    private class FakeAccountRegistry(
        vararg initial: AccountRegistryEntry,
    ) : StubAccountRegistryRepository(*initial) {
        override suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus) {
            val masked = reference.maskedNumber ?: return
            val index = entries.indexOfFirst { it.bank == reference.bank && it.maskedNumber == masked }
            if (index >= 0) entries[index] = entries[index].copy(ownership = status)
            else entries += AccountRegistryEntry.forTest(reference.bank, masked, status,
                firstSeenRawSmsId = null, lastSeenRawSmsId = null)
        }

        override suspend fun updateDisplayName(reference: AccountReference, displayName: String?) {
            val index = entries.indexOfFirst { it.bank == reference.bank && it.maskedNumber == reference.maskedNumber }
            if (index >= 0) entries[index] = entries[index].copy(displayName = displayName)
        }

        override suspend fun delete(reference: AccountReference) {
            entries.removeAll { it.bank == reference.bank && it.maskedNumber == reference.maskedNumber }
        }
    }

    private class FakeLoanRegistry(
        vararg initial: LoanRegistryEntry,
    ) : LoanRegistryRepository {
        private val entries = initial.toList()

        override suspend fun observe(reference: LoanReference, rawSmsId: String) = Unit

        override suspend fun setOwnership(reference: LoanReference, status: OwnershipStatus) = Unit

        override suspend fun resolve(reference: LoanReference): OwnershipStatus = OwnershipStatus.UNKNOWN

        override suspend fun get(reference: LoanReference): LoanRegistryEntry? =
            entries.find { it.bank == reference.bank && it.loanType == reference.loanType }

        override suspend fun listAll(): List<LoanRegistryEntry> = entries

        override suspend fun updateDisplayName(reference: LoanReference, displayName: String?) = Unit
    }
}
