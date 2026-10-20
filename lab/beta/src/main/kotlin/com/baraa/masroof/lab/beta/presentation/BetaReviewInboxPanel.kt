package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import com.baraa.masroof.lab.beta.presentation.approved.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    initialEvidenceId: String? = null, onChanged: () -> Unit = {}, onDetailChanged: (Boolean) -> Unit = {},refreshRequest: Int=0) {
    val scope=rememberBetaUiScope()
    var busy by remember {mutableStateOf(false)}
    var loading by remember {mutableStateOf(true)}
    val uiBusy=busy || loading
    var ignored by rememberSaveable {mutableStateOf(false)}
    var after by rememberSaveable {mutableStateOf("")}
    var selected by rememberSaveable {mutableStateOf(initialEvidenceId)}
    var generation by remember {mutableIntStateOf(0)}
    var page by remember {mutableStateOf<BetaInboxPage?>(null)}
    var detail by remember {mutableStateOf<BetaReviewDetail?>(null)}
    var failed by remember {mutableStateOf(false)}
    var failureReason by remember {mutableStateOf<String?>(null)}
    var configureReferenceKey by rememberSaveable {mutableStateOf<String?>(null)}
    var configureReferenceToken by rememberSaveable {mutableStateOf<String?>(null)}
    fun referenceKey(candidate: BetaOwnershipCandidate)=listOf(candidate.reference.bank.value,candidate.reference.kind.name,candidate.reference.suffix).joinToString("|")
    val configureReference=detail?.ownership?.singleOrNull {referenceKey(it)==configureReferenceKey}
    var configureLoan by rememberSaveable {mutableStateOf(false)}
    SideEffect {onBusyChanged(busy);onDetailChanged(selected!=null)}
    DisposableEffect(Unit) {onDispose {onBusyChanged(false);onDetailChanged(false)}}
    BackHandler {if(!busy) {if(selected!=null) {selected=null;configureReferenceKey=null;configureReferenceToken=null;configureLoan=false} else onClose()}}
    BetaLaunchedEffect(after,ignored,selected,generation,refreshRequest) {
        loading=true;failed=false;failureReason=null;page=null
        if(detail?.row?.id!=selected) detail=null
        try {withContext(BetaIo.dispatcher) {
            if(selected!=null) null to container.reviewDetail(checkNotNull(selected))
            else container.reviewInboxPage(after,ignored) to null
        }.let {page=it.first;detail=it.second}}
        catch(c: CancellationException) {throw c}
        catch(_: Exception) {failed=true}
        finally {loading=false}
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
                configureReferenceKey=null;configureReferenceToken=null;configureLoan=false;generation++;onChanged()
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
        if(bank!=null) BetaAssetEditor(bank,existing,current.preview?.assets.orEmpty(),uiBusy,failed,
            initialKind=kind,initialSuffix=candidate?.reference?.suffix.orEmpty(),
            allowedKinds=if(kind in setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD)) setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD) else setOf(kind),
            ownershipConfirmed=candidate?.status==BetaOwnershipStatus.OWNED, registryMetadata=current.assetMetadata, metadata=existing?.let {current.assetMetadata[it.id]} ?: BetaAssetMetadata(),
            onCancel={configureReferenceKey=null;configureReferenceToken=null;configureLoan=false},onSave={draft -> perform {
                if(candidate!=null) container.saveReviewAsset(draft,candidate.reference,checkNotNull(configureReferenceToken))
                else container.saveManualAsset(draft)
            }})
    }
    Column(Modifier.fillMaxSize().then(if(current!=null) Modifier.verticalScroll(rememberScrollState()) else Modifier),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        if(failed && current==null) {
            Text(stringResource(failureReason?.let(::approvalReason) ?: R.string.beta_review_failed),color=MaterialTheme.colorScheme.error)
            OutlinedButton(enabled=!uiBusy,onClick={generation++}) {Text(stringResource(R.string.beta_retry))}
        }
        if(page==null && detail==null && !failed) CircularProgressIndicator()
        if(current!=null) {
            val entry=current.row
            BetaReviewDetailContent(betaReviewDetailView(current,ignored),restoration={
                BetaReviewActionButton(stringResource(R.string.beta_review_parity_restore),MasroofOutlineIcons.CircleCheck,!uiBusy,"review-restore") {perform(true) {container.resolveReviewAsNonFinancial(entry.id,true)}}
                if(entry.financiallySuppressed) BetaReviewFinancialActions(betaReviewFinancialTypes,uiBusy,restore=true,tagPrefix="review-restore") {type ->
                    perform(true) {container.resolveReviewAsNonFinancial(entry.id,true,type)}
                } else BetaReviewResolutionActions(current.copy(actions=betaReviewFinancialTypes),uiBusy,{request -> perform {
                    check(container.restoreReviewAsFinancial(request) is BetaFinancialAdoptionResult.Posted);selected=null
                }},onRetryPending={perform {check(container.retryFinancialApproval(entry.id,current.preview?.pending) is BetaFinancialAdoptionResult.Posted);selected=null}},
                    onCancelPending={perform {current.preview?.pending?.let {container.cancelFinancialApproval(it)}}},restore=true)
            },ownership={
                if(!entry.dismissed && !ignored) {
                    current.ownership.filter {it.status!=BetaOwnershipStatus.OWNED || it.asset==null || (it.asset.kind==AssetKind.DEBIT_CARD && it.asset.parentId==null)}.forEach {candidate ->
                        BetaReviewOwnershipPrompt(candidate,uiBusy,{owned,asset -> perform {
                            if(owned==null) container.deferOwnership(candidate) else container.decideOwnership(candidate,owned,asset)
                        }},{configureReferenceKey=referenceKey(candidate);configureReferenceToken=candidate.token})
                    }
                    if(current.loanConfiguration) BetaReviewActionButton(stringResource(R.string.beta_complete_review_loan),MasroofOutlineIcons.Landmark,!uiBusy,"review-configure-loan") {configureLoan=true}
                }
            },actions={
                if(!entry.dismissed && !ignored) {
                    if(current.preview?.pending==null && betaReviewKindLabel(false,entry.reason)!=R.string.beta_approved_review_pending_match) BetaReviewActionButton(stringResource(R.string.beta_review_parity_ignore),MasroofOutlineIcons.TriangleAlert,!uiBusy,"review-dismiss") {perform(true) {container.resolveReviewAsNonFinancial(entry.id)}}
                    if(current.correctionAction!=null) {
                        BetaReviewFinancialActions(current.actions.filter {it in current.correctionAction.allowedTypes},uiBusy,family=entry.family) {type ->
                            perform {check(container.reclassifyTransaction(current.correctionAction.token,java.util.UUID.randomUUID().toString(),type));selected=null}
                        }
                    } else BetaReviewResolutionActions(current,uiBusy,{request -> perform {
                        check(container.approveEvidenceFinancial(request) is BetaFinancialAdoptionResult.Posted) {"Financial decision remains retryable"};selected=null
                    }},onRetryPending={perform {
                        check(container.retryFinancialApproval(entry.id,checkNotNull(current.preview?.pending)) is BetaFinancialAdoptionResult.Posted);selected=null
                    }},onCancelPending={perform {check(container.cancelFinancialApproval(checkNotNull(current.preview?.pending)))}})
                    if(current.preview?.blockedReason!=null || current.actions.isEmpty()) BetaReviewActionButton(stringResource(R.string.beta_complete_review_retry),MasroofOutlineIcons.RotateCcw,!uiBusy,"review-reinterpret") {perform {container.reprocessEvidence(entry.id)}}
                }
            },feedback={BetaReviewFeedback(uiBusy,failed,failureReason?.let {stringResource(approvalReason(it))},onReload={generation++})})
            return@Column
        }
        page?.let {loaded ->
            if(loaded.processing>0) MasroofCard {
                Text(stringResource(R.string.beta_fix_processing))
                OutlinedButton(enabled=!uiBusy,onClick={perform {container.retryEvidence(100)}},modifier=Modifier.testTag("review-retry-processing")) {Text(stringResource(R.string.beta_retry))}
            }
            val items=loaded.rows.map {item -> ApprovedReviewListItem(item.description ?: item.sender,
                stringResource(betaReviewKindLabel(ignored,item.reason)),ignored,
                item.amount?.let {betaMoney(it,item.currency)},betaReviewDate(if(ignored) item.resolvedAt else item.receivedAt),
                stringResource(if(item.family==null || item.family=="UNKNOWN") R.string.beta_complete_review_unknown_reason else approvalReason(item.reason.orEmpty())),
                stringResource(R.string.beta_complete_review_evidence))}
            ApprovedReviewListContent(items,ignored,!uiBusy,{ignored=it;after=""},{selected=loaded.rows[it].id;configureReferenceKey=null;configureReferenceToken=null;configureLoan=false},afterContent=if(loaded.next!=null || after.isNotEmpty()) ({
                if(loaded.next!=null) OutlinedButton(enabled=!uiBusy,onClick={after=loaded.next},modifier=Modifier.testTag("review-next-page")) {Text(stringResource(R.string.beta_history_next))}
                if(after.isNotEmpty()) TextButton(enabled=!uiBusy,onClick={after=""},modifier=Modifier.testTag("review-first-page")) {Text(stringResource(R.string.beta_history_first))}
            }) else null) {
                Column(Modifier.fillMaxSize().testTag("review-empty").padding(MasroofSpacing.screenPaddingLarge),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    Icon(MasroofOutlineIcons.CircleCheck,null,Modifier.size(MasroofIconSizes.hero),tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(MasroofSpacing.sectionGap))
                    Text(stringResource(if(loaded.next!=null) R.string.beta_approved_review_page_empty else if(ignored) R.string.beta_complete_review_ignored_empty else R.string.beta_complete_review_empty),Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
                    Spacer(Modifier.height(MasroofSpacing.sectionGap))
                    ApprovedIconTextButton(onClose,stringResource(R.string.beta_dash_back),icon=MasroofOutlineIcons.CalendarDays,enabled=!uiBusy)
                }
            }
        }
    }
}
