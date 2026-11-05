package com.baraa.masroof.application.patterns

import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import com.baraa.masroof.engine.bank.patterns.CandidateKind
import com.baraa.masroof.engine.bank.patterns.GenericCandidateExtractor
import com.baraa.masroof.engine.bank.patterns.FieldSlot
import com.baraa.masroof.engine.bank.patterns.SavedMessagePattern
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Teaching stays preview-only; financial approval, import and eligible pending application are explicit actions. */
class MessagePatternsWorkflow(
    private val store: MessagePatternStore,
    private val accessGate: DatabaseAccessGate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pendingPreview: PendingPatternsWorkflow? = null,
    private val bundledBanks: List<BankMessagePatterns> = emptyList(),
    private val activation: PatternApprovalWorkflow? = null,
    private val requiredBundledBankIds: Set<String> = emptySet(),
    private val appLogService: com.baraa.masroof.application.logging.AppLogService? = null,
) {
    /** Pattern screens report caught failures here so they reach the app log. */
    fun reportFailure(action: String, error: Throwable) {
        appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.PARSE, action, error)
    }
    val canPreviewPending: Boolean get() = pendingPreview != null
    val canApplyPending: Boolean get() = pendingPreview?.canApply == true
    val canApprove: Boolean get() = activation != null
    suspend fun patternCards(bankId: String) = requireNotNull(activation).cards(bankId)
    suspend fun patternAccounts(bankId: String) = requireNotNull(activation).accounts(bankId)
    suspend fun confirmPatternAccount(bankId: String, last4: String) = requireNotNull(activation).confirmAccount(bankId, last4)
    suspend fun previewFinancial(generation: Long, bankId: String, patternId: String, operation: PatternOperation, accountId: String?,
        body: String, referenceField: String?, contextReferences: Set<String>, currency: com.baraa.masroof.core.money.Currency? = null, cardId: String? = null) =
        requireNotNull(activation).preview(generation, bankId, patternId, operation, accountId, body, referenceField, contextReferences, currency?.let { com.baraa.masroof.finance.value.Currency.valueOf(it.name) }, cardId)
    /** The approval, then any wallet links it enables; returns the configuration after both. */
    suspend fun saveFinancial(preview: PatternFinancialPreview): com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration {
        val saved = requireNotNull(activation).save(preview)
        linkWalletBuiltIns(setOf(preview.pattern.bankId))
        return withContext(ioDispatcher) { accessGate.withAccess { store.load() } }.takeIf { it.generation > saved.generation } ?: saved
    }

    /** Best effort: a failed link leaves the built-in patterns waiting for approval, never a half-saved approval. */
    suspend fun linkWalletBuiltIns(bankIds: Set<String>? = null) {
        val activation = activation ?: return
        try {
            load()
            activation.linkWalletBuiltIns(bundledBanks.flatMap { bank -> bank.patterns.map { it.id } }.toSet(), bankIds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            reportFailure("linkWalletBuiltIns", e)
        }
    }
    suspend fun applyPending(bankId: String, generation: Long, reviewId: String? = null, patternId: String? = null) =
        requireNotNull(pendingPreview).apply(bankId, generation, reviewId, patternId)
    suspend fun approvalExamples(bankId: String, generation: Long, patternId: String) =
        pendingPreview?.approvalExamples(bankId, generation, patternId).orEmpty()

    suspend fun previewPending(bankId: String, generation: Long, forceEnabledPatternId: String? = null) =
        requireNotNull(pendingPreview) { "Pending preview is unavailable" }.preview(bankId, generation, forceEnabledPatternId = forceEnabledPatternId)

    suspend fun catalog(): PatternCatalog = load().toCatalog()

    suspend fun load() = withContext(ioDispatcher) {
        val existing = accessGate.withAccess { store.load() }
        if (bundledBanks.none { MessagePatternStore.installation(existing, it, requiredBundledBankIds) != null }) existing
        else accessGate.withExclusiveAccess {
            store.installMissingBanks(bundledBanks, requiredBundledBankIds)
        }
    }

    suspend fun addBank(generation: Long, name: String, sender: String, bankId: String? = null) = withContext(ioDispatcher) {
        accessGate.withExclusiveAccess {
            val current = store.load()
            val id = bankId ?: "BANK_${java.util.UUID.randomUUID().toString().replace("-", "")}"
            require(current.banks.none { it.bankId == id }) { "Bank already configured" }
            validateBankEdit(current.banks, id, name, listOf(sender.trim()))
            store.saveBank(generation, BankMessagePatterns(id, listOf(sender.trim()), name.trim())).toCatalog()
        }
    }

    suspend fun updateBank(generation: Long, bankId: String, name: String, senders: List<String>) = withContext(ioDispatcher) {
        accessGate.withExclusiveAccess {
            val current = store.load()
            val bank = current.banks.single { it.bankId == bankId }
            val addresses = senders.map(String::trim).distinct()
            validateBankEdit(current.banks, bankId, name, addresses)
            require(bank.currentPatterns.all { it.sender in addresses }) { "A sender is still used by a pattern" }
            store.saveBank(generation, bank.copy(displayName = name.trim(), senders = addresses, patterns = emptyList())).toCatalog()
        }
    }

    private fun validateBankEdit(banks: List<BankMessagePatterns>, bankId: String, name: String, senders: List<String>) {
        require(name.trim().isNotEmpty() && name.length <= 120)
        require(senders.isNotEmpty() && senders.size <= 16 && senders.all { it.isNotBlank() && it.length <= 120 })
        // Detection folds case, spacing and letter forms, so ownership compares the same folded keys.
        val key = com.baraa.masroof.bank.SenderBankDetector::senderKey
        val existing = banks.singleOrNull { it.bankId == bankId }?.senders.orEmpty().map(key)
        val others = banks.filter { it.bankId != bankId }.flatMap { it.senders }.map(key).toSet()
        require(senders.map(key).filterNot { it in existing }.none { it in others }) {
            "Sender belongs to another bank"
        }
    }

    suspend fun candidates(body: String) = withContext(ioDispatcher) {
        GenericCandidateExtractor.extract(body).map { PatternText(it.text.raw, it.text.start, it.text.end, it.kind.name) }.distinct()
    }

    suspend fun editBindings(generation: Long, bankId: String, patternId: String, body: String) = withContext(ioDispatcher) {
        accessGate.withAccess {
            val configuration = store.load()
            check(configuration.generation == generation) { "Pattern configuration changed" }
            val pattern = configuration.banks.single { it.bankId == bankId }.currentPatterns.single { it.id == patternId }
            // Role evidence may include labels and multiple observations; it is not a set of teaching selections.
            if (pattern.roleProfile != null) return@withAccess emptyList<PatternBinding>()
            val ignored = pattern.parts.filterIsInstance<FieldSlot>().filter { it.ignored }.map { it.field }.toSet()
            val visible = pattern.copy(enabled = true, parts = pattern.parts.map {
                if (it is FieldSlot) it.copy(ignored = false) else it
            })
            visible.preview(bankId, pattern.sender, body)?.fields.orEmpty().map { (field, span) ->
                PatternBinding(field, span.start, span.end, ignored = field in ignored)
            }
        }
    }

    suspend fun teach(expectedGeneration: Long, bankId: String, sender: String, operation: PatternOperation,
        body: String, bindings: List<PatternBinding>, patternId: String? = null): PatternTeachingPreview = withContext(ioDispatcher) {
        accessGate.withAccess {
            val configuration = store.load()
            check(configuration.generation == expectedGeneration) { "Pattern configuration changed" }
            val bank = configuration.banks.single { it.bankId == bankId }
            val prior = patternId?.let { id -> bank.currentPatterns.single { it.id == id } }
            require(prior?.roleProfile == null) { "Bundled patterns cannot be edited" }
            require(sender in bank.senders) { "Select a configured sender" }
            require(bindings.any { it.field == "amount" && !it.ignored }) { "Bind the transaction amount" }
            require(bindings.all { it.ignored || it.field in operation.fields }) { "Field does not belong to operation" }
            fun build(choices: List<PatternBinding>) = SavedMessagePattern.fromChoices(prior?.id ?: java.util.UUID.randomUUID().toString(),
                bankId, sender, operation.name, body, choices.choices()).copy(revision = prior?.let { Math.addExact(it.revision, 1) } ?: 1,
                    enabled = prior?.enabled ?: false)
            // Unselected amounts, dates and numbers vary between messages; ignoring them keeps the pattern from matching only its teacher.
            val auto = autoIgnored(body, bindings)
            val (pattern, fixed) = runCatching { build(bindings + auto) to emptyList<PatternBinding>() }
                .getOrElse { if (auto.isEmpty()) throw it else build(bindings) to auto }
            PatternTeachingPreview(configuration.generation, pattern,
                requireNotNull(pattern.copy(enabled = true).preview(bankId, sender, body)).fields.displayFields(),
                fixed.map { PatternText(body.substring(it.start, it.end), it.start, it.end) }, body)
        }
    }

    // shortcut: all-or-nothing; a single candidate that breaks template separators drops every automatic ignore, upgrade to per-candidate retry if that shows up in practice.
    private fun autoIgnored(body: String, bindings: List<PatternBinding>): List<PatternBinding> {
        val firstLetter = body.indexOfFirst(Char::isLetter).takeIf { it >= 0 } ?: return emptyList()
        val taken = bindings.map { it.start until it.end }.toMutableList()
        val free = (1..16).filter { index -> bindings.none { it.field == "ignored_$index" } }.iterator()
        return GenericCandidateExtractor.extract(body).filter { it.kind != CandidateKind.TEXT && it.text.start > firstLetter }
            .mapNotNull { candidate ->
                val range = candidate.text.start until candidate.text.end
                if (taken.any { it.first < range.last + 1 && range.first < it.last + 1 } || !free.hasNext()) return@mapNotNull null
                taken += range
                PatternBinding("ignored_${free.next()}", range.first, range.last + 1, ignored = true)
            }
    }

    suspend fun tryPreview(preview: PatternTeachingPreview, sender: String, body: String) = withContext(ioDispatcher) {
        preview.pattern.copy(enabled = true).preview(preview.bankId, sender, body)?.fields?.displayFields()
    }

    suspend fun saveTeaching(preview: PatternTeachingPreview): com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration {
        val (saved, prior) = withContext(ioDispatcher) {
            accessGate.withExclusiveAccess {
                val current = store.load()
                check(current.generation == preview.generation) { "Preview expired; review again" }
                val bank = current.banks.single { it.bankId == preview.bankId }
                require(preview.pattern.sender in bank.senders)
                val prior = bank.currentPatterns.singleOrNull { it.id == preview.patternId }?.factContract
                store.saveBank(preview.generation, bank.copy(patterns = listOf(preview.pattern))) to prior
            }
        }
        // An edit keeps its approval only by passing the same full approval check again; the contract is never copied.
        if (prior == null || activation == null || preview.body.isEmpty()) return saved
        return activation.reapprove(saved.generation, preview.pattern, prior, preview.body) ?: saved
    }

    suspend fun setEnabled(generation: Long, bankId: String, patternId: String, enabled: Boolean) =
        revise(generation, bankId, patternId) { _, current -> current.copy(enabled = enabled) }

    suspend fun deletePattern(generation: Long, bankId: String, patternId: String) = withContext(ioDispatcher) {
        accessGate.withExclusiveAccess { store.deletePattern(generation, bankId, patternId).toCatalog() }
    }

    suspend fun restoreRevision(generation: Long, bankId: String, patternId: String, revision: Int) =
        revise(generation, bankId, patternId) { bank, current ->
            val restored = bank.patterns.single { it.id == patternId && it.revision == revision }
            // An older approval skipped today's account and message checks; only the current approval carries over.
            if (restored.roleProfile == null && restored.factContract != current.factContract) restored.copy(factContract = null) else restored
        }

    private suspend fun revise(generation: Long, bankId: String, patternId: String,
        definition: (BankMessagePatterns, SavedMessagePattern) -> SavedMessagePattern) = withContext(ioDispatcher) {
        accessGate.withExclusiveAccess {
            val snapshot = store.load()
            check(snapshot.generation == generation) { "Pattern configuration changed" }
            val bank = snapshot.banks.single { it.bankId == bankId }
            val current = bank.currentPatterns.single { it.id == patternId }
            val next = definition(bank, current).copy(revision = Math.addExact(current.revision, 1))
            store.saveBank(generation, bank.copy(patterns = listOf(next)))
        }
    }

    suspend fun saveBank(expectedGeneration: Long, bank: BankMessagePatterns) = withContext(ioDispatcher) {
        // ponytail: reuse the existing exclusive gate for rare edits; no second financial/configuration lock hierarchy.
        accessGate.withExclusiveAccess { store.saveBank(expectedGeneration, bank) }
    }
}
