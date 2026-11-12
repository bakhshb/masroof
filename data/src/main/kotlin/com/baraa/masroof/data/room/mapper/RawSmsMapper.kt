package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.data.room.entity.RawSmsEntity
import com.baraa.masroof.domain.model.RawSms
import java.time.Instant

object RawSmsMapper {
    fun toEntity(domain: RawSms): RawSmsEntity {
        val receivedAtEpochMillis = domain.receivedAt.toEpochMilli()
        return RawSmsEntity(
            id = domain.id,
            sender = domain.sender,
            body = domain.body,
            receivedAtEpochMillis = receivedAtEpochMillis,
            deviceMessageId = domain.deviceMessageId,
            bodyHash = domain.bodyHash,
            dedupeKey = dedupeKey(
                sender = domain.sender,
                receivedAtEpochMillis = receivedAtEpochMillis,
                bodyHash = domain.bodyHash,
            ),
        )
    }

    fun toDomain(entity: RawSmsEntity): RawSms =
        RawSms(
            id = entity.id,
            sender = entity.sender,
            body = entity.body,
            receivedAt = Instant.ofEpochMilli(entity.receivedAtEpochMillis),
            deviceMessageId = entity.deviceMessageId,
            bodyHash = entity.bodyHash,
        )

    fun dedupeKey(sender: String, receivedAtEpochMillis: Long, bodyHash: String): String =
        "$sender|$receivedAtEpochMillis|$bodyHash"
}
