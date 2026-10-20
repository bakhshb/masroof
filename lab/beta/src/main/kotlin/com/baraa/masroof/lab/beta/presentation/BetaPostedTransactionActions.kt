package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel as financialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.finance.corrections.FinancialDecision
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading
import com.baraa.masroof.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Same detail actions as production: bound classification draft and confirmed ignore. */
@Composable
internal fun BetaPostedTransactionActions(container: BetaContainer,id: String,onBusyChanged: (Boolean)->Unit,onChanged: (Boolean)->Unit,title: String? = null) {
    val scope=rememberBetaUiScope()
    var detail by remember(id) {mutableStateOf<BetaTransactionActionDetail?>(null)}
    var commitmentPreview by remember(id) {mutableStateOf<BetaCommitmentCreationPreview?>(null)}
    var marking by rememberSaveable(id) {mutableStateOf(false)}
    var confirmationKey by rememberSaveable(id) {mutableStateOf<String?>(null)}
    var commitmentCreated by rememberSaveable(id) {mutableStateOf(false)}
    var commitmentFailed by remember(id) {mutableStateOf(false)}
    var failed by remember(id) {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var confirmationActionKey by rememberSaveable(id) {mutableStateOf<String?>(null)}
    var confirmClassification by rememberSaveable(id) {mutableStateOf(false)}
    var reclassified by rememberSaveable(id) {mutableStateOf(false)}
    var failedIgnore by remember(id) {mutableStateOf(false)}
    var loading by remember(id) {mutableStateOf(true)}
    var classify by rememberSaveable(id) {mutableStateOf(false)}
    var ignore by rememberSaveable(id) {mutableStateOf(false)}
    var type by rememberSaveable(id) {mutableStateOf<String?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    SideEffect {onBusyChanged(busy)}
    DisposableEffect(Unit) {onDispose {onBusyChanged(false)}}
    BetaLaunchedEffect(id,revision) {
        loading=true
        try {
            val loaded=withContext(BetaIo.dispatcher) {container.transactionActionDetail(id) to container.commitmentCreationPreview(id)}
            detail=loaded.first;commitmentPreview=loaded.second
        }
        catch(c: CancellationException) {throw c}
        catch(_: Exception) {failed=true}
        finally {loading=false}
    }
    fun submit(suppress: Boolean) {
        val current=detail ?: return
        if(busy) return
        if(confirmationActionKey!=current.confirmationKey) {
            failed=true;failedIgnore=suppress;classify=false;confirmClassification=false;ignore=false;type=null
            return
        }
        busy=true;failed=false;failedIgnore=suppress
        scope.launch {
            try {withContext(BetaIo.dispatcher) {
                val decision=UUID.randomUUID().toString()
                if(suppress) {
                    val result=container.saveReview(current.token,decision,FinancialDecision.SuppressAsNonFinancial)
                    check(result is BetaCorrectionResult.Applied || result is BetaCorrectionResult.AlreadyApplied)
                } else check(container.reclassifyTransaction(current.token,decision,FinancialType.valueOf(checkNotNull(type))))
            };classify=false;confirmClassification=false;ignore=false;reclassified=!suppress;revision++;onChanged(suppress)
            } catch(c: CancellationException) {throw c}
            catch(_: Exception) {failed=true}
            finally {busy=false}
        }
    }
    fun markCommitment() {
        val preview=commitmentPreview ?: return
        if(busy) return
        if(preview.confirmationKey!=confirmationKey) {commitmentFailed=true;return}
        busy=true;commitmentFailed=false
        scope.launch {
            try {
                when(val result=withContext(BetaIo.dispatcher) {container.createCommitment(preview)}) {
                    is BetaCommitmentCreationResult.Created,is BetaCommitmentCreationResult.AlreadyExists -> {
                        marking=false;commitmentCreated=true;revision++;onChanged(false)
                    }
                    is BetaCommitmentCreationResult.Rejected -> {
                        commitmentFailed=true
                        if(result.reason in setOf("stale_source","unavailable_source")) {
                            marking=false;revision++;onChanged(false)
                        }
                    }
                }
            } catch(c: CancellationException) {throw c}
            catch(_: Exception) {commitmentFailed=true}
            finally {busy=false}
        }
    }
    if(loading) Box(Modifier.fillMaxWidth(),contentAlignment=androidx.compose.ui.Alignment.Center) {CircularProgressIndicator(Modifier.testTag("transaction-evidence-loading"))}
    detail?.let {current ->
        BetaTransactionEvidenceContent(current.evidence)
        BetaTransactionDetailButtons(current.allowedTypes.isNotEmpty(),commitmentPreview?.eligible==true,commitmentPreview?.existingId!=null,
            busy,reclassified,commitmentCreated,
            onClassify={type=null;confirmationActionKey=current.confirmationKey;classify=true;failed=false},
            onMark={confirmationKey=commitmentPreview?.confirmationKey;marking=true;commitmentFailed=false},
            onIgnore={confirmationActionKey=current.confirmationKey;ignore=true;failed=false})
        if(commitmentFailed && !marking) Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_failed),color=MaterialTheme.colorScheme.error)
        if(classify) BetaTransactionReclassifySheet(current.currentType,type?.let(FinancialType::valueOf),current.allowedTypes,busy,
            {classify=false;type=null},{type=it.name},{confirmClassification=true})
        if(confirmClassification && type!=null) AlertDialog(onDismissRequest={if(!busy) confirmClassification=false},
            icon={Icon(MasroofOutlineIcons.ShieldCheck,null,tint=MaterialTheme.colorScheme.primary)},
            title={Text(stringResource(R.string.beta_detail_transaction_reclassify_confirm_title))},
            text={Column(verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                title?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
                Text(stringResource(R.string.beta_detail_transaction_reclassify_confirm_body,
                    stringResource(financialTypeLabel(current.currentType)),stringResource(financialTypeLabel(FinancialType.valueOf(checkNotNull(type))))),
                    style=MaterialTheme.typography.bodyMedium)
            }},
            confirmButton={ApprovedTextButton(enabled=!busy,onClick={confirmClassification=false;submit(false)},modifier=Modifier.testTag("transaction-type-confirm")) {
                Text(stringResource(R.string.beta_detail_transaction_reclassify_confirm_action))}},
            dismissButton={ApprovedTextButton(enabled=!busy,onClick={confirmClassification=false},modifier=Modifier.testTag("transaction-type-confirm-cancel")) {
                Text(stringResource(R.string.beta_detail_settings_cancel))}})
    }
    if(marking) AlertDialog(onDismissRequest={if(!busy) marking=false},
        icon={Icon(MasroofOutlineIcons.CalendarCheck,null,tint=MaterialTheme.colorScheme.primary)},
        title={Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_confirm_title))},
        text={Column {Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_confirm_body))
            if(commitmentFailed) Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_failed),color=MaterialTheme.colorScheme.error)}},
        confirmButton={ApprovedTextButton(enabled=!busy && commitmentPreview!=null,onClick={markCommitment()},modifier=Modifier.testTag("transaction-mark-commitment-confirm")) {
            Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_confirm_action))}},
        dismissButton={ApprovedTextButton(enabled=!busy,onClick={marking=false},modifier=Modifier.testTag("transaction-mark-commitment-cancel")) {
            Text(stringResource(R.string.beta_commitment_action_settings_cancel))}})
    if(ignore) AlertDialog(onDismissRequest={if(!busy) ignore=false},
        icon={Icon(MasroofOutlineIcons.TriangleAlert,null,tint=MaterialTheme.colorScheme.error)},
        title={Text(stringResource(R.string.beta_detail_transaction_detail_ignore_confirm_title))},
        text={Column(verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
            title?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
            Text(stringResource(R.string.beta_detail_transaction_detail_ignore_confirm_body),style=MaterialTheme.typography.bodyMedium)
        }},
        confirmButton={ApprovedTextButton(enabled=!busy && detail!=null,onClick={ignore=false;submit(true)},modifier=Modifier.testTag("transaction-ignore-confirm")) {
            Text(stringResource(R.string.beta_detail_transaction_detail_ignore_confirm_action))}},
        dismissButton={ApprovedTextButton(enabled=!busy,onClick={ignore=false},modifier=Modifier.testTag("transaction-ignore-cancel")) {Text(stringResource(R.string.beta_detail_settings_cancel))}})
    if(failed) {
        Text(stringResource(if(failedIgnore) R.string.beta_detail_transaction_detail_ignore_failed else R.string.beta_detail_transaction_detail_reclassify_failed),
            color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("transaction-action-error"))
        if(detail==null) ApprovedOutlinedButton(enabled=!loading && !busy,onClick={failed=false;revision++},modifier=Modifier.testTag("transaction-action-retry")) {
            Text(stringResource(R.string.beta_controls_reload))
        }
    }
}

/** Source evidence block; reused by the actual detail and frozen-data render harness. */
@Composable
internal fun BetaTransactionEvidenceContent(evidence: List<BetaTransactionEvidence>) {
    if(evidence.isEmpty()) return
    DashboardSectionHeading(stringResource(R.string.beta_detail_transaction_detail_sms_section),MasroofOutlineIcons.MessageSquare)
    evidence.forEachIndexed {index,row ->
        if(evidence.size>1) Text(stringResource(R.string.beta_detail_transaction_detail_sms_index,index+1,evidence.size),
            style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ApprovedIconLabelRow(MasroofOutlineIcons.Bell,stringResource(R.string.beta_detail_review_sender,row.sender))
        MasroofCard {Text(row.body,textAlign=androidx.compose.ui.text.style.TextAlign.Start,color=MaterialTheme.colorScheme.onSurface)}
    }
}

/** One source-derived action renderer for the real workflow and frozen rendering evidence. */
@Composable
internal fun BetaTransactionDetailButtons(canClassify: Boolean,canMark: Boolean,isCommitted: Boolean,busy: Boolean,
    reclassified: Boolean=false,commitmentCreated: Boolean=false,onClassify: ()->Unit,onMark: ()->Unit,onIgnore: ()->Unit) {
    ApprovedOutlinedButton(enabled=!busy && canClassify,onClick=onClassify,modifier=Modifier.fillMaxWidth().testTag("transaction-change-type")) {
        Icon(MasroofOutlineIcons.ShieldCheck,null,Modifier.size(MasroofIconSizes.md))
        Spacer(Modifier.width(MasroofSpacing.sectionHeaderGap))
        Text(stringResource(R.string.beta_detail_transaction_detail_edit_category))
    }
    if(isCommitted) Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_already),
        style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("transaction-commitment-already"))
    else if(canMark) ApprovedIconTextButtonOutlined(onClick=onMark,icon=MasroofOutlineIcons.CalendarCheck,
        text=stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment),enabled=!busy,
        modifier=Modifier.fillMaxWidth().testTag("transaction-mark-commitment"))
    ApprovedIconTextButtonOutlined(enabled=!busy,onClick=onIgnore,icon=MasroofOutlineIcons.TriangleAlert,
        text=stringResource(R.string.beta_detail_review_action_ignore),modifier=Modifier.fillMaxWidth().testTag("transaction-ignore"))
    if(reclassified) Text(stringResource(R.string.beta_detail_transaction_detail_reclassify_success),color=MaterialTheme.colorScheme.primary,
        modifier=Modifier.testTag("transaction-reclassified"))
    if(commitmentCreated) Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_success),
        color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("transaction-commitment-success"))
}
