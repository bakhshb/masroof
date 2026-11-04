package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.patterns.PatternBankView
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofThemeExtras

@Composable
fun SettingsBanksScreen(state: SettingsUiState, onBack: () -> Unit, onOpenBank: (String) -> Unit,
    onAddBank: (() -> Unit)? = null, onImport: (() -> Unit)? = null,
    title: String = stringResource(R.string.settings_banks_title)) {
    MasroofSecondaryScaffold(title, onBack, stringResource(R.string.settings_back)) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            if (state.bankSummaries.isEmpty()) MasroofText(stringResource(R.string.settings_banks_empty))
            else MasroofCard {
                state.bankSummaries.forEachIndexed { index, summary ->
                    val definition = state.bankCatalog?.banks?.find { it.bankId == summary.bank.id }
                    val (support, accent) = definition?.let { bankSupportLabel(it) }
                        ?: (stringResource(R.string.settings_bank_registry_only) to MasroofCardAccent.None)
                    MasroofReferenceNavigationRow(settingsBankLabel(summary.bank), support,
                        MasroofIcons.moneyMovement, { onOpenBank(summary.bank.id) },
                        divider = index != state.bankSummaries.lastIndex, subtitleColor = accent.statusTextColor())
                }
            }
            onAddBank?.let { action -> MasroofButton(action, Modifier.fillMaxWidth()) { MasroofText(stringResource(R.string.settings_add_bank)) } }
            onImport?.let { action ->
                MasroofOutlinedButton(action, Modifier.fillMaxWidth(), enabled = state.bankCatalog?.banks?.isNotEmpty() == true) {
                    MasroofText(stringResource(R.string.settings_import_selected_banks))
                }
            }
        }
    }
}

/** Short reading status of a bank and the semantic accent it is shown with. */
@Composable
internal fun bankSupportLabel(bank: PatternBankView): Pair<String, MasroofCardAccent> {
    val status = bankSupportStatus(bank)
    val label = if (status.pending > 0)
        pluralStringResource(status.label, status.pending, status.pending) else stringResource(status.label)
    return label to status.accent
}

internal data class BankSupportStatus(val label: Int, val accent: MasroofCardAccent, val pending: Int = 0)

internal fun bankSupportStatus(bank: PatternBankView): BankSupportStatus {
    // Only movement patterns record money; an enabled notice pattern alone does not make a bank ready.
    val movement = bank.currentPatterns.filter { it.canApprove }
    val active = movement.filter { it.enabled }
    val pending = active.count { !it.canApply }
    return when {
        movement.isEmpty() -> BankSupportStatus(R.string.settings_bank_needs_teaching, MasroofCardAccent.None)
        active.isEmpty() -> BankSupportStatus(R.string.settings_bank_patterns_disabled, MasroofCardAccent.None)
        pending == active.size -> BankSupportStatus(R.string.settings_bank_needs_approval, MasroofCardAccent.Liability)
        pending > 0 -> BankSupportStatus(R.plurals.settings_bank_partly_approved, MasroofCardAccent.Liability, pending)
        else -> BankSupportStatus(R.string.settings_bank_bundled, MasroofCardAccent.Inflow)
    }
}

@Composable
private fun MasroofCardAccent.statusTextColor(): Color = when (this) {
    MasroofCardAccent.Inflow -> MasroofThemeExtras.extendedColors.inflow
    MasroofCardAccent.Liability -> MasroofThemeExtras.extendedColors.liability
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
