package com.baraa.masroof.data.repository

import androidx.room.withTransaction
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.ReviewItemEntity
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.TransactionReclassificationRepository
import java.time.Instant

class RoomTransactionReclassificationRepository(
    private val database: MasroofDatabase,
    private val transactions: FinancialTransactionRepository,
    private val accessGate: DatabaseAccessGate,
) : TransactionReclassificationRepository {
    override suspend fun updateWithDecision(transaction: FinancialTransaction, rawSmsId: String, at: Instant): Boolean =
        accessGate.withAccess {
            database.withTransaction {
                if (transactions.listRawSmsIds(transaction.id) != listOf(rawSmsId)) return@withTransaction false
                val latest = transactions.getById(transaction.id) ?: return@withTransaction false
                // A concurrent amount/merchant edit must survive this type-only command.
                val edited = latest.copy(type=transaction.type,sourceContainerId=transaction.sourceContainerId,
                    destinationContainerId=transaction.destinationContainerId)
                if (!transactions.update(edited)) return@withTransaction false
                val dao = database.reviewItemDao()
                val previous = dao.findByRawSmsId(rawSmsId)
                // Keep the timestamp monotonic even for two edits within the same clock tick.
                val timestamp = maxOf(at.toEpochMilli(), (previous?.resolvedAtEpochMillis ?: -1) + 1)
                val row = ReviewItemEntity(previous?.id ?: "reclassification:$rawSmsId", rawSmsId,
                    previous?.kind ?: ReviewKind.NEEDS_REVIEW.name, ReviewStatus.RESOLVED.name,
                    previous?.reasons.orEmpty(), previous?.createdAtEpochMillis ?: timestamp, timestamp,
                    timestamp, ReviewResolutionKind.USER_FINANCIAL_TYPE.name, transaction.id)
                if (previous == null) check(dao.insertIfAbsent(row) != -1L)
                else check(dao.updateRow(row.id, row.kind, row.status, row.reasons, timestamp,
                    timestamp, row.resolutionKind, row.resolvedTransactionId) == 1)
                database.appendDecision(UserDecision.posting(UserDecisionKind.RECLASSIFY, rawSmsId, edited, at))
                true
            }
        }
}
