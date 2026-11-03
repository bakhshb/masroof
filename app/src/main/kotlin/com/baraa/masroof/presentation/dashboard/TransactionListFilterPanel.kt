package com.baraa.masroof.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.ui.theme.*

@Composable
fun TransactionListToolbar(
    periodLabel: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onClearFilters: () -> Unit,
    filteredCount: Int,
    filterActive: Boolean,
    typeChoices: List<Pair<String, String>> = emptyList(),
    selectedType: String? = null,
    onSelectType: (String) -> Unit = {},
    sourceChoices: List<Pair<String, String>> = emptyList(),
    selectedSource: String? = null,
    onSelectSource: (String) -> Unit = {},
) {
    val types = listOf("" to stringResource(R.string.reference_all_types)) + typeChoices +
        listOf("advanced" to stringResource(R.string.transaction_list_filter_sheet_title))
    val sources = listOf("" to stringResource(R.string.reference_all_sources)) + sourceChoices
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        MasroofPeriodDisplay(periodLabel)
        MasroofFormField(searchQuery, onSearchQueryChange, Modifier.fillMaxWidth(),
            placeholder = { MasroofText(stringResource(R.string.transaction_list_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }, singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            MasroofSelect(types.firstOrNull { it.first == selectedType }?.second ?: types.first().second,
                types.map { it.second }, { index -> if (types[index].first == "advanced") onOpenFilters() else onSelectType(types[index].first) }, Modifier.weight(1f))
            MasroofSelect(sources.firstOrNull { it.first == selectedSource }?.second ?: sources.first().second,
                sources.map { it.second }, { index -> onSelectSource(sources[index].first) }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            MasroofText(androidx.compose.ui.res.pluralStringResource(R.plurals.transaction_list_count, filteredCount, filteredCount), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (filterActive) TextButton(onClearFilters) { MasroofText(stringResource(R.string.transaction_list_clear_filters)) }
        }
    }
}
