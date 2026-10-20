package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.evidence.BetaReviewDetail
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading
import com.baraa.masroof.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Source ReviewDetailUi display contract; actions remain application-bound slots. */
internal data class BetaReviewDetailView(val family: String?,val kind: String,val amount: String?,val sender: String?,
    val date: String,val ignoredAt: String?,val merchant: String?,val counterparty: String?,val reasons: List<String>,
    val body: String,val showRestore: Boolean)

@Composable internal fun betaReviewDate(at: Instant?): String = at?.atZone(ZoneId.systemDefault())?.toLocalDate()?.let {
    DateTimeFormatter.ofPattern("d MMM yyyy",LocalConfiguration.current.locales[0]).format(it)
} ?: "—"

internal fun betaReviewKindLabel(ignored: Boolean,reason: String?): Int = when {
    ignored->R.string.beta_review_parity_ignored
    reason=="AMBIGUOUS_TRANSFER_NOTICES" || reason=="AMBIGUOUS_MATCH"->R.string.beta_approved_review_pending_match
    else->R.string.beta_review_parity_kind
}

@Composable internal fun betaReviewDetailView(detail: BetaReviewDetail,ignored: Boolean): BetaReviewDetailView {
    val row=detail.row
    return BetaReviewDetailView(row.family,stringResource(betaReviewKindLabel(ignored || row.dismissed,row.reason)),
        row.amount?.let {betaMoney(it,row.currency)},row.sender,betaReviewDate(row.receivedAt),detail.resolvedAt?.let {betaReviewDate(it)},
        detail.merchant,detail.counterparty,reviewReasonLabels(detail).map {stringResource(it)},row.body,row.dismissed)
}

/** Verbatim source order/geometry; caller owns the one scroll container and financial writes. */
@Composable
internal fun ColumnScope.BetaReviewDetailContent(view: BetaReviewDetailView,
    restoration: @Composable ColumnScope.()->Unit = {},ownership: @Composable ColumnScope.()->Unit = {},
    actions: @Composable ColumnScope.()->Unit = {},feedback: @Composable ColumnScope.()->Unit = {}) {
    MasroofCard {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(reviewFamilyIcon(view.family),null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(MasroofIconSizes.logo))
            Spacer(Modifier.size(MasroofSpacing.carouselGap))
            Text(view.kind,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)
        }
        view.amount?.let {ApprovedAmountText(it,ApprovedAmountRole.Hero,Modifier.padding(top=MasroofSpacing.sectionHeaderGap))}
    }
    view.sender?.let {ApprovedIconLabelRow(MasroofOutlineIcons.Bell,stringResource(R.string.beta_complete_review_sender,it))}
    ApprovedIconLabelRow(MasroofOutlineIcons.CalendarCheck,stringResource(R.string.beta_complete_review_date,view.date))
    view.ignoredAt?.let {ApprovedIconLabelRow(MasroofOutlineIcons.CircleCheck,stringResource(R.string.beta_review_parity_ignored_at,it))}
    if(view.showRestore) {
        MasroofCard {ApprovedGuidanceLabel(stringResource(R.string.beta_review_parity_restore_title),stringResource(R.string.beta_review_parity_restore_hint))}
        DashboardSectionHeading(stringResource(R.string.beta_review_parity_restore_title),MasroofOutlineIcons.ShieldCheck)
        restoration()
    }
    view.merchant?.let {ApprovedIconLabelRow(MasroofOutlineIcons.Store,stringResource(R.string.beta_review_parity_merchant,it))}
    view.counterparty?.let {ApprovedIconLabelRow(MasroofOutlineIcons.User,stringResource(R.string.beta_review_parity_counterparty,it))}
    DashboardSectionHeading(stringResource(R.string.beta_complete_review_reasons),MasroofOutlineIcons.TriangleAlert)
    view.reasons.forEach {Text("• $it",color=MaterialTheme.colorScheme.onSurface)}
    ownership()
    DashboardSectionHeading(stringResource(R.string.beta_review_detail_review_sms_body),MasroofOutlineIcons.MessageSquare)
    MasroofCard {Text(view.body,textAlign=TextAlign.Start,color=MaterialTheme.colorScheme.onSurface)}
    actions()
    feedback()
}

@Composable
internal fun ColumnScope.BetaReviewFeedback(busy: Boolean,failed: Boolean,reason: String?,onReload: (() -> Unit)? = null) {
    if(failed) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(MasroofOutlineIcons.CircleAlert,null,tint=MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(MasroofSpacing.compactCardLabelTop))
            Text(stringResource(R.string.beta_review_detail_review_action_failed),color=MaterialTheme.colorScheme.error)
        }
        reason?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        onReload?.let {reload -> ApprovedOutlinedButton(enabled=!busy,onClick=reload) {Text(stringResource(R.string.beta_controls_reload))}}
    }
    if(busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
}
