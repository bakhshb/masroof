package com.baraa.masroof.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRegistryCategoryScreen(
    category: SettingsRegistryCategory,
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenBank: (SettingsBankSummaryUi) -> Unit,
) {
    val banks = category.banksWithContent(state.bankSummaries)

    MasroofSecondaryScaffold(
        title = category.screenTitle(),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
        actions = { com.baraa.masroof.presentation.common.MasroofGuidanceButton(category.screenTitle(), category.screenHint()) },
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {

            if (banks.isEmpty()) {
                Text(
                    when (category) {
                        SettingsRegistryCategory.Accounts -> stringResource(R.string.settings_accounts_empty)
                        SettingsRegistryCategory.Cards -> stringResource(R.string.settings_cards_empty)
                        SettingsRegistryCategory.Loans -> stringResource(R.string.settings_loans_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                banks.forEach { summary ->
                    SettingsNavRow(
                        icon = MasroofIcons.moneyMovement,
                        title = settingsBankLabel(summary.bank),
                        subtitle = category.bankRowSubtitle(summary),
                        onClick = { onOpenBank(summary) },
                    )
                }
            }
        }
    }
}
