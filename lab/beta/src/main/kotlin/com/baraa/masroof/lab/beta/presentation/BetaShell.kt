package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedBackNavigationIcon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import com.baraa.masroof.lab.beta.application.BetaBankChoice
import com.baraa.masroof.lab.beta.application.BankSelectionState
import com.baraa.masroof.lab.finance.asset.OwnedAsset
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.lab.beta.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BetaShell(
    markerCount: Int,
    restoreMissing: Boolean,
    banks: List<BetaBankChoice> = emptyList(),
    assets: List<OwnedAsset> = emptyList(),
    editor: (@Composable () -> Unit)? = null,
    editorKey: String? = null,
    dashboard: (@Composable () -> Unit)? = null,
    dashboardOwnsScroll: Boolean = false,
    onOpenDiscovery: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenReview: () -> Unit = {},
    onOpenApproval: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDeveloperTools: () -> Unit = {},
    onOpenHome: (() -> Unit)? = null,
    onOpenTransactions: () -> Unit = {},
    selectedHomeTab: String = "HOME",
    showMaintenanceActions: Boolean = true,
    onAddAsset: (BankId) -> Unit = {},
    onEditAsset: (OwnedAsset) -> Unit = {},
    returnEnabled: Boolean = true,
    busy: Boolean = false,
    failed: Boolean = false,
    onSetBankEnabled: (BankId, Boolean) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
    onReturn: () -> Unit,
    onReset: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    showReturn: Boolean = true,
    setupFooter: (@Composable () -> Unit)? = null,
    secondaryTitle: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    notificationCount: Int = 0,
    onOpenNotifications: () -> Unit = onOpenReview,
    developerWorkspaceRequest: Int = 0,
) {
    var workspaceOpen by rememberSaveable { mutableStateOf(false) }
    var lastDeveloperRequest by rememberSaveable {mutableIntStateOf(0)}
    LaunchedEffect(developerWorkspaceRequest) {if(developerWorkspaceRequest!=lastDeveloperRequest) {lastDeveloperRequest=developerWorkspaceRequest;workspaceOpen=true}}
    val homeState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val editorState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    BackHandler { if (returnEnabled) { if (workspaceOpen && editor == null) workspaceOpen = false else onReturn() } }
    Scaffold(
        topBar = {
            com.baraa.masroof.ui.components.MasroofAppBar(
                title = stringResource(R.string.beta_home_brand),
                showLogo = true,
                actions = {
                    if(dashboard!=null) com.baraa.masroof.lab.beta.presentation.approved.ApprovedNotificationIconButton(
                        notificationCount,onOpenNotifications,Modifier.padding(end=com.baraa.masroof.ui.theme.MasroofSpacing.inlineGap).testTag("beta-notifications"),enabled=!busy && returnEnabled)

                    Surface(Modifier.padding(end = BetaSpacing.section).testTag("beta-badge"),
                        color = MaterialTheme.colorScheme.errorContainer) {
                        Text(stringResource(R.string.beta_badge),
                            Modifier.padding(horizontal = BetaSpacing.badgeHorizontal, vertical = BetaSpacing.badgeVertical),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                },
            )
        },
        bottomBar = {
            Column {
            if (dashboard != null && onOpenHome != null) {
                val icons = com.baraa.masroof.ui.components.MasroofOutlineIcons
                val items = listOf(
                    com.baraa.masroof.ui.components.MasroofNavigationItem(stringResource(R.string.beta_nav_home), icons.LayoutDashboard, selectedHomeTab == "HOME"),
                    com.baraa.masroof.ui.components.MasroofNavigationItem(stringResource(R.string.beta_nav_transactions), icons.List, selectedHomeTab == "TRANSACTIONS"),
                    com.baraa.masroof.ui.components.MasroofNavigationItem(stringResource(R.string.beta_nav_review), icons.ShieldCheck, selectedHomeTab == "REVIEW"),
                    com.baraa.masroof.ui.components.MasroofNavigationItem(stringResource(R.string.beta_nav_settings), icons.Settings, selectedHomeTab == "SETTINGS"))
                Column(Modifier.testTag("beta-bottom-navigation")) {
                com.baraa.masroof.ui.components.MasroofBottomNavigation(items, { index ->
                    when (index) { 0 -> { workspaceOpen = false; onOpenHome() }; 1 -> { workspaceOpen = false; onOpenTransactions() }; 2 -> { workspaceOpen = false; onOpenReview() }; 3 -> { workspaceOpen = false; onOpenSettings() } }
                }, enabled = returnEnabled && !busy)
                }
            }
            setupFooter?.invoke()
            if(showReturn) Button(onClick = onReturn, enabled = returnEnabled, modifier = Modifier.fillMaxWidth().padding(BetaSpacing.screen).testTag("beta-return")) {
                Text(stringResource(R.string.beta_return))
            }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        if(secondaryTitle!=null) com.baraa.masroof.ui.components.MasroofAppBar(
            title=secondaryTitle,showLogo=false,
            navigation=onNavigateBack?.let {back -> {
                ApprovedBackNavigationIcon(back,stringResource(R.string.beta_dash_back),!busy && returnEnabled,Modifier.testTag("beta-page-back"))
            }})
        val shellScroll=rememberSaveable(secondaryTitle,selectedHomeTab,workspaceOpen,saver=androidx.compose.foundation.ScrollState.Saver) {androidx.compose.foundation.ScrollState(0)}
        val bodyModifier=Modifier.weight(1f).fillMaxWidth().let {base ->
            if(dashboardOwnsScroll && dashboard!=null && editor==null && !workspaceOpen) base
            else base.padding(horizontal=BetaSpacing.screen).padding(bottom=BetaSpacing.screen)
                .then(if(secondaryTitle==null) Modifier.padding(top=BetaSpacing.screen) else Modifier).verticalScroll(shellScroll)
        }
        Column(bodyModifier,verticalArrangement=Arrangement.spacedBy(BetaSpacing.section)
        ) {
            if (editor != null) {
                if(editorKey==null) editor() else editorState.SaveableStateProvider(editorKey) {editor()}
                return@Column
            }
            if (dashboard != null && !workspaceOpen) {
                homeState.SaveableStateProvider("dashboard") { dashboard() }
                return@Column
            }
            Text(stringResource(if (dashboard == null) R.string.beta_welcome else R.string.beta_home_tools), style = MaterialTheme.typography.titleLarge)
            if (dashboard == null) Text(stringResource(R.string.beta_welcome_steps))
            OutlinedButton(enabled = !busy, onClick = onOpenSettings, modifier = Modifier.testTag("beta-settings")) { Text(stringResource(R.string.beta_nav_settings)) }
            OutlinedButton(enabled = !busy, onClick = onOpenDeveloperTools, modifier = Modifier.testTag("beta-developer-tools")) { Text(stringResource(R.string.beta_developer_tools)) }
            OutlinedButton(enabled = !busy, onClick = onOpenDiscovery, modifier = Modifier.testTag("beta-discovery")) { Text(stringResource(R.string.beta_discovery_title)) }
            if (dashboard == null) Text(stringResource(R.string.beta_empty_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(enabled = !busy, onClick = onOpenHistory, modifier = Modifier.testTag("beta-history")) {
                Text(stringResource(R.string.beta_history_title))
            }
            OutlinedButton(enabled = !busy, onClick = onOpenReview, modifier = Modifier.testTag("beta-review")) { Text(stringResource(R.string.beta_review_title)) }
            OutlinedButton(enabled = !busy, onClick = onOpenApproval, modifier = Modifier.testTag("beta-approval")) { Text(stringResource(R.string.beta_approval_title)) }
            Text(stringResource(R.string.beta_bank_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.beta_bank_help), style = MaterialTheme.typography.bodyMedium)
            if (failed) {
                Text(stringResource(R.string.beta_action_failed), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.testTag("beta-retry")) {
                    Text(stringResource(R.string.beta_retry))
                }
            }
            banks.forEach { choice ->
                Card(modifier = Modifier.fillMaxWidth().testTag("beta-bank-${choice.bankId.value}")) {
                    Column(Modifier.padding(BetaSpacing.screen), verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
                        Text(stringResource(bankName(choice.bankId)), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when (choice.state) {
                            BankSelectionState.ENABLED -> R.string.beta_bank_enabled
                            BankSelectionState.DISABLED -> R.string.beta_bank_disabled
                            null -> R.string.beta_bank_not_selected
                        }))
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { onSetBankEnabled(choice.bankId, choice.state != BankSelectionState.ENABLED) },
                            modifier = Modifier.testTag("beta-bank-action-${choice.bankId.value}"),
                        ) {
                            Text(stringResource(when (choice.state) {
                                BankSelectionState.ENABLED -> R.string.beta_bank_disable
                                BankSelectionState.DISABLED -> R.string.beta_bank_resume
                                null -> R.string.beta_bank_add
                            }))
                        }
                        if (choice.state == BankSelectionState.ENABLED) {
                            OutlinedButton(enabled = !busy, onClick = { onAddAsset(choice.bankId) }, modifier = Modifier.testTag("asset-add-${choice.bankId.value}")) {
                                Text(stringResource(R.string.beta_asset_add))
                            }
                        }
                    }
                }
            }
            if (assets.isNotEmpty()) Text(stringResource(R.string.beta_asset_list), style = MaterialTheme.typography.titleLarge)
            assets.forEachIndexed { index, asset ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(BetaSpacing.screen)) {
                        Text(stringResource(R.string.beta_asset_summary, stringResource(assetKindLabel(asset.kind)), asset.last4.orEmpty(), index + 1))
                        OutlinedButton(enabled = !busy, onClick = { onEditAsset(asset) }, modifier = Modifier.testTag("asset-edit-${asset.id.localId}")) {
                            Text(stringResource(R.string.beta_asset_edit))
                        }
                    }
                }
            }
            if (showMaintenanceActions) {
            OutlinedButton(
                enabled = !busy,
                onClick = onBackup,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("beta-backup"),
            ) {
                Text(stringResource(R.string.beta_backup))
            }
            OutlinedButton(
                enabled = !busy,
                onClick = onRestore,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("beta-restore"),
            ) {
                Text(stringResource(R.string.beta_restore))
            }
            OutlinedButton(
                enabled = !busy,
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("beta-reset"),
            ) {
                Text(stringResource(R.string.beta_reset))
            }
            }
            if (showMaintenanceActions) Text(stringResource(R.string.beta_marker_count, markerCount), style = MaterialTheme.typography.bodySmall)
            if (restoreMissing) Text(stringResource(R.string.beta_restore_missing), color = MaterialTheme.colorScheme.error)
        }
        }
    }
}

/** Every real supported bank must have a localized name before joining the picker. */
internal fun bankName(bankId: BankId): Int = when (bankId.value) {
    "BANK_ALJAZIRA" -> R.string.beta_bank_aljazira
    else -> error("Supported bank has no localized picker label")
}
