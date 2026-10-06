package com.baraa.masroof.bank.aljazira.extraction

import com.baraa.masroof.parsing.normalizer.comparisonRegex
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.parsing.model.NormalizedSms

data class BalanceExtraction(
    val availableBalance: Money? = null,
    val outstandingBalance: Money? = null,
)

/**
 * Available / outstanding balances — never transaction amount.
 */
class BalanceExtractor {
    fun extract(sms: NormalizedSms): BalanceExtraction {
        val text = sms.comparisonBody
        val available = firstMoney(text, AVAILABLE_PATTERNS)
        val outstanding = firstMoney(text, OUTSTANDING_PATTERNS)
        return BalanceExtraction(availableBalance = available, outstandingBalance = outstanding)
    }

    private fun firstMoney(text: String, patterns: List<Regex>): Money? {
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            return MoneyTokens.parseMoneyFromMatch(match)
        }
        return null
    }

    companion object {
        private val AVAILABLE_PATTERNS = listOf(
            comparisonRegex("""الرصيد\s*المتاح""" + MoneyTokens.moneyAfterLabel.pattern, RegexOption.IGNORE_CASE),
            comparisonRegex("""available\s*balance(?:\s*is)?""" + MoneyTokens.moneyAfterLabel.pattern, RegexOption.IGNORE_CASE),
        )

        private val OUTSTANDING_PATTERNS = listOf(
            comparisonRegex("""إجمالي\s*المبلغ\s*المستحق""" + MoneyTokens.moneyAfterLabel.pattern, RegexOption.IGNORE_CASE),
            comparisonRegex("""المبلغ\s*المتبقي""" + MoneyTokens.moneyAfterLabel.pattern, RegexOption.IGNORE_CASE),
            comparisonRegex("""due\s*amount""" + MoneyTokens.moneyAfterLabel.pattern, RegexOption.IGNORE_CASE),
        )
    }
}
