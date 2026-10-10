package com.baraa.masroof.application.statement

import com.baraa.masroof.domain.statement.StatementMatchPolicy
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
    private val ownedAccounts: suspend () -> Set<StatementMatchPolicy.QualifiedAccount>,
) {
    suspend fun compare(stream: InputStream): StatementImportResult =
        importStatement.import(stream, knownBankIds(), ownedAccounts())
}
