package com.baraa.masroof.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.update.UpdateChannel
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold

@Composable
fun SettingsUpdatesScreen(updateChannel: UpdateChannel, githubTokenConfigured: Boolean,
    updateState: AppUpdateUiState, updateMessage: AppUpdateMessage?, onBack: () -> Unit,
    onSelectUpdateChannel: (UpdateChannel) -> Unit, onSaveGithubToken: (String) -> Unit,
    onClearGithubToken: () -> Unit, onCheckForUpdates: () -> Unit, onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit, onClearUpdateMessage: () -> Unit, appVersion: String = com.baraa.masroof.BuildConfig.VERSION_NAME) {
    var token by com.baraa.masroof.presentation.common.rememberSensitiveInput()
    MasroofSecondaryScaffold(stringResource(R.string.settings_updates_title), onBack,
        stringResource(R.string.settings_back)) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.sectionGap)) {
            com.baraa.masroof.presentation.common.MasroofCard {
                com.baraa.masroof.presentation.common.MasroofText(stringResource(R.string.settings_current_version, appVersion),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            }
            UpdateSectionCard(updateChannel, onSelectUpdateChannel, githubTokenConfigured, token,
                { token = it }, { onSaveGithubToken(token); token = "" },
                { token = ""; onClearGithubToken() }, updateState, { onClearUpdateMessage(); onCheckForUpdates() }, onDownloadUpdate, onInstallUpdate, message = updateMessage)
            updateMessageOutsideCard(updateState, updateMessage)?.let {
                com.baraa.masroof.presentation.common.MasroofText(resolveUpdateMessage(it))
            }
        }
    }
}
