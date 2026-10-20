package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.ui.components.MasroofCard
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable
internal fun BetaOnboardingPanel(step: BetaOnboardingStep, banks: List<BetaBankChoice>, busy: Boolean, failed: Boolean, hasMessages: Boolean, ownedAssetCount: Int, pendingReviewCount: Int,
    onChooseBank: (BankId) -> Unit, onNext: () -> Unit, onHistory: () -> Unit, onDiscovery: () -> Unit,
    onManage: () -> Unit, onApproval: () -> Unit) {
    if(step==BetaOnboardingStep.PERMISSION) { BetaPermissionPanel(busy,onNext); return }
    if(step==BetaOnboardingStep.REVIEW) {
        Column(Modifier.testTag("onboarding-REVIEW"),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally) {
            com.baraa.masroof.ui.components.MasroofLogo(size=com.baraa.masroof.ui.theme.MasroofSizes.size64)
            Text(stringResource(R.string.beta_parity_done_title),style=MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.beta_parity_done_counts,ownedAssetCount,pendingReviewCount))
            Text(stringResource(R.string.beta_parity_done_help))
            SetupAction(true,!busy,onNext,"onboarding-next",R.string.beta_onboarding_finish)
            if(failed) Text(stringResource(R.string.beta_action_failed),color=MaterialTheme.colorScheme.error)
        }
        return
    }
    Column(Modifier.testTag("onboarding-${step.name}"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        Text(stringResource(R.string.beta_onboarding_progress, when(step) {BetaOnboardingStep.BANK -> 1;BetaOnboardingStep.PERMISSION -> 2;BetaOnboardingStep.IMPORT -> 3;BetaOnboardingStep.ASSETS -> 5;else -> 6}, 6), style = MaterialTheme.typography.labelLarge)
        val title = when (step) {
            BetaOnboardingStep.BANK -> R.string.beta_onboarding_bank
            BetaOnboardingStep.IMPORT -> R.string.beta_onboarding_import
            BetaOnboardingStep.ASSETS -> R.string.beta_onboarding_assets
            else -> R.string.beta_onboarding_review
        }
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        MasroofCard {
            Text(stringResource(when (step) {
                BetaOnboardingStep.BANK -> R.string.beta_onboarding_bank_help
                BetaOnboardingStep.IMPORT -> R.string.beta_onboarding_import_help
                BetaOnboardingStep.ASSETS -> R.string.beta_onboarding_assets_help
                else -> R.string.beta_onboarding_review_help
            }))
        }
        val nextIsPrimary = when (step) {
            BetaOnboardingStep.IMPORT -> hasMessages
            BetaOnboardingStep.ASSETS -> ownedAssetCount > 0
            BetaOnboardingStep.REVIEW -> pendingReviewCount == 0
            else -> false
        }
        if (step == BetaOnboardingStep.IMPORT && hasMessages)
            Text(stringResource(R.string.beta_setup_imported), modifier = Modifier.testTag("onboarding-imported"))
        if (step == BetaOnboardingStep.ASSETS && ownedAssetCount > 0)
            Text(stringResource(R.string.beta_setup_assets_saved, ownedAssetCount), modifier = Modifier.testTag("onboarding-assets-saved"))
        if (step == BetaOnboardingStep.REVIEW) {
            Text(stringResource(if (pendingReviewCount == 0) R.string.beta_setup_no_reviews else R.string.beta_setup_review_count, pendingReviewCount))
        }
        val nextLabel = when (step) {
            BetaOnboardingStep.IMPORT -> if (hasMessages) R.string.beta_setup_next_assets else R.string.beta_setup_skip_import
            BetaOnboardingStep.ASSETS -> if (ownedAssetCount > 0) R.string.beta_setup_next_approval else R.string.beta_setup_skip_assets
            else -> R.string.beta_onboarding_finish
        }
        if (nextIsPrimary) SetupAction(true, !busy, onNext, "onboarding-next", nextLabel)
        when (step) {
            BetaOnboardingStep.BANK -> banks.forEach { bank ->
                SetupAction(true, !busy, { onChooseBank(bank.bankId) }, "onboarding-bank-${bank.bankId.value}", bankName(bank.bankId))
            }
            BetaOnboardingStep.IMPORT -> SetupAction(!nextIsPrimary, !busy, onHistory, "onboarding-import", R.string.beta_setup_import_action)
            BetaOnboardingStep.ASSETS -> {
                SetupAction(!nextIsPrimary, !busy, onDiscovery, "onboarding-discovery", R.string.beta_setup_assets_action)
                SetupAction(false, !busy, onManage, "onboarding-manage", R.string.beta_setup_manual_action)
            }
            BetaOnboardingStep.REVIEW -> if (pendingReviewCount > 0)
                SetupAction(true, !busy, onApproval, "onboarding-approval", R.string.beta_setup_approval_action)
            BetaOnboardingStep.PERMISSION, BetaOnboardingStep.COMPLETE -> Unit
        }
        if (!nextIsPrimary && step != BetaOnboardingStep.BANK && step != BetaOnboardingStep.COMPLETE)
            SetupAction(false, !busy, onNext, "onboarding-next", nextLabel)
        if (failed) Text(stringResource(R.string.beta_action_failed), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SetupAction(primary: Boolean, enabled: Boolean, onClick: () -> Unit, tag: String, label: Int) {
    val modifier = Modifier.fillMaxWidth().testTag(tag)
    if (primary) Button(enabled = enabled, onClick = onClick, modifier = modifier) { Text(stringResource(label)) }
    else OutlinedButton(enabled = enabled, onClick = onClick, modifier = modifier) { Text(stringResource(label)) }
}
