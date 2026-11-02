package com.baraa.masroof.presentation.patterns

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.patterns.*
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.presentation.common.*
import com.baraa.masroof.presentation.common.MasroofText as Text
import com.baraa.masroof.ui.theme.MasroofSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One page: imported example, confirmed account, explicit reference meaning, then approval. */
@Composable
internal fun PatternFinancialApprovalScreen(workflow: MessagePatternsWorkflow, generation: Long, pattern: PatternDefinitionView,
    initialBody: String, readOnlyBody: Boolean, onSaved: (PatternCatalog) -> Unit, onBack: () -> Unit) {
    var accounts by remember { mutableStateOf(emptyList<PatternAccountOption>()) }
    var cards by remember { mutableStateOf(emptyList<PatternAccountOption>()) }
    var examples by remember { mutableStateOf(emptyList<PatternApprovalExample>()) }
    var exampleIndex by remember { mutableStateOf<Int?>(null) }
    var cardId by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf((pattern.approval?.operation ?: PatternOperation.entries.firstOrNull { it.name == pattern.label })?.takeIf { it in pattern.operations }) }
    // A built-in rule keeps its movement and reads its own values: only the account or card it may link is chosen.
    val builtIn = pattern.recognitionPhrases.isNotEmpty()
    val cardOperation = operation in setOf(PatternOperation.PURCHASE, PatternOperation.REFUND, PatternOperation.CARD_PAYMENT)
    val showCards = "card" in pattern.fields || cardOperation
    val showAccounts = !builtIn || operation !in setOf(PatternOperation.PURCHASE, PatternOperation.REFUND)
    var body by remember { mutableStateOf(initialBody) }
    var reference by remember { mutableStateOf(pattern.approval?.referenceField) }
    var referenceChosen by remember { mutableStateOf(pattern.referenceFields.isEmpty() || pattern.approval != null) }
    var currency by remember { mutableStateOf(pattern.approval?.currency) }
    var checked by remember { mutableStateOf<Pair<List<Any?>, PatternFinancialPreview>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var validating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<PatternApprovalProblem?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val operationNames = stringArrayResource(R.array.pattern_operations)
    val contextFields = if (referenceChosen) pattern.referenceFields.toSet() - setOfNotNull(reference) else emptySet()
    val ready = !loading && operation != null && (accountId != null || cardId != null) &&
        ("card" !in pattern.fields || cardId != null) && referenceChosen && body.isNotBlank() &&
        (!builtIn || operation != PatternOperation.CARD_PAYMENT || (accountId != null && cardId != null))
    // A preview is valid only for the inputs it was checked with; re-choosing the same option keeps it.
    val inputs = listOf(ready, body, operation, accountId, cardId, reference, referenceChosen, currency)
    val preview = checked?.takeIf { it.first == inputs }?.second
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (caught: Exception) { workflow.reportFailure("PatternFinancialApprovalScreen", caught); failed = true }
            finally { busy = false }
        }
    }
    suspend fun loadOptions(initialLoad: Boolean = false) {
        accounts = workflow.patternAccounts(pattern.bankId)
        cards = workflow.patternCards(pattern.bankId)
        if (initialLoad) {
            // A built-in link is always the user's explicit choice: only a previously approved one is shown again.
            accountId = accounts.singleOrNull { it.last4 == pattern.approval?.accountLast4 }?.id
                ?: accounts.singleOrNull()?.takeIf { !builtIn && "card" !in pattern.fields }?.id
            cardId = cards.singleOrNull { it.last4 == pattern.approval?.cardLast4 }?.id
                ?: cards.singleOrNull()?.takeIf { !builtIn && "card" in pattern.fields }?.id
        }
        if (!initialLoad || !readOnlyBody) {
            examples = workflow.approvalExamples(pattern.bankId, generation, pattern.id)
            if (body.isBlank() && examples.isNotEmpty()) { exampleIndex = 0; body = examples.first().body }
        }
    }
    LaunchedEffect(workflow, pattern.bankId) {
        try { loadOptions(initialLoad = true) }
        catch (e: CancellationException) { throw e }
        catch (caught: Exception) { workflow.reportFailure("PatternFinancialApprovalScreen", caught); failed = true }
        finally { loading = false }
    }
    // Validation is read-only. Every edit invalidates the preview before Save can be used.
    LaunchedEffect(inputs) {
        checked = null; problem = null
        if (ready) {
            validating = true
            try { checked = inputs to workflow.previewFinancial(generation, pattern.bankId, pattern.id, requireNotNull(operation), accountId,
                body, reference, contextFields, currency, cardId) }
            catch (e: CancellationException) { throw e }
            catch (e: PatternApprovalException) { problem = e.problem }
            catch (caught: Exception) { workflow.reportFailure("PatternFinancialApprovalScreen", caught); problem = PatternApprovalProblem.VALIDATION }
            finally { validating = false }
        }
    }
    BackHandler { if (!busy) onBack() }
    MasroofSecondaryScaffold(stringResource(R.string.pattern_approval_title), { if (!busy) onBack() }, stringResource(R.string.settings_back)) { modifier ->
        Column(modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MasroofSpacing.sectionGap)) {
            if (loading || busy) CircularProgressIndicator()
            if (failed) {
                Text(stringResource(R.string.pattern_financial_blocked), color = MaterialTheme.colorScheme.error)
                MasroofOutlinedButton({ work { loadOptions() } }) { Text(stringResource(R.string.dashboard_retry)) }
            }
            PatternApprovalMessage(examples, exampleIndex, body, readOnlyBody, !busy && !loading,
                onSelect = { index -> exampleIndex = index; body = examples[index].body },
                onManual = { exampleIndex = null; body = "" },
                onBody = { body = it })
            MasroofCard {
                val select = stringResource(R.string.pattern_financial_select_operation)
                val operations = pattern.operations
                if (builtIn) {
                    operation?.let { Text(operationNames[it.ordinal], style = MaterialTheme.typography.titleMedium) }
                    MasroofHintBox(stringResource(R.string.pattern_approval_link_help))
                } else PatternChoice(listOf(select) + operations.map { operationNames[it.ordinal] },
                    operation?.let { operationNames[it.ordinal] } ?: select, !busy && !loading) {
                    if (operations.getOrNull(it - 1) != operation) {
                        operation = operations.getOrNull(it - 1); reference = null
                        referenceChosen = pattern.referenceFields.isEmpty()
                    }
                }
                if (showAccounts) PatternApprovalAccounts(accounts, accountId, !busy && !loading,
                    onChoose = { accountId = it }, onConfirm = { last4 -> work {
                        val account = workflow.confirmPatternAccount(pattern.bankId, last4)
                        accounts = workflow.patternAccounts(pattern.bankId); accountId = account.id
                    } })
                if (showCards) {
                    val choose = stringResource(R.string.settings_pattern_select_card)
                    val names = cards.map { "${it.name.orEmpty()} • ${it.last4}" }
                    PatternChoice(listOf(choose) + names, cards.indexOfFirst { it.id == cardId }.takeIf { it >= 0 }?.let { names[it] } ?: choose, !busy) {
                        cardId = cards.getOrNull(it - 1)?.id
                    }
                    if (cards.isEmpty()) MasroofGuidanceLabel(stringResource(R.string.settings_card_setup),
                        stringResource(R.string.settings_pattern_add_card_hint))
                }
            }
            if (pattern.referenceFields.isNotEmpty()) PatternApprovalReferences(pattern, operation, reference, referenceChosen,
                !busy && !loading, examples.getOrNull(exampleIndex ?: -1)?.fields.orEmpty()) {
                reference = it; referenceChosen = true
            }
            MasroofCard {
                val fromMessage = stringResource(R.string.pattern_financial_currency_from_message)
                if (!builtIn) PatternChoice(listOf(fromMessage) + Currency.entries.map { it.name }, currency?.name ?: fromMessage, !busy && !loading) {
                    currency = Currency.entries.getOrNull(it - 1)
                }
                if (validating) CircularProgressIndicator()
                problem?.let { Text(stringResource(patternApprovalProblemText(it)), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("pattern-approval-error")) }
                preview?.let { validated ->
                    MasroofAmountText("${validated.amount.amount.toPlainString()} ${validated.amount.currency}", MasroofAmountRole.Card)
                    Text(validated.occurredAt)
                    validated.accountLast4?.let { Text(stringResource(R.string.pattern_approval_account_summary, it)) }
                    validated.cardLast4?.let { Text(stringResource(R.string.pattern_approval_card_summary, it)) }
                    Text(stringResource(R.string.pattern_approval_save_scope), style = MaterialTheme.typography.bodySmall)
                }
                val reason = when {
                    loading -> null
                    body.isBlank() -> R.string.pattern_approval_need_message
                    accountId == null && cardId == null -> R.string.pattern_approval_need_account
                    !referenceChosen -> R.string.pattern_approval_need_reference
                    else -> null
                }
                reason?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                MasroofButton(onClick = { preview?.let { validated -> work {
                    workflow.saveFinancial(validated); onSaved(workflow.catalog())
                } } }, enabled = !busy && !validating && ready && preview != null,
                    modifier = Modifier.fillMaxWidth().testTag("pattern-financial-save")) { Text(stringResource(R.string.pattern_financial_approve)) }
            }
        }
    }
}
