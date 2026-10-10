package com.baraa.masroof.data.repository

import androidx.room.withTransaction
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.repository.TransactionIgnoreOutcome
import com.baraa.masroof.domain.repository.TransactionIgnoreRepository
import java.time.Instant

class RoomTransactionIgnoreRepository(
    private val database: MasroofDatabase,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
    private val afterDelete: () -> Unit = {},
) : TransactionIgnoreRepository {
    override suspend fun ignoreSingle(transactionId: String, resolvedAt: Instant): TransactionIgnoreOutcome =
        accessGate.withAccess {
            try {
                database.withTransaction {
                    val financial = database.financialTransactionDao()
                    if (financial.getById(transactionId) == null) {
                        return@withTransaction TransactionIgnoreOutcome.Rejected("transaction_not_found")
                    }
                    val ids = financial.listRawSmsIdsForTransaction(transactionId)
                    if (ids.size != 1) {
                        return@withTransaction TransactionIgnoreOutcome.Rejected("paired_transaction_not_supported")
                    }
                    val id = ids.single()
                    val reviews = RoomReviewRepository(database.reviewItemDao())
                    val review = reviews.findByRawSmsId(id) ?: reviews.upsertRequired(
                        id, ReviewKind.NEEDS_REVIEW, listOf("user_ignored_transaction"), resolvedAt,
                    )
                    if (!financial.deleteIfExclusiveRawSmsLink(id)) throw IgnoreRollback("delete_failed")
                    afterDelete()
                    val resolved = reviews.markResolved(
                        review.id, ReviewResolutionKind.USER_NON_FINANCIAL, resolvedAt, null,
                    ) ?: throw IgnoreRollback("review_resolution_failed")
                    if (resolved.resolutionKind != ReviewResolutionKind.USER_NON_FINANCIAL) {
                        throw IgnoreRollback("review_resolution_failed")
                    }
                    database.processingRetryDao().delete(id)
                    TransactionIgnoreOutcome.Ignored
                }
            } catch (error: IgnoreRollback) {
                TransactionIgnoreOutcome.Rejected(error.reason)
            }
        }

    private class IgnoreRollback(val reason: String) : Exception(reason)
}
