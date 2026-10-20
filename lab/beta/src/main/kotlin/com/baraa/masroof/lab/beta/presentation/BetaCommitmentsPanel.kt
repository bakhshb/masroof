package com.baraa.masroof.lab.beta.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.presentation.approved.*
import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text
import com.baraa.masroof.ui.components.*
import com.baraa.masroof.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter

/** Source settings journey. All writes go through the revision-checked application facade. */
@Composable
internal fun BetaCommitmentsPanel(controls: BetaDashboardControls, externalBusy: Boolean,
    onBusyChanged: (Boolean)->Unit, onTitle: (String?)->Unit, initialId: String? = null, onDirectExit: ()->Unit = {}) {
    val workflow=remember(controls) {BetaCommitmentsWorkflow(controls)}
    val scope=rememberBetaUiScope()
    var config by remember {mutableStateOf<BetaDashboardConfiguration?>(null)}
    var history by remember {mutableStateOf<List<BetaCommitmentHistoryEntry>?>(null)}
    var tab by rememberSaveable {mutableStateOf("ACTIVE")}
    var selectedId by rememberSaveable {mutableStateOf<String?>(initialId)}
    var expectedRevision by rememberSaveable {mutableLongStateOf(-1)}
    var reload by remember {mutableIntStateOf(0)}
    var saving by remember {mutableStateOf(false)}
    var loading by remember {mutableStateOf(true)}
    var failure by remember {mutableStateOf<String?>(null)}
    val busy=externalBusy || saving
    val title=stringResource(if(selectedId==null) R.string.beta_commitment_reference_manage_commitments else R.string.beta_commitment_settings_commitment_edit_title)
    SideEffect {onTitle(title)}
    DisposableEffect(Unit) {onDispose {onTitle(null);onBusyChanged(false)}}
    BetaLaunchedEffect(workflow,reload) {
        loading=true;failure=null;history=null
        try {
            config=workflow.read().also {check(it.available);if(expectedRevision<0 && initialId!=null) expectedRevision=it.revision}
            history=workflow.history()
        } catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {failure="ERROR"}
        finally {loading=false}
    }
    BackHandler(selectedId!=null) {if(!busy) {if(initialId!=null) onDirectExit() else selectedId=null;failure=null}}
    fun write(action: suspend ()->BetaDashboardConfigurationWrite,close: Boolean=false) {
        if(busy || loading) return
        saving=true;onBusyChanged(true);failure=null
        scope.launch {
            try {
                when(val result=action()) {
                    is BetaDashboardConfigurationWrite.Saved -> {
                        config=result.configuration;expectedRevision=result.configuration.revision
                        if(close) {if(initialId!=null) onDirectExit() else selectedId=null}
                        history=null
                        history=workflow.history()
                    }
                    is BetaDashboardConfigurationWrite.Conflict -> failure="CONFLICT"
                    is BetaDashboardConfigurationWrite.Invalid -> failure="INVALID"
                    BetaDashboardConfigurationWrite.Unavailable -> failure="UNAVAILABLE"
                }
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(_: Exception) {failure="ERROR"}
            finally {saving=false;onBusyChanged(false)}
        }
    }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        if(failure!=null) {
            Text(stringResource(when(failure) {"CONFLICT"->R.string.beta_controls_conflict;"INVALID"->R.string.beta_controls_invalid;"UNAVAILABLE"->R.string.beta_controls_unavailable;else->R.string.beta_dash_error}),
                color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("commitments-error"))
            ApprovedOutlinedButton(enabled=!busy,onClick={if(initialId==null) selectedId=null;expectedRevision=-1;reload++},modifier=Modifier.testTag("commitments-retry")) {Text(stringResource(R.string.beta_controls_reload))}
        }
        if(loading) CircularProgressIndicator(Modifier.testTag("commitments-loading"))
        else if(config?.available==true) {
            val current=checkNotNull(config)
            val selected=current.draft.commitments.singleOrNull {it.id==selectedId}
            if(selectedId!=null && selected==null) {
                Text(stringResource(R.string.beta_dash_missing))
            } else if(selected!=null) key(selected.id,reload) {
                BetaCommitmentEditorContent(selected,busy,
                    onSave={draft -> write({workflow.edit(selected.id,expectedRevision,draft)})},
                    onToggleActive={write({workflow.toggle(selected.id,expectedRevision)})},
                    onDelete={write({workflow.delete(selected.id,expectedRevision)},close=true)})
            } else BetaCommitmentsContent(current.draft.commitments,history.orEmpty(),tab,busy,
                onTab={tab=it},onEdit={selectedId=it;expectedRevision=current.revision;failure=null},
                onToggle={id -> write({workflow.toggle(id,current.revision)})},
                onDelete={id -> write({workflow.delete(id,current.revision)})},historyAvailable=history!=null)
        }
    }
}

/** Source SettingsCommitmentsScreen, without a second app bar or a second scroll owner. */
@Composable
internal fun BetaCommitmentsContent(commitments: List<BetaRecurringCommitment>,history: List<BetaCommitmentHistoryEntry>,
    tab: String,busy: Boolean,onTab: (String)->Unit,onEdit: (String)->Unit,onToggle: (String)->Unit,onDelete: (String)->Unit,historyAvailable: Boolean=true) {
    var deleteId by rememberSaveable {mutableStateOf<String?>(null)}
    deleteId?.let {id -> AlertDialog(onDismissRequest={if(!busy) deleteId=null},
        title={Text(stringResource(R.string.beta_commitment_settings_commitment_delete_confirm_title))},
        text={Text(stringResource(R.string.beta_commitment_settings_commitment_delete_confirm_body))},
        confirmButton={ApprovedTextButton(enabled=!busy,onClick={deleteId=null;onDelete(id)},modifier=Modifier.testTag("commitment-delete-confirm")) {Text(stringResource(R.string.beta_commitment_reference_delete))}},
        dismissButton={ApprovedTextButton(enabled=!busy,onClick={deleteId=null},modifier=Modifier.testTag("commitment-delete-cancel")) {Text(stringResource(R.string.beta_commitment_settings_cancel))}})
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("commitments-list"),
        verticalArrangement=Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
        Row(horizontalArrangement=Arrangement.spacedBy(MasroofReference.shortcutGap)) {
            listOf("ACTIVE" to R.string.beta_commitment_settings_commitments_tab_active,
                "DISABLED" to R.string.beta_commitment_settings_commitments_tab_disabled,"RECORD" to R.string.beta_commitment_settings_commitments_tab_record).forEach {(key,label) ->
                ApprovedChoice(stringResource(label),tab==key,{onTab(key)},enabled=!busy,modifier=Modifier.testTag("commitments-tab-$key"))
            }
        }
        if(tab=="RECORD") {
            if(!historyAvailable) Text(stringResource(R.string.beta_dash_error),color=MaterialTheme.colorScheme.error)
            else if(history.isEmpty()) CommitmentEmpty(R.string.beta_commitment_settings_commitments_record_empty)
            history.forEachIndexed {index,entry ->
                val event=stringResource(when(entry.event) {
                    BetaCommitmentRecordEvent.CREATED->R.string.beta_commitment_settings_commitment_record_created
                    BetaCommitmentRecordEvent.PAUSED->R.string.beta_commitment_settings_commitment_record_paused
                    BetaCommitmentRecordEvent.RESUMED->R.string.beta_commitment_settings_commitment_record_resumed})
                ApprovedSettingsNavigationRow(entry.name,stringResource(R.string.beta_commitment_settings_commitment_record_line,event,commitmentDateLabel(entry.at.atZone(ZoneId.systemDefault()).toLocalDate())),
                    when(entry.event) {BetaCommitmentRecordEvent.CREATED->MasroofOutlineIcons.CalendarCheck;BetaCommitmentRecordEvent.PAUSED->MasroofOutlineIcons.TriangleAlert;BetaCommitmentRecordEvent.RESUMED->MasroofOutlineIcons.ArrowDownLeft},
                    !busy,{onEdit(entry.id)},Modifier.testTag("commitment-record-$index"))
            }
        } else {
            val rows=BetaCommitmentLists.rows(commitments,tab=="ACTIVE",ZoneId.systemDefault())
            if(rows.isEmpty()) CommitmentEmpty(if(tab=="ACTIVE") R.string.beta_commitment_settings_commitments_active_empty else R.string.beta_commitment_settings_commitments_disabled_empty)
            rows.forEach {row -> MasroofCard(Modifier.testTag("commitment-${row.id}")) {
                Text(row.label,style=MaterialTheme.typography.titleMedium)
                ApprovedDetailRow(stringResource(R.string.beta_commitment_settings_commitment_amount),MasroofMoneyDisplay.format(row.amount.amount,row.amount.currency.name,LocalConfiguration.current.locales[0].toLanguageTag()))
                ApprovedDetailRow(stringResource(R.string.beta_commitment_settings_commitment_recurrence),commitmentRecurrenceLabel(row.recurrence))
                ApprovedDetailRow(stringResource(R.string.beta_commitment_settings_commitment_transaction_date),commitmentDateLabel(row.anchorDate))
                Row(horizontalArrangement=Arrangement.spacedBy(MasroofSpacing.smallGap)) {
                    ApprovedManagementAction(stringResource(R.string.beta_commitment_reference_edit),{onEdit(row.id)},enabled=!busy,modifier=Modifier.testTag("commitment-edit-${row.id}"))
                    ApprovedManagementAction(stringResource(if(row.active) R.string.beta_commitment_reference_pause else R.string.beta_commitment_settings_commitment_enable),{onToggle(row.id)},enabled=!busy,modifier=Modifier.testTag("commitment-toggle-${row.id}"))
                    ApprovedManagementAction(stringResource(R.string.beta_commitment_reference_delete),{deleteId=row.id},danger=true,enabled=!busy,modifier=Modifier.testTag("commitment-delete-${row.id}"))
                }
            }}
        }
    }
}

@Composable private fun CommitmentEmpty(resource: Int) {
    Text(stringResource(resource),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=Modifier.padding(horizontal=MasroofSpacing.screenHorizontal))
}
@Composable internal fun commitmentDateLabel(date: LocalDate): String =
    DateTimeFormatter.ofPattern("d MMM",LocalConfiguration.current.locales[0]).format(date)
@Composable internal fun commitmentRecurrenceLabel(recurrence: BetaCommitmentRecurrence?): String = stringResource(when(recurrence) {
    null->R.string.beta_commitment_settings_commitment_recurrence_none
    BetaCommitmentRecurrence.WEEKLY->R.string.beta_commitment_settings_commitment_recurrence_weekly
    BetaCommitmentRecurrence.MONTHLY->R.string.beta_commitment_settings_commitment_recurrence_monthly
    BetaCommitmentRecurrence.YEARLY->R.string.beta_commitment_settings_commitment_recurrence_yearly
})
