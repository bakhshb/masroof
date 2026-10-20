package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.ui.theme.*

/** Source TransactionReclassifyBottomSheet; allowed choices remain owned by the application gate. */
@Composable
internal fun BetaTransactionReclassifySheet(current: FinancialType,selected: FinancialType?,allowed: List<FinancialType>,busy: Boolean,
    onDismiss: ()->Unit,onSelect: (FinancialType)->Unit,onSave: ()->Unit) {
    BetaBottomSheet(stringResource(R.string.beta_detail_transaction_reclassify_sheet_title),busy,onDismiss,onSave,
        canApply=selected!=null && selected!=current && selected in allowed && !busy,
        applyTag="transaction-type-save",cancelTag="transaction-type-cancel",applyLabel=stringResource(R.string.beta_detail_transaction_reclassify_sheet_save)) {
        selected?.takeIf {it!=current}?.let {pending ->
            Text(stringResource(R.string.beta_detail_transaction_reclassify_preview,stringResource(approvedFinancialTypeLabel(current)),stringResource(approvedFinancialTypeLabel(pending))),
                Modifier.padding(horizontal=MasroofSpacing.screenHorizontal,vertical=MasroofSpacing.sectionHeaderGap),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth()) {
            (allowed+current).distinct().sortedBy {it.name}.forEach {type ->
                val isCurrent=type==current
                Row(Modifier.fillMaxWidth().testTag("transaction-type-${type.name}")
                    .clickable(enabled=!isCurrent && !busy) {onSelect(type)}
                    .padding(horizontal=MasroofSpacing.sectionHeaderGap,vertical=MasroofSpacing.inlineGap),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(type==(selected ?: current),{if(!isCurrent) onSelect(type)},enabled=!isCurrent && !busy)
                    Icon(approvedFinancialTypeIcon(type),null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(MasroofIconSizes.lg))
                    Spacer(Modifier.width(MasroofSpacing.carouselGap))
                    Text(stringResource(approvedFinancialTypeLabel(type)),style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
                    if(isCurrent) Text(stringResource(R.string.beta_detail_transaction_reclassify_current_badge),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
