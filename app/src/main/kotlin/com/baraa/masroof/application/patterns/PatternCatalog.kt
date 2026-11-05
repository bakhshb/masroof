package com.baraa.masroof.application.patterns

import com.baraa.masroof.engine.bank.patterns.FieldSlot
import com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration
import com.baraa.masroof.engine.bank.patterns.PATTERN_REFERENCE_FIELDS
import com.baraa.masroof.engine.bank.patterns.PatternAccountRole
import com.baraa.masroof.engine.bank.patterns.StaticText
import com.baraa.masroof.core.money.Currency

/** Display values only. Approval and posting are rechecked against durable definitions. */
data class PatternCatalog(val generation: Long, val banks: List<PatternBankView>)
data class PatternBankView(val bankId: String, val displayName: String?, val senders: List<String>,
    val currentPatterns: List<PatternDefinitionView>) {
    /** Same rule as detection, so a message routed to this bank can be taught for it under its configured sender. */
    fun configuredSender(sender: String): String? = com.baraa.masroof.engine.bank.patterns.SenderIdentity.configuredSender(senders, sender)
    fun ownsSender(sender: String): Boolean = configuredSender(sender) != null
}
enum class PatternApprovalStatus { BUNDLED, PREVIEW_ONLY, APPROVED }
data class PatternApprovalSettings(val operation: PatternOperation?, val accountLast4: String?,
    val referenceField: String?, val currency: Currency?, val cardLast4: String? = null)
data class PatternDefinitionView(val id: String, val bankId: String, val label: String, val revision: Int,
    val enabled: Boolean, val status: PatternApprovalStatus, val example: String, val fields: List<String>,
    val priorRevisions: List<Int>, val approval: PatternApprovalSettings? = null, val sender: String = "",
    val messageTemplate: String = example,
    /** Built-in rules only: the phrases that recognize a message and, per field, the words its value follows. */
    val recognitionPhrases: List<String> = emptyList(), val fieldLabels: Map<String, List<String>> = emptyMap()) {
    val canApprove: Boolean get() = PatternOperation.entries.any { it.name == label }
    // Editing teaches a new template under the same id, which would silently replace a bundled role rule.
    val canEdit: Boolean get() = status != PatternApprovalStatus.BUNDLED && PatternOperation.entries.any { it.name == label }
    /** Movements whose fields cover everything this template captures. */
    val operations: List<PatternOperation> get() = PatternOperation.entries.filter { operation -> fields.all { it in operation.fields } }
    val canApply: Boolean get() = enabled && status != PatternApprovalStatus.PREVIEW_ONLY
    val referenceFields: List<String> get() = fields.filter { it in PATTERN_REFERENCE_FIELDS }
    fun accountReferenceFields(operation: PatternOperation?): List<String> {
        val ownRole = if (operation in setOf(PatternOperation.TRANSFER_IN, PatternOperation.REFUND)) "destination_account" else "source_account"
        return referenceFields.filter { it == "account_reference" || it == ownRole }
    }
}

internal fun MessagePatternConfiguration.toCatalog() = PatternCatalog(generation, banks.map { bank ->
    PatternBankView(bank.bankId, bank.displayName, bank.senders.toList(), bank.currentPatterns.map { pattern ->
        val status = when {
            pattern.roleProfile != null -> PatternApprovalStatus.BUNDLED
            pattern.factContract == null -> PatternApprovalStatus.PREVIEW_ONLY
            else -> PatternApprovalStatus.APPROVED
        }
        val example = pattern.roleProfile?.rules?.single()?.phrases?.joinToString(" · ")
            ?: pattern.parts.joinToString("") { part -> when (part) {
                is StaticText -> part.value
                is FieldSlot -> "…"
            } }
        PatternDefinitionView(pattern.id, bank.bankId, pattern.label, pattern.revision, pattern.enabled, status,
            example.take(240), pattern.parts.filterIsInstance<FieldSlot>().filterNot { it.ignored }.map { it.field },
            bank.patterns.filter { it.id == pattern.id && it.revision < pattern.revision }.map { it.revision },
            pattern.factContract?.let { contract -> PatternApprovalSettings(
                PatternOperation.entries.firstOrNull { it.name == contract.family.name },
                contract.accountBindings.singleOrNull { it.role != PatternAccountRole.CARD }?.last4,
                contract.accountBindings.singleOrNull { it.role != PatternAccountRole.CARD }?.field,
                contract.currency?.let { Currency.valueOf(it.name) },
                contract.accountBindings.singleOrNull { it.role == PatternAccountRole.CARD }?.last4) }, sender = pattern.sender,
            messageTemplate = if (pattern.roleProfile != null) example else pattern.parts.joinToString("") { part -> when (part) {
                is StaticText -> part.value
                is FieldSlot -> if (part.ignored) "…" else "{${part.field}}"
            } },
            recognitionPhrases = pattern.roleProfile?.rules?.single()?.phrases.orEmpty(),
            fieldLabels = pattern.roleProfile?.rules?.single()?.labels.orEmpty()
                .filterValues { it.isNotEmpty() }.mapKeys { (field, _) -> field.name.lowercase(java.util.Locale.ROOT) })
    })
})
