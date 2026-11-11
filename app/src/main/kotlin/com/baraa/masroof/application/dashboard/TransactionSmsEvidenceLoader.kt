package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.RawSmsRepository

/**
 * Loads original SMS evidence linked to a reconciled [com.baraa.masroof.domain.model.FinancialTransaction].
 */
class TransactionSmsEvidenceLoader(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val rawSmsRepository: RawSmsRepository,
    private val provenance: suspend (String) -> com.baraa.masroof.application.interpretation.InterpretationProvenance?,
) {
    data class SmsEvidence(
        val body: String,
        val sender: String?,
        val provenance: com.baraa.masroof.application.interpretation.InterpretationProvenance? = null,
    )

    suspend fun loadForTransaction(transactionId: String): List<SmsEvidence> =
        financialTransactionRepository.listRawSmsIds(transactionId)
            .mapNotNull { rawSmsId ->
                rawSmsRepository.getById(rawSmsId)?.let { raw ->
                    SmsEvidence(body = raw.body, sender = raw.sender, provenance = provenance(rawSmsId))
                }
            }
}
