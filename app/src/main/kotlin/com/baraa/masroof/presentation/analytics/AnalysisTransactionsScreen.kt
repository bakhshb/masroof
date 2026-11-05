package com.baraa.masroof.presentation.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.analytics.AnalysisSnapshot
import com.baraa.masroof.application.analytics.AnalysisUiState
import com.baraa.masroof.application.analytics.AnalyticsWorkflow
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofGuidanceLabel
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.ui.theme.MasroofSpacing

@Composable internal fun AnalysisTransactionsScreen(snapshot: AnalysisSnapshot?, selection: String, workflow: AnalyticsWorkflow,
    onBack: () -> Unit, onOpenTransaction: (String) -> Unit, state: AnalysisUiState = AnalysisUiState()) {
    val observation = state.observations.firstOrNull { "observation:${it.id}" == selection }
    val insights = snapshot?.insights
    val comparison = insights?.comparison
    val change = comparison?.changes?.firstOrNull { "change:${it.group.key}" == selection }
    val group = change?.group ?: (insights?.components.orEmpty() + insights?.merchants.orEmpty() + insights?.categories.orEmpty()).firstOrNull { it.key == selection }
    val title = observation?.merchant ?: group?.let { spendingGroupName(it) } ?: stringResource(R.string.transaction_detail_title)
    MasroofSecondaryScaffold(title, onBack, stringResource(R.string.lab_analysis_back)) { modifier ->
        Column(modifier.testTag("analysis-transactions").verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            if (snapshot != null && observation != null && !state.updating && !state.unavailable) {
                MasroofCard {
                    MasroofGuidanceLabel(stringResource(R.string.personal_spending_attention), stringResource(R.string.personal_spending_hint),
                        style = MaterialTheme.typography.titleMedium)
                    AnalysisSpendingObservationBars(observation)
                    EvidenceGroup(listOf(observation.id), stringResource(R.string.personal_spending_payment), workflow,
                        snapshot.sourceHash, onOpenTransaction, collapsible = false)
                }
                MasroofCard {
                    MasroofSectionHeader(stringResource(R.string.personal_spending_previous))
                    Text(stringResource(R.string.personal_spending_reference, observation.referenceIds.size, day(observation.from), day(observation.through)))
                    EvidenceGroup(observation.referenceIds, stringResource(R.string.personal_spending_previous), workflow,
                        snapshot.sourceHash, onOpenTransaction, collapsible = false)
                }
            } else if (snapshot == null || group == null) {
                Text(stringResource(R.string.lab_analysis_transaction_missing))
            } else if (change != null) {
                MasroofCard {
                    Amount(stringResource(if (change.delta < 0) R.string.analysis_decrease else R.string.analysis_increase, ""), change.delta)
                }
                MasroofCard {
                    MasroofSectionHeader(stringResource(R.string.lab_analysis_comparison_current))
                    Text("${day(comparison.from)} — ${day(comparison.through)}", style = MaterialTheme.typography.bodySmall)
                    Amount(title, change.group.amount)
                    EvidenceGroup(change.group.ids, title, workflow, snapshot.sourceHash, onOpenTransaction, collapsible = false)
                }
                MasroofCard {
                    MasroofSectionHeader(stringResource(R.string.lab_analysis_comparison_previous))
                    Text("${day(comparison.previousFrom)} — ${day(comparison.previousThrough)}", style = MaterialTheme.typography.bodySmall)
                    Amount(title, change.previous)
                    EvidenceGroup(change.previousIds, title, workflow, snapshot.sourceHash, onOpenTransaction, collapsible = false)
                }
            } else {
                Text("${day(snapshot.periodStart)} — ${day(snapshot.periodEnd - 1)}", style = MaterialTheme.typography.bodySmall)
                // Stays on screen while analysis refreshes, so a running save is never dropped with its sheet.
                if (group.key.startsWith("merchant:") && !state.unavailable) MerchantCategoryEditor(group, workflow, enabled = !state.updating)
                MasroofCard {
                    Amount(title, group.amount)
                    EvidenceGroup(group.ids, title, workflow, snapshot.sourceHash, onOpenTransaction, collapsible = false)
                }
            }
        }
    }
}
