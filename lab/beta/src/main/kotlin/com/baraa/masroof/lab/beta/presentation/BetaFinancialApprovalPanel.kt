package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.approvedFinancialTypeLabel as financialTypeLabel
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import com.baraa.masroof.lab.finance.classification.IncomeAttribution
import com.baraa.masroof.lab.finance.classification.FinancialEndpoint
import com.baraa.masroof.lab.finance.posting.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opening a queue or preview never grants financial authority. Every action is explicit. */
@Composable
internal fun BetaFinancialApprovalPanel(container: BetaContainer, onClose: () -> Unit, onBusyChanged: (Boolean) -> Unit = {}, initialEvidenceId: String? = null) {
    val scope = rememberBetaUiScope()
    var after by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(initialEvidenceId) }
    var rows by remember { mutableStateOf<List<BetaFinancialReviewItem>?>(null) }
    var preview by remember { mutableStateOf<BetaFinancialApprovalPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var resultReason by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    SideEffect { onBusyChanged(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    BetaLaunchedEffect(after, selected, generation) {
        rows = null; preview = null; failed = false
        try {
            val snapshot = withContext(BetaIo.dispatcher) {
                selected?.let { null to container.financialApprovalPreview(it) }
                    ?: (container.financialReviewPage(after) to null)
            }
            rows = snapshot.first; preview = snapshot.second
            if (selected != null && preview == null) failed = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    fun perform(action: () -> BetaFinancialAdoptionResult) {
        if (busy) return
        busy = true; failed = false; resultReason = null
        scope.launch {
            try {
                val result = withContext(BetaIo.dispatcher) { action() }
                val posted = (result as? BetaFinancialAdoptionResult.Posted)?.result
                if (posted is FinancialPostingResult.Applied || posted is FinancialPostingResult.AlreadyApplied) {
                    selected = null; generation++
                } else { resultReason = (result as? BetaFinancialAdoptionResult.Review)?.reason ?: "FINANCIAL_REVIEW"; generation++ }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { resultReason = "FINANCIAL_REVIEW"; generation++ }
            finally { busy = false }
        }
    }
    BackHandler { if (!busy) { if (selected != null) selected = null else onClose() } }
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        Text(stringResource(R.string.beta_approval_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.beta_approval_help))
        if (failed) Text(stringResource(R.string.beta_review_failed), color = MaterialTheme.colorScheme.error)
        resultReason?.let { Text(stringResource(approvalReason(it)), color = MaterialTheme.colorScheme.error) }
        if (rows == null && preview == null && !failed) CircularProgressIndicator()
        rows?.let { page ->
            if (page.isEmpty()) Text(stringResource(R.string.beta_review_empty))
            page.forEachIndexed { index, item ->
                OutlinedButton(onClick = { selected = item.evidenceId; resultReason = null }, enabled = !busy,
                    modifier = Modifier.testTag("approval-item-$index")) {
                    Text(stringResource(R.string.beta_approval_item, index + 1, stringResource(when(item.status) {
                        "ACTIVE" -> R.string.beta_review_active
                        "RETRY" -> R.string.beta_approval_pending
                        else -> R.string.beta_review_required
                    })))
                }
            }
            if (page.size == 25) OutlinedButton(onClick = { after = page.last().evidenceId }, enabled = !busy) { Text(stringResource(R.string.beta_history_next)) }
            if (after.isNotEmpty()) OutlinedButton(onClick = { after = "" }, enabled = !busy) { Text(stringResource(R.string.beta_history_first)) }
        }
        preview?.let { current -> key(current.item.evidenceId, current.item.archiveRevision, current.registryRevision, current.item.postingRevision, current.pending) {
            BetaFinancialApprovalEditor(current, busy, onCancel = { selected = null },
                onApprove = { request -> perform { container.approveEvidenceFinancial(request) } },
                onRetry = { current.pending?.let { expected -> perform { container.retryFinancialApproval(current.item.evidenceId, expected) } } },
                onCancelPending = { current.pending?.let { request -> perform {
                    if (container.cancelFinancialApproval(request)) BetaFinancialAdoptionResult.Review("CANCELLED")
                    else BetaFinancialAdoptionResult.Review("SUPERSEDED_APPROVAL")
                } } },
                onReconcile = { perform { container.reconcileApprovedEvidence(current.item.evidenceId) } })
        } }
        if (failed) OutlinedButton(onClick = { generation++ }, enabled = !busy) { Text(stringResource(R.string.beta_retry)) }
        if (selected == null) OutlinedButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("approval-close")) { Text(stringResource(R.string.beta_asset_cancel)) }
    }
}

@Composable
internal fun BetaFinancialApprovalEditor(preview: BetaFinancialApprovalPreview, busy: Boolean,
    onCancel: () -> Unit, onApprove: (BetaFinancialApproval) -> Unit,
    onRetry: () -> Unit, onCancelPending: () -> Unit, onReconcile: () -> Unit) {
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var income by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (!busy) onCancel() }
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        preview.amount?.let { Text("${it.amount.toPlainString()} ${it.currency.name}", style = MaterialTheme.typography.titleMedium) }
        preview.time?.let { Text(it.instant.atZone(it.zone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) }
        preview.merchant?.let { Text(stringResource(R.string.beta_approval_merchant, it)) }
        preview.exchangeRate?.let { Text(stringResource(R.string.beta_approval_rate, it)) }
        preview.internationalFee?.let { Text(stringResource(R.string.beta_approval_fee, "${it.amount.toPlainString()} ${it.currency.name}")) }
        preview.blockedReason?.let { Text(stringResource(approvalReason(it)), color = MaterialTheme.colorScheme.error) }
        if (preview.item.reason == "FX_UNAVAILABLE") Text(stringResource(R.string.beta_approval_fx_unknown))
        if (preview.pending != null) {
            Text(stringResource(R.string.beta_approval_pending_help))
            Text(stringResource(financialTypeLabel(preview.pending.type)))
            val assets = preview.assets.associateBy { it.id }
            val pendingSource = preview.pending.source?.let(com.baraa.masroof.lab.finance.classification.FinancialEndpoint::Owned)
                ?: com.baraa.masroof.lab.finance.classification.FinancialEndpoint.External
            val pendingDestination = preview.pending.destination?.let(com.baraa.masroof.lab.finance.classification.FinancialEndpoint::Owned)
                ?: com.baraa.masroof.lab.finance.classification.FinancialEndpoint.External
            Text(stringResource(R.string.beta_review_source) + ": " + endpointSummary(pendingSource, assets))
            Text(stringResource(R.string.beta_review_destination) + ": " + endpointSummary(pendingDestination, assets))
            if (preview.pending.archiveRevision != preview.item.archiveRevision ||
                preview.pending.expectedRegistryRevision?.let { it != preview.registryRevision } == true)
                Text(stringResource(R.string.beta_approval_stale), color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, enabled = !busy && preview.blockedReason == null, modifier = Modifier.testTag("approval-retry")) { Text(stringResource(R.string.beta_retry)) }
            OutlinedButton(onClick = onCancelPending, enabled = !busy, modifier = Modifier.testTag("approval-cancel-pending")) { Text(stringResource(R.string.beta_approval_cancel_pending)) }
        } else if (preview.correctionOwned) {
            OutlinedButton(onClick = onReconcile, enabled = !busy, modifier = Modifier.testTag("approval-reconcile")) { Text(stringResource(R.string.beta_approval_reconcile)) }
        } else if (preview.blockedReason == null) {
            Text(stringResource(R.string.beta_review_type))
            FinancialType.entries.filter { it !in setOf(FinancialType.UNKNOWN, FinancialType.ADJUSTMENT) }.forEach { value ->
                BetaChoice(selected = type == value.name, onClick = { type = value.name; confirmed = false }, enabled = !busy,
                    label = { Text(stringResource(financialTypeLabel(value))) }, modifier = Modifier.testTag("approval-type-${value.name}"))
            }
            @Composable fun endpoints(label: Int, prefix: String, selected: String?, choose: (String) -> Unit) {
                Text(stringResource(label))
                BetaChoice(selected == "external", { choose("external"); confirmed = false }, enabled = !busy,
                    label = { Text(stringResource(R.string.beta_review_external)) }, modifier = Modifier.testTag("approval-$prefix-external"))
                preview.assets.forEachIndexed { index, asset ->
                    BetaChoice(selected == betaAssetChoiceKey(asset.id), { choose(betaAssetChoiceKey(asset.id)); confirmed = false }, enabled = !busy,
                        label = { Column { Text(stringResource(bankName(asset.id.bank)))
                            Text(stringResource(R.string.beta_asset_summary, stringResource(assetKindLabel(asset.kind)), asset.last4.orEmpty(), index + 1)) } },
                        modifier = Modifier.testTag("approval-$prefix-$index"))
                }
            }
            endpoints(R.string.beta_review_source, "source", source) { source = it }
            endpoints(R.string.beta_review_destination, "destination", destination) { destination = it }
            if (type in setOf(FinancialType.INCOME.name, FinancialType.EXTERNAL_TRANSFER_IN.name)) IncomeAttribution.entries.forEach { value ->
                BetaChoice(income == value.name, { income = value.name; confirmed = false }, enabled = !busy,
                    label = { Text(stringResource(if (value == IncomeAttribution.SALARY) R.string.beta_approval_salary else R.string.beta_approval_other_income)) },
                    modifier = Modifier.testTag("approval-income-${value.name}"))
            }
            Row { Checkbox(confirmed, { confirmed = it }, enabled = !busy, modifier = Modifier.testTag("approval-confirm"))
                Text(stringResource(R.string.beta_approval_confirm)) }
            val request = betaApprovalDraft(preview, type, source, destination, income)
            Button(onClick = { request?.let(onApprove) }, enabled = !busy && confirmed && request != null,
                modifier = Modifier.testTag("approval-save")) { Text(stringResource(R.string.beta_approval_save)) }
        }
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("approval-cancel")) { Text(stringResource(R.string.beta_asset_cancel)) }
    }
}

internal fun approvalReason(reason: String) = when (reason) {
    "CONFIRM_LOAN" -> R.string.beta_complete_review_loan_reason
    "CONFIRM_OWNERSHIP", "LINK_DEBIT_ACCOUNT", "CONFIRM_CARD", "CONFIGURE_CARD", "AMBIGUOUS_OWNER", "CONFLICTING_ACCOUNT_LINK" -> R.string.beta_fix_ownership_reason
    "CONFIRM_TRANSFER", "CONFIRM_TRANSFER_OR_TYPE", "AUTOMATIC_POSTING_REVIEW" -> R.string.beta_fix_transfer_reason
    "POSSIBLE_DUPLICATE" -> R.string.beta_fix_duplicate_reason
    "FINANCIAL_APPROVAL_PENDING" -> R.string.beta_fix_saved_decision
    "INTERPRETATION_PENDING", "NO_COMPLETED_ARCHIVE" -> R.string.beta_approval_wait
    "BANK_DISABLED" -> R.string.beta_approval_bank_disabled
    "STALE_ARCHIVE", "STALE_POSTING", "STALE_REGISTRY", "SUPERSEDED_APPROVAL" -> R.string.beta_approval_stale
    "CORRECTION_OWNS_DECISION", "MANUAL_SUPPRESSION_PRESERVED", "CORRECTION_REVIEW" -> R.string.beta_approval_correction
    "CANCELLED" -> R.string.beta_approval_cancelled
    "FX_UNAVAILABLE" -> R.string.beta_approval_fx_unknown
    else -> R.string.beta_approval_unsafe
}

@Composable
internal fun endpointSummary(endpoint: FinancialEndpoint, assets: Map<com.baraa.masroof.lab.finance.asset.AssetId, com.baraa.masroof.lab.finance.asset.OwnedAsset>): String = when (endpoint) {
    FinancialEndpoint.External -> stringResource(R.string.beta_review_external)
    FinancialEndpoint.Unresolved -> stringResource(R.string.beta_review_unknown_endpoint)
    is FinancialEndpoint.Owned -> assets[endpoint.id]?.let {
        stringResource(bankName(it.id.bank)) + " · " + stringResource(assetKindLabel(it.kind)) + " · " + it.last4.orEmpty()
    } ?: stringResource(R.string.beta_review_unknown_endpoint)
}
