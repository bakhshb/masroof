package com.baraa.masroof

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.presentation.common.MasroofScreenBackground
import androidx.core.content.ContextCompat
import com.baraa.masroof.application.backup.BackupPackageFormat
import com.baraa.masroof.application.maintenance.StartupMaintenanceOutcome
import com.baraa.masroof.application.theme.ThemeMode
import com.baraa.masroof.application.update.InstallPermissionHelper
import com.baraa.masroof.presentation.navigation.MasroofRoot
import com.baraa.masroof.presentation.dashboard.DashboardViewModel
import com.baraa.masroof.presentation.dashboard.DashboardViewModelFactory
import com.baraa.masroof.presentation.onboarding.OnboardingPermissionPolicy
import com.baraa.masroof.presentation.onboarding.OnboardingViewModel
import com.baraa.masroof.presentation.onboarding.OnboardingViewModelFactory
import com.baraa.masroof.presentation.notification.NotificationCenterViewModel
import com.baraa.masroof.presentation.notification.NotificationCenterViewModelFactory
import com.baraa.masroof.presentation.review.ReviewViewModel
import com.baraa.masroof.presentation.review.ReviewViewModelFactory
import com.baraa.masroof.presentation.settings.SettingsViewModel
import com.baraa.masroof.presentation.settings.SettingsViewModelFactory
import com.baraa.masroof.presentation.theme.MasroofSpacing
import com.baraa.masroof.presentation.theme.MasroofTheme
import com.baraa.masroof.presentation.locale.AppLocaleContext
import kotlinx.coroutines.launch

/**
 * Launcher: P10 onboarding until complete, then P11 monthly dashboard.
 */
class MainActivity : ComponentActivity() {
    private val container by lazy { (application as MasroofApplication).container }
    private var startupFinancialReady: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            AppLocaleContext.wrap(newBase, AppLocaleContext.readStoredLanguageTag(newBase)),
        )
    }

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        OnboardingViewModelFactory(
            container = container,
            onboardingPreferencesRepository = container.onboardingPreferencesRepository,
            permissionStateProvider = { hasSmsPermissions() },
        )
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModelFactory(
            container = container,
            permissionStateProvider = { hasSmsPermissions() },
        )
    }

    private val reviewViewModel: ReviewViewModel by viewModels {
        ReviewViewModelFactory(container = container)
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(
            container = container,
            appVersion = BuildConfig.VERSION_NAME,
            permissionStateProvider = { hasSmsPermissions() },
            onRequestInstallPermission = {
                startActivity(InstallPermissionHelper.buildManageUnknownSourcesIntent(this))
            },
        )
    }

    private val notificationCenterViewModel: NotificationCenterViewModel by viewModels {
        NotificationCenterViewModelFactory(
            container = container,
            permissionStateProvider = { hasSmsPermissions() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var startupOutcome by remember { mutableStateOf<StartupMaintenanceOutcome?>(null) }
            var startupRetrying by remember { mutableStateOf(false) }
            val startupScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                startupOutcome = container.awaitStartupMaintenance()
                startupFinancialReady = startupOutcome == StartupMaintenanceOutcome.READY
            }
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
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(MasroofSpacing.screenPaddingLarge),
                                verticalArrangement = Arrangement.spacedBy(
                                    MasroofSpacing.sectionGap,
                                    Alignment.CenterVertically,
                                ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.startup_maintenance_blocked),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Button(
                                    onClick = {
                                        startupRetrying = true
                                        startupScope.launch {
                                            startupOutcome = container.retryStartupMaintenance()
                                            startupFinancialReady =
                                                startupOutcome == StartupMaintenanceOutcome.READY
                                            startupRetrying = false
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.dashboard_retry))
                                }
                            }
                        }
                    }
                }
                return@setContent
            }
            val settingsState by settingsViewModel.uiState.collectAsState()
            val themeMode = settingsState.themeMode
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
                        if (container.onboardingPreferencesRepository.isOnboardingCompleted()) {
                            if (granted) {
                                dashboardViewModel.rescanSms()
                            } else {
                                dashboardViewModel.refresh()
                            }
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
                            val name = container.appLogService.exportFileName()
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
        if (startupFinancialReady &&
            container.onboardingPreferencesRepository.isOnboardingCompleted()
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
