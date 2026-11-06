package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.logging.AppLogCategories
import com.baraa.masroof.application.logging.AppLogFormatting
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.ownership.OwnershipResolver
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.TransactionReclassificationRepository
import java.time.Clock

sealed interface ReclassificationResult {
    data class Success(val transaction: FinancialTransaction) : ReclassificationResult

    data class Rejected(val reason: String) : ReclassificationResult
}

/**
 * Updates an already-persisted single-SMS [FinancialTransaction] type and container wiring.
 */
class TransactionReclassificationService(
    private val financialTransactionRepository: FinancialTransactionRepository,
    private val effectiveParsedEventProvider: EffectiveParsedEventProvider,
    private val ownershipResolver: OwnershipResolver,
    private val ownershipConfirmationService: OwnershipConfirmationService? = null,
    private val appLogService: AppLogService? = null,
    private val persistence: TransactionReclassificationRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val afterDecisionCommitted: () -> Unit = {},
) {
    private suspend fun ownsCounterpart(ref: com.baraa.masroof.domain.model.AccountReference?) =
        ref != null && ownershipResolver.resolveAccount(ref) == OwnershipStatus.OWNED

    suspend fun reclassify(
        transactionId: String,
        newType: FinancialTransactionType,
    ): ReclassificationResult {
        if (newType !in ALLOWED_TYPES) {
            return ReclassificationResult.Rejected("type_not_allowed")
        }
        val existing = financialTransactionRepository.getById(transactionId)
            ?: return ReclassificationResult.Rejected("transaction_not_found")
        if (existing.type == newType) {
            return ReclassificationResult.Success(existing)
        }

        val rawSmsIds = financialTransactionRepository.listRawSmsIds(transactionId)
        if (rawSmsIds.size != 1) {
            return ReclassificationResult.Rejected("paired_transaction_not_supported")
        }

        val record = effectiveParsedEventProvider.findEffectiveByRawSmsId(rawSmsIds.single())
            ?: return ReclassificationResult.Rejected("parsed_event_missing")
        val event = record.event

        val sourceAccountId = event.sourceAccountRef?.let(FinancialContainerIdFactory::accountId)
        val destAccountId = event.destinationAccountRef?.let(FinancialContainerIdFactory::accountId)
        val cardId = event.cardRef?.let(FinancialContainerIdFactory::cardId)

        var loanToConfirm: LoanReference? = null
        val (sourceId, destId) = when (newType) {
            FinancialTransactionType.EXTERNAL_TRANSFER_IN -> {
                val destRef = event.destinationAccountRef
                    ?: return ReclassificationResult.Rejected("missing_destination")
                if (ownershipResolver.resolveAccount(destRef) != OwnershipStatus.OWNED) {
                    return ReclassificationResult.Rejected("destination_not_owned")
                }
                if (FinancialContainerIdFactory.accountId(destRef) == null) {
                    return ReclassificationResult.Rejected("destination_not_durable")
                }
                if (ownsCounterpart(event.sourceAccountRef)) sourceAccountId to destAccountId else null to destAccountId
            }

            FinancialTransactionType.EXTERNAL_TRANSFER_OUT -> {
                val sourceRef = event.sourceAccountRef
                    ?: return ReclassificationResult.Rejected("missing_source")
                if (ownershipResolver.resolveAccount(sourceRef) != OwnershipStatus.OWNED) {
                    return ReclassificationResult.Rejected("source_not_owned")
                }
                if (FinancialContainerIdFactory.accountId(sourceRef) == null) {
                    return ReclassificationResult.Rejected("source_not_durable")
                }
                if (ownsCounterpart(event.destinationAccountRef)) sourceAccountId to destAccountId else sourceAccountId to null
            }

            FinancialTransactionType.EXPENSE ->
                (cardId ?: sourceAccountId) to null

            FinancialTransactionType.INCOME ->
                null to destAccountId

            FinancialTransactionType.CREDIT_CARD_PAYMENT ->
                sourceAccountId to cardId

            FinancialTransactionType.REFUND ->
                null to (destAccountId ?: cardId)

            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.FEE,
            ->
                sourceAccountId to null

            FinancialTransactionType.LOAN_REPAYMENT -> {
                val sourceRef = event.sourceAccountRef
                    ?: return ReclassificationResult.Rejected("missing_source")
                if (ownershipResolver.resolveAccount(sourceRef) != OwnershipStatus.OWNED) {
                    return ReclassificationResult.Rejected("source_not_owned")
                }
                val loanType = record.details.loanType
                    ?: return ReclassificationResult.Rejected("loan_type_missing")
                loanToConfirm = LoanReference(event.bank, loanType)
                sourceAccountId to FinancialContainerIdFactory.loanId(event.bank, loanType)
            }

            else ->
                return ReclassificationResult.Rejected("type_not_allowed")
        }

        // Money moving between two owned accounts leaves one and enters the other; it is never spending or income.
        val type = if (newType in EXTERNAL_TRANSFERS && sourceId != null && destId != null) FinancialTransactionType.SELF_TRANSFER else newType
        if (type == FinancialTransactionType.SELF_TRANSFER && sourceId == destId) return ReclassificationResult.Rejected("same_transfer_account")
        val updated = existing.copy(
            type = type,
            sourceContainerId = sourceId,
            destinationContainerId = destId,
        )
        val saved = try { persistence?.updateWithDecision(updated, rawSmsIds.single(), clock.instant())
            ?: financialTransactionRepository.update(updated) }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(_: Exception) { false }
        if (!saved) {
            return ReclassificationResult.Rejected("update_failed")
        }
        // Only a saved repayment may mark its loan owned. The repayment is already saved, so a failed
        // confirmation leaves the loan for the user to confirm instead of reporting the save as failed.
        loanToConfirm?.let { loan ->
            try {
                maybeConfirmLoanOwned(loan)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogService?.warn(
                    AppLogCategories.TRANSACTION,
                    "Loan ownership confirmation failed after reclassify (${e::class.java.simpleName})",
                )
            }
        }
        afterDecisionCommitted()
        appLogService?.info(
            AppLogCategories.TRANSACTION,
            "Reclassified ${AppLogFormatting.maskId(transactionId)} to ${type.name.lowercase()}",
        )
        return ReclassificationResult.Success(financialTransactionRepository.getById(updated.id) ?: updated)
    }

    private suspend fun maybeConfirmLoanOwned(reference: LoanReference) {
        if (ownershipResolver.resolveLoan(reference) != OwnershipStatus.UNKNOWN) return
        ownershipConfirmationService?.confirmLoanOwned(reference)
    }

    companion object {
        private val EXTERNAL_TRANSFERS = setOf(FinancialTransactionType.EXTERNAL_TRANSFER_IN, FinancialTransactionType.EXTERNAL_TRANSFER_OUT)
        val ALLOWED_TYPES: Set<FinancialTransactionType> = setOf(
            FinancialTransactionType.EXPENSE,
            FinancialTransactionType.INCOME,
            FinancialTransactionType.CREDIT_CARD_PAYMENT,
            FinancialTransactionType.REFUND,
            FinancialTransactionType.CASH_WITHDRAWAL,
            FinancialTransactionType.BILL_PAYMENT,
            FinancialTransactionType.FEE,
            FinancialTransactionType.LOAN_REPAYMENT,
            FinancialTransactionType.EXTERNAL_TRANSFER_IN,
            FinancialTransactionType.EXTERNAL_TRANSFER_OUT,
        )
    }
}
