package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.commitment.CommitmentCreationResult
import com.baraa.masroof.application.dashboard.DashboardCommitmentsWorkflow
import com.baraa.masroof.application.dashboard.DashboardTransactionFacts
import com.baraa.masroof.application.dashboard.TransactionSmsEvidenceLoader
import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.transaction.IgnoreResult
import com.baraa.masroof.application.transaction.ReclassificationResult
import com.baraa.masroof.application.transaction.TransactionIgnoreService
import com.baraa.masroof.application.transaction.TransactionReclassificationService
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.presentation.common.launchLogged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The open transaction of [DashboardViewModel]: its messages and the edits made from its detail screen. It shares the dashboard state. */
internal class DashboardTransactionDetailActions(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<DashboardUiState>,
    private val reclassificationService: TransactionReclassificationService,
    private val ignoreService: TransactionIgnoreService,
    private val dashboardCommitmentsWorkflow: DashboardCommitmentsWorkflow,
    private val smsEvidenceLoader: TransactionSmsEvidenceLoader,
    private val analytics: com.baraa.masroof.application.analytics.AnalyticsWorkflow?,
    private val appLogService: AppLogService?,
    private val toPreview: (FinancialTransaction, DashboardTransactionFacts?) -> TransactionPreviewUi,
    private val refresh: () -> Unit,
) {
    fun openTransactionDetail(transactionId: String) {
        state.update {
            it.copy(
                selectedTransactionId = transactionId,
                labSelectedTransaction = null,
                selectedTransactionSms = emptyList(),
                selectedTransactionSmsLoading = true,
                reclassifySuccess = false,
                reclassifyError = null,
                ignoring = false,
                ignoreError = null,
                markingCommitment = false,
                markCommitmentError = null,
                markCommitmentSuccess = false,
            )
        }
        loadSelectedTransactionSms(transactionId)
    }

    fun closeTransactionDetail() {
        state.update {
            it.copy(
                selectedTransactionId = null,
                labSelectedTransaction = null,
                selectedTransactionSms = emptyList(),
                selectedTransactionSmsLoading = false,
                reclassifying = false,
                reclassifySuccess = false,
                reclassifyError = null,
                ignoring = false,
                ignoreError = null,
                markingCommitment = false,
                markCommitmentError = null,
                markCommitmentSuccess = false,
            )
        }
    }

    fun loadSelectedTransactionSms(transactionId: String) {
        scope.launch {
            val outsidePeriod = state.value.allTransactions.none { it.id == transactionId } && state.value.recentTransactions.none { it.id == transactionId }
            val labRow = if (!outsidePeriod) null else try {
                analytics?.evidence(listOf(transactionId))?.firstOrNull()?.let { item -> item.transaction?.let { toPreview(it, item.facts) } }
            } catch (ce: CancellationException) { throw ce } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.DASHBOARD, "Dashboard loadSelectedTransactionSms", caught); null }
            val evidence = try {
                smsEvidenceLoader.loadForTransaction(transactionId).map { item ->
                    TransactionSmsEvidenceUi(body = item.body, sender = item.sender, provenance = item.provenance)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.DASHBOARD, "Dashboard loadSelectedTransactionSms", caught);
                emptyList()
            }
            state.update { current ->
                if (current.selectedTransactionId != transactionId) {
                    current
                } else {
                    current.copy(
                        selectedTransactionSms = evidence,
                        labSelectedTransaction = labRow,
                        selectedTransactionSmsLoading = false,
                    )
                }
            }
        }
    }

    fun reclassifySelectedTransaction(newType: FinancialTransactionType) {
        val transactionId = state.value.selectedTransactionId ?: return
        scope.launchLogged(appLogService, AppLogCategories.DASHBOARD, "Dashboard reclassifySelectedTransaction", onError = {
            state.update { it.copy(reclassifying = false, reclassifyError = "update_failed") }
        }) {
            state.update { it.copy(reclassifying = true, reclassifySuccess = false, reclassifyError = null, ignoreError = null) }
            when (val result = reclassificationService.reclassify(transactionId, newType)) {
                is ReclassificationResult.Success -> {
                    refresh()
                    state.update {
                        it.copy(
                            reclassifying = false,
                            reclassifySuccess = true,
                            reclassifyError = null,
                        )
                    }
                }
                is ReclassificationResult.Rejected -> {
                    state.update {
                        it.copy(
                            reclassifying = false,
                            reclassifyError = result.reason,
                        )
                    }
                }
            }
        }
    }

    fun markSelectedTransactionAsCommitment() {
        val transactionId = state.value.selectedTransactionId ?: return
        scope.launch {
            state.update {
                it.copy(
                    markingCommitment = true,
                    markCommitmentError = null,
                    markCommitmentSuccess = false,
                )
            }
            val result = try {
                dashboardCommitmentsWorkflow.createFromTransaction(transactionId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (caught: Exception) { appLogService?.failure(com.baraa.masroof.application.logging.AppLogCategories.DASHBOARD, "Dashboard markSelectedTransactionAsCommitment", caught);
                CommitmentCreationResult.Rejected("create_failed")
            }
            when (result) {
                CommitmentCreationResult.Success -> {
                    refresh()
                    state.update {
                        it.copy(
                            markingCommitment = false,
                            markCommitmentSuccess = true,
                            markCommitmentError = null,
                            committedSourceTransactionIds = it.committedSourceTransactionIds + transactionId,
                        )
                    }
                }
                CommitmentCreationResult.AlreadyExists -> {
                    state.update {
                        it.copy(
                            markingCommitment = false,
                            markCommitmentError = "already_exists",
                            committedSourceTransactionIds = it.committedSourceTransactionIds + transactionId,
                        )
                    }
                }
                is CommitmentCreationResult.Rejected -> {
                    state.update {
                        it.copy(
                            markingCommitment = false,
                            markCommitmentError = result.reason,
                        )
                    }
                }
            }
        }
    }

    fun ignoreSelectedTransaction() {
        val transactionId = state.value.selectedTransactionId ?: return
        scope.launch {
            state.update { it.copy(ignoring = true, ignoreError = null, reclassifyError = null) }
            when (val result = ignoreService.ignore(transactionId)) {
                is IgnoreResult.Success -> {
                    refresh()
                    state.update {
                        it.copy(
                            selectedTransactionId = null,
                labSelectedTransaction = null,
                            selectedTransactionSms = emptyList(),
                            selectedTransactionSmsLoading = false,
                            ignoring = false,
                            ignoreError = null,
                        )
                    }
                }
                is IgnoreResult.Rejected -> {
                    state.update {
                        it.copy(
                            ignoring = false,
                            ignoreError = result.reason,
                        )
                    }
                }
            }
        }
    }
}
