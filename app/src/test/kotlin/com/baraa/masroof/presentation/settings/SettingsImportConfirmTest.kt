package com.baraa.masroof.presentation.settings

import android.net.Uri
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.locale.AppLocale
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.theme.ThemePreferencesRepository
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.testsupport.SettingsViewModelTestSupport
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.testsupport.NoOpCardRegistryRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsImportConfirmTest {
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
    fun offerImport_asksForConfirm_withoutImporting() = runTest {
        val backup = RecordingBackupGateway()
        val vm = viewModel(backup)
        vm.onImportPassphraseChange("secret")
        vm.offerImport(Uri.parse("content://backup/copy.masroof"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.awaitingImportConfirm)
        assertEquals(0, backup.importCalls)
    }

    @Test
    fun cancelPendingImport_doesNotImport() = runTest {
        val backup = RecordingBackupGateway()
        val vm = viewModel(backup)
        vm.onImportPassphraseChange("secret")
        vm.offerImport(Uri.parse("content://backup/copy.masroof"))
        advanceUntilIdle()
        vm.cancelPendingImport()
        assertFalse(vm.uiState.value.awaitingImportConfirm)
        assertEquals(0, backup.importCalls)
    }

    @Test
    fun confirmPendingImport_runsImport() = runTest {
        val backup = RecordingBackupGateway()
        val vm = viewModel(backup)
        vm.onImportPassphraseChange("secret")
        vm.offerImport(Uri.parse("content://backup/copy.masroof"))
        advanceUntilIdle()
        vm.confirmPendingImport()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.awaitingImportConfirm)
        assertEquals("", vm.uiState.value.importPassphrase)
        assertEquals(1, backup.importCalls)
        assertEquals(BackupMessage.IMPORT_INVALID, vm.uiState.value.backupMessage)
    }

    @Test
    fun cancelLegacyImport_doesNotImport() = runTest {
        val backup = RecordingBackupGateway(kind = BackupPackageKind.LEGACY_PLAINTEXT)
        val vm = viewModel(backup)
        vm.offerImport(Uri.parse("content://backup/legacy.masroof"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.awaitingLegacyImportConfirm)
        assertEquals(0, backup.importCalls)
        vm.cancelPendingImport()
        assertFalse(vm.uiState.value.awaitingLegacyImportConfirm)
        assertEquals(0, backup.importCalls)
    }

    private fun viewModel(backup: DatabaseBackupGateway): SettingsViewModel =
        SettingsViewModel(
            settingsRegistryWorkflow = SettingsViewModelTestSupport.settingsRegistryWorkflow(
                cards = EmptyCards(),
                accounts = EmptyAccounts(),
                loans = NoOpLoanRegistryRepository,
            ),
            settingsCommitmentsWorkflow = SettingsCommitmentsWorkflow(
                commitmentRepository = com.baraa.masroof.testsupport.NoOpCommitmentRepository(),
            ),
            appLocaleRepository = object : AppLocaleRepository {
                override fun getLanguageTag() = AppLocale.DEFAULT_TAG
                override fun setLanguageTag(languageTag: String) = Unit
            },
            themePreferencesRepository = object : ThemePreferencesRepository {
                override fun getThemeMode() = ThemeMode.SYSTEM
                override fun setThemeMode(mode: ThemeMode) = Unit
            },
            databaseBackupService = backup,
            refreshReviewQueue = {},
            reparseStoredEvents = { 0 },
            importSmsFromInbox = { com.baraa.masroof.application.onboarding.HistoricalImportResult() },
            permissionStateProvider = { true },
            appVersion = SettingsViewModelTestFixtures.APP_VERSION,
            appUpdateService = SettingsViewModelTestFixtures.appUpdateService(),
            updateCheckCoordinator = SettingsViewModelTestFixtures.updateCheckCoordinator(),
            appLogService = SettingsViewModelTestFixtures.appLogService(),
            apkInstaller = SettingsViewModelTestFixtures.apkInstaller(),
            canInstallPackages = { true },
        )

    private class RecordingBackupGateway(
        private val kind: BackupPackageKind = BackupPackageKind.ENCRYPTED,
    ) : DatabaseBackupGateway {
        var importCalls: Int = 0

        override suspend fun exportTo(destination: Uri, passphrase: CharArray): Result<Unit> = Result.success(Unit)

        override suspend fun inspect(source: Uri): BackupPackageKind = kind

        override suspend fun importFrom(
            source: Uri,
            passphrase: CharArray,
            confirmLegacyPlaintext: Boolean,
        ): BackupImportOutcome {
            importCalls++
            return BackupImportOutcome.InvalidPackage
        }
    }

    private class EmptyCards : NoOpCardRegistryRepository()

    private class EmptyAccounts : AccountRegistryRepository {
        override suspend fun observe(reference: AccountReference, rawSmsId: String) = Unit
        override suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus) = Unit
        override suspend fun resolve(reference: AccountReference) = OwnershipStatus.UNKNOWN
        override suspend fun get(reference: AccountReference): AccountRegistryEntry? = null
        override suspend fun listAll(): List<AccountRegistryEntry> = emptyList()
        override suspend fun updateDisplayName(reference: AccountReference, displayName: String?) = Unit
            override suspend fun updateAccountType(reference: com.baraa.masroof.domain.model.AccountReference, accountType: com.baraa.masroof.domain.model.AccountType) = Unit
    }
}
