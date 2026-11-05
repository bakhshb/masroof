package com.baraa.masroof.testsupport

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.math.BigDecimal
import java.time.Instant

/** Ledger row for tests; every field not named by the test keeps a neutral default. */
internal fun tx(
    id: String = "tx-1",
    type: FinancialTransactionType = FinancialTransactionType.EXPENSE,
    amount: String = "10.00",
    source: String? = null,
    dest: String? = null,
    linked: List<String> = emptyList(),
    occurredAt: Instant = Instant.parse("2026-08-10T12:00:00Z"),
    merchant: String? = null,
    counterparty: String? = null,
    currency: Currency = Currency.SAR,
    rate: BigDecimal? = null,
    rateSource: ExchangeRateSource? = null,
    occurredAtZone: String? = null,
): FinancialTransaction =
    FinancialTransaction(
        id = id,
        type = type,
        amount = Money.of(amount, currency),
        occurredAt = occurredAt,
        sourceContainerId = source,
        destinationContainerId = dest,
        merchant = merchant,
        counterparty = counterparty,
        categoryId = null,
        linkedParsedEventIds = linked,
        appliedExchangeRate = rate,
        exchangeRateSource = rateSource,
        occurredAtZone = occurredAtZone,
    )

/** Successful AlJazira parsed record for tests; last4 values become bank-qualified references. */
internal fun parsedRecord(
    id: String,
    family: MessageFamily = MessageFamily.PURCHASE,
    direction: MoneyDirection? = MoneyDirection.OUTGOING,
    amount: Money? = Money.of("1.00", Currency.SAR),
    sourceLast4: String? = null,
    destinationLast4: String? = null,
    cardLast4: String? = null,
    body: String? = null,
    occurredAt: Instant = Instant.parse("2026-08-10T12:00:00Z"),
    rawSmsId: String = "sms-$id",
    cardSmsChannel: CardSmsChannel? = null,
    loanType: LoanType? = null,
    salaryIncomeWording: Boolean? = null,
    debitSourceAccountLast4: String? = null,
): ParsedEventRecord =
    ParsedEventRecord(
        event = ParsedEvent(
            id = id,
            rawSmsId = rawSmsId,
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = family,
            direction = direction,
            amount = amount,
            purchaseChannel = null,
            sourceAccountRef = sourceLast4?.let { AccountReference(Bank.BANK_ALJAZIRA, it) },
            destinationAccountRef = destinationLast4?.let { AccountReference(Bank.BANK_ALJAZIRA, it) },
            cardRef = cardLast4?.let { CardReference(Bank.BANK_ALJAZIRA, it) },
            merchant = null,
            counterparty = body,
            occurredAt = occurredAt,
            bankNetworkType = null,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        ),
        details = ParsedEventDetails(
            cardSmsChannel = cardSmsChannel,
            loanType = loanType,
            salaryIncomeWording = salaryIncomeWording,
            debitSourceAccountLast4 = debitSourceAccountLast4,
        ),
    )
