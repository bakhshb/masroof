package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.dashboard.TransactionFacts
import com.baraa.masroof.lab.finance.posting.FinancialType

/** Production account-direction behavior over stable, bank-qualified lab endpoints. */
internal enum class BetaTransactionDirection { INWARD, OUTWARD, NEUTRAL }

internal fun transactionDirection(row: TransactionFacts, account: AssetId? = null): BetaTransactionDirection {
    if (row.effectiveType == FinancialType.SELF_TRANSFER && account != null) {
        val source = row.source == account
        val destination = row.destination == account
        return when {
            destination && !source -> BetaTransactionDirection.INWARD
            source && !destination -> BetaTransactionDirection.OUTWARD
            else -> BetaTransactionDirection.NEUTRAL
        }
    }
    return when (row.effectiveType) {
        FinancialType.INCOME, FinancialType.EXTERNAL_TRANSFER_IN, FinancialType.REFUND -> BetaTransactionDirection.INWARD
        FinancialType.SELF_TRANSFER, FinancialType.ADJUSTMENT, FinancialType.UNKNOWN -> BetaTransactionDirection.NEUTRAL
        else -> BetaTransactionDirection.OUTWARD
    }
}
