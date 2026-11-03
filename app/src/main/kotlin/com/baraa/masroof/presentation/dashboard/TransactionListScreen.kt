package com.baraa.masroof.presentation.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.baraa.masroof.ui.theme.MasroofSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.ShareActionIcon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    periodLabel: String,
    transactions: List<TransactionPreviewUi>,
    projection: TransactionListProjectionUi,
    onFilterChanged: (TransactionListFilterState) -> Unit,
    onOpenTransaction: (String) -> Unit,
    ownedCards: List<OwnedCardUi> = emptyList(),
    ownedAccounts: List<OwnedAccountUi> = emptyList(),
    /** The period shown in the pill; [periodLabel] stays the full label for shared text. */
    periodRangeLabel: String = periodLabel,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTypeNames by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedCardLast4s by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedAccountContainerIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var filtersSheetOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val filterState = remember(
        searchQuery,
        selectedTypeNames,
        selectedCardLast4s,
        selectedAccountContainerIds,
    ) {
        TransactionListFilterState(
            searchQuery = searchQuery,
            types = selectedTypeNames.mapNotNull { name ->
                runCatching { FinancialTransactionType.valueOf(name) }.getOrNull()
            }.toSet(),
            cardLast4s = selectedCardLast4s.toSet(),
            accountContainerIds = selectedAccountContainerIds.toSet(),
        )
    }

    LaunchedEffect(filterState) { onFilterChanged(filterState) }
    val filterResult = projection.result
    val availableTypes = projection.availableTypes
    val availableCards = projection.availableCards
    val availableAccounts = projection.availableAccounts
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.transaction_share)
    val shareSubject = stringResource(R.string.transaction_list_title)
    val canShare = filterResult.transactions.isNotEmpty()

    val clearFilters = {
        searchQuery = ""
        selectedTypeNames = emptyList()
        selectedCardLast4s = emptyList()
        selectedAccountContainerIds = emptyList()
        filtersSheetOpen = false
    }

    MasroofSecondaryScaffold(
        title = stringResource(R.string.transaction_list_title),
        onBack = null,
        backContentDescription = null,
        actions = {
            ShareActionIcon(
                enabled = canShare,
                onClick = {
                    SharePlainText.share(
                        context = context,
                        text = transactionListShareText(
                            context = context,
                            periodLabel = periodLabel,
                            filter = filterState,
                            transactions = filterResult.transactions,
                            totalAmount = filterResult.totalAmount,
                            ownedCards = ownedCards,
                            ownedAccounts = ownedAccounts,
                        ),
                        chooserTitle = shareChooserTitle,
                        subject = shareSubject,
                    )
                },
            )
        },
    ) { contentModifier ->
        if (transactions.isEmpty()) {
            Column(
                modifier = contentModifier
                    .fillMaxSize()
                    .padding(MasroofSpacing.screenPaddingLarge),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.dashboard_empty_period),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            return@MasroofSecondaryScaffold
        }

        val compactHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat() < com.baraa.masroof.ui.theme.MasroofReference.compactScreenHeight.value
        val header = remember { CollapsingHeader() }
        header.enabled = compactHeight
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        header.onCollapse = { focusManager.clearFocus() }
        Column(
            modifier = contentModifier.fillMaxSize().then(if (compactHeight) Modifier.nestedScroll(header) else Modifier),
        ) {
            Column(header.layoutModifier) {
                TransactionListToolbar(
                    periodLabel = periodRangeLabel,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onOpenFilters = { filtersSheetOpen = true },
                    onClearFilters = clearFilters,
                    filteredCount = filterResult.transactions.size,
                    filterActive = filterState.isActive,
                    typeChoices = availableTypes.map { it.name to transactionTypeLabel(it) },
                    selectedType = selectedTypeNames.singleOrNull(),
                    onSelectType = { name -> selectedTypeNames = if (name.isEmpty()) emptyList() else listOf(name) },
                    sourceChoices = availableAccounts.map { "account:$it" to (accountDisplayLabel(ownedAccounts, it) ?: it) } +
                        availableCards.map { "card:$it" to cardDisplayLabel(ownedCards, it) },
                    selectedSource = selectedAccountContainerIds.singleOrNull()?.let { "account:$it" }
                        ?: selectedCardLast4s.singleOrNull()?.let { "card:$it" },
                    onSelectSource = { key ->
                        selectedAccountContainerIds = if (key.startsWith("account:")) listOf(key.removePrefix("account:")) else emptyList()
                        selectedCardLast4s = if (key.startsWith("card:")) listOf(key.removePrefix("card:")) else emptyList()
                    },
                )
                Spacer(Modifier.height(MasroofSpacing.cardInnerGap))
            }

            androidx.compose.material3.Surface(modifier = Modifier.weight(1f),
                shape = com.baraa.masroof.ui.theme.MasroofCardShape,
                border = androidx.compose.foundation.BorderStroke(MasroofSpacing.borderWidth, com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.cardBorder)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(horizontal = MasroofSpacing.cardInnerPadding),
                verticalArrangement = Arrangement.spacedBy(MasroofSpacing.none),
            ) {
                if (filterResult.transactions.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.transaction_list_no_filter_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = MasroofSpacing.contentPaddingLarge),
                        )
                    }
                } else {
                    items(filterResult.transactions, key = { it.id }) { row ->
                        TransactionPreviewRow(
                            row = row,
                            ownedCards = ownedCards,
                            onClick = { onOpenTransaction(row.id) },
                        )
                    }
                }

                item { Spacer(Modifier.height(MasroofSpacing.screenHorizontal)) }
            }
            }
        }

        TransactionListFilterBottomSheet(
            visible = filtersSheetOpen,
            onDismiss = { filtersSheetOpen = false },
            filterState = filterState,
            availableTypes = availableTypes,
            availableCards = availableCards,
            availableAccounts = availableAccounts,
            ownedCards = ownedCards,
            ownedAccounts = ownedAccounts,
            onApply = { draft ->
                selectedTypeNames = draft.types.map { it.name }
                selectedCardLast4s = draft.cardLast4s.toList()
                selectedAccountContainerIds = draft.accountContainerIds.toList()
                searchQuery = draft.searchQuery
            },
        )
    }
}

/**
 * Short screens slide the filter header away while the list scrolls and bring it back at the top.
 * The header stays composed, so a focused search field keeps its focus and keyboard.
 */
private class CollapsingHeader : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
    private var height = 0
    private var offset by androidx.compose.runtime.mutableFloatStateOf(0f)
    var enabled by androidx.compose.runtime.mutableStateOf(false)
    // A collapsed header must not keep a hidden search field focused.
    var onCollapse: () -> Unit = {}

    val layoutModifier: Modifier = Modifier.clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        height = placeable.height
        offset = if (enabled) offset.coerceIn(-height.toFloat(), 0f) else 0f
        layout(placeable.width, (placeable.height + offset).roundToInt()) { placeable.place(0, offset.roundToInt()) }
    }

    private fun consume(delta: Float): androidx.compose.ui.geometry.Offset {
        val next = (offset + delta).coerceIn(-height.toFloat(), 0f)
        val consumed = next - offset
        offset = next
        return androidx.compose.ui.geometry.Offset(0f, consumed)
    }

    // Collapse before the list scrolls up; expand only with what the list left over at its top.
    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
        if (available.y < 0) consume(available.y).also { if (it.y != 0f) onCollapse() } else androidx.compose.ui.geometry.Offset.Zero

    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
        if (available.y > 0) consume(available.y) else androidx.compose.ui.geometry.Offset.Zero
}
