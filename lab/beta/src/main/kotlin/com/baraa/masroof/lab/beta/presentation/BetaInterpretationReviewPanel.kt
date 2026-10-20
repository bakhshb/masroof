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
import androidx.compose.ui.text.input.TextFieldValue
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import kotlinx.coroutines.*
import java.util.UUID

/** Original text is visible only in this explicit local review, never exported or logged. */
@Composable
internal fun BetaInterpretationReviewPanel(container: BetaContainer, evidenceId: String, onClose: () -> Unit,
    onBusyChanged: (Boolean) -> Unit = {}) {
    val scope = rememberBetaUiScope()
    var review by remember { mutableStateOf<BetaInterpretationReview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf(TextFieldValue()) }
    var selectedStart by remember { mutableIntStateOf(-1) }
    var selectedEnd by remember { mutableIntStateOf(-1) }
    var fieldName by rememberSaveable { mutableStateOf(BetaLabelField.FAMILY.name) }
    var value by rememberSaveable { mutableStateOf("") }
    var decisionId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var fieldsOpen by remember { mutableStateOf(false) }
    var valuesOpen by remember { mutableStateOf(false) }
    val field = BetaLabelField.valueOf(fieldName)
    suspend fun refresh() {
        review = withContext(BetaIo.dispatcher) { container.interpretationReview(evidenceId) }
        original = TextFieldValue(review?.body.orEmpty())
        // A reopened/restored form must not silently attach an old draft to a new interpretation.
        value = ""; confirmed = false; selectedStart = -1; selectedEnd = -1; decisionId = UUID.randomUUID().toString()
        loaded = true
    }
    BetaLaunchedEffect(evidenceId) {
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true; loaded = true }
    }
    SideEffect { onBusyChanged(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    BackHandler { if (!busy) onClose() }
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        Text(stringResource(R.string.beta_label_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.beta_label_help))
        if (!loaded) CircularProgressIndicator()
        if (loaded && review == null) Text(stringResource(R.string.beta_label_unavailable))
        review?.let { current ->
            OutlinedTextField(value = original, readOnly = true, enabled = !busy,
                onValueChange = { next ->
                    original = next.copy(text = current.body)
                    if (!next.selection.collapsed) {
                        selectedStart = next.selection.min; selectedEnd = next.selection.max; confirmed = false
                    }
                },
                label = { Text(stringResource(R.string.beta_label_original)) },
                modifier = Modifier.fillMaxWidth().testTag("label-original"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
            Text(stringResource(R.string.beta_label_select))
            if (selectedStart >= 0 && selectedEnd > selectedStart) {
                Text(stringResource(R.string.beta_label_selected, current.body.substring(selectedStart, selectedEnd)), modifier = Modifier.testTag("label-selected"))
            }
            Box {
                OutlinedButton(enabled = !busy, onClick = { fieldsOpen = true }, modifier = Modifier.testTag("label-field")) {
                    Text(stringResource(labelFieldName(field)))
                }
                DropdownMenu(expanded = fieldsOpen, onDismissRequest = { fieldsOpen = false }) {
                    BetaLabelField.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(stringResource(labelFieldName(choice))) }, modifier = Modifier.testTag("label-field-${choice.name}"),
                            onClick = { fieldName = choice.name; value = ""; confirmed = false; decisionId = UUID.randomUUID().toString(); fieldsOpen = false })
                    }
                }
            }
            current.observed[field]?.let { observed ->
                Text(stringResource(R.string.beta_label_observed, if (field == BetaLabelField.FAMILY) stringResource(labelFamilyName(observed)) else labelChoice(field, observed)))
            }
            if (field in setOf(BetaLabelField.FAMILY, BetaLabelField.CURRENCY, BetaLabelField.DIRECTION, BetaLabelField.EXECUTION_STATE)) {
                Box {
                    OutlinedButton(enabled = !busy, onClick = { valuesOpen = true }, modifier = Modifier.testTag("label-value-choice")) {
                        Text(if (value.isEmpty()) stringResource(R.string.beta_label_value) else if (field == BetaLabelField.FAMILY) stringResource(labelFamilyName(value)) else labelChoice(field, value))
                    }
                    DropdownMenu(expanded = valuesOpen, onDismissRequest = { valuesOpen = false }) {
                        val choices = if (field == BetaLabelField.FAMILY) com.baraa.masroof.lab.bank.MessageFamily.entries.map { it.name }
                            else when (field) {
                                BetaLabelField.DIRECTION -> com.baraa.masroof.lab.bank.MoneyDirection.entries.map { it.name }
                                BetaLabelField.EXECUTION_STATE -> listOf("EXECUTED", "PENDING", "REJECTED", "CANCELLED", "UNKNOWN")
                                else -> com.baraa.masroof.lab.finance.value.Currency.entries.map { it.name }
                            }
                        choices.forEach { choice ->
                            DropdownMenuItem(text = { Text(if (field == BetaLabelField.FAMILY) stringResource(labelFamilyName(choice)) else labelChoice(field, choice)) },
                                modifier = Modifier.testTag("label-value-$choice"), onClick = { value = choice; confirmed = false; valuesOpen = false })
                        }
                    }
                }
            } else OutlinedTextField(value = value, enabled = !busy, onValueChange = { value = it; confirmed = false },
                label = { Text(stringResource(R.string.beta_label_value)) }, modifier = Modifier.fillMaxWidth().testTag("label-value"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
            Row {
                Checkbox(checked = confirmed, enabled = !busy, onCheckedChange = { confirmed = it }, modifier = Modifier.testTag("label-confirm"))
                Text(stringResource(R.string.beta_label_confirm))
            }
            Button(enabled = !busy && confirmed && value.isNotBlank() && selectedStart >= 0 && selectedEnd > selectedStart,
                modifier = Modifier.testTag("label-save"), onClick = {
                    val draft = BetaLabelDraft(current.token, decisionId, field, value, selectedStart, selectedEnd, confirmed)
                    busy = true; failed = false
                    scope.launch {
                        try {
                            withContext(BetaIo.dispatcher) { container.saveInterpretationLabel(draft) }
                            refresh(); value = ""; confirmed = false; decisionId = UUID.randomUUID().toString()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.beta_label_save)) }
            Text(stringResource(R.string.beta_label_count, current.labels.size), modifier = Modifier.testTag("label-count"))
            current.labels.takeLast(25).forEach { label ->
                val currentBasis = label.archiveHash == current.token.archiveHash && label.attempt == current.token.attempt
                Text(stringResource(R.string.beta_label_saved_row, label.revision,
                    stringResource(labelFieldName(label.field)), if (label.field == BetaLabelField.FAMILY) stringResource(labelFamilyName(label.value)) else labelChoice(label.field, label.value)))
                Text(label.originalText)
                if (!currentBasis) Text(stringResource(R.string.beta_label_previous))
            }
        }
        if (failed) Text(stringResource(R.string.beta_action_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("label-error"))
        OutlinedButton(enabled = !busy, onClick = onClose, modifier = Modifier.testTag("label-close")) { Text(stringResource(R.string.beta_asset_cancel)) }
    }
}

private fun labelFieldName(field: BetaLabelField): Int = when (field) {
    BetaLabelField.FAMILY -> R.string.beta_label_field_family
    BetaLabelField.AMOUNT -> R.string.beta_label_field_amount
    BetaLabelField.CURRENCY -> R.string.beta_label_field_currency
    BetaLabelField.SOURCE_ACCOUNT -> R.string.beta_label_field_source
    BetaLabelField.DESTINATION_ACCOUNT -> R.string.beta_label_field_destination
    BetaLabelField.CARD -> R.string.beta_label_field_card
    BetaLabelField.DIRECTION -> R.string.beta_label_field_direction
    BetaLabelField.EXECUTION_STATE -> R.string.beta_label_field_execution
    BetaLabelField.AVAILABLE_BALANCE -> R.string.beta_label_field_available
    BetaLabelField.OUTSTANDING_BALANCE -> R.string.beta_label_field_outstanding
}
private fun labelFamilyName(value: String): Int = when (value) {
    "PURCHASE" -> R.string.beta_label_family_purchase; "TRANSFER_IN" -> R.string.beta_label_family_transfer_in
    "TRANSFER_OUT" -> R.string.beta_label_family_transfer_out; "CARD_PAYMENT" -> R.string.beta_label_family_card_payment
    "BILL_PAYMENT" -> R.string.beta_label_family_bill; "FINANCING_INSTALLMENT" -> R.string.beta_label_family_loan
    "WITHDRAWAL" -> R.string.beta_label_family_withdrawal; "REFUND" -> R.string.beta_label_family_refund
    "FEE" -> R.string.beta_label_family_fee; "BALANCE_NOTICE" -> R.string.beta_label_family_balance
    "OTP" -> R.string.beta_label_family_otp; "NON_FINANCIAL" -> R.string.beta_label_family_nonfinancial
    else -> R.string.beta_label_family_unknown
}

@Composable
private fun labelChoice(field: BetaLabelField, value: String): String = when (field) {
    BetaLabelField.DIRECTION -> stringResource(when (value) {
        "INCOMING" -> R.string.beta_label_incoming
        "OUTGOING" -> R.string.beta_label_outgoing
        "NEUTRAL" -> R.string.beta_label_neutral
        else -> R.string.beta_label_unknown
    })
    BetaLabelField.EXECUTION_STATE -> stringResource(when (value) {
        "EXECUTED" -> R.string.beta_label_executed
        "PENDING" -> R.string.beta_label_pending
        "REJECTED" -> R.string.beta_label_rejected
        "CANCELLED" -> R.string.beta_label_cancelled
        else -> R.string.beta_label_unknown
    })
    else -> value
}
