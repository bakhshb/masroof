package com.baraa.masroof.presentation.startup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.MasroofButton
import com.baraa.masroof.presentation.common.MasroofOutlinedButton
import com.baraa.masroof.ui.theme.MasroofSpacing

/**
 * Shown while startup maintenance has not certified the data. It shows no financial content; restoring a
 * backup stays reachable so a blocked start cannot trap the user. [restorePage] gets the action that returns here.
 */
@Composable
fun StartupBlockedScreen(onRetry: () -> Unit, restorePage: @Composable (onBack: () -> Unit) -> Unit) {
    var restoreOpen by rememberSaveable { mutableStateOf(false) }
    if (restoreOpen) {
        BackHandler { restoreOpen = false }
        restorePage { restoreOpen = false }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MasroofSpacing.screenPaddingLarge),
        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.startup_maintenance_blocked),
            style = MaterialTheme.typography.bodyLarge,
        )
        MasroofButton(onRetry, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dashboard_retry))
        }
        MasroofOutlinedButton({ restoreOpen = true }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reference_restore_title))
        }
    }
}
