package com.baraa.masroof.lab.bank

/** Closed vocabulary. Terms are literal token phrases, never executable regex or scripts. */
enum class RuleOperator { CONTAINS_TOKEN_PHRASE }
enum class ExtractionField { AMOUNT, CARD, SOURCE_ACCOUNT, DESTINATION_ACCOUNT, MERCHANT, AVAILABLE_BALANCE }

data class BankRule(
    val id: String,
    val family: MessageFamily,
    val phrases: List<String>,
    val excludes: List<String> = emptyList(),
    val direction: MoneyDirection = MoneyDirection.OUTGOING,
    val labels: Map<ExtractionField, List<String>> = emptyMap(),
    val operator: RuleOperator = RuleOperator.CONTAINS_TOKEN_PHRASE,
    val detailLabels: Map<String, List<String>> = emptyMap(),
    val observations: Map<String, List<String>> = emptyMap(),
)

/** M6.1 first schema. Validation runs before handing literal rules to the shared lab engine. */
data class BankRuleSchema(val schema: Int = 1, val revision: Int, val rules: List<BankRule>, val network: NetworkRules = NetworkRules()) {
    fun validate() {
        require(schema == 1) { "Unsupported rule schema" }
        network.validate()
        require(revision > 0 && rules.size in 1..100)
        require(rules.map { it.id }.distinct().size == rules.size)
        rules.forEach { rule ->
            require(rule.id.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
            // OTP protection belongs to the engine security gate; user rules cannot replace it.
            require(rule.family !in setOf(MessageFamily.OTP, MessageFamily.UNKNOWN))
            require(rule.direction in setOf(MoneyDirection.INCOMING, MoneyDirection.OUTGOING))
            require(rule.phrases.isNotEmpty())
            require(rule.detailLabels.keys.all { it in setOf("dueAmount", "foreignAmount", "counterparty", "transactionReference", "biller", "billerCode", "paymentDueDate") })
            require(rule.observations.keys.all { it in setOf("purchase.pos", "purchase.online", "card.credit", "card.debit", "card.statement", "card.available_balance", "card.credit_balance", "network.intra", "network.inter", "income.salary", "loan.personal", "loan.auto", "loan.mortgage", "fee.sar_unlabeled") })
            (listOf(rule.phrases, rule.excludes) + rule.labels.values + rule.detailLabels.values + rule.observations.values).forEach { terms ->
                require(terms.size <= 20 && terms.all {
                    it.isNotBlank() && it.length <= 100 && '\n' !in it && '\r' !in it
                })
            }
        }
    }
}
