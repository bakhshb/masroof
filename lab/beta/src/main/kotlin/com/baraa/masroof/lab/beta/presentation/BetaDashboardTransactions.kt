package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel as financialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedFormField as OutlinedTextField
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.application.evidence.betaAssetChoiceKey
import com.baraa.masroof.ui.theme.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.PostingKey

/** Source TransactionListScreen toolbar and independent list; no money totals are computed here. */
@Composable
internal fun BetaDashboardTransactions(snapshot: DashboardSnapshot, rows: List<TransactionFacts> = snapshot.transactions,
    enabled: Boolean, onTransaction: (PostingKey) -> Unit, complete: Boolean = true, bounded: Boolean = false,onShare: (BetaListShare?)->Unit = {},openGeneration: Int=0) {
    var search by rememberSaveable {mutableStateOf("")}
    var types by rememberSaveable {mutableStateOf(emptyList<String>())}
    var accounts by rememberSaveable {mutableStateOf(emptyList<String>())}
    var cards by rememberSaveable {mutableStateOf(emptyList<String>())}
    var sheet by rememberSaveable {mutableStateOf(false)}
    var appliedGeneration by rememberSaveable {mutableIntStateOf(openGeneration)}
    val labels=dashboardAssetLabels(snapshot).toMutableMap().apply {snapshot.accounts.forEach {put(it.id,betaAccountDisplayLabel(it))}}
    val availableTypes=remember(rows) {BetaTransactionListWorkflow.types(rows)}
    val availableAccounts=remember(snapshot,rows) {BetaTransactionListWorkflow.accounts(snapshot,rows)}
    val availableCards=remember(snapshot,rows) {BetaTransactionListWorkflow.cards(snapshot,rows)}
    val filter=BetaTransactionFilter(search,types.toSet(),accounts.toSet(),cards.toSet())
    val filtered=remember(rows,filter) {BetaTransactionListWorkflow.filter(rows,filter)}
    val share=betaListShare(snapshot,filtered,filter,labels,complete && enabled)
    SideEffect {onShare(share)}
    DisposableEffect(Unit) {onDispose {onShare(null)}}
    val allTypes=stringResource(R.string.beta_list_reference_all_types)
    val advanced=stringResource(R.string.beta_list_transaction_list_filter_sheet_title)
    val allSources=stringResource(R.string.beta_list_reference_all_sources)
    val typeChoices=listOf("" to allTypes)+availableTypes.map {it.name to stringResource(financialTypeLabel(it))}+listOf("advanced" to advanced)
    val sourceChoices=listOf("" to allSources)+(availableAccounts+availableCards).map {betaAssetChoiceKey(it) to labels[it].orEmpty()}
    fun clear() {search="";types=emptyList();accounts=emptyList();cards=emptyList();sheet=false}
    LaunchedEffect(openGeneration) {
        if(appliedGeneration!=openGeneration) {clear();appliedGeneration=openGeneration}
    }
    fun open(id: String) {rows.singleOrNull {it.key.transactionId==id}?.let {onTransaction(it.key)}}
    Column(if(bounded) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        if(rows.isEmpty() && complete) {
            Text(stringResource(R.string.beta_list_dashboard_empty_period),Modifier.fillMaxWidth().testTag("transactions-empty"),
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        } else {
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                ApprovedPeriodDisplay(approvedPeriodRange(snapshot.query.from.atZone(snapshot.query.zone).toLocalDate(),
                    snapshot.query.until.minusNanos(1).atZone(snapshot.query.zone).toLocalDate(),locale))
                OutlinedTextField(search,{search=it},Modifier.fillMaxWidth().testTag("transactions-search"),
                    placeholder={Text(stringResource(R.string.beta_list_transaction_list_search_hint),color=MaterialTheme.colorScheme.onSurfaceVariant)},singleLine=true)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                    ApprovedSelect(typeChoices.firstOrNull {it.first==types.singleOrNull()}?.second ?: allTypes,typeChoices.map {it.second},
                        {index -> if(typeChoices[index].first=="advanced") sheet=true else types=typeChoices[index].first.takeIf(String::isNotEmpty)?.let(::listOf).orEmpty()},
                        Modifier.weight(1f),enabled=enabled,tag="transactions-filters")
                    ApprovedSelect(sourceChoices.firstOrNull {it.first==(accounts+cards).singleOrNull()}?.second ?: allSources,sourceChoices.map {it.second},
                        {index -> val key=sourceChoices[index].first;accounts=availableAccounts.filter {betaAssetChoiceKey(it)==key}.map(::betaAssetChoiceKey);cards=availableCards.filter {betaAssetChoiceKey(it)==key}.map(::betaAssetChoiceKey)},
                        Modifier.weight(1f),enabled=enabled,tag="transactions-sources")
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.beta_list_dashboard_summary_transactions_title,filtered.size),style=MaterialTheme.typography.titleMedium)
                    if(filter.active) com.baraa.masroof.ui.components.MasroofTextButton(onClick=::clear,enabled=enabled,modifier=Modifier.testTag("transactions-clear")) {Text(stringResource(R.string.beta_list_transaction_list_clear_filters))}
                }
            }
            Spacer(Modifier.height(MasroofSpacing.cardInnerGap))
            if(!complete) Text(stringResource(R.string.beta_fix_confirmed_only),Modifier.testTag("transactions-unavailable"))
            Surface(modifier=if(bounded) Modifier.weight(1f) else Modifier.fillMaxWidth(),shape=MasroofCardShape,
                border=androidx.compose.foundation.BorderStroke(MasroofSpacing.borderWidth,MasroofThemeExtras.extendedColors.cardBorder)) {
                if(bounded) androidx.compose.foundation.lazy.LazyColumn(state=androidx.compose.foundation.lazy.rememberLazyListState(),
                    modifier=Modifier.padding(horizontal=MasroofSpacing.cardInnerPadding).testTag("transactions-list")) {
                    if(filtered.isEmpty()) item {TransactionNoResults()}
                    items(filtered.size,key={filtered[it].key.transactionId}) {index -> BetaMovementRow(snapshot,filtered[index],enabled,::open)}
                    item {Spacer(Modifier.height(MasroofSpacing.screenHorizontal))}
                } else Column(Modifier.padding(horizontal=MasroofSpacing.cardInnerPadding)) {
                    if(filtered.isEmpty()) TransactionNoResults()
                    filtered.forEach {BetaMovementRow(snapshot,it,enabled,::open)}
                    Spacer(Modifier.height(MasroofSpacing.screenHorizontal))
                }
            }
        }
    }
    if(sheet) {
        var draftTypes by rememberSaveable {mutableStateOf(types)}
        var draftAccounts by rememberSaveable {mutableStateOf(accounts)}
        var draftCards by rememberSaveable {mutableStateOf(cards)}
        var reset by rememberSaveable {mutableStateOf(false)}
        ApprovedBottomSheet(advanced,{sheet=false},{types=draftTypes;accounts=draftAccounts;cards=draftCards;if(reset) search="";sheet=false},
            stringResource(R.string.beta_list_transaction_list_filter_apply),resetLabel=stringResource(R.string.beta_list_transaction_list_clear_filters),
            onReset={draftTypes=emptyList();draftAccounts=emptyList();draftCards=emptyList();reset=true},
            applyTag="transactions-filter-apply",cancelTag="transactions-filter-cancel") {
            ApprovedGuidanceLabel(advanced,stringResource(R.string.beta_list_transaction_list_multi_filter_hint))
            if(availableTypes.isNotEmpty()) {
                Text(stringResource(R.string.beta_list_transaction_list_filter_type_section),style=MaterialTheme.typography.titleSmall)
                FilterChip(draftTypes.isEmpty(),{draftTypes=emptyList()},label={Text(stringResource(R.string.beta_list_transaction_list_filter_all_types))})
                MasroofSheetChoices(availableTypes,availableTypes.filter {it.name in draftTypes}.toSet(),{stringResource(financialTypeLabel(it))},
                    {value -> draftTypes=if(value.name in draftTypes) draftTypes-value.name else draftTypes+value.name},{"transaction-type-${it.name}"})
            }
            if(availableAccounts.isNotEmpty()) {
                Text(stringResource(R.string.beta_list_transaction_list_filter_account_section),style=MaterialTheme.typography.titleSmall)
                FilterChip(draftAccounts.isEmpty(),{draftAccounts=emptyList()},label={Text(stringResource(R.string.beta_list_transaction_list_filter_all_accounts))})
                MasroofSheetChoices(availableAccounts,availableAccounts.filter {betaAssetChoiceKey(it) in draftAccounts}.toSet(),{labels[it].orEmpty()},
                    {value -> val key=betaAssetChoiceKey(value);draftAccounts=if(key in draftAccounts) draftAccounts-key else draftAccounts+key},{"transaction-account-${betaAssetChoiceKey(it)}"})
            }
            if(availableCards.isNotEmpty()) {
                Text(stringResource(R.string.beta_list_transaction_list_filter_card_section),style=MaterialTheme.typography.titleSmall)
                FilterChip(draftCards.isEmpty(),{draftCards=emptyList()},label={Text(stringResource(R.string.beta_list_transaction_list_filter_all_cards))})
                MasroofSheetChoices(availableCards,availableCards.filter {betaAssetChoiceKey(it) in draftCards}.toSet(),{labels[it].orEmpty()},
                    {value -> val key=betaAssetChoiceKey(value);draftCards=if(key in draftCards) draftCards-key else draftCards+key},{"transaction-card-${betaAssetChoiceKey(it)}"})
            }
        }
    }
}

@Composable private fun TransactionNoResults() {
    Text(stringResource(R.string.beta_list_transaction_list_no_filter_results),Modifier.fillMaxWidth().padding(vertical=MasroofSpacing.contentPaddingLarge).testTag("transactions-empty"),
        style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable internal fun dashboardAssetLabels(snapshot: DashboardSnapshot): Map<AssetId, String> = buildMap {
    snapshot.accounts.forEachIndexed { index, row -> put(row.id, (row.label?.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_dash_account_number, index + 1)) + row.last4?.let {" · •• $it"}.orEmpty() + " · " + stringResource(if (row.id.bank.value == "BANK_ALJAZIRA") R.string.beta_bank_aljazira else R.string.beta_bank_title)) }
    snapshot.debitCards.forEach { put(it.cardId, com.baraa.masroof.lab.beta.application.approvedRegistryDisplayLabel(it.label,stringResource(R.string.beta_asset_debit),it.last4.orEmpty())) }
    snapshot.creditFacilities.forEach { row ->
        put(row.facilityId, stringResource(R.string.beta_asset_credit))
        row.cards.forEachIndexed { cardIndex, card -> put(card.cardId,betaCreditDisplayLabel(card)) }
    }
    snapshot.standaloneCreditCards.forEach { card -> put(card.cardId,betaCreditDisplayLabel(card)) }
    snapshot.loans.forEach { put(it.id, it.label.takeIf(String::isNotBlank) ?: stringResource(R.string.beta_asset_loan)) }
}
