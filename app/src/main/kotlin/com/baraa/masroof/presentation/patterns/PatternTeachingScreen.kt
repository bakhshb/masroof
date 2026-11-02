package com.baraa.masroof.presentation.patterns

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.baraa.masroof.R
import com.baraa.masroof.application.patterns.*
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.ui.theme.MasroofSpacing
import com.baraa.masroof.ui.theme.MasroofTeachingTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Question and navigation stay visible; only message content scrolls. Drafts are saved explicitly. */
@Composable
internal fun PatternTeachingScreen(
    workflow: MessagePatternsWorkflow, generation: Long, bank: PatternBankView,
    source: PatternTeachingSource?, onSaved: (PatternTeachingPreview, String) -> Unit, onReload: () -> Unit, onBack: () -> Unit,
    existingPattern: PatternDefinitionView? = null, initialBody: String = "", initialOperation: PatternOperation? = null,
) {
    // Progress survives another screen covering this one. A restored preview step falls back to binding (LaunchedEffect(generation)).
    var step by rememberSaveable { mutableIntStateOf(0) }
    var operation by rememberSaveable { mutableStateOf(PatternOperation.entries.firstOrNull { it.name == existingPattern?.label } ?: initialOperation ?: PatternOperation.PURCHASE) }
    var sender by rememberSaveable { mutableStateOf(source?.sender ?: existingPattern?.sender?.takeIf { it.isNotBlank() } ?: bank.senders.first()) }
    // A Review message is read again from its source on restore, so only text the user typed is saved.
    var body by rememberSaveable(stateSaver = if (source == null) TextFieldValue.Saver else notSaved) { mutableStateOf(TextFieldValue(source?.body ?: initialBody)) }
    var fieldIndex by rememberSaveable { mutableIntStateOf(0) }
    var bindings by rememberSaveable(stateSaver = bindingsSaver) { mutableStateOf(emptyList<PatternBinding>()) }
    var candidates by remember { mutableStateOf(emptyList<PatternText>()) }
    var preview by remember { mutableStateOf<PatternTeachingPreview?>(null) }
    var sample by remember { mutableStateOf("") }
    var sampleResult by remember { mutableStateOf<Map<String, PatternText>?>(null) }
    var sampleChecked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadingExample by remember { mutableStateOf(existingPattern != null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val operations = stringArrayResource(R.array.pattern_operations)
    val field = operation.fields[fieldIndex]
    fun back() {
        if (busy) return
        body = body.copy(selection = TextRange.Zero)
        when {
            step == 3 -> step = 2
            step == 2 -> { step = 1; preview = null }
            step == 1 && fieldIndex > 0 -> fieldIndex--
            step == 1 -> step = 0
            else -> onBack()
        }
    }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        keyboard?.hide(); focus.clearFocus()
        busy = true; failed = false
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (caught: Exception) { workflow.reportFailure("PatternTeachingScreen", caught); failed = true }
            finally { busy = false }
        }
    }
    fun buildPreview() = work {
        preview = workflow.teach(generation, bank.bankId, sender, operation, body.text, bindings, patternId = existingPattern?.id)
        sampleChecked = false; sampleResult = null; step = 2
    }
    fun advance() {
        body = body.copy(selection = TextRange.Zero)
        if (fieldIndex < operation.fields.lastIndex) fieldIndex++ else buildPreview()
    }
    fun bindSelection(key: String, ignored: Boolean = false) {
        val start = body.selection.min; val end = body.selection.max
        bindings = bindings.filterNot { it.field == key || it.start < end && start < it.end } + PatternBinding(key, start, end, ignored)
        preview = null; body = body.copy(selection = TextRange.Zero)
    }
    LaunchedEffect(body.text, generation) {
        loadingExample = existingPattern != null && step == 0
        try {
            candidates = workflow.candidates(body.text)
            if (existingPattern != null && step == 0 && operation.name == existingPattern.label)
                bindings = workflow.editBindings(generation, bank.bankId, existingPattern.id, body.text)
        } catch (e: CancellationException) { throw e }
        catch (caught: Exception) { workflow.reportFailure("PatternTeachingScreen", caught); failed = true }
        finally { loadingExample = false }
    }
    LaunchedEffect(step, fieldIndex) {
        if (step != 1) return@LaunchedEffect
        val bound = bindings.singleOrNull { it.field == field }
        // occurred_at needs a time of day; a date-only value can never be approved for it, so it is not suggested.
        val kinds = when (field) { "amount" -> listOf("MONEY"); "occurred_at" -> listOf("DATE_TIME"); else -> emptyList() }
        // Offer the first unbound money/date value so the common fields only need a confirmation tap.
        val suggested = kinds.firstNotNullOfOrNull { kind -> candidates.firstOrNull { candidate ->
            candidate.kind == kind && bindings.none { it.start < candidate.end && candidate.start < it.end }
        } }
        body = body.copy(selection = bound?.let { TextRange(it.start, it.end) } ?: suggested?.let { TextRange(it.start, it.end) } ?: TextRange.Zero)
    }
    LaunchedEffect(generation) { preview = null; if (step >= 2) step = 1 }
    BackHandler { back() }
    val question = when (step) {
        0 -> stringResource(R.string.teaching_kind_title)
        1 -> stringResource(R.string.teaching_field_title, patternFieldName(field))
        2 -> stringResource(R.string.teaching_preview_title)
        else -> stringResource(R.string.teaching_sample_title)
    }
    MasroofSecondaryScaffold(stringResource(if (existingPattern == null) R.string.teaching_title else R.string.pattern_edit_title), ::back,
        stringResource(R.string.review_back), actions = {
            if (step == 1) MasroofTextButton(::buildPreview,
                enabled = !busy && bindings.any { it.field == "amount" && !it.ignored }) {
                Text(stringResource(R.string.pattern_preview))
            }
        }) { modifier ->
        Box(modifier, contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = MasroofTeachingTokens.maxWidth).fillMaxSize().imePadding(),
                verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                PatternTeachingHeader(step, question, fieldIndex, operation.fields.size, field == "amount",
                    help = if (step == 1) patternFieldHelp(field) else null)
                if (busy || loadingExample) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) MasroofOutlinedButton({ preview = null; failed = false; onReload() }, enabled = !busy) {
                    Text(stringResource(R.string.dashboard_retry))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (step == 1) key(field) {
                        PatternTeachingValues(body, candidates, !busy, { body = it },
                            { body = body.copy(selection = TextRange(it.start, it.end)) },
                            !busy && !body.selection.collapsed && bindings.size < 16, {
                                val key = (1..16).firstOrNull { index -> bindings.none { it.field == "ignored_$index" } }
                                if (key != null) bindSelection("ignored_$key", true)
                            })
                    } else key(step) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
                            when (step) {
                                0 -> {
                                    Text(bank.displayName ?: bank.bankId, style = MaterialTheme.typography.titleMedium)
                                    if (existingPattern?.status == PatternApprovalStatus.APPROVED) MasroofHintBox(stringResource(R.string.teaching_edit_resets_approval))
                                    if (bank.senders.size > 1) PatternChoice(bank.senders, sender, !busy && source == null) { sender = bank.senders[it] }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap),
                                        verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                                        PatternOperation.entries.forEach { choice ->
                                            MasroofChoice(operations[choice.ordinal], operation == choice, {
                                                operation = choice; fieldIndex = 0; bindings = emptyList(); preview = null
                                            }, enabled = !busy, modifier = Modifier.testTag("pattern-operation-${choice.name}"))
                                        }
                                    }
                                    TeachingMessage(body, source != null, !busy) {
                                        if (it.text != body.text) { bindings = emptyList(); preview = null }
                                        body = it
                                    }
                                }
                                2 -> preview?.let { reviewed ->
                                    if (reviewed.fixedValues.isNotEmpty()) MasroofHintBox(stringResource(R.string.teaching_fixed_values,
                                        reviewed.fixedValues.joinToString(" · ") { it.text }))
                                    PatternTeachingReview(reviewed) { key ->
                                        bindings = bindings.filterNot { it.field == key }; preview = null
                                        fieldIndex = operation.fields.indexOf(key).coerceAtLeast(0); step = 1
                                    }
                                    MasroofOutlinedButton({ step = 3 }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.teaching_try_another))
                                    }
                                }
                                3 -> {
                                    OutlinedTextField(sample, { sample = it; sampleChecked = false; sampleResult = null }, minLines = 4, maxLines = 8,
                                        label = { Text(stringResource(R.string.pattern_other_message)) }, enabled = !busy,
                                        modifier = Modifier.fillMaxWidth().testTag("pattern-sample"))
                                    if (sampleChecked && sampleResult == null) Text(stringResource(R.string.pattern_no_match))
                                    sampleResult?.forEach { (key, value) -> Text("${patternFieldName(key)}: ${value.text}") }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                    when (step) {
                        0 -> PatternTeachingAction(R.string.teaching_next,
                            { keyboard?.hide(); focus.clearFocus(); step = 1 }, !busy && !loadingExample && body.text.isNotBlank())
                        1 -> {
                            Text(if (body.selection.collapsed) stringResource(R.string.teaching_select_value)
                                else body.text.substring(body.selection.min, body.selection.max), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MasroofSpacing.cardInnerGap)) {
                                if (field != "amount") MasroofTextButton({ bindings = bindings.filterNot { it.field == field }; advance() },
                                    enabled = !busy) { Text(stringResource(R.string.teaching_skip_field)) }
                                MasroofButton({ bindSelection(field); advance() }, enabled = !busy && !body.selection.collapsed,
                                    modifier = Modifier.weight(1f).testTag("pattern-bind")) { Text(stringResource(R.string.teaching_confirm_field)) }
                            }
                        }
                        2 -> PatternTeachingAction(R.string.pattern_save, {
                            preview?.let { reviewed -> work { workflow.saveTeaching(reviewed); onSaved(reviewed, body.text) } }
                        }, !busy && preview != null)
                        3 -> PatternTeachingAction(R.string.pattern_test, {
                            preview?.let { reviewed -> work { sampleResult = workflow.tryPreview(reviewed, sender, sample); sampleChecked = true } }
                        }, !busy && sample.isNotBlank())
                    }
                }
            }
        }
    }
}

private val notSaved = Saver<TextFieldValue, Any>(save = { null }, restore = { null })

private val bindingsSaver = listSaver<List<PatternBinding>, Any>(
    save = { bindings -> bindings.flatMap { listOf(it.field, it.start, it.end, it.ignored) } },
    restore = { saved -> saved.chunked(4) { PatternBinding(it[0] as String, it[1] as Int, it[2] as Int, it[3] as Boolean) } },
)

@Composable
internal fun patternFieldName(key: String): String {
    val keys = stringArrayResource(R.array.pattern_field_keys)
    return stringArrayResource(R.array.pattern_field_names).getOrNull(keys.indexOf(key)) ?: key
}

/** Plain explanation and an SMS example for a field such as Ref / Acc; null for unknown keys. */
@Composable
internal fun patternFieldHelp(key: String): String? {
    val keys = stringArrayResource(R.array.pattern_field_keys)
    return stringArrayResource(R.array.pattern_field_help).getOrNull(keys.indexOf(key))
}

@Composable
internal fun patternOperationName(label: String): String {
    val index = PatternOperation.entries.indexOfFirst { it.name == label }
    return stringArrayResource(R.array.pattern_operations).getOrNull(index) ?: when (label) {
        "FINANCING_INSTALLMENT" -> stringResource(R.string.review_family_financing_installment)
        "FEE" -> stringResource(R.string.review_family_fee)
        "NON_FINANCIAL" -> stringResource(R.string.review_reason_non_financial)
        "BALANCE_NOTICE" -> stringResource(R.string.pattern_balance_notice)
        else -> label
    }
}
