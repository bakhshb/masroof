package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing

@Composable
internal fun BetaStartupFailure(onRetry: () -> Unit, onReturn: () -> Unit, processing: Boolean = false) {
    Column(Modifier.padding(BetaSpacing.screen).testTag("beta-startup-failure"), verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        Text(stringResource(R.string.beta_startup_title))
        Text(stringResource(if(processing) R.string.beta_startup_processing_failed else R.string.beta_startup_failed))
        OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("beta-startup-retry")) { Text(stringResource(R.string.beta_retry)) }
        OutlinedButton(onClick = onReturn) { Text(stringResource(R.string.beta_return)) }
    }
}
