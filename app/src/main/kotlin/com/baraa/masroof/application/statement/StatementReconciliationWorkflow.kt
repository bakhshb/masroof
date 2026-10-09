package com.baraa.masroof.application.statement

import java.io.InputStream

/**
 * User-initiated, read-only statement comparison.
 *
 * The caller supplies an open stream. This workflow does not take a bank
 * credential, call a bank API, or copy the statement into an unencrypted file.
 */
class StatementReconciliationWorkflow(
    private val importStatement: ImportStatementUseCase,
    private val knownBankIds: suspend () -> Set<String>,
) {
    suspend fun compare(stream: InputStream): StatementImportResult =
        importStatement.import(stream, knownBankIds())
}
