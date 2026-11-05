package com.baraa.masroof.presentation.settings

import android.net.Uri
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.testsupport.FakeDatabaseBackupGateway
import com.baraa.masroof.testsupport.FakeThemePreferencesRepository
import com.baraa.masroof.testsupport.SettingsViewModelTestSupport
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
        val backup = FakeDatabaseBackupGateway(kind = BackupPackageKind.ENCRYPTED)
        val vm = viewModel(backup)
        vm.onImportPassphraseChange("secret")
        vm.offerImport(Uri.parse("content://backup/copy.masroof"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.awaitingImportConfirm)
        assertEquals(0, backup.importCalls)
    }

    @Test
    fun cancelPendingImport_doesNotImport() = runTest {
        val backup = FakeDatabaseBackupGateway(kind = BackupPackageKind.ENCRYPTED)
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
        val backup = FakeDatabaseBackupGateway(kind = BackupPackageKind.ENCRYPTED)
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
        val backup = FakeDatabaseBackupGateway(kind = BackupPackageKind.LEGACY_PLAINTEXT)
        val vm = viewModel(backup)
        vm.offerImport(Uri.parse("content://backup/legacy.masroof"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.awaitingLegacyImportConfirm)
        assertEquals(0, backup.importCalls)
        vm.cancelPendingImport()
        assertFalse(vm.uiState.value.awaitingLegacyImportConfirm)
        assertEquals(0, backup.importCalls)
    }

    @Test
    fun export_afterProcessRecreation_failsClosedWithExplicitRetryMessage() = runTest {
        val backup = FakeDatabaseBackupGateway(kind = BackupPackageKind.ENCRYPTED)
        val old = viewModel(backup)
        old.onExportPassphraseChange("secret")
        assertTrue(old.prepareExport())
        assertEquals("", old.uiState.value.exportPassphrase)
        val recreated = viewModel(backup)
        recreated.exportBackup(Uri.parse("content://backup/result.masroof"))
        advanceUntilIdle()
        assertEquals(0, backup.exportCalls)
        assertEquals(BackupMessage.EXPORT_REENTER_PASSPHRASE, recreated.uiState.value.backupMessage)
        assertFalse(recreated.uiState.value.exportingBackup)
    }

    @Test
    fun export_retainedViewModel_consumesPhraseOnceAndWipesIt() = runTest {
        val backup = FakeDatabaseBackupGateway(kind = BackupPackageKind.ENCRYPTED)
        val vm = viewModel(backup)
        vm.onExportPassphraseChange("secret")
        assertTrue(vm.prepareExport())
        vm.exportBackup(Uri.parse("content://backup/result.masroof"))
        advanceUntilIdle()
        assertEquals(1, backup.exportCalls)
        assertEquals(BackupMessage.EXPORT_SUCCESS, vm.uiState.value.backupMessage)
        assertTrue(backup.exportPhrase!!.all { it == '\u0000' })
        vm.exportBackup(Uri.parse("content://backup/result.masroof"))
        assertEquals(1, backup.exportCalls)
        assertEquals(BackupMessage.EXPORT_REENTER_PASSPHRASE, vm.uiState.value.backupMessage)
    }

    @Test
    fun export_cancelledPicker_discardsSecretAndRequiresNewPreparation() = runTest {
        val backup = FakeDatabaseBackupGateway(kind = BackupPackageKind.ENCRYPTED)
        val vm = viewModel(backup)
        vm.onExportPassphraseChange("secret")
        assertTrue(vm.prepareExport())
        vm.abandonPreparedExport()
        vm.exportBackup(Uri.parse("content://backup/result.masroof"))
        advanceUntilIdle()
        assertEquals(0, backup.exportCalls)
        assertEquals(BackupMessage.EXPORT_REENTER_PASSPHRASE, vm.uiState.value.backupMessage)
    }

    private fun viewModel(backup: DatabaseBackupGateway): SettingsViewModel =
        SettingsViewModel(
            settingsRegistryWorkflow = SettingsViewModelTestSupport.settingsRegistryWorkflow(),
            settingsCommitmentsWorkflow = SettingsCommitmentsWorkflow(
                commitmentRepository = com.baraa.masroof.testsupport.NoOpCommitmentRepository(),
            ),
            appLocaleRepository = com.baraa.masroof.testsupport.FakeAppLocaleRepository(),
            themePreferencesRepository = FakeThemePreferencesRepository(),
            databaseBackupService = backup,
            reparseStoredEvents = { com.baraa.masroof.application.maintenance.ReparseAllStoredEventsResult(0, 0) },
            permissionStateProvider = { true },
            appVersion = SettingsViewModelTestFixtures.APP_VERSION,
            appUpdateService = SettingsViewModelTestFixtures.appUpdateService(),
            updateCheckCoordinator = SettingsViewModelTestFixtures.updateCheckCoordinator(),
            appLogService = SettingsViewModelTestFixtures.appLogService(),
            apkInstaller = SettingsViewModelTestFixtures.apkInstaller(),
            canInstallPackages = { true },
        )
}
