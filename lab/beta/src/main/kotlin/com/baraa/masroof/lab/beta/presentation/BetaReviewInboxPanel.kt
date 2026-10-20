package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel as financialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeIcon as reviewTypeIcon
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import com.baraa.masroof.lab.beta.presentation.approved.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading
import com.baraa.masroof.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Production review list/detail journey, over isolated lab facts and write facades. */
@Composable
internal fun BetaReviewInboxPanel(container: BetaContainer, onClose: () -> Unit,
    onBusyChanged: (Boolean) -> Unit,
    initialEvidenceId: String? = null, onChanged: () -> Unit = {}, onDetailChanged: (Boolean) -> Unit = {}) {
    val scope=rememberBetaUiScope()
    var busy by remember {mutableStateOf(false)}
    var ignored by rememberSaveable {mutableStateOf(false)}
    var after by rememberSaveable {mutableStateOf("")}
    var selected by rememberSaveable {mutableStateOf(initialEvidenceId)}
    var generation by remember {mutableIntStateOf(0)}
    var page by remember {mutableStateOf<BetaInboxPage?>(null)}
    var detail by remember {mutableStateOf<BetaReviewDetail?>(null)}
    var failed by remember {mutableStateOf(false)}
    var failureReason by remember {mutableStateOf<String?>(null)}
    var configureReference by remember {mutableStateOf<BetaOwnershipCandidate?>(null)}
    var configureLoan by remember {mutableStateOf(false)}
    SideEffect {onBusyChanged(busy);onDetailChanged(selected!=null)}
    DisposableEffect(Unit) {onDispose {onBusyChanged(false);onDetailChanged(false)}}
    BackHandler {if(!busy) {if(selected!=null) selected=null else onClose()}}
    BetaLaunchedEffect(after,ignored,selected,generation) {
        failed=false;failureReason=null;page=null;detail=null
        try {withContext(BetaIo.dispatcher) {
            if(selected!=null) null to container.reviewDetail(checkNotNull(selected))
            else container.reviewInboxPage(after,ignored) to null
        }.let {page=it.first;detail=it.second}}
        catch(c: CancellationException) {throw c}
        catch(_: Exception) {failed=true}
    }
    fun perform(closeOnSuccess: Boolean = false, action: ()->Unit) {
        if(busy) return
        busy=true;failed=false
        val selectedId=selected
        scope.launch {
            try {val posted=withContext(BetaIo.dispatcher) {
                    action();container.processAutomaticEvidence()
                    selectedId!=null && container.financialPosting(com.baraa.masroof.lab.finance.posting.PostingKey(com.baraa.masroof.lab.finance.value.Environment.BETA,"evidence:$selectedId"))!=null
                }
                if(closeOnSuccess || posted) selected=null
                configureReference=null;configureLoan=false;generation++;onChanged()
            } catch(c: CancellationException) {throw c}
            catch(error: Exception) {failed=true;failureReason=error.message?.takeIf {it.matches(Regex("[A-Z_]{1,80}"))}}
            finally {busy=false}
        }
    }
    val current=detail
    if(current!=null && (configureReference!=null || configureLoan)) {
        val candidate=configureReference
        val existing=candidate?.asset ?: current.preview?.assets?.filter {it.kind==AssetKind.LOAN && it.id.bank.value==current.row.bank}?.singleOrNull().takeIf {configureLoan}
        val kind=existing?.kind ?: if(configureLoan) AssetKind.LOAN else if(candidate?.reference?.kind==BetaOwnershipKind.ACCOUNT) AssetKind.ACCOUNT else AssetKind.DEBIT_CARD
        val bank=existing?.id?.bank ?: candidate?.reference?.bank ?: current.row.bank?.let {com.baraa.masroof.lab.finance.value.BankId(it)}
        if(bank!=null) BetaAssetEditor(bank,existing,current.preview?.assets.orEmpty(),busy,failed,
            initialKind=kind,initialSuffix=candidate?.reference?.suffix.orEmpty(),
            allowedKinds=if(kind in setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD)) setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD) else setOf(kind),
            ownershipConfirmed=candidate?.status==BetaOwnershipStatus.OWNED, registryMetadata=current.assetMetadata, metadata=existing?.let {current.assetMetadata[it.id]} ?: BetaAssetMetadata(),
            onCancel={configureReference=null;configureLoan=false},onSave={draft -> perform {
                val saved=container.saveManualAsset(draft)
                candidate?.reference?.let {ref -> container.ownershipSnapshot().candidates.singleOrNull {it.reference==ref}?.let {container.decideOwnership(it,true,saved.id)}}
            }})
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        if(failed) {
            Text(stringResource(failureReason?.let(::approvalReason) ?: R.string.beta_review_failed),color=MaterialTheme.colorScheme.error)
            OutlinedButton(enabled=!busy,onClick={generation++}) {Text(stringResource(R.string.beta_retry))}
        }
        if(page==null && detail==null && !failed) CircularProgressIndicator()
        if(current!=null) {
            val entry=current.row
            MasroofCard {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.carouselGap)) {
                    Icon(reviewFamilyIcon(entry.family),null,Modifier.size(MasroofIconSizes.logo),tint=MaterialTheme.colorScheme.primary)
                    Text(stringResource(if(entry.dismissed) R.string.beta_review_parity_ignored else R.string.beta_review_parity_kind),style=MaterialTheme.typography.titleMedium)
                }
                entry.amount?.let {Text(betaMoney(it,entry.currency),Modifier.padding(top=MasroofSpacing.sectionHeaderGap),style=MaterialTheme.typography.displayMedium,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
            }
            BetaReviewMeta(MasroofOutlineIcons.MessageSquare,stringResource(R.string.beta_complete_review_sender,entry.sender))
            BetaReviewMeta(MasroofOutlineIcons.CalendarDays,stringResource(R.string.beta_complete_review_date,betaDate(entry.receivedAt)))
            current.resolvedAt?.let {BetaReviewMeta(MasroofOutlineIcons.CircleCheck,stringResource(R.string.beta_review_parity_ignored_at,betaDate(it)))}
            if(entry.dismissed) {
                MasroofCard {Text(stringResource(R.string.beta_review_parity_restore_hint))}
                DashboardSectionHeading(stringResource(R.string.beta_review_parity_restore_title),MasroofOutlineIcons.ShieldCheck)
                BetaReviewActionButton(stringResource(R.string.beta_review_parity_restore),MasroofOutlineIcons.CircleCheck,!busy,"review-restore") {perform(true) {container.resolveReviewAsNonFinancial(entry.id,true)}}
                if(entry.financiallySuppressed) betaReviewFinancialTypes.forEach {type ->
                    BetaReviewActionButton(stringResource(R.string.beta_review_parity_restore_as,stringResource(financialTypeLabel(type))),reviewTypeIcon(type),!busy,"review-restore-${type.name}") {
                        perform(true) {container.resolveReviewAsNonFinancial(entry.id,true,type)}
                    }
                } else BetaReviewResolutionActions(current.copy(actions=betaReviewFinancialTypes),busy,{request -> perform {
                    check(container.restoreReviewAsFinancial(request) is BetaFinancialAdoptionResult.Posted)
                    selected=null
                }},onRetryPending={perform {check(container.retryFinancialApproval(entry.id,current.preview?.pending) is BetaFinancialAdoptionResult.Posted);selected=null}},onCancelPending={perform {current.preview?.pending?.let {container.cancelFinancialApproval(it)}}},restore=true)
            }
            current.merchant?.let {BetaReviewMeta(MasroofOutlineIcons.Store,stringResource(R.string.beta_review_parity_merchant,it))}
            current.counterparty?.let {BetaReviewMeta(MasroofOutlineIcons.User,stringResource(R.string.beta_review_parity_counterparty,it))}
            DashboardSectionHeading(stringResource(R.string.beta_complete_review_reasons),MasroofOutlineIcons.TriangleAlert)
            reviewReasonLabels(current).forEach {Text("• "+stringResource(it))}
            if(!entry.dismissed && !ignored) {
                current.ownership.filter {it.status!=BetaOwnershipStatus.OWNED || it.asset==null || (it.asset.kind==AssetKind.DEBIT_CARD && it.asset.parentId==null)}.forEach {candidate ->
                    BetaReviewOwnershipPrompt(candidate,busy,{owned,asset -> perform {
                        if(owned==null) container.deferOwnership(candidate) else container.decideOwnership(candidate,owned,asset)
                    }},{configureReference=candidate})
                }
                if(current.loanConfiguration) Button(enabled=!busy,onClick={configureLoan=true},modifier=Modifier.fillMaxWidth().testTag("review-configure-loan")) {
                    Text(stringResource(R.string.beta_complete_review_loan))
                }
            }
            DashboardSectionHeading(stringResource(R.string.beta_complete_review_message),MasroofOutlineIcons.MessageSquare)
            MasroofCard {Text(entry.body,textAlign=TextAlign.Start)}
            if(!entry.dismissed && !ignored) {
                if(current.preview?.pending==null) BetaReviewActionButton(stringResource(R.string.beta_review_parity_ignore),MasroofOutlineIcons.TriangleAlert,!busy,"review-dismiss") {perform(true) {container.resolveReviewAsNonFinancial(entry.id)}}
                if(current.correctionAction!=null) {
                    DashboardSectionHeading(stringResource(R.string.beta_review_parity_actions),MasroofOutlineIcons.ShieldCheck)
                    current.actions.filter {it in current.correctionAction.allowedTypes}.forEach {type ->
                        BetaReviewActionButton(stringResource(reviewFinancialTypeActionLabel(type)),reviewTypeIcon(type),!busy,"review-resolve-${type.name}") {
                            perform {
                                check(container.reclassifyTransaction(current.correctionAction.token,java.util.UUID.randomUUID().toString(),type))
                                selected=null
                            }
                        }
                    }
                } else BetaReviewResolutionActions(current,busy,{request ->
                    perform {
                        val result=container.approveEvidenceFinancial(request)
                        check(result is BetaFinancialAdoptionResult.Posted) {"Financial decision remains retryable"}
                        selected=null
                    }
                },onRetryPending={perform {
                    val request=checkNotNull(current.preview?.pending)
                    check(container.retryFinancialApproval(entry.id,request) is BetaFinancialAdoptionResult.Posted)
                    selected=null
                }},onCancelPending={perform {checkNotNull(current.preview?.pending).let {check(container.cancelFinancialApproval(it))}}})
                if(current.preview?.blockedReason!=null || current.actions.isEmpty()) BetaReviewActionButton(stringResource(R.string.beta_complete_review_retry),MasroofOutlineIcons.RotateCcw,!busy,"review-reinterpret") {perform {container.reprocessEvidence(entry.id)}}
            }
            return@Column
        }
        Row(horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            BetaChoice(!ignored,{ignored=false;after=""},Modifier.testTag("review-filter-pending"),enabled=!busy) {Text(stringResource(R.string.beta_fix_review_pending))}
            BetaChoice(ignored,{ignored=true;after=""},Modifier.testTag("review-filter-ignored"),enabled=!busy) {Text(stringResource(R.string.beta_fix_review_informational))}
        }
        page?.let {loaded ->
            if(loaded.processing>0) MasroofCard {
                Text(stringResource(R.string.beta_fix_processing))
                OutlinedButton(enabled=!busy,onClick={perform {container.retryEvidence(100)}},modifier=Modifier.testTag("review-retry-processing")) {Text(stringResource(R.string.beta_retry))}
            }
            if(loaded.rows.isEmpty()) Column(Modifier.fillMaxWidth().testTag("review-empty").padding(MasroofSpacing.screenPaddingLarge),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(MasroofOutlineIcons.CircleCheck,null,Modifier.size(MasroofIconSizes.hero),tint=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(MasroofSpacing.sectionGap))
                Text(stringResource(if(ignored) R.string.beta_complete_review_ignored_empty else R.string.beta_complete_review_empty),textAlign=TextAlign.Center)
            } else {
                loaded.rows.forEachIndexed {index,item ->
                    ApprovedReviewListCard(ApprovedReviewListItem(item.description ?: item.sender,
                        stringResource(if(ignored) R.string.beta_review_parity_ignored else R.string.beta_review_parity_kind),ignored,
                        item.amount?.let {betaMoney(it,item.currency)},betaDate(item.receivedAt),
                        stringResource(if(item.family==null || item.family=="UNKNOWN") R.string.beta_complete_review_unknown_reason else approvalReason(item.reason.orEmpty())),
                        stringResource(R.string.beta_complete_review_evidence)),!busy,Modifier.testTag("review-item-$index"),Modifier.testTag("history-review-$index")) {selected=item.id}
                }
            }
            if(loaded.next!=null) OutlinedButton(enabled=!busy,onClick={after=loaded.next}) {Text(stringResource(R.string.beta_history_next))}
            if(after.isNotEmpty()) TextButton(enabled=!busy,onClick={after=""}) {Text(stringResource(R.string.beta_history_first))}
        }
    }
}
