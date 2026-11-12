package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.data.room.entity.ReviewItemEntity
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import java.time.Instant

object ReviewItemMapper {
    fun toEntity(item: ReviewItem): ReviewItemEntity =
        ReviewItemEntity(
            id = item.id,
            rawSmsId = item.rawSmsId,
            kind = item.kind.name,
            status = item.status.name,
            reasons = encodeReasons(item.reasons),
            createdAtEpochMillis = item.createdAt.toEpochMilli(),
            updatedAtEpochMillis = item.updatedAt.toEpochMilli(),
            resolvedAtEpochMillis = item.resolvedAt?.toEpochMilli(),
            resolutionKind = item.resolutionKind?.name,
            resolvedTransactionId = item.resolvedTransactionId,
        )

    fun toDomain(entity: ReviewItemEntity): ReviewItem =
        ReviewItem(
            id = entity.id,
            rawSmsId = entity.rawSmsId,
            kind = ReviewKind.valueOf(entity.kind),
            status = ReviewStatus.valueOf(entity.status),
            reasons = decodeReasons(entity.reasons),
            createdAt = Instant.ofEpochMilli(entity.createdAtEpochMillis),
            updatedAt = Instant.ofEpochMilli(entity.updatedAtEpochMillis),
            resolvedAt = entity.resolvedAtEpochMillis?.let(Instant::ofEpochMilli),
            resolutionKind = entity.resolutionKind?.let(ReviewResolutionKind::valueOf),
            resolvedTransactionId = entity.resolvedTransactionId,
        )

    fun encodeReasons(reasons: List<String>): String = ReasonsCodec.encode(reasons, ReviewItemEntity.REASON_SEPARATOR)

    fun decodeReasons(encoded: String): List<String> = ReasonsCodec.decode(encoded, ReviewItemEntity.REASON_SEPARATOR)
}

/** Persisted reason lists share one encoding; each entity keeps its own separator constant. */
internal object ReasonsCodec {
    fun encode(reasons: List<String>, separator: Char): String {
        require(reasons.none { separator in it }) { "Reason must not contain the persistence separator" }
        return reasons.joinToString(separator.toString())
    }

    fun decode(encoded: String, separator: Char): List<String> = if (encoded.isEmpty()) emptyList() else encoded.split(separator)
}
