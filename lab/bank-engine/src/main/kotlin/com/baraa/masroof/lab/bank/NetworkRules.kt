package com.baraa.masroof.lab.bank

/** Literal identity data and a closed, role-bound extraction grammar; never a detector or ownership rule. */
data class NetworkRules(
    val bankAliases: Map<String, List<String>> = emptyMap(),
    val sourceBankLabels: List<String> = emptyList(),
    val destinationBankLabels: List<String> = emptyList(),
    val standaloneDestinationBrackets: Boolean = false,
    val networkLabels: List<String> = emptyList(),
    val standaloneSourceBrackets: Boolean = false,
    val unidentifiedExternalBankMarkers: List<String> = emptyList(),
) {
    fun validate() {
        require(bankAliases.size <= 100)
        require(bankAliases.keys.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        require(bankAliases.values.all { it.isNotEmpty() })
        (bankAliases.values + listOf(sourceBankLabels, destinationBankLabels, networkLabels, unidentifiedExternalBankMarkers)).forEach { terms ->
            require(terms.size <= 20 && terms.all { it.any(Char::isLetterOrDigit) && it.length <= 100 && '\n' !in it && '\r' !in it })
        }
    }
}
