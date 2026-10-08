package com.baraa.masroof.testsupport

import android.content.Context
import com.baraa.masroof.application.dashboard.accountFlow
import com.baraa.masroof.application.sms.HistoricalBatchDerivedResult
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.parsing.model.CardSmsChannel
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import java.time.Instant
import java.time.LocalDate

/**
 * Replays a [GoldenScenario] through [DashboardLedgerWorld]: production historical
 * import for AlJazira SMS, or persisted facts when no parser exists for the bank.
 */
class GoldenLedgerReplay(context: Context) : AutoCloseable {
    private val world = DashboardLedgerWorld(context)

    suspend fun replay(scenario: GoldenScenario): GoldenObservation {
        applyOwnership(scenario.ownership)
        val rawIdToMessageId = linkedMapOf<String, String>()
        val ingestion = when (scenario.mode) {
            GoldenScenario.MODE_SMS_REPLAY -> ingestSms(scenario, rawIdToMessageId)
            GoldenScenario.MODE_PERSISTED_FACTS -> ingestFacts(scenario, rawIdToMessageId)
            else -> error("Unknown mode ${scenario.mode}")
        }
        applyOwnership(scenario.ownership)
        val beforeReplay = ledgerFingerprint()
        var stable = true
        if (scenario.mode == GoldenScenario.MODE_SMS_REPLAY && scenario.reimport) {
            world.importProviderBatch(providerRows(scenario.messages), enrichment())
            stable = stable && ledgerFingerprint() == beforeReplay
        }
        if (scenario.mode == GoldenScenario.MODE_SMS_REPLAY && scenario.restart) {
            world.reprocessAllStored(enrichment())
            stable = stable && ledgerFingerprint() == beforeReplay
        }
        return observe(scenario, rawIdToMessageId, ingestion, stable)
    }

    override fun close() {
        world.close()
    }

    private fun enrichment() = world.exchangeRateEnrichmentWorkflow()

    private suspend fun ingestSms(
        scenario: GoldenScenario,
        rawIdToMessageId: MutableMap<String, String>,
    ): String? {
        val rows = providerRows(scenario.messages)
        rows.forEachIndexed { index, row ->
            rawIdToMessageId[AndroidSmsMapper.toRawSms(row).id] = scenario.messages[index].id
        }
        val result = world.importProviderBatch(rows, enrichment())
        return when (result) {
            is HistoricalBatchDerivedResult.Succeeded -> null
            is HistoricalBatchDerivedResult.Incomplete -> "ingestion incomplete at ${result.stage}"
        }
    }

    private suspend fun ingestFacts(
        scenario: GoldenScenario,
        rawIdToMessageId: MutableMap<String, String>,
    ): String? {
        scenario.facts.forEach { fact -> seedFact(fact, rawIdToMessageId) }
        return null
    }

    private suspend fun seedFact(
        fact: GoldenPersistedFact,
        rawIdToMessageId: MutableMap<String, String>,
    ) {
        val rawId = "golden-sms:${fact.messageId}"
        rawIdToMessageId[rawId] = fact.messageId
        val bank = Bank.fromId(fact.bank)
        val occurredAt = Instant.parse(fact.occurredAt)
        world.rawRepo.insertIfAbsent(
            RawSms(
                id = rawId,
                sender = fact.sender,
                body = fact.body,
                receivedAt = Instant.parse(fact.receivedAt),
                deviceMessageId = fact.messageId,
                bodyHash = "golden-${fact.messageId}",
            ),
        )
        val eventId = "evt-$rawId"
        world.parsedRepo.save(
            ParsedEvent(
                id = eventId,
                rawSmsId = rawId,
                bank = bank,
                messageFamily = MessageFamily.valueOf(fact.messageFamily),
                direction = MoneyDirection.valueOf(fact.direction),
                amount = Money.of(fact.amount, Currency.valueOf(fact.currency)),
                purchaseChannel = null,
                sourceAccountRef = accountRef(fact.sourceKind, fact.sourceBank, fact.sourceRef),
                destinationAccountRef = accountRef(fact.destinationKind, fact.destinationBank, fact.destinationRef),
                cardRef = fact.cardLast4?.let { CardReference(bank, it) },
                merchant = fact.merchant,
                counterparty = null,
                occurredAt = occurredAt,
                bankNetworkType = null,
                confidence = Confidence(0.9),
                parseStatus = ParseStatus.SUCCESS,
            ),
            ParsedEventDetails(
                cardSmsChannel = fact.cardSmsChannel?.let(CardSmsChannel::valueOf),
                occurredAtLocal = occurredAt.atZone(world.zone).toLocalDateTime(),
            ),
        )
        val transaction = FinancialTransaction(
            id = TransactionIdFactory.fromRawSmsIds(listOf(rawId)),
            type = FinancialTransactionType.valueOf(fact.transactionType),
            amount = Money.of(fact.amount, Currency.valueOf(fact.currency)),
            occurredAt = occurredAt,
            sourceContainerId = containerId(fact.sourceKind, fact.sourceBank, fact.sourceRef),
            destinationContainerId = containerId(fact.destinationKind, fact.destinationBank, fact.destinationRef),
            merchant = fact.merchant,
            counterparty = null,
            categoryId = null,
            linkedParsedEventIds = listOf(eventId),
        )
        world.ftRepo.save(transaction, listOf(rawId))
    }

    private suspend fun observe(
        scenario: GoldenScenario,
        rawIdToMessageId: Map<String, String>,
        ingestionError: String?,
        reimportStable: Boolean,
    ): GoldenObservation {
        val messageByEventId = messageIdsByEventId(rawIdToMessageId)
        val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse(scenario.periodAnchor))
        val projection = world.dashboardService().loadProjection(period)
        val persisted = world.ftRepo.listAll().map { observeTransaction(it, messageByEventId) }
        val displayed = projection.transactions.map { tx ->
            val observed = observeTransaction(tx, messageByEventId)
            val sar = projection.transactionFacts[tx.id]?.sarEquivalent
            observed.copy(
                appliedRate = tx.appliedExchangeRate?.stripTrailingZeros()?.toPlainString(),
                rateSource = tx.exchangeRateSource?.name,
                sarEquivalent = sar?.amount?.stripTrailingZeros()?.toPlainString(),
            )
        }
        val accounts = projection.perAccount.associate { account ->
            val key = "${account.bank.id}:${account.maskedNumber}"
            val flow = account.summary.accountFlow()
            key to GoldenObservedAccount(
                selfTransfersIn = flow.internalTransfersIn.amount.toPlainString(),
                selfTransfersOut = flow.internalTransfersOut.amount.toPlainString(),
                externalTransfersIn = flow.externalTransfersIn.amount.toPlainString(),
                externalTransfersOut = flow.externalTransfersOut.amount.toPlainString(),
                posPurchases = flow.posPurchases.amount.toPlainString(),
                salary = flow.salary.amount.toPlainString(),
                otherIncome = flow.otherIncome.amount.toPlainString(),
                cashPosition = flow.accountSummary().remaining.amount.toPlainString(),
            )
        }
        val cards = projection.creditFacilities.facilities.map { facility ->
            GoldenObservedCard(
                bank = facility.bank.id,
                last4 = facility.primary.last4,
                salaryPeriodSpendingNet = facility.primary.salaryPeriodSpendingNet.amount.toPlainString(),
            )
        }
        return GoldenObservation(
            ingestionError = ingestionError,
            rawSmsCount = world.rawRepo.listIdsByReceivedAt().size,
            persisted = persisted,
            displayed = displayed,
            accounts = accounts,
            cards = cards,
            fleetTotalInflow = projection.accountsFleet.totalInflow?.amount?.toPlainString(),
            fleetTotalOutflow = projection.accountsFleet.totalOutflow?.amount?.toPlainString(),
            reimportStable = reimportStable,
        )
    }

    private suspend fun messageIdsByEventId(fallback: Map<String, String>): Map<String, String> {
        val resolved = mutableMapOf<String, String>()
        world.parsedRepo.listAll().forEach { record ->
            val raw = world.rawRepo.getById(record.event.rawSmsId)
            val messageId = raw?.deviceMessageId
                ?: fallback[record.event.rawSmsId]
                ?: return@forEach
            resolved[record.event.id] = messageId
        }
        return resolved
    }

    private fun observeTransaction(
        transaction: FinancialTransaction,
        messageByEventId: Map<String, String>,
    ): GoldenObservedTransaction {
        val source = parseContainer(transaction.sourceContainerId)
        val destination = parseContainer(transaction.destinationContainerId)
        val evidence = transaction.linkedParsedEventIds.mapNotNull { eventId ->
            messageByEventId[eventId]
        }.sorted()
        return GoldenObservedTransaction(
            type = transaction.type.name,
            amount = transaction.amount.amount.toPlainString(),
            currency = transaction.amount.currency.name,
            direction = directionOf(transaction.type),
            evidenceMessageIds = evidence,
            sourceKind = source?.kind,
            sourceBank = source?.bank,
            sourceRef = source?.ref,
            destinationKind = destination?.kind,
            destinationBank = destination?.bank,
            destinationRef = destination?.ref,
        )
    }

    private suspend fun ledgerFingerprint(): List<String> =
        world.ftRepo.listAll()
            .sortedBy { it.id }
            .map { tx ->
                val rate = tx.appliedExchangeRate?.stripTrailingZeros()?.toPlainString() ?: "-"
                val source = tx.exchangeRateSource?.name ?: "-"
                listOf(
                    tx.id,
                    tx.type.name,
                    tx.amount.amount.toPlainString(),
                    tx.amount.currency.name,
                    tx.sourceContainerId ?: "-",
                    tx.destinationContainerId ?: "-",
                    tx.linkedParsedEventIds.sorted().joinToString("+"),
                    rate,
                    source,
                ).joinToString("|")
            }

    private suspend fun applyOwnership(ownership: GoldenOwnership) {
        ownership.accounts.forEach { account ->
            world.accounts.setOwnership(
                AccountReference(Bank.fromId(account.bank), account.masked),
                OwnershipStatus.OWNED,
            )
        }
        ownership.cards.forEach { card ->
            val reference = CardReference(Bank.fromId(card.bank), card.last4)
            world.cards.setOwnership(reference, OwnershipStatus.OWNED)
            card.type?.let { world.cards.updateCardType(reference, CardType.valueOf(it)) }
        }
    }

    private fun providerRows(messages: List<GoldenMessage>): List<ProviderSmsRecord> =
        messages.map { message ->
            ProviderSmsRecord(
                providerMessageId = if (message.live) null else message.id,
                sender = message.sender,
                body = message.body,
                receivedAt = Instant.parse(message.receivedAt),
            )
        }

    private fun accountRef(kind: String?, bank: String?, ref: String?): AccountReference? {
        if (kind != "ACCOUNT" || bank == null || ref == null) return null
        return AccountReference(Bank.fromId(bank), ref)
    }

    private fun containerId(kind: String?, bank: String?, ref: String?): String? {
        if (kind == null || bank == null || ref == null) return null
        val parsedBank = Bank.fromId(bank)
        return when (kind) {
            "ACCOUNT" -> FinancialContainerIdFactory.accountId(parsedBank, ref)
            "CARD" -> FinancialContainerIdFactory.cardId(parsedBank, ref)
            else -> null
        }
    }

    private fun parseContainer(id: String?): ContainerParts? {
        if (id.isNullOrBlank()) return null
        val parts = id.split(":")
        if (parts.size < 3) return null
        return ContainerParts(
            kind = parts[0].uppercase(),
            bank = parts[1],
            ref = parts.drop(2).joinToString(":"),
        )
    }

    private fun directionOf(type: FinancialTransactionType): String = when (type) {
        FinancialTransactionType.SELF_TRANSFER,
        FinancialTransactionType.ADJUSTMENT,
        FinancialTransactionType.UNKNOWN,
        -> "NEUTRAL"
        FinancialTransactionType.EXTERNAL_TRANSFER_IN,
        FinancialTransactionType.INCOME,
        FinancialTransactionType.REFUND,
        -> "INCOMING"
        else -> "OUTGOING"
    }

    private data class ContainerParts(
        val kind: String,
        val bank: String,
        val ref: String,
    )
}

data class GoldenObservation(
    val ingestionError: String?,
    val rawSmsCount: Int,
    val persisted: List<GoldenObservedTransaction>,
    val displayed: List<GoldenObservedTransaction>,
    val accounts: Map<String, GoldenObservedAccount>,
    val cards: List<GoldenObservedCard>,
    val fleetTotalInflow: String?,
    val fleetTotalOutflow: String?,
    val reimportStable: Boolean,
)

data class GoldenObservedTransaction(
    val type: String,
    val amount: String,
    val currency: String,
    val direction: String,
    val evidenceMessageIds: List<String>,
    val sourceKind: String? = null,
    val sourceBank: String? = null,
    val sourceRef: String? = null,
    val destinationKind: String? = null,
    val destinationBank: String? = null,
    val destinationRef: String? = null,
    val appliedRate: String? = null,
    val rateSource: String? = null,
    val sarEquivalent: String? = null,
)

data class GoldenObservedCard(
    val bank: String,
    val last4: String,
    val salaryPeriodSpendingNet: String,
)

data class GoldenObservedAccount(
    val selfTransfersIn: String,
    val selfTransfersOut: String,
    val externalTransfersIn: String,
    val externalTransfersOut: String,
    val posPurchases: String,
    val salary: String,
    val otherIncome: String,
    val cashPosition: String,
)
