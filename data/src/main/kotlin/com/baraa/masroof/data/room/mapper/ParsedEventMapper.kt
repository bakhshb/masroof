package com.baraa.masroof.data.room.mapper

import com.baraa.masroof.data.room.entity.ParsedEventEntity
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.Instant
import java.time.LocalDateTime

object ParsedEventMapper {
    fun toEntity(event: ParsedEvent, details: ParsedEventDetails): ParsedEventEntity {
        val (amountDecimal, amountCurrency) = MoneyPersistence.toColumns(event.amount)
        val (availableDecimal, availableCurrency) = MoneyPersistence.toColumns(details.availableBalance)
        val (outstandingDecimal, outstandingCurrency) =
            MoneyPersistence.toColumns(details.outstandingBalance)
        val (internationalFeeDecimal, internationalFeeCurrency) =
            MoneyPersistence.toColumns(details.internationalFee)
        val (labeledForeignDecimal, labeledForeignCurrency) =
            MoneyPersistence.toColumns(details.labeledForeignAmount)

        return ParsedEventEntity(
            id = event.id,
            rawSmsId = event.rawSmsId,
            bankId = event.bank.id,
            messageFamily = event.messageFamily.name,
            direction = event.direction?.name,
            amountDecimal = amountDecimal,
            amountCurrency = amountCurrency,
            purchaseChannel = event.purchaseChannel?.name,
            sourceAccountBankId = event.sourceAccountRef?.bank?.id,
            sourceAccountMaskedNumber = event.sourceAccountRef?.maskedNumber,
            destinationAccountBankId = event.destinationAccountRef?.bank?.id,
            destinationAccountMaskedNumber = event.destinationAccountRef?.maskedNumber,
            cardBankId = event.cardRef?.bank?.id,
            cardLast4 = event.cardRef?.last4,
            merchant = event.merchant,
            counterparty = event.counterparty,
            occurredAtEpochMillis = event.occurredAt?.toEpochMilli(),
            bankNetworkType = event.bankNetworkType?.name,
            confidenceScore = event.confidence.score,
            confidenceReasons = encodeReasons(event.confidence.reasons),
            parseStatus = event.parseStatus.name,
            transactionReference = details.transactionReference,
            availableBalanceDecimal = availableDecimal,
            availableBalanceCurrency = availableCurrency,
            outstandingBalanceDecimal = outstandingDecimal,
            outstandingBalanceCurrency = outstandingCurrency,
            biller = details.biller,
            billerCode = details.billerCode,
            // LocalDateTime.toString() is ISO-8601 local; preserves fractional seconds.
            occurredAtLocal = details.occurredAtLocal?.toString(),
            cardSmsChannel = details.cardSmsChannel?.name,
            paymentDueDate = details.paymentDueDate?.toString(),
            exchangeRate = details.exchangeRate?.toPlainString(),
            internationalFeeDecimal = internationalFeeDecimal,
            internationalFeeCurrency = internationalFeeCurrency,
            labeledForeignAmountDecimal = labeledForeignDecimal,
            labeledForeignAmountCurrency = labeledForeignCurrency,
            loanType = details.loanType?.name,
            debitSourceAccountLast4 = details.debitSourceAccountLast4,
            salaryIncomeWording = details.salaryIncomeWording?.let(::booleanToInt),
        )
    }

    fun toRecord(entity: ParsedEventEntity): ParsedEventRecord =
        ParsedEventRecord(
            event = toDomainEvent(entity),
            details = toDetails(entity),
        )

    fun toDomainEvent(entity: ParsedEventEntity): ParsedEvent =
        ParsedEvent(
            id = entity.id,
            rawSmsId = entity.rawSmsId,
            bank = Bank(entity.bankId),
            messageFamily = enumValue(MessageFamily::class.java, entity.messageFamily),
            direction = entity.direction?.let { enumValue(MoneyDirection::class.java, it) },
            amount = MoneyPersistence.fromColumns(entity.amountDecimal, entity.amountCurrency),
            purchaseChannel = entity.purchaseChannel?.let {
                enumValue(PurchaseChannel::class.java, it)
            },
            sourceAccountRef = accountRef(entity.sourceAccountBankId, entity.sourceAccountMaskedNumber),
            destinationAccountRef = accountRef(
                entity.destinationAccountBankId,
                entity.destinationAccountMaskedNumber,
            ),
            cardRef = cardRef(entity.cardBankId, entity.cardLast4),
            merchant = entity.merchant,
            counterparty = entity.counterparty,
            occurredAt = entity.occurredAtEpochMillis?.let { Instant.ofEpochMilli(it) },
            bankNetworkType = entity.bankNetworkType?.let {
                enumValue(BankNetworkType::class.java, it)
            },
            confidence = Confidence(
                score = entity.confidenceScore,
                reasons = decodeReasons(entity.confidenceReasons),
            ),
            parseStatus = enumValue(ParseStatus::class.java, entity.parseStatus),
        )

    fun toDetails(entity: ParsedEventEntity): ParsedEventDetails =
        ParsedEventDetails(
            transactionReference = entity.transactionReference,
            availableBalance = MoneyPersistence.fromColumns(
                entity.availableBalanceDecimal,
                entity.availableBalanceCurrency,
            ),
            outstandingBalance = MoneyPersistence.fromColumns(
                entity.outstandingBalanceDecimal,
                entity.outstandingBalanceCurrency,
            ),
            biller = entity.biller,
            billerCode = entity.billerCode,
            occurredAtLocal = entity.occurredAtLocal?.let { LocalDateTime.parse(it) },
            cardSmsChannel = entity.cardSmsChannel?.let { cardSmsChannel(it) },
            paymentDueDate = entity.paymentDueDate?.let { java.time.LocalDate.parse(it) },
            exchangeRate = entity.exchangeRate?.toBigDecimalOrNull(),
            internationalFee = MoneyPersistence.fromColumns(
                entity.internationalFeeDecimal,
                entity.internationalFeeCurrency,
            ),
            labeledForeignAmount = MoneyPersistence.fromColumns(
                entity.labeledForeignAmountDecimal,
                entity.labeledForeignAmountCurrency,
            ),
            loanType = entity.loanType?.let(::loanType),
            debitSourceAccountLast4 = entity.debitSourceAccountLast4,
            salaryIncomeWording = entity.salaryIncomeWording?.let(::intToBoolean),
        )

    fun encodeReasons(reasons: List<String>): String = ReasonsCodec.encode(reasons, ParsedEventEntity.CONFIDENCE_REASON_SEPARATOR)

    fun decodeReasons(encoded: String): List<String> = ReasonsCodec.decode(encoded, ParsedEventEntity.CONFIDENCE_REASON_SEPARATOR)

    private fun accountRef(bankId: String?, masked: String?): AccountReference? {
        if (bankId == null && masked == null) return null
        require(bankId != null) { "AccountReference bank id missing while maskedNumber=$masked" }
        return AccountReference(bank = Bank(bankId), maskedNumber = masked)
    }

    private fun cardRef(bankId: String?, last4: String?): CardReference? {
        if (bankId == null && last4 == null) return null
        require(bankId != null) { "CardReference bank id missing while last4=$last4" }
        return CardReference(bank = Bank(bankId), last4 = last4)
    }

    private fun booleanToInt(value: Boolean): Int = if (value) 1 else 0

    private fun intToBoolean(value: Int): Boolean = value != 0

    private fun loanType(name: String): LoanType =
        try {
            LoanType.valueOf(name)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unrecognized persisted LoanType value '$name'", e)
        }

    private fun cardSmsChannel(name: String): CardSmsChannel =
        try {
            CardSmsChannel.valueOf(name)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unrecognized persisted CardSmsChannel value '$name'", e)
        }

    private fun <E : Enum<E>> enumValue(type: Class<E>, name: String): E =
        try {
            java.lang.Enum.valueOf(type, name)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Unrecognized persisted ${type.simpleName} value '$name'",
                e,
            )
        }
}
