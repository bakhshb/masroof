package com.baraa.masroof.lab.beta.presentation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedFormField as OutlinedTextField
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedOutlinedButton as OutlinedButton
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedTextButton as TextButton
import com.baraa.masroof.lab.finance.value.Money
import com.baraa.masroof.ui.components.MasroofCard
import com.baraa.masroof.ui.components.MasroofOutlineIcons
import com.baraa.masroof.ui.dashboard.DashboardSectionHeading as MasroofSectionHeader
import com.baraa.masroof.ui.theme.MasroofSpacing
import java.time.*

/** Source SettingsCommitmentEditorScreen; the host owns its app bar and navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BetaCommitmentEditorContent(
    commitment: BetaRecurringCommitment,
    saving: Boolean,
    onSave: (BetaCommitmentEditorDraft) -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(commitment.label) }
    var amountText by rememberSaveable { mutableStateOf(commitment.amount.amount.toPlainString()) }
    var transactionDate by rememberSaveable { mutableStateOf(commitment.anchorDate.toString()) }
    var recurrence by rememberSaveable { mutableStateOf(commitment.recurrence?.name) }
    var dueDate by rememberSaveable(commitment.dueDate?.toString()) {
        mutableStateOf(commitment.dueDate?.toString())
    }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showTransactionDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDueDatePicker by rememberSaveable { mutableStateOf(false) }
    val parsedAmount = amountText.trim().toBigDecimalOrNull()
    val canSave = !saving && name.isNotBlank() && parsedAmount != null && parsedAmount.signum() > 0

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if(!saving) showDeleteConfirm = false },
            title = { Text(stringResource(R.string.beta_commitment_settings_commitment_delete_confirm_title)) },
            text = { Text(stringResource(R.string.beta_commitment_settings_commitment_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.beta_commitment_settings_commitment_delete))
                }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.beta_commitment_settings_cancel))
                }
            },
        )
    }

    if (showTransactionDatePicker) {
        CommitmentDatePickerDialog(
            initialDate = LocalDate.parse(transactionDate),
            onDismiss = { showTransactionDatePicker = false },
            onConfirm = {
                transactionDate = it.toString()
                showTransactionDatePicker = false
            },
        )
    }

    if (showDueDatePicker) {
        CommitmentDatePickerDialog(
            initialDate = dueDate?.let(LocalDate::parse) ?: LocalDate.now(),
            onDismiss = { showDueDatePicker = false },
            onConfirm = {
                dueDate = it.toString()
                showDueDatePicker = false
            },
        )
    }

        Column(
            modifier = Modifier.testTag("commitment-editor")
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = MasroofSpacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap),
        ) {
            MasroofCard {
                Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                    OutlinedTextField(
                        visualTransformation = ApprovedDigitsVisualTransformation,
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.beta_commitment_settings_commitment_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("commitment-name"),
                        singleLine = true,
                        enabled = !saving,
                    )
                    OutlinedTextField(
                        visualTransformation = ApprovedDigitsVisualTransformation,
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(stringResource(R.string.beta_commitment_settings_commitment_amount)) },
                        modifier = Modifier.fillMaxWidth().testTag("commitment-amount"),
                        singleLine = true,
                        enabled = !saving,
                    )
                    OutlinedButton(
                        onClick = { showTransactionDatePicker = true },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.beta_commitment_settings_commitment_transaction_date,
                            ) + ": " + commitmentDateLabel(LocalDate.parse(transactionDate)),
                        )
                    }
                    RecurrenceSelector(
                        selected = recurrence?.let { runCatching { BetaCommitmentRecurrence.valueOf(it) }.getOrNull() },
                        onSelect = { selected -> recurrence = selected?.name },
                        enabled = !saving,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap),
                    ) {
                        OutlinedButton(
                            onClick = { showDueDatePicker = true },
                            enabled = !saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                dueDate?.let {
                                    commitmentDateLabel(LocalDate.parse(it))
                                } ?: stringResource(R.string.beta_commitment_settings_commitment_due_date_none),
                            )
                        }
                        if (dueDate != null) {
                            OutlinedButton(enabled = !saving, onClick = { dueDate = null }) {
                                Text(stringResource(R.string.beta_commitment_settings_commitment_due_date_clear))
                            }
                        }
                    }
                }
            }

            if (commitment.pauseIntervals.isNotEmpty()) {
                MasroofCard {
                    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
                        MasroofSectionHeader(
                            title = stringResource(R.string.beta_commitment_settings_commitment_pause_history),
                            icon = MasroofOutlineIcons.Info,
                        )
                        commitment.pauseIntervals.forEachIndexed { index, interval ->
                            val pausedLabel = commitmentDateLabel(interval.pausedAt.atZone(ZoneId.systemDefault()).toLocalDate())
                            val resumedLabel = interval.resumedAt?.let { commitmentDateLabel(it.atZone(ZoneId.systemDefault()).toLocalDate()) }
                            Text(
                                text = if (resumedLabel != null) {
                                    stringResource(
                                        R.string.beta_commitment_settings_commitment_pause_interval_resumed,
                                        pausedLabel,
                                        resumedLabel,
                                    )
                                } else {
                                    stringResource(R.string.beta_commitment_settings_commitment_pause_interval_open, pausedLabel)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (index < commitment.pauseIntervals.lastIndex) {
                                Spacer(Modifier.height(MasroofSpacing.inlineGap))
                            }
                        }
                    }
                }
            }

            ApprovedButton(
                onClick = {
                    val amount = parsedAmount ?: return@ApprovedButton
                    if (amount.signum() <= 0) return@ApprovedButton
                    onSave(
                        BetaCommitmentEditorDraft(
                            name = name.trim(),
                            amount = Money.of(amount.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(), commitment.amount.currency),
                            transactionDate = LocalDate.parse(transactionDate),
                            recurrence = recurrence?.let { BetaCommitmentRecurrence.valueOf(it) },
                            dueDate = dueDate?.let(LocalDate::parse),
                        ),
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().testTag("commitment-save"),
            ) { Text(stringResource(R.string.beta_commitment_settings_commitment_save)) }

            OutlinedButton(
                onClick = onToggleActive,
                enabled = !saving,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor =
                    if (commitment.active) com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.liability
                    else com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.inflow),
                modifier = Modifier.fillMaxWidth().testTag("commitment-toggle"),
            ) { Text(stringResource(
                    if (commitment.active) {
                        R.string.beta_commitment_reference_pause_commitment
                    } else {
                        R.string.beta_commitment_settings_commitment_enable
                    },
                )) }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.beta_commitment_reference_delete_commitment))
            }
        }
}

@Composable
private fun RecurrenceSelector(
    selected: BetaCommitmentRecurrence?,
    onSelect: (BetaCommitmentRecurrence?) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
        MasroofSectionHeader(
            title = stringResource(R.string.beta_commitment_settings_commitment_recurrence),
            icon = MasroofOutlineIcons.Info,
        )
        val options = recurrenceOptions()
        val labels = options.map { stringResource(it.second) }
        ApprovedSelect(
            labels[options.indexOfFirst { it.first == selected }.coerceAtLeast(0)],
            labels, { index -> onSelect(options[index].first) }, Modifier.fillMaxWidth(), enabled=enabled, tag="commitment-recurrence")
    }
}

private fun recurrenceOptions(): List<Pair<BetaCommitmentRecurrence?, Int>> =
    listOf(
        null to R.string.beta_commitment_settings_commitment_recurrence_none,
        BetaCommitmentRecurrence.WEEKLY to R.string.beta_commitment_settings_commitment_recurrence_weekly,
        BetaCommitmentRecurrence.MONTHLY to R.string.beta_commitment_settings_commitment_recurrence_monthly,
        BetaCommitmentRecurrence.YEARLY to R.string.beta_commitment_settings_commitment_recurrence_yearly,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitmentDatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = pickerState.selectedDateMillis
                if (millis != null) {
                    onConfirm(Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate())
                }
                onDismiss()
            }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}
