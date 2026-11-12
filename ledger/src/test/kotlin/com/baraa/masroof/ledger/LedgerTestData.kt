package com.baraa.masroof.ledger

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.LoanType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

val AJ = Bank.BANK_ALJAZIRA
val D360 = Bank("D360")
val UTC: ZoneId = ZoneOffset.UTC
val T0: Instant = Instant.parse("2026-08-01T09:00:00Z")

fun account(number: String, bank: Bank = AJ) = AccountReference(bank, number)

fun sar(amount: String) = Money.of(amount, Currency.SAR)

@Suppress("LongParameterList")
fun event(
    id: String,
    family: MessageFamily,
    amount: String? = "100",
    bank: Bank = AJ,
    source: AccountReference? = null,
    destination: AccountReference? = null,
    card: CardReference? = null,
    network: BankNetworkType? = null,
    status: ParseStatus = ParseStatus.SUCCESS,
    occurredAt: Instant? = null,
) = ParsedEvent(
    id = "e-$id",
    rawSmsId = id,
    bank = bank,
    messageFamily = family,
    direction = null,
    amount = amount?.let(::sar),
    purchaseChannel = null,
    sourceAccountRef = source,
    destinationAccountRef = destination,
    cardRef = card,
    merchant = null,
    counterparty = null,
    occurredAt = occurredAt,
    bankNetworkType = network,
    confidence = Confidence(1.0),
    parseStatus = status,
)

fun evidence(
    event: ParsedEvent,
    receivedAt: Instant,
    transactionReference: String? = null,
    occurredAtLocal: LocalDateTime? = null,
    loanType: LoanType? = null,
) = LedgerEvidence(event, receivedAt, transactionReference, occurredAtLocal, loanType, automationConfirmed = false)

/** A stored ledger row linking [rawSmsIds]: an expense of 100 SAR at [T0] unless told otherwise. */
@Suppress("LongParameterList")
fun storedRow(
    vararg rawSmsIds: String,
    zone: String? = "Asia/Riyadh",
    type: FinancialTransactionType = FinancialTransactionType.EXPENSE,
    rate: BigDecimal? = null,
    rateSource: ExchangeRateSource? = null,
    source: String? = null,
) = LedgerRow(
    FinancialTransaction(
        id = TransactionIdFactory.fromRawSmsIds(rawSmsIds.toList()), type = type, amount = sar("100"), occurredAt = T0,
        sourceContainerId = source, destinationContainerId = null, merchant = "stored", counterparty = null,
        categoryId = null, linkedParsedEventIds = rawSmsIds.map { "e-$it" }.sorted(), appliedExchangeRate = rate,
        exchangeRateSource = rateSource, occurredAtZone = zone,
    ),
    rawSmsIds.sorted(),
)

/** Folds [rows] (in append order) into the decisions in force for every raw SMS they name. */
fun fold(rows: List<UserDecision>): Map<String, EffectiveUserDecisions> =
    rows.flatMap { listOfNotNull(it.rawSmsId, it.pairedRawSmsId) }.distinct()
        .associateWith { id -> EffectiveUserDecisions.of(id, rows.filter { it.rawSmsId == id || it.pairedRawSmsId == id }) }

fun project(
    events: List<LedgerEvidence>,
    decisions: Map<String, EffectiveUserDecisions> = emptyMap(),
    registry: OwnershipFacts = OwnershipFacts(),
    stored: StoredFacts = StoredFacts(),
    zone: ZoneId = UTC,
) = LedgerProjector.project(events, decisions, registry, stored, zone)
