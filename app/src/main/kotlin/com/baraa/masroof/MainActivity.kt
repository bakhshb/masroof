package com.baraa.masroof

import com.baraa.masroof.presentation.navigation.AnalyticsNavigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.baraa.masroof.presentation.startup.StartupBlockedScreen
import com.baraa.masroof.presentation.startup.rememberStartupMaintenanceOutcome
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.baraa.masroof.presentation.common.MasroofScreenBackground
import androidx.core.content.ContextCompat
import com.baraa.masroof.application.backup.BackupPackageFormat
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.update.InstallPermissionHelper
import com.baraa.masroof.presentation.navigation.MasroofRoot
import com.baraa.masroof.presentation.dashboard.DashboardViewModel
import com.baraa.masroof.presentation.dashboard.dashboardViewModelFactory
import com.baraa.masroof.presentation.onboarding.OnboardingPermissionPolicy
import com.baraa.masroof.presentation.onboarding.OnboardingViewModel
import com.baraa.masroof.presentation.onboarding.onboardingViewModelFactory
import com.baraa.masroof.presentation.notification.NotificationCenterViewModel
import com.baraa.masroof.presentation.notification.notificationCenterViewModelFactory
import com.baraa.masroof.presentation.review.ReviewViewModel
import com.baraa.masroof.presentation.review.reviewViewModelFactory
import com.baraa.masroof.presentation.settings.BackupPage
import com.baraa.masroof.presentation.settings.SettingsDataBackupScreen
import com.baraa.masroof.presentation.settings.SettingsViewModel
import com.baraa.masroof.presentation.settings.settingsViewModelFactory
import com.baraa.masroof.presentation.statement.StatementReconciliationViewModel
import com.baraa.masroof.presentation.statement.statementReconciliationViewModelFactory
import com.baraa.masroof.ui.theme.MasroofTheme
import com.baraa.masroof.presentation.locale.AppLocaleContext
import kotlinx.coroutines.launch

/**
 * Launcher: onboarding until complete, then the monthly dashboard.
 */
class MainActivity : ComponentActivity() {
    private val container by lazy { (application as MasroofApplication).container }
    private var startupFinancialReady: Boolean = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("lab_analysis_alert")?.let { AnalyticsNavigation.open(it) }
    }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            AppLocaleContext.wrap(newBase, AppLocaleContext.readStoredLanguageTag(newBase)),
        )
    }

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        onboardingViewModelFactory(
            storage = container.storage,
            backup = container.backup,
            review = container.review,
            sms = container.sms,
            patterns = container.patterns,
            onboardingPreferencesRepository = container.storage.onboardingPreferencesRepository,
            permissionStateProvider = { hasSmsPermissions() },
        )
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        dashboardViewModelFactory(
            storage = container.storage,
            maintenance = container.maintenance,
            dashboard = container.dashboard,
            patterns = container.patterns,
            review = container.review,
            analytics = { container.analytics },
            permissionStateProvider = { hasSmsPermissions() },
        )
    }

    private val reviewViewModel: ReviewViewModel by viewModels {
        reviewViewModelFactory(
            storage = container.storage,
            maintenance = container.maintenance,
            review = container.review,
            sms = container.sms,
            patterns = container.patterns,
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        settingsViewModelFactory(
            storage = container.storage,
            backup = container.backup,
            dashboard = container.dashboard,
            patterns = container.patterns,
            review = container.review,
            sms = container.sms,
            update = container.update,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            permissionStateProvider = { hasSmsPermissions() },
            onRequestInstallPermission = {
                startActivity(InstallPermissionHelper.buildManageUnknownSourcesIntent(this))
            },
        )
    }

    private val statementReconciliationViewModel: StatementReconciliationViewModel by viewModels {
        statementReconciliationViewModelFactory(container.sms.statementReconciliationWorkflow)
    }

    private val notificationCenterViewModel: NotificationCenterViewModel by viewModels {
        notificationCenterViewModelFactory(
            storage = container.storage,
            dashboard = container.dashboard,
            permissionStateProvider = { hasSmsPermissions() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, language) gets the same launch intent again; the alert was already opened.
        if (savedInstanceState == null) intent?.getStringExtra("lab_analysis_alert")?.let { AnalyticsNavigation.open(it) }
        if (intent.getBooleanExtra(com.baraa.masroof.application.backup.DatabaseBackupService.EXTRA_RESTORE_FAILED, false)) {
            intent.removeExtra(com.baraa.masroof.application.backup.DatabaseBackupService.EXTRA_RESTORE_FAILED)
            android.widget.Toast.makeText(this, R.string.settings_import_failed, android.widget.Toast.LENGTH_LONG).show()
        }
        setContent {
            var startupOutcome by rememberStartupMaintenanceOutcome(container.maintenance::awaitStartupMaintenance) { outcome ->
                startupFinancialReady = outcome == StartupMaintenanceOutcome.READY
                if (startupFinancialReady) container.sms.scheduleMissedSmsCatchUp()
            }
            var startupRetrying by remember { mutableStateOf(false) }
            val startupScope = rememberCoroutineScope()
            if (startupOutcome != StartupMaintenanceOutcome.READY) {
                MasroofTheme(darkTheme = isSystemInDarkTheme()) {
                    MasroofScreenBackground(modifier = Modifier.fillMaxSize()) {
                        if (startupOutcome == null || startupRetrying) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            StartupBlockedScreen(
                                onRetry = {
                                    startupRetrying = true
                                    startupScope.launch {
                                        startupOutcome = container.maintenance.retryStartupMaintenance()
                                        startupFinancialReady =
                                            startupOutcome == StartupMaintenanceOutcome.READY
                                        if (startupFinancialReady) container.sms.scheduleMissedSmsCatchUp()
                                        startupRetrying = false
                                    }
                                },
                            ) { onBack ->
                                val blockedSettingsState by settingsViewModel.uiState.collectAsState()
                                val blockedImportLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.OpenDocument(),
                                ) { uri: Uri? -> if (uri != null) settingsViewModel.offerImport(uri) }
                                SettingsDataBackupScreen(
                                    state = blockedSettingsState,
                                    onBack = onBack,
                                    onReparseStored = {},
                                    onExportPassphraseChange = {},
                                    onImportPassphraseChange = settingsViewModel::onImportPassphraseChange,
                                    onRequestExport = {},
                                    onRequestImport = { blockedImportLauncher.launch(arrayOf("*/*")) },
                                    onConfirmPendingImport = settingsViewModel::confirmPendingImport,
                                    onCancelPendingImport = settingsViewModel::cancelPendingImport,
                                    onClearBackupMessage = settingsViewModel::clearBackupMessage,
                                    onRequestFxRateCorrection = {},
                                    onConfirmFxRateCorrection = {},
                                    onDismissFxRateCorrection = {},
                                    page = BackupPage.Restore,
                                    onVerifyPendingImport = settingsViewModel::verifyPendingImport,
                                )
                            }
                        }
                    }
                }
                return@setContent
            }
            // Only the theme is needed here; collecting all of Settings redrew the app on every Settings change.
            val themeMode by settingsViewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            MasroofTheme(darkTheme = darkTheme) {
                MasroofScreenBackground(modifier = Modifier.fillMaxSize()) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions(),
                    ) { _ ->
                        val granted = hasSmsPermissions()
                        onboardingViewModel.onPermissionResult(granted)
                        settingsViewModel.refreshSmsPermissionState()
                        if (container.storage.onboardingPreferencesRepository.isOnboardingCompleted()) {
                            dashboardViewModel.refresh()
                        }
                    }

                    val exportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument(BackupPackageFormat.MIME_TYPE),
                    ) { uri: Uri? ->
                        if (uri != null) {
                            settingsViewModel.exportBackup(uri)
                        } else {
                            settingsViewModel.abandonPreparedExport()
                        }
                    }

                    val importLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                    ) { uri: Uri? ->
                        if (uri != null) {
                            settingsViewModel.offerImport(uri)
                        }
                    }

                    val onboardingImportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocument(),
                    ) { uri: Uri? ->
                        if (uri != null) {
                            onboardingViewModel.restoreBackup(uri)
                        }
                    }

                    val openSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            ),
                        )
                    }

                    val logExportLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.CreateDocument("text/plain"),
                    ) { uri: Uri? ->
                        if (uri != null) {
                            settingsViewModel.exportLogs(uri)
                        }
                    }

                    MasroofRoot(
                        onboardingViewModel = onboardingViewModel,
                        dashboardViewModel = dashboardViewModel,
                        reviewViewModel = reviewViewModel,
                        settingsViewModel = settingsViewModel,
                        statementReconciliationViewModel = statementReconciliationViewModel,
                        notificationCenterViewModel = notificationCenterViewModel,
                        onRequestPermissions = {
                            permissionLauncher.launch(OnboardingPermissionPolicy.REQUIRED_SMS_PERMISSIONS)
                        },
                        onOpenAppSettings = openSettings,
                        onLocaleChanged = {
                            dashboardViewModel.refresh()
                            reviewViewModel.refresh()
                            settingsViewModel.refresh()
                            recreate()
                        },
                        onRequestExport = {
                            if (settingsViewModel.prepareExport()) {
                                val name = BackupPackageFormat.defaultExportFileName(System.currentTimeMillis())
                                exportLauncher.launch(name)
                            }
                        },
                        onRequestImport = {
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        onRequestExportLogs = {
                            val name = container.storage.appLogService.exportFileName()
                            logExportLauncher.launch(name)
                        },
                        onRequestRestoreBackup = {
                            onboardingImportLauncher.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
        }
    }


    override fun onResume() {
        super.onResume()
        onboardingViewModel.reloadFromCurrentState()
        settingsViewModel.retryInstallAfterPermissionGranted()
        settingsViewModel.refreshSmsPermissionState()
        if (startupFinancialReady &&
            container.storage.onboardingPreferencesRepository.isOnboardingCompleted()
        ) {
            dashboardViewModel.onAppResumed()
            reviewViewModel.refresh()
            settingsViewModel.checkForUpdatesIfStale(silent = true)
        }
    }

    private fun hasSmsPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
}
