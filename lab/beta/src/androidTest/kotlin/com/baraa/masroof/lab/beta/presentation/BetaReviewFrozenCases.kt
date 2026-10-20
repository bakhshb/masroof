package com.baraa.masroof.lab.beta.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.ui.components.MasroofOutlineIcons
import com.baraa.masroof.ui.theme.MasroofSpacing
import java.time.Instant

internal val betaReviewCaptureCases=setOf("reviewDetail","reviewIgnored","reviewOwnership","reviewTransfer","reviewBusy")

/** Same ready display facts/flags passed to the original private renderer in the pinned reference. */
@Composable internal fun BetaReviewFrozenCase(page: String) {
    val ignored=page=="reviewIgnored";val ownership=page=="reviewOwnership";val transfer=page=="reviewTransfer";val busy=page=="reviewBusy"
    val view=BetaReviewDetailView(if(transfer) "TRANSFER_IN" else "PURCHASE",
        stringResource(if(ignored) R.string.beta_review_parity_ignored else if(transfer) R.string.beta_approved_review_pending_match else R.string.beta_review_parity_kind),
        betaMoney("50","SAR"),"ALJAZIRA",betaReviewDate(Instant.parse("2026-10-08T10:00:00Z")),
        if(ignored) betaReviewDate(Instant.parse("2026-10-09T10:00:00Z")) else null,
        if(transfer) null else "NOON",if(transfer) "Sender" else null,
        listOf(stringResource(when {ignored->R.string.beta_review_detail_review_reason_user_ignored_transaction;ownership->R.string.beta_review_detail_review_reason_purchase_ownership_unknown;transfer->R.string.beta_review_detail_review_reason_transfer_pending_match;else->R.string.beta_review_detail_review_reason_needs_review})),
        if(transfer) "حوالة واردة\nحساب: 3001\nمبلغ: SAR 50.00\nمن: Sender" else "شراء عبر الإنترنت\nبطاقة: 7271\nمبلغ: SAR 50.00\nلدى: NOON",ignored)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("review-detail-fixture-ready"),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        BetaReviewDetailContent(view,restoration={
            BetaReviewActionButton(stringResource(R.string.beta_review_parity_restore),MasroofOutlineIcons.CircleCheck,!busy,"review-restore") {}
            BetaReviewFinancialActions(betaReviewFinancialTypes,busy,restore=true) {}
        },ownership={
            if(ownership) BetaReviewOwnershipPrompt(BetaOwnershipCandidate(BetaOwnershipReference(BankId("BANK_ALJAZIRA"),BetaOwnershipKind.CARD,"7271"),
                BetaOwnershipStatus.UNKNOWN,"fixture",null,emptyList(),false),busy,{_,_->},{})
        },actions={
            if(!ignored) {
                if(!transfer) BetaReviewActionButton(stringResource(R.string.beta_review_parity_ignore),MasroofOutlineIcons.TriangleAlert,!busy,"review-dismiss") {}
                if(!ownership) BetaReviewFinancialActions(if(transfer) listOf(FinancialType.EXTERNAL_TRANSFER_IN,FinancialType.INCOME) else betaReviewFinancialTypes,busy,family=view.family) {}
            }
        },feedback={BetaReviewFeedback(busy,false,null)})
    }
}
