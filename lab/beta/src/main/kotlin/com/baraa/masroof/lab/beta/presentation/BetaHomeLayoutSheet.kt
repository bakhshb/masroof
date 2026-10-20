package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.ui.components.MasroofOutlineIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BetaHomeLayoutSheet(saved: BetaHomeLayout, preferences: BetaDashboardViewPreferencesWorkflow?,
    onClose: () -> Unit, onSaved: (BetaHomeLayout) -> Unit, onConfiguration: () -> Unit = {}) {
    var draft by remember { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberBetaUiScope()
    BetaBottomSheet(stringResource(R.string.beta_home_layout_title), busy, onClose, onApply = {
        busy = true
        scope.launch {
            try {
                if (preferences?.saveHomeLayout(saved, draft) != false) onSaved(draft) else failed = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }) {
        draft.sections.forEachIndexed { index, entry ->
            val title = stringResource(homeSectionLabel(entry.section))
            DashboardLayoutChoiceRow(title, MasroofOutlineIcons.LayoutDashboard, entry.visible,
                index > 0, index < draft.sections.lastIndex,
                stringResource(R.string.beta_home_move_up, title), stringResource(R.string.beta_home_move_down, title),
                onToggle = { draft = draft.toggle(entry.section) }, onMoveUp = { draft = draft.move(entry.section, -1) },
                onMoveDown = { draft = draft.move(entry.section, 1) }, enabled = !busy,
                modifier = Modifier.testTag("home-layout-${entry.section.name}"))
        }
        BetaChoice(draft.expensesVisible, { draft = draft.copy(expensesVisible = !draft.expensesVisible) },
            enabled = !busy, label = { Text(stringResource(R.string.beta_home_expenses)) })
        BetaChoice(draft.averageVisible, { draft = draft.copy(averageVisible = !draft.averageVisible) },
            enabled = !busy, label = { Text(stringResource(R.string.beta_home_daily_average)) })
        OutlinedButton(enabled = !busy, onClick = onConfiguration, modifier = Modifier.testTag("home-period-settings")) { Text(stringResource(R.string.beta_controls_title)) }
        if (failed) Text(stringResource(R.string.beta_chart_save_error), color = MaterialTheme.colorScheme.error)
    }
}

private fun homeSectionLabel(section: BetaHomeSection): Int = when (section) {
    BetaHomeSection.HERO -> R.string.beta_home_remaining
    BetaHomeSection.SHORTCUTS -> R.string.beta_home_layout_shortcuts
    BetaHomeSection.COMMITMENTS -> R.string.beta_home_commitments
    BetaHomeSection.METRICS -> R.string.beta_home_expenses
    BetaHomeSection.TREND -> R.string.beta_home_purchases
    BetaHomeSection.MERCHANTS -> R.string.beta_home_top_merchants
    BetaHomeSection.TRANSACTIONS -> R.string.beta_home_recent
}
