package com.baraa.masroof.testsupport

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.parsing.model.ParsedEventDetails
import java.math.BigDecimal
import java.time.Instant

/**
 * Persisted ledger rows for reconstruction tests.
 *
 * One database holds a single-link transaction, several independent transactions,
 * and one transaction linked to two SMS rows. Linked parsed-event ids are stored
 * on the parsed-event rows; reconstruction must sort them. The saved domain
 * object's link list is intentionally reversed so a mapper that echoed it would fail.
 *
 * Current read shape, recorded for later batching: each list load issues one
 * link query and one parsed-event lookup per link, per transaction.
 */
object TransactionReconstructionFixture {
    const val ZONE = "Asia/Riyadh"

    val single: Sample = sample(
        name = "single",
        transactionId = "tx-recon-single",
        occurredAt = Instant.parse("2026-08-01T09:00:00Z"),
        amount = Money.of("51.99", Currency.SAR),
        merchant = "Keeta",
        counterparty = null,
        categoryId = "cat-food",
        appliedExchangeRate = BigDecimal("1.00"),
        exchangeRateSource = ExchangeRateSource.SMS,
        links = listOf(Link("raw-recon-single", "pe-recon-single")),
    )

    val many: List<Sample> = listOf(
        sample(
            name = "many-early",
            transactionId = "tx-recon-many-early",
            occurredAt = Instant.parse("2026-08-02T08:00:00Z"),
            amount = Money.of("10.00", Currency.SAR),
            merchant = "Early Shop",
            counterparty = null,
            categoryId = null,
            appliedExchangeRate = null,
            exchangeRateSource = null,
            links = listOf(Link("raw-recon-many-early", "pe-recon-many-early")),
        ),
        sample(
            name = "many-mid",
            transactionId = "tx-recon-many-mid",
            occurredAt = Instant.parse("2026-08-02T12:00:00Z"),
            amount = Money.of("20.50", Currency.USD),
            merchant = "Mid Shop",
            counterparty = "Visa",
            categoryId = null,
            appliedExchangeRate = null,
            exchangeRateSource = null,
            links = listOf(Link("raw-recon-many-mid", "pe-recon-many-mid")),
        ),
        sample(
            name = "many-late",
            transactionId = "tx-recon-many-late",
            occurredAt = Instant.parse("2026-08-02T18:00:00Z"),
            amount = Money.of("3.25", Currency.SAR),
            merchant = null,
            counterparty = "ATM",
            categoryId = "cat-cash",
            appliedExchangeRate = null,
            exchangeRateSource = null,
            links = listOf(Link("raw-recon-many-late", "pe-recon-many-late")),
        ),
    )

    val multiLink: Sample = sample(
        name = "multi",
        transactionId = "tx-recon-multi",
        occurredAt = Instant.parse("2026-08-03T07:36:00Z"),
        amount = Money.of("5500.00", Currency.SAR),
        merchant = null,
        counterparty = "Self",
        categoryId = null,
        appliedExchangeRate = null,
        exchangeRateSource = null,
        links = listOf(
            Link("raw-recon-multi-b", "pe-recon-multi-a"),
            Link("raw-recon-multi-a", "pe-recon-multi-b"),
        ),
    )

    val all: List<Sample> = listOf(single) + many + multiLink

    suspend fun seed(
        rawSmsRepository: RoomRawSmsRepository,
        parsedEventRepository: RoomParsedEventRepository,
        financialTransactionRepository: RoomFinancialTransactionRepository,
    ) {
        for (sample in all) {
            for (link in sample.links) {
                rawSmsRepository.insertIfAbsent(
                    RawSms(
                        id = link.rawSmsId,
                        sender = "AlJazira",
                        body = "body-${link.rawSmsId}",
                        receivedAt = sample.expected.occurredAt,
                        deviceMessageId = link.rawSmsId,
                        bodyHash = "hash-${link.rawSmsId}",
                    ),
                )
                parsedEventRepository.save(parsedEvent(sample, link), ParsedEventDetails())
            }
            financialTransactionRepository.save(
                sample.expected.copy(linkedParsedEventIds = sample.expected.linkedParsedEventIds.asReversed()),
                sample.links.map { it.rawSmsId }.asReversed(),
            )
        }
    }

    private fun sample(
        name: String,
        transactionId: String,
        occurredAt: Instant,
        amount: Money,
        merchant: String?,
        counterparty: String?,
        categoryId: String?,
        appliedExchangeRate: BigDecimal?,
        exchangeRateSource: ExchangeRateSource?,
        links: List<Link>,
    ): Sample {
        val expected = FinancialTransaction(
            id = transactionId,
            type = FinancialTransactionType.EXPENSE,
            amount = amount,
            occurredAt = occurredAt,
            sourceContainerId = "account:aljazira:$name",
            destinationContainerId = null,
            merchant = merchant,
            counterparty = counterparty,
            categoryId = categoryId,
            linkedParsedEventIds = links.map { it.parsedEventId }.sorted(),
            appliedExchangeRate = appliedExchangeRate,
            exchangeRateSource = exchangeRateSource,
            occurredAtZone = ZONE,
        )
        return Sample(name = name, links = links, expected = expected)
    }

    private fun parsedEvent(sample: Sample, link: Link) = ParsedEvent(
        id = link.parsedEventId,
        rawSmsId = link.rawSmsId,
        bank = Bank.BANK_ALJAZIRA,
        messageFamily = MessageFamily.PURCHASE,
        direction = MoneyDirection.OUTGOING,
        amount = sample.expected.amount,
        purchaseChannel = null,
        sourceAccountRef = null,
        destinationAccountRef = null,
        cardRef = null,
        merchant = sample.expected.merchant,
        counterparty = sample.expected.counterparty,
        occurredAt = sample.expected.occurredAt,
        bankNetworkType = null,
        confidence = Confidence(1.0),
        parseStatus = ParseStatus.SUCCESS,
    )

    data class Link(
        val rawSmsId: String,
        val parsedEventId: String,
    )

    data class Sample(
        val name: String,
        val links: List<Link>,
        val expected: FinancialTransaction,
    )
}
