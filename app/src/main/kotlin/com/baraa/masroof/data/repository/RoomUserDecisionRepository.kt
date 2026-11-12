package com.baraa.masroof.data.repository

import androidx.room.withTransaction
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.dao.RoomBatch
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import com.baraa.masroof.data.room.mapper.MoneyPersistence
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.domain.repository.UserDecisionRepository
import java.time.Instant

class RoomUserDecisionRepository(
    private val database: MasroofDatabase,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : UserDecisionRepository {
    override suspend fun <R : Any> recordWith(decision: UserDecision, before: suspend () -> R?, write: suspend () -> R?): R? =
        accessGate.withAccess {
            database.withTransaction {
                val previous = before()
                write()?.also { if (it != previous) database.appendDecision(decision) }
            }
        }

    override suspend fun effectiveFor(rawSmsIds: Collection<String>): Map<String, EffectiveUserDecisions> =
        accessGate.withAccess {
            val ids = rawSmsIds.distinct()
            // A pair row can name one id in each of two chunks, so rows are de-duplicated by sequence.
            val rows = RoomBatch.query(ids, RoomBatch.MAX_BIND_ARGS / 2) { database.userDecisionDao().listNaming(it) }
                .distinctBy { it.sequence }.sortedBy { it.sequence }.map(::toDomain)
            val naming = HashMap<String, MutableList<UserDecision>>()
            for (row in rows) setOfNotNull(row.rawSmsId, row.pairedRawSmsId).forEach { naming.getOrPut(it, ::mutableListOf) += row }
            ids.associateWith { id -> naming[id]?.let { EffectiveUserDecisions.of(id, it) } ?: EffectiveUserDecisions.NONE }
        }

    private fun toDomain(row: UserDecisionEntity) = UserDecision(
        rawSmsId = row.rawSmsId,
        kind = UserDecisionKind.valueOf(row.kind),
        decidedAt = Instant.ofEpochMilli(row.decidedAtEpochMillis),
        pairedRawSmsId = row.pairedRawSmsId,
        transactionType = row.transactionType?.let(FinancialTransactionType::valueOf),
        sourceContainerId = row.sourceContainerId,
        destinationContainerId = row.destinationContainerId,
        occurredAt = row.occurredAtEpochMillis?.let(Instant::ofEpochMilli),
        occurredAtZone = row.occurredAtZone,
        messageFamily = row.messageFamily?.let(MessageFamily::valueOf),
        amount = MoneyPersistence.fromColumns(row.amountDecimal, row.amountCurrency),
        merchant = row.merchant,
        counterparty = row.counterparty,
        bankId = row.bankId,
    )
}

/** Appends [decision]; the caller is already inside a database transaction. */
internal suspend fun MasroofDatabase.appendDecision(decision: UserDecision) {
    val (amountDecimal, amountCurrency) = MoneyPersistence.toColumns(decision.amount)
    userDecisionDao().insert(
        UserDecisionEntity(
            rawSmsId = decision.rawSmsId,
            pairedRawSmsId = decision.pairedRawSmsId,
            kind = decision.kind.name,
            decidedAtEpochMillis = decision.decidedAt.toEpochMilli(),
            transactionType = decision.transactionType?.name,
            sourceContainerId = decision.sourceContainerId,
            destinationContainerId = decision.destinationContainerId,
            occurredAtEpochMillis = decision.occurredAt?.toEpochMilli(),
            occurredAtZone = decision.occurredAtZone,
            messageFamily = decision.messageFamily?.name,
            amountDecimal = amountDecimal,
            amountCurrency = amountCurrency,
            merchant = decision.merchant,
            counterparty = decision.counterparty,
            bankId = decision.bankId,
        ),
    )
}
