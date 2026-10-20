package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import com.baraa.masroof.ui.theme.*
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
    onClose: () -> Unit, onSaved: (BetaHomeLayout) -> Unit) {
    var draft by remember { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberBetaUiScope()
    BetaBottomSheet(stringResource(R.string.beta_approved_home_customize_title), busy, onClose, applyLabel=stringResource(R.string.beta_approved_home_customize_save), onApply = {
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
            DashboardLayoutChoiceRow(title,homeSectionIcon(entry.section),entry.visible,
                index>0,index<draft.sections.lastIndex,stringResource(R.string.beta_home_move_up,title),stringResource(R.string.beta_home_move_down,title),
                onToggle={draft=draft.toggle(entry.section)},onMoveUp={draft=draft.move(entry.section,-1)},onMoveDown={draft=draft.move(entry.section,1)},
                enabled=!busy,modifier=Modifier.testTag("home-layout-${entry.section.name}"),
                extraContent=if(entry.section==BetaHomeSection.METRICS) ({
                    HomeQuickChild(stringResource(R.string.beta_home_expenses),draft.expensesVisible,!busy) {draft=draft.copy(expensesVisible=!draft.expensesVisible)}
                    HomeQuickChild(stringResource(R.string.beta_home_daily_average),draft.averageVisible,!busy) {draft=draft.copy(averageVisible=!draft.averageVisible)}
                }) else null)
        }

        if (failed) Text(stringResource(R.string.beta_chart_save_error), color = MaterialTheme.colorScheme.error)
    }
}

private fun homeSectionLabel(section: BetaHomeSection): Int = when (section) {
    BetaHomeSection.HERO -> R.string.beta_approved_home_section_hero
    BetaHomeSection.SHORTCUTS -> R.string.beta_approved_home_section_shortcuts
    BetaHomeSection.COMMITMENTS -> R.string.beta_approved_home_section_commitments
    BetaHomeSection.METRICS -> R.string.beta_approved_home_section_quick
    BetaHomeSection.MERCHANTS -> R.string.beta_approved_home_section_analysis
    BetaHomeSection.TRANSACTIONS -> R.string.beta_home_recent
}

@Composable
private fun HomeQuickChild(label: String,checked: Boolean,enabled: Boolean,onToggle: ()->Unit) {
    Row(Modifier.fillMaxWidth().padding(start=MasroofSpacing.entityIconSize,top=MasroofSpacing.inlineGap),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,style=MaterialTheme.typography.labelSmall)
        Switch(checked,{onToggle()},enabled=enabled)
    }
}
private fun homeSectionIcon(section: BetaHomeSection)=when(section) {
    BetaHomeSection.HERO -> MasroofOutlineIcons.Wallet
    BetaHomeSection.SHORTCUTS -> MasroofOutlineIcons.Landmark
    BetaHomeSection.COMMITMENTS -> MasroofOutlineIcons.CalendarCheck
    BetaHomeSection.METRICS -> MasroofOutlineIcons.ArrowRightLeft
    BetaHomeSection.MERCHANTS -> MasroofOutlineIcons.Store
    BetaHomeSection.TRANSACTIONS -> MasroofOutlineIcons.List
}
