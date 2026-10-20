package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*
import java.time.YearMonth

/** Financial management and explicit lab setup/reset actions. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BetaSettingsHubPanel(category: String, onCategory: (String) -> Unit,
    banks: List<BetaBankChoice>, assets: List<OwnedAsset>, controls: BetaDashboardControls, busy: Boolean,
    onEdit: (OwnedAsset) -> Unit,
    onBusyChanged: (Boolean) -> Unit, onClose: () -> Unit,
    onRename: (OwnedAsset,String,Long,()->Unit) -> Unit = {_,_,_,_->}, failed: Boolean = false,
    tracking: List<BetaTrackedAsset> = emptyList(),references: List<BetaOwnershipCandidate> = emptyList(),
    onTracking: (BetaTrackedAsset,Boolean,()->Unit)->Unit = {_,_,_->},onOwnership: (BetaOwnershipCandidate,Boolean,AssetId?)->Unit = {_,_,_->},
    onAccountType: (OwnedAsset,String,Long,()->Unit)->Unit = {_,_,_,_->},onRegistryTitle: (String?)->Unit = {},initialRegistryTab: String="followed",
    theme: String = "SYSTEM", language: String = "SYSTEM", cardTasks: List<BetaCardSetupTask> = emptyList(), metadata: Map<AssetId,BetaAssetMetadata> = emptyMap(), onConfigureTask: (BetaCardSetupTask) -> Unit = {}, onTheme: (String) -> Unit = {}, onLanguage: (String) -> Unit = {}, onData: () -> Unit = {}, onSetup: () -> Unit = {}, onReset: () -> Unit = {}, onDeveloper: () -> Unit = {},onHistory: () -> Unit = {}) {
    var renameId by rememberSaveable {mutableStateOf<String?>(null)}
    var renameBank by rememberSaveable {mutableStateOf<String?>(null)}
    var renameRevision by rememberSaveable {mutableLongStateOf(0)}
    var renameName by rememberSaveable {mutableStateOf("")}
    val renameAsset=(tracking.map {it.asset}.ifEmpty {assets}).singleOrNull {it.id.bank.value==renameBank && it.id.localId==renameId}
    var typeTarget by remember {mutableStateOf<OwnedAsset?>(null)}
    var resetConfirmation by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (!busy) { if (resetConfirmation) resetConfirmation = false else if (category == "HUB") onClose() else onCategory("HUB") } }
    Column(Modifier.testTag("beta-settings-panel").then(if(category=="COMMITMENTS") Modifier.fillMaxSize() else Modifier), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        if (category == "HUB") MasroofCard(Modifier.testTag("beta-management-settings")) {
            Text(stringResource(R.string.beta_manage_title), style = MaterialTheme.typography.titleMedium)
            val groups = listOf("ACCOUNTS" to R.string.beta_home_accounts, "CARDS" to R.string.beta_home_cards,
                "LOANS" to R.string.beta_home_loans, "COMMITMENTS" to R.string.beta_home_commitments_shortcut)
            groups.forEachIndexed { index, (key, label) ->
                BetaManagementRow(stringResource(label), stringResource(R.string.beta_complete_asset_count,assets.count {a -> when(key) {"ACCOUNTS"->a.kind==AssetKind.ACCOUNT;"LOANS"->a.kind==AssetKind.LOAN;"CARDS"->a.kind in setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD);else->false}}), when (key) {
                    "ACCOUNTS" -> MasroofOutlineIcons.Landmark; "CARDS" -> MasroofOutlineIcons.CreditCard
                    "LOANS" -> MasroofOutlineIcons.HandCoins; else -> MasroofOutlineIcons.CalendarCheck
                }, enabled = !busy, onClick = { onCategory(key) }, modifier = Modifier.testTag("manage-$key"), divider = index != groups.lastIndex)
            }

        }
        if(category=="HUB") MasroofCard {
            Text(stringResource(R.string.beta_complete_app_section),style=MaterialTheme.typography.titleMedium)
            BetaManagementRow(stringResource(R.string.beta_approved_reference_preferences_title),"",MasroofOutlineIcons.Settings,!busy,{onCategory("APP")},modifier=Modifier.testTag("manage-APP"))
            BetaManagementRow(stringResource(R.string.beta_parity_data_settings),"",MasroofOutlineIcons.DatabaseBackup,!busy,onData,modifier=Modifier.testTag("manage-DATA"))
            BetaManagementRow(stringResource(R.string.beta_complete_about),"",MasroofOutlineIcons.CircleAlert,!busy,{onCategory("ABOUT")},modifier=Modifier.testTag("manage-ABOUT"),divider=false)
        } else if(category=="APP") {
            val resolved=if(language=="SYSTEM") androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language else language
            com.baraa.masroof.lab.beta.presentation.approved.ApprovedPreferencesContent(resolved,theme,!busy,onLanguage,onTheme)
        } else if(category=="ABOUT") {
            MasroofCard {Text(stringResource(R.string.beta_home_brand),style=MaterialTheme.typography.titleLarge);Text(stringResource(R.string.beta_complete_about_body))}
            BetaManagementRow(stringResource(R.string.beta_complete_import_messages),"",MasroofOutlineIcons.MessageSquare,!busy,onHistory,modifier=Modifier.testTag("settings-import-sms"))
            BetaManagementRow(stringResource(R.string.beta_complete_developer),"",MasroofOutlineIcons.SlidersHorizontal,!busy,onDeveloper,modifier=Modifier.testTag("settings-developer-tools"))
        } else if (category == "COMMITMENTS") {
            BetaCommitmentsPanel(controls,busy,onBusyChanged,onRegistryTitle)
        } else {
            BetaRegistryManagementPanel(category,banks,tracking.ifEmpty {assets.map {BetaTrackedAsset(it,BetaOwnershipStatus.OWNED,metadata[it.id]?.revision ?: 0)}},
                references,metadata,cardTasks,busy,initialRegistryTab,onCategory,onRegistryTitle,onRename={asset ->
                    renameId=asset.id.localId;renameBank=asset.id.bank.value
                    renameName=metadata[asset.id]?.displayName.orEmpty();renameRevision=metadata[asset.id]?.revision ?: 0
                },onEdit=onEdit,onPickAccountType={typeTarget=it},onTracking,onOwnership,onConfigureTask)
        }

        if (category == "ABOUT") MasroofCard {
            Text(stringResource(R.string.beta_setup_lab_settings), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled = !busy, onClick = onSetup, modifier = Modifier.fillMaxWidth().testTag("settings-setup")) {
                Text(stringResource(R.string.beta_setup_continue))
            }
            TextButton(enabled = !busy, onClick = { resetConfirmation = true }, modifier = Modifier.fillMaxWidth().testTag("settings-reset-lab")) {
                Text(stringResource(R.string.beta_setup_reset), color = MaterialTheme.colorScheme.error)
            }
        }

    }
    typeTarget?.let {target ->
        AlertDialog(onDismissRequest={if(!busy) typeTarget=null},title={Text(stringResource(R.string.beta_registry_settings_account_type_title))},
            text={Column(verticalArrangement=Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
                listOf("CURRENT","SAVINGS","WALLET").forEach {type -> TextButton(enabled=!busy && type!=metadata[target.id]?.accountType,onClick={
                    onAccountType(target,type,metadata[target.id]?.revision ?: 0) {typeTarget=null}
                },modifier=Modifier.testTag("registry-account-type-$type")) {Text(registryAccountType(type))}}
            }},confirmButton={},dismissButton={TextButton(enabled=!busy,onClick={typeTarget=null}) {Text(stringResource(R.string.beta_registry_settings_cancel))}})
    }
    if(renameAsset!=null) com.baraa.masroof.lab.beta.presentation.approved.ApprovedRenameAssetDialog(
        "${renameAsset.id.bank.value}:${renameAsset.id.localId}",renameName,
        stringResource(if(renameAsset.kind==AssetKind.ACCOUNT) R.string.beta_approved_settings_rename_account_title else R.string.beta_approved_settings_rename_card_title),
        busy,onDismiss={renameId=null;renameBank=null},onSave={name ->
            onRename(renameAsset,name,renameRevision) {renameId=null;renameBank=null}
        },failed=failed)
    if (resetConfirmation) AlertDialog(
        onDismissRequest = { if (!busy) resetConfirmation = false },
        title = { Text(stringResource(R.string.beta_setup_reset_title)) },
        text = { Text(stringResource(R.string.beta_setup_reset_help)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { resetConfirmation = false; onReset() }, modifier = Modifier.testTag("settings-reset-confirm")) {
            Text(stringResource(R.string.beta_setup_reset), color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = { resetConfirmation = false }, modifier = Modifier.testTag("settings-reset-cancel")) {
            Text(stringResource(R.string.beta_asset_cancel))
        } })

}

/** Production navigation geometry expressed with existing shared tokens and renderers. */
@Composable
internal fun BetaManagementRow(title: String, subtitle: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, divider: Boolean = true) {
    com.baraa.masroof.lab.beta.presentation.approved.ApprovedReferenceNavigationRow(
        title,subtitle,icon,onClick,modifier,divider,enabled=enabled)
}
