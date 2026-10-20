package com.baraa.masroof.lab.beta.presentation.approved

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.ui.components.MasroofOutlineIcons
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading
import com.baraa.masroof.ui.theme.MasroofSpacing

/** ReviewListScreen content from production; the caller supplies lab decisions and paging. */
@Composable
internal fun ApprovedReviewListContent(items: List<ApprovedReviewListItem>,ignored: Boolean,enabled: Boolean,
    onMode: (Boolean)->Unit,onOpen: (Int)->Unit,afterContent: (@Composable ()->Unit)? = null,emptyContent: @Composable ()->Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom=MasroofSpacing.sectionGap),
            horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            ApprovedChoice(stringResource(R.string.beta_fix_review_pending),!ignored,{onMode(false)},enabled=enabled,
                modifier=Modifier.testTag("review-filter-pending"))
            ApprovedChoice(stringResource(R.string.beta_fix_review_informational),ignored,{onMode(true)},enabled=enabled,
                modifier=Modifier.testTag("review-filter-ignored"))
        }
        if(items.isEmpty()) Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {emptyContent()}
            afterContent?.invoke()
        } else LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            item {
            DashboardSectionHeading(stringResource(if(ignored) R.string.beta_approved_review_ignored_count else R.string.beta_approved_review_count,items.size),
                MasroofOutlineIcons.ShieldCheck,Modifier.padding(vertical=MasroofSpacing.sectionHeaderGap).testTag("review-count"))
            }
            itemsIndexed(items) {index,item -> ApprovedReviewListCard(item,enabled,Modifier.testTag("review-item-$index"),
                Modifier.testTag("history-review-$index")) {onOpen(index)}}
            if(afterContent!=null) item {afterContent()}
            item {Spacer(Modifier.height(MasroofSpacing.screenVertical))}
        }
    }
}
