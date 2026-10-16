package com.baraa.masroof.presentation.beta

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.MasroofBadge
import com.baraa.masroof.presentation.common.MasroofButton
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofCardAccent
import com.baraa.masroof.presentation.common.MasroofOutlinedButton
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.MasroofText
import com.baraa.masroof.presentation.theme.MasroofSpacing

@Composable
fun BetaShell(
    evidenceCount: Int,
    restoreMissing: Boolean,
    onReturn: () -> Unit,
    onReset: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    BackHandler(onBack = onReturn)
    MasroofSecondaryScaffold(
        title = stringResource(R.string.beta_title),
        onBack = onReturn,
        backContentDescription = stringResource(R.string.beta_return),
        actions = { BetaBadge() },
    ) { contentModifier ->
        Column(
            modifier = contentModifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
        ) {
            BetaBadge()
            MasroofCard {
                Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                    MasroofText(
                        stringResource(R.string.beta_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    MasroofText(
                        stringResource(R.string.beta_evidence_count, evidenceCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (restoreMissing) {
                        MasroofText(
                            stringResource(R.string.beta_restore_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            MasroofButton(
                onClick = onReturn,
                modifier = Modifier.fillMaxWidth().testTag("beta-return"),
            ) {
                MasroofText(stringResource(R.string.beta_return))
            }
            MasroofOutlinedButton(
                onClick = onBackup,
                modifier = Modifier.fillMaxWidth().testTag("beta-backup"),
            ) {
                MasroofText(stringResource(R.string.beta_backup))
            }
            MasroofOutlinedButton(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth().testTag("beta-restore"),
            ) {
                MasroofText(stringResource(R.string.beta_restore))
            }
            MasroofOutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth().testTag("beta-reset"),
            ) {
                MasroofText(stringResource(R.string.beta_reset))
            }
        }
    }
}

@Composable
private fun BetaBadge() {
    MasroofBadge(
        text = stringResource(R.string.beta_badge),
        accent = MasroofCardAccent.Liability,
        modifier = Modifier.testTag("beta-badge"),
    )
}
