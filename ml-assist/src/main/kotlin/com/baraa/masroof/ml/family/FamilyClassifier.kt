package com.baraa.masroof.ml.family

/**
 * Exact human-template memory for one bank ("remember my decision"). Human decisions are the only labels and
 * predictions never teach themselves; a conflicted or blocked template proposes nothing. A remembered
 * "not financial" decision is proposed too, so the same wording stops returning to review.
 */
class FamilyClassifier(val bankId: String, labels: List<HumanFamilyLabel> = emptyList(),
    blocks: List<HumanTemplateBlock> = emptyList()) {
    val latestBlocks = blocks.filter { it.bankId == bankId }.groupBy { it.rawId }.mapNotNull { (_, rows) ->
        rows.maxWith(compareBy<HumanTemplateBlock> { it.at }.thenBy { it.decisionId }).takeIf { block ->
            labels.none { it.rawId == block.rawId && it.bankId == block.bankId && it.at > block.at }
        }
    }.sortedBy { it.rawId }
    private val blockedTemplates = latestBlocks.map { it.template }.toSet()
    val latestLabels = labels.filter { it.bankId == bankId && latestBlocks.none { block -> block.rawId == it.rawId && block.at >= it.at } }.groupBy { it.rawId }.map { (_, rows) ->
        rows.maxWith(compareBy<HumanFamilyLabel> { it.at }
            .thenBy { if (it.provenance == HumanLabelSource.USER_MESSAGE_FAMILY.name) 1 else 0 }.thenBy { it.decisionId })
            .let { it.copy(terms = it.terms.toMap()) }
    }.sortedBy { it.rawId }
    private val templates = latestLabels.groupBy { it.template }.mapValues { (_, rows) -> rows.map { it.family }.toSet() }
    val version = if (latestLabels.isEmpty() && latestBlocks.isEmpty()) VERSION else VERSION + "-human-" + FamilyInputCleaner.sha256(
        latestLabels.joinToString("|") { "${it.rawId}:${it.decisionId}:${it.family}:${it.template}" } +
            latestBlocks.joinToString("|") { "block:${it.rawId}:${it.decisionId}:${it.template}" }).take(16)

    /** True when at least one learned template can propose; the same rule as [suggest]. */
    val canSuggest: Boolean = templates.any { (template, families) ->
        template !in blockedTemplates && families.singleOrNull() in REMEMBERED }

    fun suggest(bankId: String, features: CleanFamilyFeatures): FamilyPrediction? {
        if (bankId != this.bankId || features.template in blockedTemplates) return null
        val family = templates[features.template]?.singleOrNull()?.takeIf { it in REMEMBERED } ?: return null
        return FamilyPrediction(family, version)
    }

    companion object {
        const val VERSION = "family-template-v1"
        /** Families a decision can be remembered for: every financial family plus "not financial". */
        private val REMEMBERED = SmsFamilyLabel.entries.toSet() - setOf(SmsFamilyLabel.OTP, SmsFamilyLabel.BALANCE_NOTICE, SmsFamilyLabel.UNKNOWN)
    }
}
