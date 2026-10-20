package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedFormField as OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import java.util.UUID

/** Maps the approved form to the isolated registry command; no parsing or ledger writes here. */
@Composable
internal fun BetaCardSetupEditor(
    bank: BankId, existing: OwnedAsset?, assets: List<OwnedAsset>, busy: Boolean, failed: Boolean,
    onSave: (ManualAssetDraft) -> Unit, onCancel: () -> Unit, proposal: BetaAssetProposal?,
    initialKind: AssetKind, initialSuffix: String, metadata: BetaAssetMetadata,
    registryMetadata: Map<AssetId, BetaAssetMetadata>, ownershipConfirmed: Boolean,
) {
    val localId = rememberSaveable { existing?.id?.localId ?: proposal?.id?.localId ?: UUID.randomUUID().toString() }
    val facilityId = rememberSaveable { UUID.randomUUID().toString() }
    val originalKind by rememberSaveable { mutableStateOf(existing?.kind?.name) }
    val originalSuffix by rememberSaveable { mutableStateOf(existing?.last4) }
    val originalParent by rememberSaveable { mutableStateOf(existing?.parentId?.localId) }
    val originalRole by rememberSaveable { mutableStateOf(existing?.creditCardRole?.name) }
    val stableId = AssetId(Environment.BETA, bank, localId)
    val original = originalKind?.let { OwnedAsset(stableId, AssetKind.valueOf(it), originalSuffix,
        originalParent?.let { value -> AssetId(Environment.BETA, bank, value) }, originalRole?.let(CreditCardRole::valueOf)) }
    var suffix by rememberSaveable { mutableStateOf(existing?.last4 ?: proposal?.last4 ?: initialSuffix) }
    var confirmed by rememberSaveable { mutableStateOf(ownershipConfirmed) }
    val targetAsset = existing ?: OwnedAsset(stableId, proposal?.kind ?: initialKind, suffix.takeIf { it.length == 4 })
    val allMetadata = registryMetadata + (stableId to metadata)
    val cards = betaManagedCards(assets.filter { it.id != stableId } + targetAsset, allMetadata)
    val target = cards.single { it.id == stableId }.let {
        if(existing==null && proposal==null && ownershipConfirmed) it.copy(cardType=null) else it
    }
    val accounts = assets.filter { it.kind == AssetKind.ACCOUNT }.map { BetaManagedAccountUi(it, registryMetadata[it.id]?.displayName) }
    ApprovedCardSetupSheet(target, null, cards, accounts, busy, failed, onCancel,
        additionalValid = existing != null || (confirmed && suffix.matches(Regex("[0-9]{4}"))),
        extraContent = {
            if (existing == null && !ownershipConfirmed) {
                OutlinedTextField(suffix, { normalizeManualAssetSuffix(it)?.let { value -> suffix = value } },
                    enabled = !busy, singleLine = true, label = { ApprovedText(stringResource(R.string.beta_asset_suffix)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    visualTransformation = ApprovedDigitsVisualTransformation,
                    modifier = Modifier.fillMaxWidth().testTag("asset-last4"))
                Row {
                    Checkbox(confirmed, { confirmed = it }, enabled = !busy, modifier = Modifier.testTag("asset-owned-confirm"))
                    ApprovedText(stringResource(R.string.beta_asset_owned_confirm))
                }
            }
        }, onSave = { configuration ->
            val parent = when {
                configuration.type == AssetKind.DEBIT_CARD -> configuration.accountId
                configuration.role == CreditCardRole.SUPPLEMENTARY -> assets.single { it.id == configuration.primaryCardId }.parentId
                original?.kind == AssetKind.CREDIT_CARD && original.creditCardRole in setOf(CreditCardRole.PRIMARY, CreditCardRole.STANDALONE) && original.parentId != null -> original.parentId
                else -> AssetId(Environment.BETA, bank, facilityId)
            }
            onSave(ManualAssetDraft(stableId, configuration.type, suffix, parent, configuration.role,
                ownedConfirmed = existing != null || confirmed, editing = existing != null, expectedOriginal = original,
                createCreditFacility = configuration.type == AssetKind.CREDIT_CARD && configuration.role != CreditCardRole.SUPPLEMENTARY && assets.none { it.id == parent },
                metadata = metadata.copy(network = configuration.network?.name, accountType = null, loanType = null)))
        })
}
