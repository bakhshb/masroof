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
    onBankEnabled: (BankId, Boolean) -> Unit, onAdd: (BankId, AssetKind) -> Unit, onEdit: (OwnedAsset) -> Unit,
    onBusyChanged: (Boolean) -> Unit, onClose: () -> Unit,
    theme: String = "SYSTEM", language: String = "SYSTEM", cardTasks: List<BetaCardSetupTask> = emptyList(), metadata: Map<AssetId,BetaAssetMetadata> = emptyMap(), onConfigureTask: (BetaCardSetupTask) -> Unit = {}, onTheme: (String) -> Unit = {}, onLanguage: (String) -> Unit = {}, onData: () -> Unit = {}, onSetup: () -> Unit = {}, onReset: () -> Unit = {}, onDeveloper: () -> Unit = {},onHistory: () -> Unit = {}) {
    var resetConfirmation by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (!busy) { if (resetConfirmation) resetConfirmation = false else if (category == "HUB") onClose() else onCategory("HUB") } }
    Column(Modifier.testTag("beta-settings-panel"), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
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
            BetaManagementRow(stringResource(R.string.beta_complete_preferences),"",MasroofOutlineIcons.Settings,!busy,{onCategory("APP")},modifier=Modifier.testTag("manage-APP"))
            BetaManagementRow(stringResource(R.string.beta_parity_data_settings),"",MasroofOutlineIcons.DatabaseBackup,!busy,onData,modifier=Modifier.testTag("manage-DATA"))
            BetaManagementRow(stringResource(R.string.beta_complete_about),"",MasroofOutlineIcons.CircleAlert,!busy,{onCategory("ABOUT")},modifier=Modifier.testTag("manage-ABOUT"),divider=false)
        } else if(category=="APP") {
            MasroofCard {
                Text(stringResource(R.string.beta_parity_theme),style=MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement=Arrangement.spacedBy(MasroofReference.shortcutGap),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                    listOf(Triple("LIGHT",R.string.beta_parity_light,MasroofOutlineIcons.Sun),Triple("DARK",R.string.beta_parity_dark,MasroofOutlineIcons.Moon),Triple("SYSTEM",R.string.beta_parity_system,MasroofOutlineIcons.Monitor)).forEach {(value,label,icon) ->
                        BetaChoice(theme==value,{onTheme(value)},Modifier.testTag("settings-theme-$value"),!busy) {
                            Row(horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap),verticalAlignment=Alignment.CenterVertically) {
                                Icon(icon,null,Modifier.size(MasroofIconSizes.sm));Text(stringResource(label))
                            }
                        }
                    }
                }
            }
            MasroofCard {
                Text(stringResource(R.string.beta_parity_language),style=MaterialTheme.typography.titleMedium)
                val resolved=if(language=="SYSTEM") androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language else language
                listOf("ar" to "العربية","en" to "English").forEach {(tag,label) ->
                    Row(Modifier.fillMaxWidth().clickable(enabled=!busy) {onLanguage(tag)}.padding(vertical=MasroofReference.rowVertical).testTag("settings-language-$tag"),
                        horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text(label,style=MasroofReference.pair)
                        Surface(color=if(resolved==tag) MasroofThemeExtras.extendedColors.inflowSoft else MaterialTheme.colorScheme.surface,shape=MasroofShapes.small) {
                            Text(stringResource(if(resolved==tag) R.string.beta_complete_current_language else R.string.beta_complete_available_language),Modifier.padding(MasroofSpacing.sectionHeaderGap),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider(color=MasroofThemeExtras.extendedColors.cardBorder)
                }
            }
            MasroofCard {
                Text(stringResource(R.string.beta_complete_color_meanings),style=MaterialTheme.typography.titleMedium)
                val colors=MasroofThemeExtras.extendedColors
                listOf(Triple(R.string.beta_fix_inflow,MasroofOutlineIcons.ArrowDownLeft,colors.inflow),Triple(R.string.beta_home_expenses,MasroofOutlineIcons.ArrowUpRight,colors.outflow),Triple(R.string.beta_asset_credit,MasroofOutlineIcons.CreditCard,colors.card),Triple(R.string.beta_dash_commitments,MasroofOutlineIcons.CalendarCheck,colors.liability)).forEach {(label,icon,color) ->
                    Row(Modifier.padding(top=MasroofSpacing.sectionGap),horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap),verticalAlignment=Alignment.CenterVertically) {
                        Icon(icon,null,Modifier.size(MasroofIconSizes.md),tint=color);Text(stringResource(label),style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else if(category=="ABOUT") {
            MasroofCard {Text(stringResource(R.string.beta_home_brand),style=MaterialTheme.typography.titleLarge);Text(stringResource(R.string.beta_complete_about_body))}
            BetaManagementRow(stringResource(R.string.beta_complete_import_messages),"",MasroofOutlineIcons.MessageSquare,!busy,onHistory,modifier=Modifier.testTag("settings-import-sms"))
            BetaManagementRow(stringResource(R.string.beta_complete_developer),"",MasroofOutlineIcons.SlidersHorizontal,!busy,onDeveloper,modifier=Modifier.testTag("settings-developer-tools"))
        } else if (category == "COMMITMENTS") {
            BetaDashboardConfigurationPanel(controls, emptyList(), YearMonth.now(java.time.ZoneId.of("Asia/Riyadh")), !busy,
                onBusyChanged, onSaved = {}, commitmentsOnly = true)
        } else {
            val kinds = when (category) {
                "ACCOUNTS" -> setOf(AssetKind.ACCOUNT)
                "LOANS" -> setOf(AssetKind.LOAN)
                else -> setOf(AssetKind.CREDIT_CARD, AssetKind.DEBIT_CARD)
            }
            banks.forEach { bank ->
                MasroofCard {
                    Text(stringResource(bankName(bank.bankId)), style = MaterialTheme.typography.titleMedium)
                    if (category == "ACCOUNTS") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (bank.state == BankSelectionState.ENABLED) R.string.beta_bank_enabled else R.string.beta_bank_disabled), Modifier.weight(1f))
                            Switch(bank.state == BankSelectionState.ENABLED, onCheckedChange = { onBankEnabled(bank.bankId, it) }, enabled = !busy,
                                modifier = Modifier.testTag("manage-bank-${bank.bankId.value}"))
                        }
                    }
                    if(category=="CARDS") cardTasks.filter { it.bank==bank.bankId && it.assetId==null }.forEach { task ->
                        BetaManagementRow("•• "+task.suffix,stringResource(R.string.beta_parity_card_missing),MasroofOutlineIcons.CreditCard,!busy,{onConfigureTask(task)},modifier=Modifier.testTag("configure-card-${task.suffix}"))
                    }
                    assets.filter { it.id.bank == bank.bankId && it.kind in kinds }.forEach { asset ->
                        BetaManagementRow(metadata[asset.id]?.displayName ?: stringResource(assetKindLabel(asset.kind)), asset.last4?.let { "•• $it" }.orEmpty(), MasroofOutlineIcons.Wallet,
                            enabled = !busy, onClick = { onEdit(asset) }, modifier = Modifier.testTag("manage-asset-${asset.id.bank.value}-${asset.id.localId}"))
                    }
                    OutlinedButton(enabled = !busy && bank.state == BankSelectionState.ENABLED, onClick = {
                        onAdd(bank.bankId, when (category) { "ACCOUNTS" -> AssetKind.ACCOUNT; "LOANS" -> AssetKind.LOAN; else -> AssetKind.CREDIT_CARD })
                    }, modifier = Modifier.fillMaxWidth().testTag("manage-add-${bank.bankId.value}")) { Text(stringResource(R.string.beta_asset_add)) }
                }
            }
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
        OutlinedButton(enabled = !busy, onClick = { if (category == "HUB") onClose() else onCategory("HUB") },
            modifier = Modifier.testTag("beta-settings-close")) { Text(stringResource(if (category == "HUB") R.string.beta_asset_cancel else R.string.beta_dash_back)) }
    }
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
    Column(modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(top = MasroofReference.rowVertical,
            bottom = if (divider) MasroofReference.rowVertical else MasroofReference.lastRowBottom),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MasroofReference.rowGap)) {
            Surface(shape = MasroofShapes.small, color = MasroofThemeExtras.extendedColors.accountSoft) {
                Box(Modifier.size(MasroofReference.iconBox), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(MasroofReference.icon), tint = MasroofThemeExtras.extendedColors.account)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MasroofReference.metadataGap)) {
                Text(title, style = MasroofReference.pair.copy(fontWeight = MasroofFontWeights.semiBold))
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (LocalLayoutDirection.current == LayoutDirection.Rtl) MasroofOutlineIcons.ChevronLeft else MasroofOutlineIcons.ChevronRight,
                null, Modifier.size(MasroofReference.icon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (divider) HorizontalDivider(color = MasroofThemeExtras.extendedColors.cardBorder)
    }
}
