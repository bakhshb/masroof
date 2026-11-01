package com.baraa.masroof.presentation.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.baraa.masroof.presentation.common.MasroofOutlinedButton as OutlinedButton
import com.baraa.masroof.presentation.common.MasroofFormField as OutlinedTextField
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.presentation.common.MasroofTextButton as TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.application.settings.SettingsCommitmentsWorkflow
import com.baraa.masroof.domain.model.CommitmentRecurrence
import com.baraa.masroof.presentation.common.IconTextButtonOutlined
import com.baraa.masroof.presentation.common.MasroofCard
import com.baraa.masroof.presentation.common.MasroofIcons
import com.baraa.masroof.presentation.common.MasroofSecondaryScaffold
import com.baraa.masroof.presentation.common.MasroofSectionHeader
import com.baraa.masroof.presentation.locale.formatLocalizedTransactionDate
import com.baraa.masroof.ui.theme.MasroofSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCommitmentEditorScreen(
    commitment: ManagedCommitmentUi,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (SettingsCommitmentsWorkflow.CommitmentEditorDraft) -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    newSuggestion: Boolean = false,
) {
    var name by rememberSaveable { mutableStateOf(commitment.name) }
    var amountText by rememberSaveable { mutableStateOf(commitment.amount.amount.toPlainString()) }
    var transactionDate by rememberSaveable { mutableStateOf(commitment.transactionDate.toString()) }
    var recurrence by rememberSaveable { mutableStateOf(commitment.recurrence?.name) }
    var dueDate by rememberSaveable(commitment.dueDate?.toString()) {
        mutableStateOf(commitment.dueDate?.toString())
    }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showTransactionDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDueDatePicker by rememberSaveable { mutableStateOf(false) }
    // Accept the forms people type: Arabic digits and decimal mark, and thousands separators.
    val parsedAmount = com.baraa.masroof.application.analytics.normalizeAnalysisNumber(amountText)
        .replace(",", "").replace("٬", "").toBigDecimalOrNull()
    val canSave = !saving && name.isNotBlank() && parsedAmount != null && parsedAmount.signum() > 0

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.settings_commitment_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_commitment_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.settings_commitment_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
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

    MasroofSecondaryScaffold(
        title = stringResource(R.string.settings_commitment_edit_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_back),
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = MasroofSpacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.sectionGap),
        ) {
            MasroofCard {
                Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap)) {
                    OutlinedTextField(
                        visualTransformation = com.baraa.masroof.presentation.locale.AppDigitsVisualTransformation,
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.settings_commitment_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        visualTransformation = com.baraa.masroof.presentation.locale.AppDigitsVisualTransformation,
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(stringResource(R.string.settings_commitment_amount)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = { showTransactionDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.settings_commitment_transaction_date,
                            ) + ": " + formatLocalizedTransactionDate(LocalDate.parse(transactionDate)),
                        )
                    }
                    RecurrenceSelector(
                        selected = recurrence?.let { runCatching { CommitmentRecurrence.valueOf(it) }.getOrNull() },
                        onSelect = { selected -> recurrence = selected?.name },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionHeaderGap),
                    ) {
                        OutlinedButton(
                            onClick = { showDueDatePicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                dueDate?.let {
                                    formatLocalizedTransactionDate(LocalDate.parse(it))
                                } ?: stringResource(R.string.settings_commitment_due_date_none),
                            )
                        }
                        if (dueDate != null) {
                            OutlinedButton(onClick = { dueDate = null }) {
                                Text(stringResource(R.string.settings_commitment_due_date_clear))
                            }
                        }
                    }
                }
            }

            if (commitment.pauseIntervals.isNotEmpty()) {
                MasroofCard {
                    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.listItemGap)) {
                        MasroofSectionHeader(
                            title = stringResource(R.string.settings_commitment_pause_history),
                            icon = MasroofIcons.periodHint,
                        )
                        commitment.pauseIntervals.forEachIndexed { index, interval ->
                            val pausedLabel = formatLocalizedTransactionDate(interval.pausedAt)
                            val resumedLabel = interval.resumedAt?.let { formatLocalizedTransactionDate(it) }
                            Text(
                                text = if (resumedLabel != null) {
                                    stringResource(
                                        R.string.settings_commitment_pause_interval_resumed,
                                        pausedLabel,
                                        resumedLabel,
                                    )
                                } else {
                                    stringResource(R.string.settings_commitment_pause_interval_open, pausedLabel)
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

            com.baraa.masroof.presentation.common.MasroofButton(
                onClick = {
                    val amount = parsedAmount ?: return@MasroofButton
                    if (amount.signum() <= 0) return@MasroofButton
                    onSave(
                        SettingsCommitmentsWorkflow.CommitmentEditorDraft(
                            name = name.trim(),
                            amount = Money(amount.setScale(Money.SCALE, java.math.RoundingMode.HALF_EVEN), commitment.amount.currency),
                            transactionDate = LocalDate.parse(transactionDate),
                            recurrence = recurrence?.let { CommitmentRecurrence.valueOf(it) },
                            dueDate = dueDate?.let(LocalDate::parse),
                        ),
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_commitment_save)) }

            if (!newSuggestion) OutlinedButton(
                onClick = onToggleActive,
                enabled = !saving,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor =
                    if (commitment.active) com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.liability
                    else com.baraa.masroof.ui.theme.MasroofThemeExtras.extendedColors.inflow),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(
                    if (commitment.active) {
                        R.string.reference_pause_commitment
                    } else {
                        R.string.settings_commitment_enable
                    },
                )) }

            if (!newSuggestion) OutlinedButton(
                onClick = { showDeleteConfirm = true },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.reference_delete_commitment))
            }
        }
    }
}

@Composable
private fun RecurrenceSelector(
    selected: CommitmentRecurrence?,
    onSelect: (CommitmentRecurrence?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MasroofSpacing.inlineGap)) {
        MasroofSectionHeader(
            title = stringResource(R.string.settings_commitment_recurrence),
            icon = MasroofIcons.periodHint,
        )
        val options = recurrenceOptions()
        val labels = options.map { stringResource(it.second) }
        com.baraa.masroof.presentation.common.MasroofSelect(
            labels[options.indexOfFirst { it.first == selected }.coerceAtLeast(0)],
            labels, { index -> onSelect(options[index].first) }, Modifier.fillMaxWidth())
    }
}

private fun recurrenceOptions(): List<Pair<CommitmentRecurrence?, Int>> =
    listOf(
        null to R.string.settings_commitment_recurrence_none,
        CommitmentRecurrence.WEEKLY to R.string.settings_commitment_recurrence_weekly,
        CommitmentRecurrence.MONTHLY to R.string.settings_commitment_recurrence_monthly,
        CommitmentRecurrence.YEARLY to R.string.settings_commitment_recurrence_yearly,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitmentDatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = pickerState.selectedDateMillis
                if (millis != null) {
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())
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
