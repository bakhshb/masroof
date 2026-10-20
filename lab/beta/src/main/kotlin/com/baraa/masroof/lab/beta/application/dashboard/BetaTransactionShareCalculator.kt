package com.baraa.masroof.lab.beta.application.dashboard

import com.baraa.masroof.lab.finance.dashboard.TransactionFacts
import com.baraa.masroof.lab.finance.value.Currency
import com.baraa.masroof.lab.finance.value.Money
import java.math.BigDecimal

/** Source list-share total: original matching-currency amounts, with no FX or ledger effects. */
internal object BetaTransactionShareCalculator {
    fun total(rows: List<TransactionFacts>,currency: Currency): Money? {
        val matching=rows.filter {it.amount.currency==currency}
        if(matching.isEmpty()) return null
        return Money.of(matching.fold(BigDecimal.ZERO) {sum,row -> sum+row.amount.amount},currency)
    }
}
