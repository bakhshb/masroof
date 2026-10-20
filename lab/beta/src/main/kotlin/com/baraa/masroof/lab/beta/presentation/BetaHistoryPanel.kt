package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedText as Text

import com.baraa.masroof.lab.beta.presentation.approved.ApprovedImportDateStep

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.baraa.masroof.lab.beta.BetaIo
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.R
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.application.history.HistorySession
import com.baraa.masroof.lab.beta.presentation.theme.BetaSpacing
import kotlinx.coroutines.*

/** Explicit date-scoped import. No reads, jobs or automatic resume on entering this screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BetaHistoryPanel(container: BetaContainer, onClose: () -> Unit, onBusyChanged: (Boolean) -> Unit = {}, onFinancialReview: ((String) -> Unit)? = null, reviewOnly: Boolean = false, onConfirmAccounts: (() -> Unit)? = null, onContinueSetup: (() -> Unit)? = null) {
    var selectedEvidence by rememberSaveable { mutableStateOf<String?>(null) }
    selectedEvidence?.let { id ->
        BetaInterpretationReviewPanel(container, id, onClose = { selectedEvidence = null }, onBusyChanged = onBusyChanged)
        return
    }
    val context = LocalContext.current
    val scope = rememberBetaUiScope()
    var session by remember { mutableStateOf<HistorySession?>(null) }
    var page by remember { mutableStateOf<List<EvidenceSummary>>(emptyList()) }
    var needsReviewOnly by rememberSaveable { mutableStateOf(true) }
    var counts by remember { mutableStateOf<EvidenceInboxCounts?>(null) }
    var after by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var dateOption by rememberSaveable { mutableStateOf(com.baraa.masroof.lab.beta.application.history.BetaImportDateOption.LAST_27TH) }
    var customEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }
    val today=java.time.LocalDate.now(com.baraa.masroof.lab.beta.application.history.BetaImportDatePolicy.zone)
    val selectedDate=com.baraa.masroof.lab.beta.application.history.BetaImportDatePolicy.date(dateOption,today,customEpochDay?.let(java.time.LocalDate::ofEpochDay) ?: today)
    val datePicker=rememberDatePickerState(initialSelectedDateMillis=selectedDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
    var pendingDate by rememberSaveable { mutableStateOf<Long?>(null) }
    suspend fun refresh() {
        val snapshot = withContext(BetaIo.dispatcher) { Triple(container.historySession(), container.evidencePage(after, needsReviewOnly = needsReviewOnly), container.evidenceInboxCounts()) }
        session = snapshot.first
        page = snapshot.second
        counts = snapshot.third
    }
    SideEffect { onBusyChanged(busy) }
    DisposableEffect(Unit) { onDispose { onBusyChanged(false) } }
    BackHandler { if (!busy) onClose() }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try { block(); refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                failed = true
                // The durable checkpoint can advance even when a later batch operation fails.
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the failure visible; reopening may recover storage. */ }
            }
            finally { busy = false; running = false }
        }
    }
    fun scan(newDate: java.time.LocalDate?) = action {
        if (newDate != null) session = withContext(BetaIo.dispatcher) { container.startHistoryFrom(newDate) }
        running = true
        do {
            val current = withContext(BetaIo.dispatcher) { container.stepHistory() }
            session = current
            yield()
        } while (running && !current.finished && !current.limited)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingDate
        pendingDate = null
        denied = !granted
        // A grant does not authorize an automatic restarted import; user explicitly resumes/starts.
        if (granted && requested != null) scan(requested.takeIf { it != Long.MIN_VALUE }?.let(java.time.LocalDate::ofEpochDay))
    }
    BetaLaunchedEffect(needsReviewOnly) {
        try { refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true }
    }
    if(datePickerOpen) DatePickerDialog(onDismissRequest={datePickerOpen=false},confirmButton={
        TextButton(onClick={datePicker.selectedDateMillis?.let { millis ->
            val date=java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            if(!date.isAfter(today)) { customEpochDay=date.toEpochDay();dateOption=com.baraa.masroof.lab.beta.application.history.BetaImportDateOption.CUSTOM;datePickerOpen=false }
        }}) { Text(stringResource(R.string.beta_asset_save)) }
    },dismissButton={TextButton(onClick={datePickerOpen=false}) {Text(stringResource(R.string.beta_asset_cancel))}}) { DatePicker(state=datePicker) }
    Column(verticalArrangement = Arrangement.spacedBy(BetaSpacing.section)) {
        if (!reviewOnly) {
        if(onContinueSetup!=null) Text(stringResource(R.string.beta_onboarding_progress,if(running) 4 else 3,6),style=MaterialTheme.typography.bodySmall)
        ApprovedImportDateStep(dateOption,selectedDate,busy,{dateOption=it},{datePickerOpen=true},{
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED) scan(selectedDate)
            else {pendingDate=selectedDate.toEpochDay();permission.launch(Manifest.permission.READ_SMS)}
        },Modifier.testTag("onboarding-IMPORT"))
        if(denied) Text(stringResource(R.string.beta_history_denied),modifier=Modifier.testTag("history-denied"))
        if(failed) Text(stringResource(R.string.beta_action_failed),color=MaterialTheme.colorScheme.error)
        session?.let { current ->
            val formatter = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(java.util.Locale.getDefault()).withZone(java.time.ZoneId.systemDefault())
            Text(stringResource(R.string.beta_history_range, formatter.format(java.time.Instant.ofEpochMilli(current.from)),
                formatter.format(java.time.Instant.ofEpochMilli(current.until))))
            current.banks.forEach { Text(stringResource(bankName(it))) }
            Text(stringResource(R.string.beta_history_progress, current.scanned, current.stored, current.excluded, current.conflicts), modifier = Modifier.testTag("history-progress"))
            Text(stringResource(when { current.finished -> R.string.beta_history_done; current.limited -> R.string.beta_history_limited; else -> R.string.beta_history_paused }))
            if (!current.finished && !current.limited) OutlinedButton(enabled = !busy, onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) scan(null)
                else { denied = true; pendingDate = Long.MIN_VALUE; permission.launch(Manifest.permission.READ_SMS) }
            }, modifier = Modifier.testTag("history-resume")) { Text(stringResource(R.string.beta_history_resume)) }
        }
        if (busy) {
            CircularProgressIndicator()
            if (running) OutlinedButton(onClick = { running = false }, modifier = Modifier.testTag("history-pause")) {
                Text(stringResource(R.string.beta_history_pause))
            }
        }
        OutlinedButton(enabled = !busy, onClick = { action { withContext(BetaIo.dispatcher) { container.retryEvidence(100) } } }, modifier = Modifier.testTag("history-retry")) {
            Text(stringResource(R.string.beta_history_retry))
        }
        }
        if (onContinueSetup == null) {
        counts?.let {
            Text(stringResource(R.string.beta_history_counts, it.total, it.needsReview), modifier = Modifier.testTag("history-counts"))
            if (it.needsOwnership > 0) {
                Text(stringResource(R.string.beta_setup_waiting_accounts, it.needsOwnership), modifier = Modifier.testTag("history-needs-ownership"))
                if (onConfirmAccounts != null) Button(enabled = !busy, onClick = onConfirmAccounts, modifier = Modifier.testTag("history-confirm-accounts")) {
                    Text(stringResource(R.string.beta_setup_assets_action))
                }
            }
        }
        BetaChoice(selected = needsReviewOnly, enabled = !busy, onClick = { after = ""; needsReviewOnly = true },
            label = { Text(stringResource(R.string.beta_history_filter_review)) }, modifier = Modifier.testTag("history-filter-review"))
        if (!reviewOnly) BetaChoice(selected = !needsReviewOnly, enabled = !busy, onClick = { after = ""; needsReviewOnly = false },
            label = { Text(stringResource(R.string.beta_history_filter_all)) }, modifier = Modifier.testTag("history-filter-all"))
        Text(stringResource(if (needsReviewOnly) R.string.beta_history_filter_review else R.string.beta_history_saved), style = MaterialTheme.typography.titleMedium)
        if (needsReviewOnly && counts?.needsReview == 0) Text(stringResource(R.string.beta_history_no_review), modifier = Modifier.testTag("history-no-review"))
        page.forEachIndexed { index, summary ->
            Text(stringResource(R.string.beta_history_row, index + 1, stringResource(when (summary.processingState) {
                EvidenceProcessingState.RETRY -> R.string.beta_history_pending
                EvidenceProcessingState.REVIEW -> R.string.beta_history_review
                EvidenceProcessingState.COMPARISON_ONLY -> R.string.beta_history_compared
                EvidenceProcessingState.INTERPRETED -> R.string.beta_history_interpreted
            })))
            OutlinedButton(enabled = !busy, onClick = { if (summary.processingState == EvidenceProcessingState.INTERPRETED && summary.financialReviewReason != null && onFinancialReview != null) onFinancialReview(summary.id) else selectedEvidence = summary.id }, modifier = Modifier.testTag("history-review-$index")) {
                Text(stringResource(if (summary.processingState == EvidenceProcessingState.REVIEW || summary.processingState == EvidenceProcessingState.RETRY || summary.processingState == EvidenceProcessingState.COMPARISON_ONLY || summary.financialReviewReason != null) R.string.beta_history_review_action else R.string.beta_history_view_action))
            }
            OutlinedButton(enabled = !busy, onClick = { action { withContext(BetaIo.dispatcher) { container.reprocessEvidence(summary.id) } } }, modifier = Modifier.testTag("history-reparse-$index")) {
                Text(stringResource(R.string.beta_history_reparse))
            }
        }
        if (page.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = { after = page.last().id; action {} }) { Text(stringResource(R.string.beta_history_next)) }
        if (after.isNotEmpty()) OutlinedButton(enabled = !busy, onClick = { after = ""; action {} }) { Text(stringResource(R.string.beta_history_first)) }
        }
        val setupCanContinue=session?.finished==true || (session==null && (counts?.total ?: 0)>0)
        if(onContinueSetup==null || setupCanContinue) Button(enabled = !busy, onClick = { if (onContinueSetup != null && setupCanContinue) onContinueSetup() else onClose() }, modifier = Modifier.testTag("history-close")) {
            Text(stringResource(if (onContinueSetup != null && setupCanContinue) R.string.beta_setup_next_assets else R.string.beta_history_return))
        }
    }
}
