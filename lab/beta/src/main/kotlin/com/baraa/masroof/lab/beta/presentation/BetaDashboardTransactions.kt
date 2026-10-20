package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel as financialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeIcon as reviewTypeIcon
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedFormField as OutlinedTextField
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.PostingKey
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BetaDashboardTransactions(snapshot: DashboardSnapshot, rows: List<TransactionFacts> = snapshot.transactions,
    enabled: Boolean, onTransaction: (PostingKey) -> Unit, complete: Boolean = true) {
    var limit by rememberSaveable(snapshot.query.from.toString()) { mutableIntStateOf(20) }
    var search by rememberSaveable {mutableStateOf("")}
    var type by rememberSaveable {mutableStateOf<String?>(null)}
    var assetBank by rememberSaveable {mutableStateOf<String?>(null)}
    var assetLocal by rememberSaveable {mutableStateOf<String?>(null)}
    var filters by rememberSaveable {mutableStateOf(false)}
    val labels=dashboardAssetLabels(snapshot)
    val asset=labels.keys.singleOrNull {it.bank.value==assetBank && it.localId==assetLocal}
    val filtered=remember(rows,search,type,asset) {BetaDashboardExplorer.filterTransactions(rows,search,type,asset)}
    Text(stringResource(R.string.beta_transaction_title),style=MaterialTheme.typography.headlineSmall)
    OutlinedTextField(search,{search=it;limit=20},label={Text(stringResource(R.string.beta_fix_search))},
        singleLine=true,modifier=Modifier.fillMaxWidth().testTag("transactions-search"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
    TextButton(onClick={filters=true},enabled=enabled,modifier=Modifier.testTag("transactions-filters")) {Text(stringResource(R.string.beta_fix_filters))}
    if(filters) {
        var draftType by remember {mutableStateOf(type)}
        var draftAsset by remember {mutableStateOf(asset)}
        BetaBottomSheet(stringResource(R.string.beta_fix_filters),false,{filters=false},{type=draftType;assetBank=draftAsset?.bank?.value;assetLocal=draftAsset?.localId;limit=20;filters=false}) {
            val types=listOf<String?>(null)+rows.map {it.effectiveType.name}.distinct()
            MasroofSheetChoices(types,setOf(draftType),{value -> if(value==null) stringResource(R.string.beta_fix_all) else stringResource(financialTypeLabel(com.baraa.masroof.lab.finance.posting.FinancialType.valueOf(value)))},
                {draftType=it},{"transaction-type-${it ?: "all"}"})
            Text(stringResource(R.string.beta_fix_filter_account))
            val choices=listOf<AssetId?>(null)+labels.keys.toList()
            MasroofSheetChoices(choices,setOf(draftAsset),{value -> value?.let {labels[it]}.orEmpty().ifEmpty {stringResource(R.string.beta_fix_all)}},
                {draftAsset=it},{"transaction-asset-${it?.bank?.value}-${it?.localId}"})
        }
    }
    if(!complete) Text(stringResource(R.string.beta_fix_confirmed_only),Modifier.testTag("transactions-unavailable"))
    if(filtered.isEmpty()) Text(stringResource(if(rows.isEmpty()) R.string.beta_dash_empty else R.string.beta_fix_no_matches),Modifier.testTag("transactions-empty"))
    com.baraa.masroof.ui.components.MasroofCard {
        filtered.take(limit).forEach {row ->
            com.baraa.masroof.ui.dashboard.DashboardTransactionRow(
                row.merchant ?: row.counterparty ?: stringResource(financialTypeLabel(row.effectiveType)),
                stringResource(financialTypeLabel(row.effectiveType)) + row.primaryCardLast4?.let {" · •• $it"}.orEmpty(),
                betaMoney(row.amount.amount.toPlainString(),row.amount.currency.name),betaDate(row.time.instant,row.time.zone),
                com.baraa.masroof.ui.components.MasroofOutlineIcons.List,
                MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.secondaryContainer,
                Modifier.testTag("dash-transaction-${row.key.transactionId}"),onClick=if(enabled) ({onTransaction(row.key)}) else null)
        }
    }
    if(filtered.size>limit) OutlinedButton(onClick={limit+=20},modifier=Modifier.testTag("transactions-more")) {Text(stringResource(R.string.beta_dash_more))}

}

@Composable
internal fun BetaDashboardTransactionDetail(snapshot: DashboardSnapshot, transactionId: String) {
    val tx = snapshot.transactions.singleOrNull { it.key.transactionId == transactionId }
    if (tx == null) { Text(stringResource(R.string.beta_dash_missing), modifier = Modifier.testTag("transaction-missing")); return }
    val assets = dashboardAssetLabels(snapshot)
    Column(Modifier.testTag("transaction-detail"),verticalArrangement=Arrangement.spacedBy(com.baraa.masroof.ui.theme.MasroofSpacing.sectionGap)) {
        com.baraa.masroof.ui.components.MasroofHeroCard(
            tx.merchant ?: tx.counterparty ?: stringResource(financialTypeLabel(tx.effectiveType)),
            betaMoney(tx.amount.amount.toPlainString(),tx.amount.currency.name),
            subtitle=stringResource(financialTypeLabel(tx.effectiveType)),role=com.baraa.masroof.ui.theme.MasroofHeroRole.Account,
            icon=reviewTypeIcon(tx.effectiveType))
        com.baraa.masroof.ui.components.MasroofCard {
            Text(stringResource(R.string.beta_complete_transactions_data),style=MaterialTheme.typography.titleMedium)
            BetaFinancialDetailRow(stringResource(R.string.beta_review_type),stringResource(financialTypeLabel(tx.effectiveType)))
            BetaFinancialDetailRow(stringResource(R.string.beta_complete_review_date,betaDate(tx.time.instant,tx.time.zone)),"")
            tx.merchant?.let {BetaFinancialDetailRow(stringResource(R.string.beta_transaction_merchant),it)}
            tx.counterparty?.let {BetaFinancialDetailRow(stringResource(R.string.beta_transaction_counterparty),it)}
            BetaFinancialDetailRow(stringResource(R.string.beta_transaction_source),tx.source?.let(assets::get) ?: stringResource(R.string.beta_transaction_external_unknown))
            BetaFinancialDetailRow(stringResource(R.string.beta_transaction_destination),tx.destination?.let(assets::get) ?: stringResource(R.string.beta_transaction_external_unknown))
            if(tx.amount.currency!=com.baraa.masroof.lab.finance.value.Currency.SAR) {
                BetaFinancialDetailRow(stringResource(R.string.beta_transaction_sar_free),betaSummaryMoney(tx.sarEquivalent))
                BetaFinancialDetailRow(stringResource(R.string.beta_transaction_fx),tx.appliedFx?.rateToSar?.toPlainString() ?: stringResource(R.string.beta_approved_dashboard_value_unavailable))
            }
            tx.internationalFee?.let {BetaFinancialDetailRow(stringResource(R.string.beta_transaction_fee),betaSummaryMoney(it))}
        }
    }
}

@Composable private fun dashboardAmount(value: SignedAmount?) = value?.let { betaMoney(it.amount.toPlainString(),it.currency.name) } ?: stringResource(R.string.beta_approved_dashboard_value_unavailable)

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
