package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedFormField as OutlinedTextField
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.ManualAssetDraft
import com.baraa.masroof.lab.beta.application.normalizeManualAssetSuffix
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import java.util.UUID

@Composable
internal fun BetaAssetEditor(
    bank: BankId,
    existing: OwnedAsset?,
    assets: List<OwnedAsset>,
    busy: Boolean,
    failed: Boolean,
    onSave: (ManualAssetDraft) -> Unit,
    onCancel: () -> Unit,
    proposal: com.baraa.masroof.lab.beta.application.BetaAssetProposal? = null,
    initialKind: AssetKind = AssetKind.ACCOUNT,
    initialSuffix: String = "",
    metadata: com.baraa.masroof.lab.beta.application.BetaAssetMetadata = com.baraa.masroof.lab.beta.application.BetaAssetMetadata(),
    allowedKinds: Set<AssetKind>? = null,
    ownershipConfirmed: Boolean = false,
    registryMetadata: Map<AssetId, com.baraa.masroof.lab.beta.application.BetaAssetMetadata> = emptyMap(),
) {
    if ((existing?.kind ?: proposal?.kind ?: initialKind) in setOf(AssetKind.CREDIT_CARD, AssetKind.DEBIT_CARD)) {
        BetaCardSetupEditor(bank, existing, assets, busy, failed, onSave, onCancel,
            proposal, initialKind, initialSuffix, metadata, registryMetadata, ownershipConfirmed)
        return
    }
    val localId = rememberSaveable { existing?.id?.localId ?: proposal?.id?.localId ?: UUID.randomUUID().toString() }
    val originalKind by rememberSaveable { mutableStateOf(existing?.kind?.name) }
    val originalSuffix by rememberSaveable { mutableStateOf(existing?.last4) }
    val originalParent by rememberSaveable { mutableStateOf(existing?.parentId?.localId) }
    val originalRole by rememberSaveable { mutableStateOf(existing?.creditCardRole?.name) }
    val stableId = AssetId(Environment.BETA, bank, localId)
    val original = originalKind?.let { OwnedAsset(stableId, AssetKind.valueOf(it), originalSuffix,
        originalParent?.let { parent -> AssetId(Environment.BETA, bank, parent) }, originalRole?.let(CreditCardRole::valueOf)) }
    var kindName by rememberSaveable { mutableStateOf((existing?.kind ?: proposal?.kind ?: initialKind).name) }
    var suffix by rememberSaveable { mutableStateOf((existing?.last4 ?: proposal?.last4 ?: initialSuffix)) }
    // Registry edits preserve already confirmed ownership; a new asset still needs an explicit decision.
    var confirmed by rememberSaveable { mutableStateOf(existing != null) }
    var displayName by rememberSaveable { mutableStateOf(metadata.displayName.orEmpty()) }
    var accountType by rememberSaveable { mutableStateOf(metadata.accountType) }
    var loanType by rememberSaveable { mutableStateOf(metadata.loanType) }
    val kind = AssetKind.valueOf(kindName)
    val suffixValid = suffix.matches(Regex("[0-9]{4}")) || (kind in setOf(AssetKind.CREDIT_FACILITY,AssetKind.LOAN) && suffix.isEmpty())
    val valid = confirmed && suffixValid
    BackHandler { if (!busy) onCancel() }
    fun save() { onSave(ManualAssetDraft(stableId,kind,suffix,
        ownedConfirmed=confirmed,editing=existing!=null,expectedOriginal=original,
        metadata=com.baraa.masroof.lab.beta.application.BetaAssetMetadata(displayName.trim().takeIf { it.isNotEmpty() },
            null,accountType.takeIf {kind==AssetKind.ACCOUNT},loanType.takeIf {kind==AssetKind.LOAN},metadata.revision))) }
    BetaBottomSheet(stringResource(if(existing==null) R.string.beta_asset_add else R.string.beta_asset_edit),busy,onCancel,::save,
        canApply=valid,applyTag="asset-save",cancelTag="asset-cancel",applyLabel=stringResource(R.string.beta_asset_save)) {
        Text(stringResource(bankName(bank)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.beta_asset_manual_help), style = MaterialTheme.typography.bodyMedium)
        val kinds = if (existing == null) (allowedKinds?.toList() ?: listOf(AssetKind.ACCOUNT, AssetKind.LOAN))
            else listOf(existing.kind)
        MasroofSheetChoices(kinds,setOf(kind),{stringResource(assetKindLabel(it))},
            {kindName=it.name},{"asset-kind-${it.name}"},enabled=!busy)
        OutlinedTextField(value=displayName,onValueChange={if(it.length<=120) displayName=it},enabled=!busy,singleLine=true,
            label={Text(stringResource(R.string.beta_parity_display_name))},modifier=Modifier.fillMaxWidth().testTag("asset-display-name"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
        if(kind==AssetKind.ACCOUNT) {
            Text(stringResource(R.string.beta_parity_account_type))
            val types=listOf("CURRENT" to R.string.beta_parity_current,"SAVINGS" to R.string.beta_parity_savings,"WALLET" to R.string.beta_approved_settings_account_type_wallet)
            MasroofSheetChoices(types,types.filter {it.first==accountType}.toSet(),{stringResource(it.second)},
                {accountType=it.first},{"asset-account-type-${it.first}"},enabled=!busy)
        }
        if(kind==AssetKind.LOAN) {
            Text(stringResource(R.string.beta_parity_loan_type))
            val types=listOf("PERSONAL" to R.string.beta_parity_personal,"AUTO" to R.string.beta_parity_auto,"MORTGAGE" to R.string.beta_parity_mortgage)
            MasroofSheetChoices(types,types.filter {it.first==loanType}.toSet(),{stringResource(it.second)},
                {loanType=it.first},{"asset-loan-type-${it.first}"},enabled=!busy)
        }
        OutlinedTextField(value = suffix, enabled = !busy,
            onValueChange = { value -> normalizeManualAssetSuffix(value)?.let { suffix = it } },
            label = { Text(stringResource(R.string.beta_asset_suffix)) }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number,imeAction=androidx.compose.ui.text.input.ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("asset-last4"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
        if(existing==null) Row {
            Checkbox(checked = confirmed, enabled = !busy, onCheckedChange = { confirmed = it }, modifier = Modifier.testTag("asset-owned-confirm"))
            Text(stringResource(R.string.beta_asset_owned_confirm), style = MaterialTheme.typography.bodyMedium)
        }
        if (failed) Text(stringResource(R.string.beta_asset_save_failed), color = MaterialTheme.colorScheme.error)

    }
}

internal fun assetKindLabel(kind: AssetKind): Int = when (kind) {
    AssetKind.ACCOUNT -> R.string.beta_asset_account
    AssetKind.DEBIT_CARD -> R.string.beta_asset_debit
    AssetKind.CREDIT_CARD -> R.string.beta_asset_credit
    AssetKind.CREDIT_FACILITY -> R.string.beta_asset_facility
    AssetKind.LOAN -> R.string.beta_asset_loan
}
