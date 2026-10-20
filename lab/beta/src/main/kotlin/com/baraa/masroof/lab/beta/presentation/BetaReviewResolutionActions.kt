package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel as financialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeIcon as reviewTypeIcon
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import com.baraa.masroof.lab.beta.presentation.approved.*

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.lab.finance.asset.OwnedAsset
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading
import com.baraa.masroof.ui.theme.*

@Composable
internal fun BetaReviewResolutionActions(detail: BetaReviewDetail,busy: Boolean,
    onApprove: (BetaFinancialApproval)->Unit,onRetryPending: ()->Unit,onCancelPending: ()->Unit,restore: Boolean = false) {
    val preview=detail.preview ?: return
    if(preview.pending!=null) {
        MasroofCard {Text(stringResource(R.string.beta_fix_saved_decision));Text(stringResource(financialTypeLabel(preview.pending.type)))}
        OutlinedButton(enabled=!busy && preview.blockedReason==null,onClick=onRetryPending,modifier=Modifier.fillMaxWidth().testTag("review-resume-decision")) {Text(stringResource(R.string.beta_retry))}
        TextButton(enabled=!busy,onClick=onCancelPending,modifier=Modifier.testTag("review-cancel-decision")) {Text(stringResource(R.string.beta_approval_cancel_pending))}
        return
    }
    if(preview.blockedReason!=null || preview.correctionOwned) return
    fun choice(asset: com.baraa.masroof.lab.finance.asset.AssetId?)=asset?.let {betaAssetChoiceKey(it)}
    val incoming=detail.row.family in setOf("TRANSFER_IN","REFUND")
    val destinationNeeded=detail.row.family in setOf("FINANCING_INSTALLMENT","CARD_PAYMENT")
    var source by rememberSaveable(detail.row.id) {mutableStateOf(choice(detail.source) ?: "external".takeIf {incoming})}
    var destination by rememberSaveable(detail.row.id) {mutableStateOf(choice(detail.destination) ?: "external".takeIf {!incoming && !destinationNeeded})}
    var selectedType by rememberSaveable(detail.row.id) {mutableStateOf<String?>(null)}
    var selector by rememberSaveable(detail.row.id) {mutableStateOf<String?>(null)}
    var draftChoice by rememberSaveable(detail.row.id) {mutableStateOf<String?>(null)}
    var draftBinding by rememberSaveable(detail.row.id) {mutableStateOf<String?>(null)}
    val currentDraft=draftBinding==preview.confirmationKey
    fun request(type: String)=betaApprovalDraft(preview,type,source,destination,detail.income?.name).takeIf {currentDraft}
    BetaReviewFinancialActions(detail.actions,busy,restore,detail.row.family) {type ->
        val endpoints=betaReviewDefaultEndpoints(detail,type)
        val ready=betaApprovalDraft(preview,type.name,endpoints.first,endpoints.second,detail.income?.name)
        if(ready!=null) onApprove(ready) else {
            draftBinding=preview.confirmationKey;selectedType=type.name;source=endpoints.first;destination=endpoints.second
        }
    }
    if(selectedType!=null) MasroofCard {
        Text(stringResource(R.string.beta_complete_review_choose_asset),style=MaterialTheme.typography.titleMedium)
        if(!currentDraft) {
            Text(stringResource(R.string.beta_controls_conflict),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("review-draft-stale"))
            ApprovedOutlinedButton(enabled=!busy,onClick={selectedType=null;selector=null;draftChoice=null;draftBinding=null},modifier=Modifier.testTag("review-reset-draft")) {Text(stringResource(R.string.beta_controls_reload))}
        }
        fun label(value: String?)=if(value=="external") null else preview.assets.singleOrNull {betaAssetChoiceKey(it.id)==value}
        @Composable fun endpoint(value: String?,field: String,title: Int) {
            OutlinedButton(enabled=!busy,onClick={selector=field;draftChoice=value},modifier=Modifier.fillMaxWidth().testTag("review-select-$field")) {
                val asset=label(value)
                Text(stringResource(title)+": "+if(asset!=null) reviewAssetLabel(asset,detail) else stringResource(if(value=="external") R.string.beta_review_external else R.string.beta_complete_review_choose_asset))
            }
        }
        endpoint(source,"source",R.string.beta_review_source);endpoint(destination,"destination",R.string.beta_review_destination)
        val ready=request(checkNotNull(selectedType))
        Button(enabled=!busy && ready!=null,onClick={ready?.let(onApprove)},modifier=Modifier.fillMaxWidth().testTag("review-confirm-resolution")) {Text(stringResource(R.string.beta_complete_review_save))}
    }
    if(selector!=null) BetaBottomSheet(stringResource(R.string.beta_complete_review_choose_asset),busy,
        onCancel={selector=null},onApply={if(selector=="source") source=draftChoice else destination=draftChoice;selector=null},
        canApply=draftChoice!=null && currentDraft,applyTag="review-asset-apply",cancelTag="review-asset-cancel") {
        val type=selectedType?.let(FinancialType::valueOf)
        val sourceField=selector=="source"
        val incomingType=type in setOf(FinancialType.INCOME,FinancialType.REFUND,FinancialType.EXTERNAL_TRANSFER_IN)
        val externalAllowed=if(sourceField) incomingType else !incomingType && type !in setOf(FinancialType.LOAN_REPAYMENT,FinancialType.CREDIT_CARD_PAYMENT)
        val kinds=when {
            sourceField && incomingType -> emptySet()
            !sourceField && type==FinancialType.LOAN_REPAYMENT -> setOf(com.baraa.masroof.lab.finance.asset.AssetKind.LOAN)
            !sourceField && type==FinancialType.CREDIT_CARD_PAYMENT -> setOf(com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD)
            (!sourceField && !incomingType) -> emptySet()
            type in setOf(FinancialType.LOAN_REPAYMENT,FinancialType.CREDIT_CARD_PAYMENT,FinancialType.INCOME,FinancialType.EXTERNAL_TRANSFER_IN,FinancialType.EXTERNAL_TRANSFER_OUT,FinancialType.CASH_WITHDRAWAL) -> setOf(com.baraa.masroof.lab.finance.asset.AssetKind.ACCOUNT)
            else -> setOf(com.baraa.masroof.lab.finance.asset.AssetKind.ACCOUNT,com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD)
        }
        val choices=(if(externalAllowed) listOf("external") else emptyList())+preview.assets.filter {it.kind in kinds}.map {betaAssetChoiceKey(it.id)}
        MasroofSheetChoices(choices,setOfNotNull(draftChoice),{key ->
            if(key=="external") stringResource(R.string.beta_review_external) else reviewAssetLabel(preview.assets.single {betaAssetChoiceKey(it.id)==key},detail)
        },{draftChoice=it},{"review-endpoint-$it"},enabled=!busy)
    }
}

@Composable
private fun reviewAssetLabel(asset: OwnedAsset,detail: BetaReviewDetail): String =
    (detail.assetMetadata[asset.id]?.displayName ?: stringResource(assetKindLabel(asset.kind))) +
        asset.last4?.let {" · •• $it"}.orEmpty()+" · "+stringResource(bankName(asset.id.bank))

@Composable
internal fun BetaReviewMeta(icon: ImageVector,label: String) = ApprovedIconLabelRow(icon,label)

internal fun reviewFamilyLabel(family: String?): Int = when(family) {
    "PURCHASE" -> financialTypeLabel(FinancialType.EXPENSE); "REFUND" -> financialTypeLabel(FinancialType.REFUND)
    "FINANCING_INSTALLMENT" -> financialTypeLabel(FinancialType.LOAN_REPAYMENT); "BILL_PAYMENT" -> financialTypeLabel(FinancialType.BILL_PAYMENT)
    "TRANSFER_IN" -> financialTypeLabel(FinancialType.EXTERNAL_TRANSFER_IN); "TRANSFER_OUT" -> financialTypeLabel(FinancialType.EXTERNAL_TRANSFER_OUT)
    "CARD_PAYMENT" -> financialTypeLabel(FinancialType.CREDIT_CARD_PAYMENT); "WITHDRAWAL" -> financialTypeLabel(FinancialType.CASH_WITHDRAWAL)
    "FEE" -> financialTypeLabel(FinancialType.FEE); else -> R.string.beta_review_required
}
internal fun reviewFamilyIcon(family: String?): ImageVector = when(family) {
    "PURCHASE" -> MasroofOutlineIcons.ShoppingCart
    "TRANSFER_IN" -> MasroofOutlineIcons.ArrowDownLeft
    "TRANSFER_OUT" -> MasroofOutlineIcons.ArrowUpRight
    "BILL_PAYMENT","CARD_PAYMENT" -> MasroofOutlineIcons.CreditCard
    "REFUND" -> MasroofOutlineIcons.Undo2
    "WITHDRAWAL" -> MasroofOutlineIcons.Banknote
    "FEE" -> MasroofOutlineIcons.List
    else -> MasroofOutlineIcons.Info
}

internal fun reviewReasonLabel(detail: BetaReviewDetail): Int = when {
    detail.correctionAction!=null -> R.string.beta_review_parity_reason_correction
    detail.row.reason=="CONFIRM_LOAN" -> R.string.beta_complete_review_loan_reason
    detail.reasons.any {it in setOf("MISSING_TRANSACTION_TIME","INVALID_TIME","UNBOUND_TIME_ROLE")} -> R.string.beta_complete_review_time_reason
    detail.reasons.any {it.contains("AMOUNT") || it.contains("CURRENCY") || it=="MISSING_POSITIVE_PRINCIPAL"} -> R.string.beta_complete_review_amount_reason
    detail.reasons.any {it.contains("CONFLICT")} -> R.string.beta_complete_review_conflict_reason
    detail.row.reason==null || detail.row.family=="UNKNOWN" -> R.string.beta_complete_review_unknown_reason
    else -> approvalReason(detail.row.reason)
}

/** Callback/tag adapter; rendering is the ported production IconTextButton. */
@Composable
internal fun BetaReviewActionButton(label: String,icon: ImageVector,enabled: Boolean,tag: String,onClick: ()->Unit) =
    ApprovedIconTextButton(onClick,label,Modifier.fillMaxWidth().testTag(tag),enabled,icon)

internal fun reviewReasonLabels(detail: BetaReviewDetail): List<Int> {
    val codes=detail.reasons.map {it.uppercase()}.toSet()
    val labels=buildList {
        if(codes.any {it.contains("INTERNATIONALFEE")}) add(R.string.beta_review_parity_reason_fee)
        if("NETWORK_ALIAS_NOT_UNIQUE" in codes) add(R.string.beta_review_parity_reason_bank)
        else if(codes.any {it.startsWith("NETWORK_") || it=="UNBOUND_BANK_BRACKET"}) add(R.string.beta_review_parity_reason_network)
        if(detail.row.reason=="CONFIRM_OWNERSHIP") add(R.string.beta_review_parity_reason_ownership)
        if(detail.row.reason in setOf("CONFIRM_CARD","CONFIGURE_CARD","LINK_DEBIT_ACCOUNT")) add(R.string.beta_review_parity_reason_card)
    }
    return labels.ifEmpty {listOf(reviewReasonLabel(detail))}.distinct()
}

internal fun reviewFinancialTypeActionLabel(type: FinancialType): Int = when(type) {
    FinancialType.EXPENSE->R.string.beta_review_parity_type_expense
    FinancialType.INCOME->R.string.beta_review_parity_type_income
    FinancialType.CREDIT_CARD_PAYMENT->R.string.beta_review_parity_type_credit_card_payment
    FinancialType.REFUND->R.string.beta_review_parity_type_refund
    FinancialType.CASH_WITHDRAWAL->R.string.beta_review_parity_type_cash_withdrawal
    FinancialType.BILL_PAYMENT->R.string.beta_review_parity_type_bill_payment
    FinancialType.FEE->R.string.beta_review_parity_type_fee
    FinancialType.LOAN_REPAYMENT->R.string.beta_review_parity_type_loan_repayment
    else->financialTypeLabel(type)
}

/** Review uses the production inline prompt; setup retains its discovery cards. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BetaReviewOwnershipPrompt(candidate: com.baraa.masroof.lab.beta.application.BetaOwnershipCandidate,
    busy: Boolean, onDecide: (Boolean?, com.baraa.masroof.lab.finance.asset.AssetId?)->Unit, onConfigure: ()->Unit) {
    val ref=candidate.reference
    val card=ref.kind==com.baraa.masroof.lab.beta.application.BetaOwnershipKind.CARD
    val tag="ownership-${ref.bank.value}-${ref.kind}-${ref.suffix}"
    var selectedKey by rememberSaveable(candidate.token) {mutableStateOf(candidate.asset?.id?.let(::betaAssetChoiceKey))}
    val selected=candidate.choices.singleOrNull {betaAssetChoiceKey(it.id)==selectedKey}?.id
    DashboardSectionHeading(stringResource(if(card) R.string.beta_review_parity_owner_title else R.string.beta_complete_review_owner),MasroofOutlineIcons.ShieldCheck)
    Text(stringResource(if(card) R.string.beta_review_parity_owner_body else R.string.beta_ownership_account_suffix,ref.suffix),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface)
    Column(Modifier.fillMaxWidth().testTag(tag),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(MasroofOutlineIcons.TriangleAlert,null,Modifier.size(MasroofIconSizes.sm),tint=MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(MasroofSpacing.compactCardLabelTop))
            Text(stringResource(if(card) R.string.beta_review_parity_owner_warning else R.string.beta_review_parity_reason_ownership),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
        }
        if(candidate.conflictingKinds) Text(stringResource(R.string.beta_ownership_kind_conflict),color=MaterialTheme.colorScheme.error)
        if(candidate.choices.size>1) {
            Text(stringResource(R.string.beta_ownership_collision))
            candidate.choices.forEachIndexed {index,asset ->
                BetaChoice(selected==asset.id,{selectedKey=betaAssetChoiceKey(asset.id)},Modifier.testTag("$tag-choice-$index"),enabled=!busy) {
                    Text(stringResource(R.string.beta_asset_summary,stringResource(assetKindLabel(asset.kind)),asset.last4.orEmpty(),index+1))
                }
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
            ApprovedIconTextButton(onClick={onDecide(true,selected)},
                text=stringResource(if(card) R.string.beta_review_parity_my_card else R.string.beta_ownership_my_account),
                enabled=!busy && !candidate.conflictingKinds && (candidate.choices.size<=1 || selected!=null),
                icon=MasroofOutlineIcons.CircleCheck,modifier=Modifier.testTag("$tag-owned"))
            ApprovedIconTextButton(onClick={onDecide(false,null)},text=stringResource(R.string.beta_review_parity_not_mine),
                enabled=!busy,icon=MasroofOutlineIcons.TriangleAlert,modifier=Modifier.testTag("$tag-external"))
        }
        if(candidate.status==com.baraa.masroof.lab.beta.application.BetaOwnershipStatus.OWNED && card &&
            (candidate.asset?.parentId==null || (candidate.asset.kind==com.baraa.masroof.lab.finance.asset.AssetKind.CREDIT_CARD && candidate.asset.creditCardRole==null)))
            BetaReviewActionButton(stringResource(R.string.beta_ownership_configure_card),MasroofOutlineIcons.CreditCard,!busy,"$tag-configure",onConfigure)
    }
}

internal val betaReviewFinancialTypes=listOf(FinancialType.EXPENSE,FinancialType.INCOME,FinancialType.CREDIT_CARD_PAYMENT,FinancialType.REFUND,FinancialType.CASH_WITHDRAWAL,FinancialType.BILL_PAYMENT,FinancialType.FEE,FinancialType.LOAN_REPAYMENT)

/** Canonical source action rows, shared by live review and frozen visual cases. */
@Composable
internal fun BetaReviewFinancialActions(types: List<FinancialType>,busy: Boolean,restore: Boolean=false,
    family: String?=null,tagPrefix: String="review-resolve",onSelect: (FinancialType)->Unit) {
    if(types.isNotEmpty() && !restore && family !in setOf("TRANSFER_IN","TRANSFER_OUT"))
        DashboardSectionHeading(stringResource(R.string.beta_review_parity_actions),MasroofOutlineIcons.ShieldCheck)
    types.forEach {type ->
        val label=if(restore) stringResource(R.string.beta_review_parity_restore_as,stringResource(financialTypeLabel(type))) else stringResource(when {
            type==FinancialType.EXTERNAL_TRANSFER_IN->R.string.beta_review_parity_external_in
            type==FinancialType.EXTERNAL_TRANSFER_OUT->R.string.beta_review_parity_external_out
            type==FinancialType.INCOME && family=="TRANSFER_IN"->R.string.beta_review_detail_review_action_incoming_income
            else->reviewFinancialTypeActionLabel(type)
        })
        BetaReviewActionButton(label,reviewTypeIcon(type),!busy,"$tagPrefix-${type.name}") {onSelect(type)}
    }
}
