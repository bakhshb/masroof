package com.baraa.masroof.lab.beta.presentation

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
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import com.baraa.masroof.lab.finance.classification.FinancialEndpoint
import com.baraa.masroof.lab.finance.corrections.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.UUID

@Composable
internal fun BetaReviewPanel(container: BetaContainer, onClose: () -> Unit, onBusyChanged: (Boolean) -> Unit = {}) {
    val scope = rememberBetaUiScope()
    var after by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<List<BetaReviewItem>?>(null) }
    var detail by remember { mutableStateOf<BetaReviewDetail?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    SideEffect { onBusyChanged(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    BetaLaunchedEffect(after, selected, generation) {
        failed = false; page = null; detail = null
        try {
            withContext(BetaIo.dispatcher) {
                if (selected == null) container.reviewPage(after) to null
                else null to container.reviewDetail(PostingKey(Environment.BETA, checkNotNull(selected)))
            }.let { page = it.first; detail = it.second; if (selected != null && detail == null) failed = true }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    BackHandler { if (!busy) { if (selected != null) selected = null else onClose() } }
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        Text(stringResource(R.string.beta_review_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.beta_review_help))
        if (failed) {
            Text(stringResource(if (selected != null && detail == null) R.string.beta_review_unavailable else R.string.beta_review_failed), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { generation++ }, enabled = !busy) { Text(stringResource(R.string.beta_retry)) }
            if (selected != null) OutlinedButton(onClick = { selected = null }, enabled = !busy) { Text(stringResource(R.string.beta_asset_cancel)) }
        }
        if (page == null && detail == null && !failed) CircularProgressIndicator()
        page?.let { rows ->
            if (rows.isEmpty()) Text(stringResource(R.string.beta_review_empty))
            rows.forEachIndexed { index, row ->
                OutlinedButton(onClick = { selected = row.key.transactionId }, enabled = !busy,
                    modifier = Modifier.testTag("review-item-$index")) {
                    Text(stringResource(R.string.beta_review_item, index + 1, stringResource(activityLabel(row.activity))))
                }
            }
            if (rows.size == 25) OutlinedButton(onClick = { after = rows.last().key.transactionId }, enabled = !busy) { Text(stringResource(R.string.beta_history_next)) }
            if (after.isNotEmpty()) OutlinedButton(onClick = { after = "" }, enabled = !busy) { Text(stringResource(R.string.beta_history_first)) }
        }
        detail?.let { current -> key(current.token) {
            BetaReviewEditor(current, busy, failed, onCancel = { selected = null }, onSave = { id, decision, metadata ->
                if (!busy) {
                    busy = true; failed = false
                    scope.launch {
                        try {
                            val result = withContext(BetaIo.dispatcher) { container.saveReview(current.token, id, decision, metadata) }
                            if (result is BetaCorrectionResult.Applied || result is BetaCorrectionResult.AlreadyApplied) { selected = null; generation++ }
                            else failed = true
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }
            })
        } }
        if (selected == null) OutlinedButton(onClick = onClose, enabled = !busy, modifier = Modifier.testTag("review-close")) { Text(stringResource(R.string.beta_asset_cancel)) }
    }
}

private fun activityLabel(activity: BetaLedgerActivity?) = when (activity) {
    BetaLedgerActivity.SUPPRESSED -> R.string.beta_review_suppressed
    BetaLedgerActivity.REVIEW -> R.string.beta_review_required
    else -> R.string.beta_review_active
}

/** Draft values survive recreation; dismiss/back never submits. No inferred financial confirmation. */
@Composable
internal fun BetaReviewEditor(detail: BetaReviewDetail, busy: Boolean, failed: Boolean,
    onCancel: () -> Unit, onSave: (String, FinancialDecision, Map<String, String>) -> Unit) {
    val input = detail.current?.overlay?.let { CorrectionSnapshots.export(it).financial } ?: detail.stored?.classification?.input ?: detail.observation.input
    var mode by rememberSaveable { mutableStateOf("note") }
    var note by rememberSaveable { mutableStateOf(detail.current?.overlay?.metadata?.get("note").orEmpty()) }
    var amount by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var endpoint by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var decisionId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    BackHandler { if (!busy) onCancel() }
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        Text("${input.amount.amount.toPlainString()} ${input.amount.currency.name}", style = MaterialTheme.typography.titleMedium)
        Text(stringResource(financialTypeLabel(input.storedType)))
        Text(input.time.instant.atZone(input.time.zone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        Text(stringResource(R.string.beta_review_source) + ": " + endpointSummary(input.source, input.assets))
        Text(stringResource(R.string.beta_review_destination) + ": " + endpointSummary(input.destination, input.assets))
        Text(stringResource(activityLabel(detail.current?.activity)))
        if (detail.current?.reasons?.isNotEmpty() == true) Text(stringResource(R.string.beta_review_blockers))
        if (detail.stored?.matchIdentity != null) Text(stringResource(R.string.beta_review_pair))
        val modes = listOf("note" to R.string.beta_review_note, "amount" to R.string.beta_review_amount,
            "type" to R.string.beta_review_type, "suppress" to R.string.beta_review_suppress,
            "time" to R.string.beta_review_time, "source" to R.string.beta_review_source, "destination" to R.string.beta_review_destination)
        val selectedFinancial = detail.current?.overlay?.let { CorrectionSnapshots.export(it).financial } != null || detail.stored != null
        modes.forEach { (value, label) ->
            BetaChoice(selected = mode == value, onClick = { mode = value; confirmed = false; endpoint = null; decisionId = UUID.randomUUID().toString() },
                enabled = !busy && (value in setOf("note", "suppress") || detail.stored?.matchIdentity == null) &&
                    (value !in setOf("time", "source", "destination") || selectedFinancial), label = { Text(stringResource(label)) }, modifier = Modifier.testTag("review-mode-$value"))
        }
        when (mode) {
            "note" -> OutlinedTextField(note, { note = it; decisionId = UUID.randomUUID().toString() }, enabled = !busy,
                label = { Text(stringResource(R.string.beta_review_note)) }, modifier = Modifier.testTag("review-note"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
            "amount" -> OutlinedTextField(amount, { amount = it; confirmed = false; decisionId = UUID.randomUUID().toString() }, enabled = !busy,
                label = { Text(stringResource(R.string.beta_review_amount)) }, supportingText = { Text(input.amount.currency.name) }, modifier = Modifier.testTag("review-amount"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
            "time" -> OutlinedTextField(time, { time = it; confirmed = false; decisionId = UUID.randomUUID().toString() }, enabled = !busy,
                label = { Text(stringResource(R.string.beta_review_time)) }, supportingText = { Text(stringResource(R.string.beta_review_time_help)) }, modifier = Modifier.testTag("review-time"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
            "type" -> FinancialType.entries.filter { it !in setOf(FinancialType.UNKNOWN, FinancialType.ADJUSTMENT, FinancialType.SELF_TRANSFER) }.forEach { value ->
                BetaChoice(selected = type == value.name, onClick = { type = value.name; confirmed = false; decisionId = UUID.randomUUID().toString() }, enabled = !busy,
                    label = { Text(stringResource(financialTypeLabel(value))) }, modifier = Modifier.testTag("review-type-${value.name}"))
            }
            "source", "destination" -> {
                BetaChoice(selected = endpoint == "external", onClick = { endpoint = "external"; confirmed = false; decisionId = UUID.randomUUID().toString() },
                    enabled = !busy, label = { Text(stringResource(R.string.beta_review_external)) })
                detail.observation.input.assets.values.forEachIndexed { index, asset ->
                    val id = "${asset.id.bank.value}:${asset.id.localId}"
                    BetaChoice(selected = endpoint == id, onClick = { endpoint = id; confirmed = false; decisionId = UUID.randomUUID().toString() }, enabled = !busy,
                        label = { Column {
                            Text(stringResource(bankName(asset.id.bank)))
                            Text(stringResource(R.string.beta_asset_summary, stringResource(assetKindLabel(asset.kind)), asset.last4.orEmpty(), index + 1))
                        } })
                }
            }
        }
        val selectedEndpoint = if (endpoint == "external") FinancialEndpoint.External else detail.observation.input.assets.keys.singleOrNull { "${it.bank.value}:${it.localId}" == endpoint }?.let { FinancialEndpoint.Owned(it) }
        val decision = runCatching {
            when (mode) {
                "note" -> FinancialDecision.Keep
                "suppress" -> FinancialDecision.SuppressAsNonFinancial
                "amount" -> FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of(amount, input.amount.currency))
                "type" -> type?.let { FinancialDecision.ResolveFinancialType(FinancialType.valueOf(it)) }
                "time" -> {
                    val local = LocalDateTime.parse(time.trim(), java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT))
                    val offsets = input.time.zone.rules.getValidOffsets(local)
                    require(offsets.size == 1) // Ambiguous/gap wall times require a future explicit offset command.
                    FinancialDecision.EditFacts(time = TransactionTime(local.toInstant(offsets.single()), input.time.zone))
                }
                "source" -> selectedEndpoint?.let { FinancialDecision.EditFacts(source = it) }
                "destination" -> selectedEndpoint?.let { FinancialDecision.EditFacts(destination = it) }
                else -> null
            }
        }.getOrNull()
        if (mode != "note") {
            Row {
                Checkbox(confirmed, { confirmed = it }, enabled = !busy, modifier = Modifier.testTag("review-confirm"))
                Text(stringResource(R.string.beta_review_confirm))
            }
        }
        if (failed) Text(stringResource(R.string.beta_review_failed), color = MaterialTheme.colorScheme.error)
        Button(onClick = { decision?.let { onSave(decisionId, it, if (mode == "note") mapOf("note" to note) else emptyMap()) } },
            enabled = !busy && decision != null && (mode == "note" || confirmed), modifier = Modifier.testTag("review-save")) { Text(stringResource(R.string.beta_asset_save)) }
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.testTag("review-cancel")) { Text(stringResource(R.string.beta_asset_cancel)) }
    }
}

@Composable
internal fun endpointSummary(endpoint: FinancialEndpoint, assets: Map<com.baraa.masroof.lab.finance.asset.AssetId, com.baraa.masroof.lab.finance.asset.OwnedAsset>): String = when (endpoint) {
    FinancialEndpoint.External -> stringResource(R.string.beta_review_external)
    FinancialEndpoint.Unresolved -> stringResource(R.string.beta_review_unknown_endpoint)
    is FinancialEndpoint.Owned -> assets[endpoint.id]?.let {
        stringResource(bankName(it.id.bank)) + " · " + stringResource(assetKindLabel(it.kind)) + " · " + it.last4.orEmpty()
    } ?: stringResource(R.string.beta_review_unknown_endpoint)
}

internal fun financialTypeLabel(type: FinancialType) = when (type) {
    FinancialType.EXPENSE -> R.string.beta_review_expense
    FinancialType.INCOME -> R.string.beta_review_income
    FinancialType.EXTERNAL_TRANSFER_IN -> R.string.beta_review_transfer_in
    FinancialType.EXTERNAL_TRANSFER_OUT -> R.string.beta_review_transfer_out
    FinancialType.CREDIT_CARD_PAYMENT -> R.string.beta_review_card_payment
    FinancialType.REFUND -> R.string.beta_review_refund
    FinancialType.CASH_WITHDRAWAL -> R.string.beta_review_cash
    FinancialType.BILL_PAYMENT -> R.string.beta_review_bill
    FinancialType.LOAN_REPAYMENT -> R.string.beta_review_loan
    FinancialType.FEE -> R.string.beta_review_fee
    FinancialType.UNKNOWN -> R.string.beta_review_unknown_type
    FinancialType.SELF_TRANSFER -> R.string.beta_review_self_transfer
    FinancialType.ADJUSTMENT -> R.string.beta_review_adjustment
}
