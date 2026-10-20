package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.BetaStartState
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing

@Composable
internal fun BetaStartPanel(state: BetaStartState, busy: Boolean, failed: Boolean,
    onContinue: () -> Unit, onFresh: () -> Unit) {
    val existing = state == BetaStartState.EXISTING
    var confirmFresh by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.testTag(if (existing) "beta-start-existing" else "beta-start-new"),
        verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        Text(stringResource(if (existing) R.string.beta_start_existing_title else R.string.beta_start_new_title))
        Text(stringResource(if (existing) R.string.beta_start_existing_body else R.string.beta_start_new_body))
        if (failed) Text(stringResource(R.string.beta_action_failed))
        Button(onClick = onContinue, enabled = !busy, modifier = Modifier.testTag("beta-start-continue")) {
            Text(stringResource(if (existing) R.string.beta_start_resume else R.string.beta_start_begin))
        }
        if (existing) OutlinedButton(onClick = { confirmFresh = true }, enabled = !busy,
            modifier = Modifier.testTag("beta-start-fresh")) { Text(stringResource(R.string.beta_start_fresh)) }
    }
    if (confirmFresh) AlertDialog(
        onDismissRequest = { if (!busy) confirmFresh = false },
        title = { Text(stringResource(R.string.beta_start_fresh)) },
        text = { Text(stringResource(R.string.beta_start_fresh_warning)) },
        confirmButton = { Button(enabled = !busy, modifier = Modifier.testTag("beta-start-fresh-confirm"),
            onClick = { confirmFresh = false; onFresh() }) { Text(stringResource(R.string.beta_start_fresh_confirm)) } },
        dismissButton = { OutlinedButton(enabled = !busy, modifier = Modifier.testTag("beta-start-fresh-cancel"),
            onClick = { confirmFresh = false }) { Text(stringResource(R.string.beta_asset_cancel)) } },
    )
}
