package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.entity.UserCorrectionEntity
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.UserCorrection
import java.math.BigDecimal
import java.time.Instant

object UserCorrectionMapper {
    fun toEntity(correction: UserCorrection): UserCorrectionEntity =
        UserCorrectionEntity(
            id = correction.id,
            targetRawSmsId = correction.targetRawSmsId,
            correctedMessageFamily = correction.correctedType?.name,
            correctedAmountDecimal = correction.correctedAmount?.amount?.toPlainString(),
            correctedAmountCurrency = correction.correctedAmount?.currency?.name,
            correctedMerchant = correction.correctedMerchant,
            correctedCounterparty = correction.correctedCounterparty,
            createdAtEpochMillis = correction.createdAt.toEpochMilli(),
        )

    fun toDomain(entity: UserCorrectionEntity): UserCorrection {
        val amount = when {
            entity.correctedAmountDecimal != null && entity.correctedAmountCurrency != null ->
                Money.of(
                    BigDecimal(entity.correctedAmountDecimal),
                    Currency.valueOf(entity.correctedAmountCurrency),
                )

            else -> null
        }
        return UserCorrection(
            id = entity.id,
            targetRawSmsId = entity.targetRawSmsId,
            correctedType = entity.correctedMessageFamily?.let(MessageFamily::valueOf),
            correctedAmount = amount,
            correctedMerchant = entity.correctedMerchant,
            correctedCounterparty = entity.correctedCounterparty,
            createdAt = Instant.ofEpochMilli(entity.createdAtEpochMillis),
        )
    }
}
