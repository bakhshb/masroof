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
internal fun BetaPostedTransactionActions(container: BetaContainer,id: String,onBusyChanged: (Boolean)->Unit,onChanged: (Boolean)->Unit) {
    val scope=rememberBetaUiScope()
    var detail by remember(id) {mutableStateOf<BetaTransactionActionDetail?>(null)}
    var commitmentPreview by remember(id) {mutableStateOf<BetaCommitmentCreationPreview?>(null)}
    var marking by rememberSaveable(id) {mutableStateOf(false)}
    var confirmationKey by rememberSaveable(id) {mutableStateOf<String?>(null)}
    var commitmentCreated by rememberSaveable(id) {mutableStateOf(false)}
    var commitmentFailed by remember(id) {mutableStateOf(false)}
    var failed by remember(id) {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var classify by rememberSaveable(id) {mutableStateOf(false)}
    var ignore by rememberSaveable(id) {mutableStateOf(false)}
    var type by rememberSaveable(id) {mutableStateOf<String?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    SideEffect {onBusyChanged(busy)}
    DisposableEffect(Unit) {onDispose {onBusyChanged(false)}}
    BetaLaunchedEffect(id,revision) {
        try {
            val loaded=withContext(BetaIo.dispatcher) {container.transactionActionDetail(id) to container.commitmentCreationPreview(id)}
            detail=loaded.first;commitmentPreview=loaded.second
        }
        catch(c: CancellationException) {throw c}
        catch(_: Exception) {failed=true}
    }
    fun submit(suppress: Boolean) {
        val current=detail ?: return
        if(busy) return
        busy=true;failed=false
        scope.launch {
            try {withContext(BetaIo.dispatcher) {
                val decision=UUID.randomUUID().toString()
                if(suppress) {
                    val result=container.saveReview(current.token,decision,FinancialDecision.SuppressAsNonFinancial)
                    check(result is BetaCorrectionResult.Applied || result is BetaCorrectionResult.AlreadyApplied)
                } else check(container.reclassifyTransaction(current.token,decision,FinancialType.valueOf(checkNotNull(type))))
            };classify=false;ignore=false;revision++;onChanged(suppress)
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
    detail?.let {current ->
        if(current.evidence.isNotEmpty()) {
            DashboardSectionHeading(stringResource(R.string.beta_complete_source_evidence),MasroofOutlineIcons.MessageSquare)
            current.evidence.forEach {evidence ->
                BetaReviewMeta(MasroofOutlineIcons.MessageSquare,evidence.sender)
                MasroofCard {Text(evidence.body)}
            }
        }
        DashboardSectionHeading(stringResource(R.string.beta_complete_transaction_actions),MasroofOutlineIcons.ShieldCheck)
        if(current.allowedTypes.isNotEmpty()) OutlinedButton(enabled=!busy,onClick={type=null;classify=true},modifier=Modifier.fillMaxWidth().testTag("transaction-change-type")) {Text(stringResource(R.string.beta_review_type))}
        commitmentPreview?.let {preview ->
            if(preview.existingId!=null) Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_already),
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.testTag("transaction-commitment-already"))
            else if(preview.eligible) ApprovedIconTextButtonOutlined(onClick={confirmationKey=preview.confirmationKey;marking=true;commitmentFailed=false},
                icon=MasroofOutlineIcons.CalendarCheck,text=stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment),
                enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("transaction-mark-commitment"))
        }
        if(commitmentFailed && !marking) Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_failed),color=MaterialTheme.colorScheme.error)
        if(commitmentCreated) Text(stringResource(R.string.beta_commitment_action_transaction_detail_mark_commitment_success),
            color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("transaction-commitment-success"))
        TextButton(enabled=!busy,onClick={ignore=true},modifier=Modifier.fillMaxWidth().testTag("transaction-ignore")) {Text(stringResource(R.string.beta_fix_dismiss))}
        if(classify) BetaBottomSheet(stringResource(R.string.beta_review_type),busy,{classify=false},{submit(false)},canApply=type!=null,
            applyTag="transaction-type-save",cancelTag="transaction-type-cancel",applyLabel=stringResource(R.string.beta_asset_save)) {
            MasroofSheetChoices(current.allowedTypes,current.allowedTypes.filter {it.name==type}.toSet(),{stringResource(financialTypeLabel(it))},
                {type=it.name},{"transaction-type-${it.name}"},enabled=!busy)
            if(failed) Text(stringResource(R.string.beta_review_failed),color=MaterialTheme.colorScheme.error)
        }
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
        title={Text(stringResource(R.string.beta_fix_dismiss))},text={Text(stringResource(R.string.beta_fix_dismiss_help))},
        confirmButton={TextButton(enabled=!busy,onClick={submit(true)},modifier=Modifier.testTag("transaction-ignore-confirm")) {Text(stringResource(R.string.beta_fix_dismiss))}},
        dismissButton={TextButton(enabled=!busy,onClick={ignore=false}) {Text(stringResource(R.string.beta_asset_cancel))}})
    if(failed && !classify) Text(stringResource(R.string.beta_review_failed),color=MaterialTheme.colorScheme.error)
}
