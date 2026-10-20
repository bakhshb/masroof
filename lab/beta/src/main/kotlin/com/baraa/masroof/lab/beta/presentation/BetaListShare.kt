package com.baraa.masroof.lab.beta.presentation

import android.content.Intent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.evidence.betaAssetChoiceKey
import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.approvedPeriodRange
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.ui.components.MasroofOutlineIcons
import java.time.format.DateTimeFormatter

/** Value payload avoids stale callbacks and recomposition loops in the shared header. */
data class BetaListShare(val subject: String,val text: String,val enabled: Boolean)

/** Source ShareActionIcon and Android text chooser; only a user click opens the chooser. */
@Composable internal fun BetaListShareAction(payload: BetaListShare) {
    val context=LocalContext.current
    val chooser=stringResource(R.string.beta_list_transaction_share)
    IconButton(onClick={context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type="text/plain";putExtra(Intent.EXTRA_SUBJECT,payload.subject);putExtra(Intent.EXTRA_TEXT,payload.text)
    },chooser))},enabled=payload.enabled,modifier=Modifier.testTag("transactions-share")) {
        Icon(MasroofOutlineIcons.Share2,chooser)
    }
}

/** Source TransactionShareContent list document. Aggregation stays in the application workflow. */
@Composable internal fun betaListShare(snapshot: DashboardSnapshot,rows: List<TransactionFacts>,filter: BetaTransactionFilter,
    labels: Map<AssetId,String>,enabled: Boolean): BetaListShare {
    val locale=LocalConfiguration.current.locales[0]
    val period=approvedPeriodRange(snapshot.query.from.atZone(snapshot.query.zone).toLocalDate(),snapshot.query.until.minusNanos(1).atZone(snapshot.query.zone).toLocalDate(),locale)
    val title=stringResource(R.string.beta_list_transaction_share_list_heading,stringResource(R.string.beta_home_brand),period)
    val typeLabels=filter.types.mapNotNull {runCatching {FinancialType.valueOf(it)}.getOrNull()}.map {stringResource(approvedFinancialTypeLabel(it))}.sorted()
    val cards=labels.filterKeys {betaAssetChoiceKey(it) in filter.cards}.values.sorted()
    val accounts=labels.filterKeys {betaAssetChoiceKey(it) in filter.accounts}.values.sorted()
    val meta=mutableListOf<String>()
    if(filter.search.isNotBlank()) meta+=stringResource(R.string.beta_list_transaction_share_search)+": "+filter.search.trim()
    if(typeLabels.isNotEmpty()) meta+=stringResource(R.string.beta_list_transaction_share_types)+": "+typeLabels.joinToString(" • ")
    if(cards.isNotEmpty()) meta+=stringResource(R.string.beta_list_transaction_share_cards)+": "+cards.joinToString(" • ")
    if(accounts.isNotEmpty()) meta+=stringResource(R.string.beta_list_transaction_share_accounts)+": "+accounts.joinToString(" • ")
    meta+=stringResource(R.string.beta_list_transaction_share_count)+": "+rows.size
    BetaTransactionListWorkflow.shareTotal(rows,snapshot.query.currency)?.let {meta+=stringResource(R.string.beta_list_transaction_share_total)+": "+betaSummaryMoney(it)}
    val lines=rows.map {row ->
        val type=stringResource(approvedFinancialTypeLabel(row.effectiveType))
        listOf(DateTimeFormatter.ofPattern("d MMM",locale).format(row.time.instant.atZone(row.time.zone)),
            row.merchant ?: row.counterparty ?: type,betaSummaryMoney(row.amount),type).joinToString(" • ")
    }
    return BetaListShare(stringResource(R.string.beta_list_transaction_list_title),BetaDisplayDigits.display(
        (listOf(title)+meta+if(lines.isEmpty()) emptyList() else listOf("")+lines).joinToString("\n")),enabled && rows.isNotEmpty())
}
