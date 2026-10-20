package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.FacilitySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.YearMonth

@Composable
internal fun BetaDashboardConfigurationPanel(controls: BetaDashboardControls, facilities: List<FacilitySummary>,
    month: YearMonth, enabled: Boolean, onBusyChanged: (Boolean) -> Unit, onSaved: () -> Unit, commitmentsOnly: Boolean = false) {
    val scope = rememberBetaUiScope()
    var config by remember { mutableStateOf<BetaDashboardConfiguration?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedBank by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var expected by rememberSaveable { mutableLongStateOf(-1L) }
    var day by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var starts by rememberSaveable { mutableStateOf("") }
    var ends by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<BetaDashboardConfigurationDraft?>(null) }
    BetaLaunchedEffect(controls, reload) {
        config = null; failed = false
        try { config = controls.read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    fun open(kind: String, id: String? = null, bank: String? = null) {
        if (busy || !enabled) return
        val current = config ?: return
        expected = current.revision; selectedId = id; selectedBank = bank; message = null
        val row = current.draft.commitments.singleOrNull { it.id == id }
        day = when (kind) { "SALARY" -> current.draft.salary?.day?.toString().orEmpty()
            "STATEMENT" -> current.draft.statements.singleOrNull { it.facilityId.bank.value == bank && it.facilityId.localId == id }?.closingDay?.toString().orEmpty()
            else -> row?.dueDay?.toString().orEmpty() }
        label = row?.label.orEmpty(); amount = row?.amount?.amount?.toPlainString().orEmpty()
        starts = row?.starts?.toString() ?: month.toString(); ends = row?.ends?.toString().orEmpty(); editor = kind
    }
    fun prepare(action: (BetaDashboardConfigurationDraft) -> BetaDashboardConfigurationDraft) {
        val current = config ?: return
        if (current.revision != expected) { message = "CONFLICT"; return }
        try { confirmation = action(current.draft); message = null }
        catch (_: Exception) { message = "INVALID" }
    }
    Column(Modifier.testTag("dash-configuration"), verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        Text(stringResource(R.string.beta_controls_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.beta_controls_help))
        when {
            failed -> {
                Text(stringResource(R.string.beta_dash_error), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { reload++ }, modifier = Modifier.testTag("controls-retry")) { Text(stringResource(R.string.beta_retry)) }
            }
            config == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            config?.available != true -> Text(stringResource(R.string.beta_controls_unavailable), modifier = Modifier.testTag("controls-unavailable"))
            else -> {
                val current = checkNotNull(config)
                if (!commitmentsOnly) {
                Text(stringResource(R.string.beta_controls_salary) + ": " + (current.draft.salary?.day?.toString() ?: stringResource(R.string.beta_controls_unset)))
                OutlinedButton(enabled = enabled && !busy, onClick = { open("SALARY") }, modifier = Modifier.testTag("controls-salary")) { Text(stringResource(R.string.beta_controls_salary)) }
                facilities.forEachIndexed { index, facility ->
                    Text(stringResource(R.string.beta_controls_facility_number, index + 1))
                    Text(current.draft.statements.singleOrNull { it.facilityId == facility.facilityId }?.closingDay?.toString() ?: stringResource(R.string.beta_controls_unset))
                    OutlinedButton(enabled = enabled && !busy, onClick = { open("STATEMENT", facility.facilityId.localId, facility.facilityId.bank.value) },
                        modifier = Modifier.testTag("controls-statement-${facility.facilityId.bank.value}-${facility.facilityId.localId}")) { Text(stringResource(R.string.beta_controls_statement)) }
                }
                }
                Text(stringResource(R.string.beta_controls_recurring), style = MaterialTheme.typography.titleMedium)
                if (current.draft.commitments.isEmpty()) Text(stringResource(R.string.beta_dash_empty))
                current.draft.commitments.forEach { row ->
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(BetaSpacing.screen), verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
                        Text(row.label); Text("${row.amount.amount.toPlainString()} ${row.amount.currency.name}")
                        Text(stringResource(R.string.beta_controls_due_day) + ": ${row.dueDay}")
                        Text("${row.starts} — ${row.ends ?: stringResource(R.string.beta_controls_no_end)}")
                        OutlinedButton(enabled = enabled && !busy, onClick = { open("COMMITMENT", row.id) }, modifier = Modifier.testTag("controls-edit-${row.id}")) { Text(stringResource(R.string.beta_controls_edit)) }
                        OutlinedButton(enabled = enabled && !busy, onClick = { open("DELETE", row.id) }, modifier = Modifier.testTag("controls-delete-${row.id}")) { Text(stringResource(R.string.beta_controls_delete)) }
                        if (month >= row.starts && (row.ends == null || month <= row.ends)) {
                            OutlinedButton(enabled = enabled && !busy, onClick = { open("PAID", row.id) }, modifier = Modifier.testTag("controls-paid-${row.id}")) {
                                Text(stringResource(if (month in row.paidMonths) R.string.beta_controls_mark_unpaid else R.string.beta_controls_mark_paid, month.toString()))
                            }
                        }
                    } }
                }
                OutlinedButton(enabled = enabled && !busy, onClick = { open("COMMITMENT") }, modifier = Modifier.testTag("controls-add")) { Text(stringResource(R.string.beta_controls_add)) }
            }
        }
    }
    if (editor != null && config?.available == true) {
        val current = checkNotNull(config)
        val stale = current.revision != expected || message == "CONFLICT"
        val facility: AssetId? = facilities.singleOrNull { it.facilityId.bank.value == selectedBank && it.facilityId.localId == selectedId }?.facilityId
        val missing = editor == "STATEMENT" && facility == null || editor in setOf("DELETE", "PAID") && current.draft.commitments.none { it.id == selectedId }
        BetaBottomSheet(stringResource(when (editor) { "SALARY" -> R.string.beta_controls_salary; "STATEMENT" -> R.string.beta_controls_statement
            "DELETE" -> R.string.beta_controls_delete; "PAID" -> R.string.beta_controls_payment; else -> R.string.beta_controls_recurring }), busy,
            onCancel = { editor = null; confirmation = null; message = null }, canApply = !stale && !missing && !failed,
            onApply = {
                prepare { base -> when (editor) {
                    "SALARY" -> BetaDashboardConfigurationWorkflow.salary(base, day)
                    "STATEMENT" -> BetaDashboardConfigurationWorkflow.statement(base, checkNotNull(facility), day)
                    "DELETE" -> BetaDashboardConfigurationWorkflow.remove(base, checkNotNull(selectedId))
                    "PAID" -> BetaDashboardConfigurationWorkflow.paid(base, checkNotNull(selectedId), month,
                        month !in base.commitments.single { it.id == selectedId }.paidMonths)
                    else -> BetaDashboardConfigurationWorkflow.commitment(base, selectedId, label, amount, day, starts, ends)
                } }
            }) {
            if (stale) {
                Text(stringResource(R.string.beta_controls_conflict), modifier = Modifier.testTag("controls-conflict"))
                OutlinedButton(onClick = { editor = null; confirmation = null; reload++ }, enabled = !busy, modifier = Modifier.testTag("controls-reload")) { Text(stringResource(R.string.beta_controls_reload)) }
            }
            if (missing) Text(stringResource(R.string.beta_dash_missing))
            when (message) {
                "INVALID" -> Text(stringResource(R.string.beta_controls_invalid), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("controls-invalid"))
                "ERROR" -> Text(stringResource(R.string.beta_dash_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("controls-error"))
                "UNAVAILABLE" -> Text(stringResource(R.string.beta_controls_unavailable))
            }
            if (editor in setOf("SALARY", "STATEMENT", "COMMITMENT")) {
                if (editor == "COMMITMENT") {
                    OutlinedTextField(label = { Text(stringResource(R.string.beta_controls_label)) }, value = label, onValueChange = { label = it }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("controls-label"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
                    OutlinedTextField(label = { Text(stringResource(R.string.beta_controls_amount)) }, value = amount, onValueChange = { amount = it }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("controls-amount"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
                    OutlinedTextField(label = { Text(stringResource(R.string.beta_controls_starts)) }, value = starts, onValueChange = { starts = it }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("controls-starts"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
                    OutlinedTextField(label = { Text(stringResource(R.string.beta_controls_ends)) }, value = ends, onValueChange = { ends = it }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("controls-ends"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
                }
                OutlinedTextField(label = { Text(stringResource(R.string.beta_controls_due_day)) }, value = day, onValueChange = { day = it }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("controls-day"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
                Text(stringResource(if (editor == "COMMITMENT") R.string.beta_controls_clamp else R.string.beta_controls_period_help))
            } else {
                Text(label); Text(month.toString()); Text(stringResource(if (editor == "PAID") R.string.beta_controls_payment_help else R.string.beta_controls_delete_help))
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("controls-busy"))
        }
    }
    confirmation?.let { draft ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text(stringResource(R.string.beta_controls_confirm)) },
            text = { Text(stringResource(R.string.beta_controls_confirm_help)) },
            confirmButton = { Button(enabled = !busy, modifier = Modifier.testTag("controls-confirm"), onClick = {
                if (busy) return@Button
                busy = true; onBusyChanged(true)
                scope.launch {
                    try {
                        when (val saved = controls.save(expected, draft)) {
                            is BetaDashboardConfigurationWrite.Saved -> { config = saved.configuration; editor = null; message = null; onSaved() }
                            is BetaDashboardConfigurationWrite.Conflict -> { config = saved.current; message = "CONFLICT" }
                            is BetaDashboardConfigurationWrite.Invalid -> message = "INVALID"
                            BetaDashboardConfigurationWrite.Unavailable -> message = "UNAVAILABLE"
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "ERROR" }
                    finally { busy = false; onBusyChanged(false); confirmation = null }
                }
            }) { Text(stringResource(R.string.beta_controls_confirm)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = null }) { Text(stringResource(R.string.beta_controls_cancel)) } })
    }
}
