package com.baraa.masroof.engine.bank.patterns

import com.baraa.masroof.engine.bank.*
import com.baraa.masroof.engine.bank.interpretation.RuleInterpreter
import com.baraa.masroof.finance.value.BankId
import java.time.Instant
import java.util.Collections

internal fun BankRuleSchema.frozen(): BankRuleSchema {
    fun terms(values: List<String>) = Collections.unmodifiableList(values.toList())
    fun <K> map(values: Map<K, List<String>>) = Collections.unmodifiableMap(values.mapValues { terms(it.value) })
    return copy(rules = Collections.unmodifiableList(rules.map { it.copy(phrases = terms(it.phrases), excludes = terms(it.excludes),
        labels = map(it.labels), detailLabels = map(it.detailLabels), observations = map(it.observations)) }),
        network = network.copy(bankAliases = map(network.bankAliases), sourceBankLabels = terms(network.sourceBankLabels),
            destinationBankLabels = terms(network.destinationBankLabels), networkLabels = terms(network.networkLabels),
            unidentifiedExternalBankMarkers = terms(network.unidentifiedExternalBankMarkers)))
}

internal fun SavedMessagePattern.previewRoles(body: String): Preview? {
    val input = MessageInput("role-preview", sender, body, Instant.EPOCH)
    val result = RuleInterpreter(requireNotNull(roleProfile)).interpretRouted(input, BankId(bankId))
    if (result.evidence.none { it.field == "family" && it.ruleId == id }) return null
    return Preview(label, result.evidence.filter { it.field != "family" }.associate { span ->
        span.field.lowercase(java.util.Locale.ROOT) to TextSpan(span.slice(input), span.start, span.end)
    })
}
