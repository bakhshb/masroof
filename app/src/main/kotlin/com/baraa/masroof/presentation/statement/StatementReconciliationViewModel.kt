package com.baraa.masroof.presentation.statement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.baraa.masroof.application.statement.LedgerLineComparison
import com.baraa.masroof.application.statement.StatementCurrencyTotal
import com.baraa.masroof.application.statement.StatementImportResult
import com.baraa.masroof.application.statement.StatementLineComparison
import com.baraa.masroof.application.statement.StatementReconciliationWorkflow
import com.baraa.masroof.domain.statement.StatementComparisonStatus
import com.baraa.masroof.domain.statement.StatementRejection
import java.io.InputStream
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatementReconciliationUiState(
    val busy: Boolean = false,
    val rejection: StatementRejection? = null,
    val unreadable: Boolean = false,
    val hasReport: Boolean = false,
    val matchedCount: Int = 0,
    val statementOnlyCount: Int = 0,
    val ledgerOnlyCount: Int = 0,
    val ambiguousCount: Int = 0,
    val unsupportedCount: Int = 0,
    val matchedFleetPaymentCount: Int = 0,
    val matchedSelfTransferCount: Int = 0,
    val balanceUnsupported: Boolean = false,
    val totals: List<StatementTotalUi> = emptyList(),
    val matchedLines: List<StatementLineUi> = emptyList(),
    val statementOnlyLines: List<StatementLineUi> = emptyList(),
    val ledgerOnlyLines: List<StatementLineUi> = emptyList(),
    val ambiguousLines: List<StatementLineUi> = emptyList(),
    val unsupportedLines: List<StatementLineUi> = emptyList(),
)

data class StatementTotalUi(
    val bankId: String,
    val accountMasked: String,
    val periodStart: String,
    val periodEnd: String,
    val currencyCode: String,
    val matchedDebit: String,
    val matchedCredit: String,
    val statementOnlyDebit: String,
    val statementOnlyCredit: String,
    val ledgerOnlyDebit: String,
    val ledgerOnlyCredit: String,
)

data class StatementLineUi(
    val title: String,
    val amountLabel: String,
    val detail: String,
)

fun statementReconciliationUiState(result: StatementImportResult): StatementReconciliationUiState =
    when (result) {
        is StatementImportResult.Rejected ->
            StatementReconciliationUiState(
                rejection = result.reason,
                unreadable = result.reason == StatementRejection.UNREADABLE,
            )

        is StatementImportResult.Compared -> {
            val report = result.report
            StatementReconciliationUiState(
                hasReport = true,
                matchedCount = report.counts.matched,
                statementOnlyCount = report.counts.statementOnly,
                ledgerOnlyCount = report.counts.ledgerOnly,
                ambiguousCount = report.counts.ambiguous,
                unsupportedCount = report.counts.unsupported,
                matchedFleetPaymentCount = report.matchedFleetPaymentCount,
                matchedSelfTransferCount = report.matchedSelfTransferCount,
                balanceUnsupported = report.balanceCheck == StatementComparisonStatus.UNSUPPORTED,
                totals = report.totals.map { it.toUi() },
                matchedLines = report.statementLines
                    .filter { it.status == StatementComparisonStatus.MATCHED }
                    .map { it.toUi() },
                statementOnlyLines = report.statementLines
                    .filter { it.status == StatementComparisonStatus.STATEMENT_ONLY }
                    .map { it.toUi() },
                ledgerOnlyLines = report.ledgerLines
                    .filter { it.status == StatementComparisonStatus.LEDGER_ONLY }
                    .map { it.toUi() },
                ambiguousLines = report.statementLines
                    .filter { it.status == StatementComparisonStatus.AMBIGUOUS }
                    .map { it.toUi() } +
                    report.ledgerLines
                        .filter { it.status == StatementComparisonStatus.AMBIGUOUS }
                        .map { it.toUi() },
                unsupportedLines = report.statementLines
                    .filter { it.status == StatementComparisonStatus.UNSUPPORTED }
                    .map { it.toUi() } +
                    report.ledgerLines
                        .filter { it.status == StatementComparisonStatus.UNSUPPORTED }
                        .map { it.toUi() },
            )
        }
    }

class StatementReconciliationViewModel(
    private val workflow: StatementReconciliationWorkflow,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StatementReconciliationUiState())
    val uiState: StateFlow<StatementReconciliationUiState> = _uiState.asStateFlow()

    fun compare(stream: InputStream) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, rejection = null, unreadable = false)
            val result = try {
                withContext(Dispatchers.IO) {
                    stream.use { workflow.compare(it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                StatementImportResult.Rejected(StatementRejection.UNREADABLE)
            }
            _uiState.value = statementReconciliationUiState(result)
        }
    }

    fun onUnreadable() {
        _uiState.value = StatementReconciliationUiState(unreadable = true)
    }
}

class StatementReconciliationViewModelFactory(
    private val workflow: StatementReconciliationWorkflow,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(StatementReconciliationViewModel::class.java))
        return StatementReconciliationViewModel(workflow) as T
    }
}

private fun StatementCurrencyTotal.toUi(): StatementTotalUi =
    StatementTotalUi(
        bankId = bankId,
        accountMasked = accountMasked,
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        currencyCode = currency.name,
        matchedDebit = plain(matchedDebit, currency.name),
        matchedCredit = plain(matchedCredit, currency.name),
        statementOnlyDebit = plain(statementOnlyDebit, currency.name),
        statementOnlyCredit = plain(statementOnlyCredit, currency.name),
        ledgerOnlyDebit = plain(ledgerOnlyDebit, currency.name),
        ledgerOnlyCredit = plain(ledgerOnlyCredit, currency.name),
    )

private fun StatementLineComparison.toUi(): StatementLineUi =
    StatementLineUi(
        title = entry.description,
        amountLabel = plain(entry.amount.amount, entry.amount.currency.name),
        detail = "${entry.bank.id} · ${entry.accountMasked} · ${entry.bookedDate} · ${entry.direction.name}",
    )

private fun LedgerLineComparison.toUi(): StatementLineUi =
    StatementLineUi(
        title = transactionId,
        amountLabel = plain(amount, currency.name),
        detail = "${bank.id} · $accountMasked · $civilDate · ${direction.name}",
    )

private fun plain(amount: BigDecimal, currencyCode: String): String =
    amount.stripTrailingZeros().let { stripped ->
        val scaled = if (stripped.scale() < 2) amount.setScale(2) else amount
        scaled.toPlainString() + " " + currencyCode
    }
