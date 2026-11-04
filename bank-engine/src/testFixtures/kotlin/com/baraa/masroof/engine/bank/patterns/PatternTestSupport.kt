package com.baraa.masroof.engine.bank.patterns

import com.baraa.masroof.engine.bank.BankRuleSchema

/** Test vocabulary: each literal rule as one role-profile pattern of a bank definition. */
fun BankRuleSchema.asMessagePatterns(bankId: String, senders: List<String>, displayName: String? = null) =
    BankMessagePatterns(bankId, senders, displayName, rules.map { rule ->
        SavedMessagePattern(rule.id, bankId, senders.first(), rule.family.name, parts = emptyList(),
            factContract = PatternFactContract(rule.family, rule.direction), roleProfile = copy(rules = listOf(rule)))
    })

/** The single pattern whose preview matches, or null when none or several do. */
fun SavedMessagePattern.Companion.unique(patterns: List<SavedMessagePattern>, bankId: String, sender: String, body: String): Pair<SavedMessagePattern, Preview>? =
    patterns.mapNotNull { pattern -> pattern.preview(bankId, sender, body)?.let { pattern to it } }.singleOrNull()
