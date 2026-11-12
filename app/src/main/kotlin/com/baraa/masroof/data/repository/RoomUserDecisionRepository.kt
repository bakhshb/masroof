package com.baraa.masroof.data.repository

import androidx.room.withTransaction
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.repository.UserDecisionRepository

class RoomUserDecisionRepository(
    private val database: MasroofDatabase,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
) : UserDecisionRepository {
    override suspend fun <R : Any> recordWith(decision: UserDecision, write: suspend () -> R?): R? =
        accessGate.withAccess {
            database.withTransaction {
                write()?.also { database.appendDecision(decision) }
            }
        }
}

/** Appends [decision]; the caller is already inside a database transaction. Encoded like `user_correction`. */
internal suspend fun MasroofDatabase.appendDecision(decision: UserDecision) {
    userDecisionDao().insert(
        UserDecisionEntity(
            rawSmsId = decision.rawSmsId,
            pairedRawSmsId = decision.pairedRawSmsId,
            kind = decision.kind.name,
            decidedAtEpochMillis = decision.decidedAt.toEpochMilli(),
            transactionType = decision.transactionType?.name,
            sourceContainerId = decision.sourceContainerId,
            destinationContainerId = decision.destinationContainerId,
            messageFamily = decision.messageFamily?.name,
            amountDecimal = decision.amount?.amount?.toPlainString(),
            amountCurrency = decision.amount?.currency?.name,
            merchant = decision.merchant,
            counterparty = decision.counterparty,
            bankId = decision.bankId,
        ),
    )
}
