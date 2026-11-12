package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.entity.CommitmentEntity
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentPauseInterval
import com.baraa.masroof.domain.model.CommitmentRecurrence
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

object CommitmentMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun toDomain(entity: CommitmentEntity): Commitment =
        Commitment(
            id = entity.id,
            name = entity.name,
            amount = Money(
                amount = entity.amountDecimal.toBigDecimal(),
                currency = Currency.valueOf(entity.amountCurrency),
            ),
            transactionDate = LocalDate.parse(entity.transactionDateIso),
            recurrence = entity.recurrence?.let(CommitmentRecurrence::valueOf),
            dueDate = entity.dueDateIso?.let(LocalDate::parse),
            active = entity.active,
            pauseIntervals = decodePauseIntervals(entity.pauseIntervalsJson),
            sourceTransactionId = entity.sourceTransactionId,
            createdAt = Instant.ofEpochMilli(entity.createdAtEpochMillis),
            updatedAt = Instant.ofEpochMilli(entity.updatedAtEpochMillis),
        )

    fun toEntity(domain: Commitment): CommitmentEntity =
        CommitmentEntity(
            id = domain.id,
            name = domain.name.trim(),
            amountDecimal = domain.amount.amount.toPlainString(),
            amountCurrency = domain.amount.currency.name,
            transactionDateIso = domain.transactionDate.toString(),
            recurrence = domain.recurrence?.name,
            dueDateIso = domain.dueDate?.toString(),
            active = domain.active,
            pauseIntervalsJson = encodePauseIntervals(domain.pauseIntervals),
            sourceTransactionId = domain.sourceTransactionId,
            createdAtEpochMillis = domain.createdAt.toEpochMilli(),
            updatedAtEpochMillis = domain.updatedAt.toEpochMilli(),
        )

    fun encodePauseIntervals(intervals: List<CommitmentPauseInterval>): String {
        if (intervals.isEmpty()) return "[]"
        val records = intervals.map { interval ->
            PauseIntervalRecord(
                pausedAtEpochMillis = interval.pausedAt.toEpochMilli(),
                resumedAtEpochMillis = interval.resumedAt?.toEpochMilli(),
            )
        }
        return json.encodeToString(records)
    }

    fun decodePauseIntervals(encoded: String): List<CommitmentPauseInterval> {
        if (encoded.isBlank() || encoded == "[]") return emptyList()
        return json.decodeFromString<List<PauseIntervalRecord>>(encoded).map { record ->
            CommitmentPauseInterval(
                pausedAt = Instant.ofEpochMilli(record.pausedAtEpochMillis),
                resumedAt = record.resumedAtEpochMillis?.let(Instant::ofEpochMilli),
            )
        }
    }

    @Serializable
    private data class PauseIntervalRecord(
        val pausedAtEpochMillis: Long,
        val resumedAtEpochMillis: Long? = null,
    )
}
