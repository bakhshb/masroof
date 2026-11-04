
package com.baraa.masroof.presentation.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofSizes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.baraa.masroof.presentation.common.MasroofButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofOutlinedButton as OutlinedButton
import com.baraa.masroof.presentation.common.MasroofFormField as OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.BuildConfig
import com.baraa.masroof.R
import com.baraa.masroof.application.update.UpdateChannel
import com.baraa.masroof.presentation.common.MasroofBadge
import com.baraa.masroof.presentation.common.MasroofCardAccent
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.IconLabelRow
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofLogo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutScreen(
    appVersion: String,
    isNightlyBuild: Boolean,
    updateChannel: UpdateChannel,
    githubTokenConfigured: Boolean,
    updateState: AppUpdateUiState,
    updateMessage: AppUpdateMessage?,
    logErrorCount: Int = 0,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenUpdates: () -> Unit = {},
    onOpenDesignCatalog: () -> Unit = {},
    onSaveGithubToken: (String) -> Unit,
    onClearGithubToken: () -> Unit,
    onSelectUpdateChannel: (UpdateChannel) -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onClearUpdateMessage: () -> Unit,
    bankCatalog: com.baraa.masroof.application.patterns.PatternCatalog? = null,
    onOpenBanks: () -> Unit = {},
    onShowGuideAgain: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val updateMessageText = updateMessage?.let { resolveUpdateMessage(it) }

    LaunchedEffect(updateMessageText) {
        if (updateMessageText != null) {
            snackbarHostState.showSnackbar(com.baraa.masroof.application.locale.AppDigits.display(updateMessageText))
            onClearUpdateMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MasroofSecondaryScaffold(
            title = stringResource(R.string.settings_about_section),
            onBack = onBack,
            backContentDescription = stringResource(R.string.settings_back),
        ) { contentModifier ->
            Column(
                modifier = contentModifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
            ) {
            MasroofCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = MasroofSpacing.periodDisplayHorizontalPadding + MasroofSpacing.borderWidth,
                vertical = com.baraa.masroof.ui.theme.MasroofReference.headerTop + MasroofSpacing.borderWidth)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                    MasroofLogo(size = com.baraa.masroof.ui.theme.MasroofReference.aboutLogo, contentDescription = null)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_about_version, appVersion), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isNightlyBuild) MasroofBadge(stringResource(R.string.badge_nightly_release), MasroofCardAccent.Liability)
                    Text(stringResource(R.string.settings_about_tagline), style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AboutInfoCard(
                icon = MasroofIcons.periodHint,
                title = stringResource(R.string.settings_about_privacy_title),
                body = stringResource(R.string.settings_about_privacy_body),
                contextualExplanation = true,
            )
            MasroofCard {
                com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.settings_bank_support_title),
                    stringResource(R.string.settings_bank_support_hint), style = MaterialTheme.typography.titleMedium)
                bankCatalog?.banks.orEmpty().forEach { bank ->
                    Text(bank.displayName ?: settingsBankLabel(com.baraa.masroof.domain.model.Bank(bank.bankId)), style = MaterialTheme.typography.titleSmall)
                    Text(bankSupportLabel(bank).first, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onOpenBanks, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_bank_manage)) }
            }

            if (BuildConfig.ENABLE_DESIGN_CATALOG) {
                SettingsNavRow(
                    icon = MasroofIcons.periodHint,
                    title = stringResource(R.string.settings_design_catalog_title),
                    subtitle = stringResource(R.string.settings_design_catalog_subtitle),
                    onClick = onOpenDesignCatalog,
                )
            }


            MasroofCard {
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_updates_title),
                    stringResource(R.string.settings_updates_hint), MasroofIcons.importBackup, onOpenUpdates)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_logs_title),
                    stringResource(R.string.settings_logs_subtitle), MasroofIcons.logs, onOpenLogs)
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_show_guide_title),
                    stringResource(R.string.settings_show_guide_subtitle), MasroofIcons.periodHint, onShowGuideAgain)
                val context = androidx.compose.ui.platform.LocalContext.current
                com.baraa.masroof.presentation.common.MasroofReferenceNavigationRow(stringResource(R.string.settings_report_problem_title),
                    stringResource(R.string.settings_report_problem_subtitle), MasroofIcons.guidance, divider = false, onClick = {
                        val body = "Version: $appVersion\nAndroid: ${android.os.Build.VERSION.SDK_INT}\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n"
                        val url = "https://github.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/issues/new?body=${android.net.Uri.encode(body)}"
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    })
            }

            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
internal fun UpdateSectionCard(
    updateChannel: UpdateChannel,
    onSelectUpdateChannel: (UpdateChannel) -> Unit,
    githubTokenConfigured: Boolean,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    onSaveGithubToken: () -> Unit,
    onClearGithubToken: () -> Unit,
    updateState: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    message: AppUpdateMessage? = null,
) {
    val failed = message in failedUpdateMessages
    var accessOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    MasroofCard {
        com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.settings_update_channel_title),
            stringResource(R.string.settings_update_channel_hint), style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.smallGap)) {
            com.baraa.masroof.presentation.common.MasroofChoice(stringResource(R.string.settings_update_channel_stable),
                updateChannel == UpdateChannel.STABLE, { onSelectUpdateChannel(UpdateChannel.STABLE) })
            com.baraa.masroof.presentation.common.MasroofChoice(stringResource(R.string.settings_update_channel_nightly),
                updateChannel == UpdateChannel.NIGHTLY, { onSelectUpdateChannel(UpdateChannel.NIGHTLY) })
        }
    }
    MasroofCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(
        horizontal = MasroofSpacing.periodDisplayHorizontalPadding + MasroofSpacing.borderWidth,
        vertical = com.baraa.masroof.ui.theme.MasroofReference.headerTop + MasroofSpacing.borderWidth)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            androidx.compose.material3.Icon(if (failed) MasroofIcons.error else when (updateState) {
                AppUpdateUiState.UpToDate -> MasroofIcons.success
                AppUpdateUiState.Checking -> MasroofIcons.retry
                else -> MasroofIcons.importBackup
            }, null, Modifier.size(com.baraa.masroof.ui.theme.MasroofReference.iconBox), tint = MaterialTheme.colorScheme.primary)
            when (updateState) {
                AppUpdateUiState.Idle -> Text(if (failed && message != null) resolveUpdateMessage(message)
                    else stringResource(R.string.reference_update_idle), style = MaterialTheme.typography.titleMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                AppUpdateUiState.Checking -> {
                    Text(stringResource(R.string.reference_update_checking), style = MaterialTheme.typography.titleMedium)
                    CircularProgressIndicator()
                }
                AppUpdateUiState.UpToDate -> Text(stringResource(R.string.settings_updates_up_to_date), style = MaterialTheme.typography.titleMedium)
                is AppUpdateUiState.Available -> {
                    Text(stringResource(R.string.settings_updates_available, updateState.manifest.versionName), style = MaterialTheme.typography.titleMedium)
                    updateState.manifest.releaseNotes?.let { notes -> Text(notes, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Button(onDownloadUpdate, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_download_update)) }
                }
                is AppUpdateUiState.Downloading -> {
                    val progress = if (updateState.totalBytes > 0) updateState.bytesRead.toFloat() / updateState.totalBytes.toFloat() else 0f
                    Text(stringResource(R.string.reference_update_downloading, (progress.coerceIn(0f, 1f) * 100).toInt()), style = MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                is AppUpdateUiState.ReadyToInstall -> {
                    Text(stringResource(R.string.settings_updates_ready, updateState.manifest.versionName), style = MaterialTheme.typography.titleMedium)
                    Button(onInstallUpdate, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_install_update)) }
                }
            }
            if (updateState !is AppUpdateUiState.Downloading && updateState !is AppUpdateUiState.Checking) {
                if (updateState is AppUpdateUiState.Available || updateState is AppUpdateUiState.ReadyToInstall) {
                    OutlinedButton(onCheckForUpdates, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_check_updates)) }
                } else Button(onCheckForUpdates, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_check_updates)) }
            }
        }
    }
    MasroofCard {
        com.baraa.masroof.presentation.common.MasroofGuidanceLabel(stringResource(R.string.reference_update_access),
            stringResource(R.string.settings_updates_hint), style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ accessOpen = !accessOpen }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.reference_configure_access)) }
        if (accessOpen) {
            OutlinedTextField(tokenInput, onTokenInputChange, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_github_token_label)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            RowActions(stringResource(R.string.settings_github_token_save), onSaveGithubToken, tokenInput.isNotBlank(),
                if (githubTokenConfigured) stringResource(R.string.settings_github_token_clear) else null,
                if (githubTokenConfigured) onClearGithubToken else null)
        }
    }
}

@Composable
private fun RowActions(
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean,
    secondaryLabel: String?,
    onSecondary: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(primaryLabel)
        }
        if (secondaryLabel != null && onSecondary != null) {
            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(secondaryLabel)
            }
        }
    }
}

private val failedUpdateMessages = setOf(AppUpdateMessage.AUTH_FAILED, AppUpdateMessage.CHECK_FAILED,
    AppUpdateMessage.DOWNLOAD_FAILED, AppUpdateMessage.INSTALL_FAILED, AppUpdateMessage.TOKEN_REQUIRED)

/** The message the update card does not already show as its status; null when the card shows it. */
internal fun updateMessageOutsideCard(state: AppUpdateUiState, message: AppUpdateMessage?): AppUpdateMessage? =
    message?.takeUnless {
        it == AppUpdateMessage.UP_TO_DATE || it == AppUpdateMessage.UPDATE_AVAILABLE ||
            (state == AppUpdateUiState.Idle && it in failedUpdateMessages)
    }

@Composable
internal fun resolveUpdateMessage(message: AppUpdateMessage): String =
    when (message) {
        AppUpdateMessage.UP_TO_DATE -> stringResource(R.string.settings_updates_up_to_date)
        AppUpdateMessage.UPDATE_AVAILABLE -> stringResource(R.string.settings_updates_available_snackbar)
        AppUpdateMessage.DOWNLOAD_SUCCESS -> stringResource(R.string.settings_download_success)
        AppUpdateMessage.TOKEN_SAVED -> stringResource(R.string.settings_github_token_saved)
        AppUpdateMessage.TOKEN_SAVE_FAILED -> stringResource(R.string.settings_github_token_save_failed)
        AppUpdateMessage.TOKEN_REQUIRED -> stringResource(R.string.settings_github_token_required)
        AppUpdateMessage.AUTH_FAILED -> stringResource(R.string.settings_github_auth_failed)
        AppUpdateMessage.CHECK_FAILED -> stringResource(R.string.settings_updates_check_failed)
        AppUpdateMessage.DOWNLOAD_FAILED -> stringResource(R.string.settings_download_failed)
        AppUpdateMessage.INSTALL_FAILED -> stringResource(R.string.settings_install_failed)
        AppUpdateMessage.INSTALL_PERMISSION_REQUIRED ->
            stringResource(R.string.settings_install_permission_required)
    }

@Composable
private fun AboutInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    contextualExplanation: Boolean = false,
) {
    MasroofCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
            IconLabelRow(icon = icon, label = title, iconTint = MaterialTheme.colorScheme.primary,
                guidance = body.takeIf { contextualExplanation })
            if (!contextualExplanation) Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
