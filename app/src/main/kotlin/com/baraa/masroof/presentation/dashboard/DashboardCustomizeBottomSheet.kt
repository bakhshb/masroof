package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.ui.theme.MasroofFontWeights
import com.baraa.masroof.ui.theme.MasroofSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.DashboardLayoutSnapshot
import com.baraa.masroof.application.dashboard.DashboardSectionId
import com.baraa.masroof.presentation.common.MasroofIcons

/** Sections the customize sheet does not list; Up/Down steps past them. */
internal val CUSTOMIZE_SHEET_HIDDEN_SECTIONS = setOf(DashboardSectionId.CARDS, DashboardSectionId.LOANS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardCustomizeBottomSheet(
    draft: DashboardLayoutSnapshot,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onToggleSection: (DashboardSectionId) -> Unit,
    onMoveSection: (DashboardSectionId, Int) -> Unit,
    onToggleQuickExpense: () -> Unit,
    onToggleQuickIncome: () -> Unit,
) {
    com.baraa.masroof.presentation.common.MasroofBottomSheet(
        title = stringResource(R.string.dashboard_customize_sheet_title), onDismiss = onDismiss,
        onApply = onSave, applyLabel = stringResource(R.string.dashboard_customize_save)) {
        val shown = draft.sections.filter { it.id !in CUSTOMIZE_SHEET_HIDDEN_SECTIONS }
        shown.forEachIndexed { index, entry ->
            CustomizeSectionRow(entry, index > 0,
                index < shown.lastIndex, { onToggleSection(entry.id) },
                { onMoveSection(entry.id, -1) }, { onMoveSection(entry.id, 1) },
                if (entry.id == DashboardSectionId.QUICK) ({
                    QuickChildToggle(stringResource(R.string.dashboard_quick_total_expense), draft.quickExpenseVisible, onToggleQuickExpense)
                    QuickChildToggle(stringResource(R.string.dashboard_quick_total_income), draft.quickIncomeVisible, onToggleQuickIncome)
                }) else null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeSectionRow(
    entry: com.baraa.masroof.application.dashboard.DashboardSectionEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    extraContent: (@Composable () -> Unit)?,
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MasroofSpacing.none),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap),
        ) {
            Icon(
                imageVector = sectionIcon(entry.id),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    sectionTitle(entry.id),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = MasroofFontWeights.semiBold),
                )
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.sheet_move_up, sectionTitle(entry.id)))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.sheet_move_down, sectionTitle(entry.id)))
                }
            }
            Switch(checked = entry.visible, onCheckedChange = { onToggle() })
        }
        extraContent?.invoke()
    }
}

@Composable
private fun QuickChildToggle(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MasroofSpacing.entityIconSize, top = MasroofSpacing.inlineGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun sectionTitle(id: DashboardSectionId): String =
    stringResource(
        when (id) {
            DashboardSectionId.HERO -> R.string.dashboard_customize_section_hero
            DashboardSectionId.QUICK -> R.string.dashboard_customize_section_quick
            DashboardSectionId.ACCOUNTS -> R.string.dashboard_customize_section_accounts
            DashboardSectionId.CARDS -> R.string.dashboard_customize_section_cards
            DashboardSectionId.LOANS -> R.string.dashboard_customize_section_loans
            DashboardSectionId.COMMITMENTS -> R.string.dashboard_customize_section_commitments
            DashboardSectionId.MERCHANTS -> R.string.dashboard_customize_section_merchants
            DashboardSectionId.TRANSACTIONS -> R.string.dashboard_customize_section_transactions
        },
    )

private fun sectionIcon(id: DashboardSectionId) =
    when (id) {
        DashboardSectionId.HERO -> MasroofIcons.appLogo
        DashboardSectionId.QUICK -> MasroofIcons.netCashFlow
        DashboardSectionId.ACCOUNTS -> MasroofIcons.moneyMovement
        DashboardSectionId.CARDS -> MasroofIcons.cardPayment
        DashboardSectionId.LOANS -> MasroofIcons.moneyMovement
        DashboardSectionId.COMMITMENTS -> MasroofIcons.calendar
        DashboardSectionId.MERCHANTS -> MasroofIcons.merchant
        DashboardSectionId.TRANSACTIONS -> MasroofIcons.recentTransactions
    }
