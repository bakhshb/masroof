package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.FinancialTransaction
import java.time.Instant

/** The financial edit and its latest explicit human decision must commit together. */
fun interface TransactionReclassificationRepository {
    suspend fun updateWithDecision(transaction: FinancialTransaction, rawSmsId: String, at: Instant): Boolean
}
