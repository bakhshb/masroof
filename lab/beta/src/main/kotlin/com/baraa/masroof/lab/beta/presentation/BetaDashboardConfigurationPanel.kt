package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedFormField as OutlinedTextField
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

@Composable
internal fun BetaDashboardConfigurationPanel(controls: BetaDashboardControls, facilities: List<FacilitySummary>,
    enabled: Boolean, onBusyChanged: (Boolean) -> Unit, onSaved: () -> Unit) {
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
        day = when (kind) { "SALARY" -> current.draft.salary?.day?.toString().orEmpty()
            "STATEMENT" -> current.draft.statements.singleOrNull { it.facilityId.bank.value == bank && it.facilityId.localId == id }?.closingDay?.toString().orEmpty()
            else -> "" }
        editor = kind
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
                Text(stringResource(R.string.beta_controls_salary) + ": " + (current.draft.salary?.day?.toString() ?: stringResource(R.string.beta_controls_unset)))
                OutlinedButton(enabled = enabled && !busy, onClick = { open("SALARY") }, modifier = Modifier.testTag("controls-salary")) { Text(stringResource(R.string.beta_controls_salary)) }
                facilities.forEachIndexed { index, facility ->
                    Text(stringResource(R.string.beta_controls_facility_number, index + 1))
                    Text(current.draft.statements.singleOrNull { it.facilityId == facility.facilityId }?.closingDay?.toString() ?: stringResource(R.string.beta_controls_unset))
                    OutlinedButton(enabled = enabled && !busy, onClick = { open("STATEMENT", facility.facilityId.localId, facility.facilityId.bank.value) },
                        modifier = Modifier.testTag("controls-statement-${facility.facilityId.bank.value}-${facility.facilityId.localId}")) { Text(stringResource(R.string.beta_controls_statement)) }
                }

            }
        }
    }
    if (editor != null && config?.available == true) {
        val current = checkNotNull(config)
        val stale = current.revision != expected || message == "CONFLICT"
        val facility: AssetId? = facilities.singleOrNull { it.facilityId.bank.value == selectedBank && it.facilityId.localId == selectedId }?.facilityId
        val missing = editor == "STATEMENT" && facility == null
        BetaBottomSheet(stringResource(if(editor=="SALARY") R.string.beta_controls_salary else R.string.beta_controls_statement), busy,
            onCancel = { editor = null; confirmation = null; message = null }, canApply = !stale && !missing && !failed,
            onApply = {
                prepare { base -> when (editor) {
                    "SALARY" -> BetaDashboardConfigurationWorkflow.salary(base, day)
                    "STATEMENT" -> BetaDashboardConfigurationWorkflow.statement(base, checkNotNull(facility), day)
                    else -> error("Unknown cycle editor")
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
            OutlinedTextField(label = { Text(stringResource(R.string.beta_controls_due_day)) }, value = day, onValueChange = { day = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("controls-day"), visualTransformation = com.baraa.masroof.lab.beta.presentation.approved.ApprovedDigitsVisualTransformation)
            Text(stringResource(R.string.beta_controls_period_help))
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
