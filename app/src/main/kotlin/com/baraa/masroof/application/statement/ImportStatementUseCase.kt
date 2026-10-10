package com.baraa.masroof.application.statement

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.statement.ParsedBankStatement
import com.baraa.masroof.domain.statement.StatementMatchPolicy
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
    suspend fun import(
        stream: InputStream,
        knownBankIds: Set<String>,
        ownedAccounts: Set<StatementMatchPolicy.QualifiedAccount>,
    ): StatementImportResult =
        when (val parsed = parser.parse(stream, knownBankIds)) {
            is StatementParseResult.Rejected -> StatementImportResult.Rejected(parsed.reason)
            is StatementParseResult.Accepted -> {
                if (!accountsAreExactOwned(parsed.statement, ownedAccounts)) {
                    StatementImportResult.Rejected(StatementRejection.UNOWNED_ACCOUNT)
                } else {
                    StatementImportResult.Compared(reconciliation.compare(parsed.statement))
                }
            }
        }
}

/**
 * Every statement account must be an exact owned registry identity.
 * A shared suffix on another bank, or a longer mask that merely ends with the
 * same digits, is not that account. External and unknown rows are omitted from
 * [ownedAccounts] by the caller.
 */
internal fun accountsAreExactOwned(
    statement: ParsedBankStatement,
    ownedAccounts: Set<StatementMatchPolicy.QualifiedAccount>,
): Boolean {
    val owned = ownedAccounts
        .filter { it.bank != Bank.UNKNOWN && it.accountMasked.isNotBlank() }
        .map { StatementMatchPolicy.QualifiedAccount(it.bank, it.accountMasked.trim()) }
        .toSet()
    val mentioned = statement.entries.map { StatementMatchPolicy.QualifiedAccount(it.bank, it.accountMasked.trim()) } +
        statement.balances.map { StatementMatchPolicy.QualifiedAccount(it.bank, it.accountMasked.trim()) }
    return mentioned.all { it in owned }
}

sealed interface StatementImportResult {
    data class Compared(val report: StatementReconciliationReport) : StatementImportResult

    data class Rejected(val reason: StatementRejection) : StatementImportResult
}
