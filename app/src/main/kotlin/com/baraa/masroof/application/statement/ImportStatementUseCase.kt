package com.baraa.masroof.application.statement

import com.baraa.masroof.domain.statement.StatementParseResult
import com.baraa.masroof.domain.statement.StatementParser
import com.baraa.masroof.domain.statement.StatementRejection
import java.io.InputStream

/**
 * Validates a canonical CSV, then compares it. A rejected file never reaches
 * [StatementReconciliationService] and therefore never reads or writes Room.
 */
class ImportStatementUseCase(
    private val parser: StatementParser,
    private val reconciliation: StatementReconciliationService,
) {
    suspend fun import(stream: InputStream, knownBankIds: Set<String>): StatementImportResult =
        when (val parsed = parser.parse(stream, knownBankIds)) {
            is StatementParseResult.Rejected -> StatementImportResult.Rejected(parsed.reason)
            is StatementParseResult.Accepted ->
                StatementImportResult.Compared(reconciliation.compare(parsed.statement))
        }
}

sealed interface StatementImportResult {
    data class Compared(val report: StatementReconciliationReport) : StatementImportResult

    data class Rejected(val reason: StatementRejection) : StatementImportResult
}
