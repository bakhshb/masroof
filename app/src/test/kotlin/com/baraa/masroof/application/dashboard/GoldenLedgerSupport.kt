package com.baraa.masroof.application.dashboard

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
import com.baraa.masroof.domain.model.PurchaseChannel
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.application.sms.HistoricalBatchDerivedResult
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import com.baraa.masroof.testsupport.LedgerImportOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Independent expected numbers for the golden ledger. Pending scenarios keep a named
 * owner milestone and a pinned observation of the current defect. They are not JUnit
 * assumptions: when the ledger matches the oracle, the owner must turn the scenario ACTIVE.
 */
@Serializable
data class GoldenLedgerScenario(
    val id: String,
    val status: String,
    val ownerMilestone: String? = null,
    val periodAnchor: String,
    val description: String,
    val ownedAccounts: List<GoldenOwnedAccount> = emptyList(),
    val ownedCards: List<GoldenOwnedCard> = emptyList(),
    val messages: List<GoldenMessage> = emptyList(),
    val seedRows: List<GoldenSeedRow> = emptyList(),
    val expected: GoldenExpected,
    val knownDefect: GoldenKnownDefect? = null,
)

@Serializable
data class GoldenOwnedAccount(val bank: String, val masked: String)

@Serializable
data class GoldenOwnedCard(val bank: String, val last4: String, val type: String)

@Serializable
data class GoldenMessage(
    val providerMessageId: String? = null,
    val sender: String,
    val body: String,
    val receivedAt: String,
)

@Serializable
data class GoldenSeedRow(
    val providerMessageId: String,
    val sender: String,
    val body: String,
    val receivedAt: String,
    val bank: String,
    val family: String,
    val parseStatus: String,
    val direction: String,
    val amount: String,
    val currency: String,
    val sourceAccount: String? = null,
    val destinationAccount: String? = null,
    val cardLast4: String? = null,
    val merchant: String? = null,
    val transactionType: String,
    val occurredAt: String,
)

@Serializable
data class GoldenExpected(
    val rawSmsCount: Int,
    val parsed: List<GoldenParsedFact>,
    val transactions: List<GoldenMovementFact>,
    val displayedRows: List<GoldenMovementFact>,
    val accountsFleetInflow: String? = null,
    val accountsFleetOutflow: String? = null,
    val accountsFleetRemaining: String? = null,
    val spendingGross: String? = null,
    val excludedOtherCurrencyCount: Int? = null,
    val accounts: List<GoldenAccountTotals> = emptyList(),
)

@Serializable
data class GoldenParsedFact(
    val family: String,
    val parseStatus: String,
    val amount: String? = null,
    val currency: String? = null,
    val bank: String,
    val sourceAccount: String? = null,
    val destinationAccount: String? = null,
    val cardLast4: String? = null,
)

@Serializable
data class GoldenMovementFact(
    val type: String,
    val amount: String,
    val currency: String,
    val source: String? = null,
    val destination: String? = null,
    val linkedRawSmsCount: Int,
)

@Serializable
data class GoldenAccountTotals(
    val bank: String,
    val masked: String,
    val selfTransfersIn: String = "0.00",
    val selfTransfersOut: String = "0.00",
    val posPurchases: String = "0.00",
    val externalTransfersOut: String = "0.00",
    val externalTransfersIn: String = "0.00",
    val salary: String = "0.00",
    val otherIncome: String = "0.00",
    val cashPosition: String,
)

@Serializable
data class GoldenKnownDefect(
    val ownerMilestone: String,
    val signals: Map<String, String>,
)

data class GoldenLedgerSnapshot(
    val rawSmsCount: Int,
    val bodyHashes: List<String>,
    val parsed: List<String>,
    val transactions: List<String>,
    val displayedRows: List<String>,
    val accountsFleetInflow: String?,
    val accountsFleetOutflow: String?,
    val accountsFleetRemaining: String?,
    val spendingGross: String,
    val excludedOtherCurrencyCount: Int,
    val accounts: List<String>,
    val metrics: Map<String, String>,
) {
    fun fingerprint(): String = buildString {
        appendLine("raw=$rawSmsCount")
        bodyHashes.sorted().forEach { appendLine("body=$it") }
        appendLine(canonical())
    }

    fun canonical(): String = buildString {
        appendLine("parsed=${parsed.sorted().joinToString(",")}")
        appendLine("transactions=${transactions.sorted().joinToString(",")}")
        appendLine("displayed=${displayedRows.sorted().joinToString(",")}")
        appendLine("fleetIn=$accountsFleetInflow")
        appendLine("fleetOut=$accountsFleetOutflow")
        appendLine("fleetRemaining=$accountsFleetRemaining")
        appendLine("spendingGross=$spendingGross")
        appendLine("excluded=$excludedOtherCurrencyCount")
        accounts.sorted().forEach { appendLine("account=$it") }
    }

    fun matches(expected: GoldenExpected): Boolean {
        if (rawSmsCount != expected.rawSmsCount) return false
        if (parsed.sorted() != expected.parsed.map(::parsedLine).sorted()) return false
        if (transactions.sorted() != expected.transactions.map(::movementLine).sorted()) return false
        if (displayedRows.sorted() != expected.displayedRows.map(::movementLine).sorted()) return false
        if (expected.accountsFleetInflow != null && accountsFleetInflow != expected.accountsFleetInflow) return false
        if (expected.accountsFleetOutflow != null && accountsFleetOutflow != expected.accountsFleetOutflow) return false
        if (expected.accountsFleetRemaining != null &&
            accountsFleetRemaining != expected.accountsFleetRemaining
        ) {
            return false
        }
        if (expected.spendingGross != null && spendingGross != expected.spendingGross) return false
        if (expected.excludedOtherCurrencyCount != null &&
            excludedOtherCurrencyCount != expected.excludedOtherCurrencyCount
        ) {
            return false
        }
        if (accounts.sorted() != expected.accounts.map(::accountLine).sorted()) return false
        return true
    }
}

object GoldenLedgerCorpus {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun loadAll(): List<GoldenLedgerScenario> {
        val root = resolveRoot()
        val files = root.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedBy { it.name }
            .orEmpty()
        require(files.isNotEmpty()) { "No golden ledger fixtures under $root" }
        return files.map { file ->
            json.decodeFromString(GoldenLedgerScenario.serializer(), file.readText())
        }
    }

    fun load(id: String): GoldenLedgerScenario =
        loadAll().singleOrNull { it.id == id } ?: error("Missing golden ledger scenario $id")

    private fun resolveRoot(): File {
        val candidates = listOf(
            File("src/test/resources/testdata/golden_ledger"),
            File("app/src/test/resources/testdata/golden_ledger"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate testdata/golden_ledger")
    }
}

object GoldenLedgerRunner {
    private val zone: ZoneId = ZoneId.of("Asia/Riyadh")
    private val accountMetricPattern = Regex("""account:([^:]+):([^:]+):([A-Za-z]+)""")

    suspend fun prepare(world: DashboardLedgerWorld, scenario: GoldenLedgerScenario) {
        for (account in scenario.ownedAccounts) {
            world.accounts.setOwnership(
                AccountReference(Bank.fromId(account.bank), account.masked),
                OwnershipStatus.OWNED,
            )
        }
        for (card in scenario.ownedCards) {
            val reference = CardReference(Bank.fromId(card.bank), card.last4)
            world.cards.setOwnership(reference, OwnershipStatus.OWNED)
            world.cards.updateCardType(reference, CardType.valueOf(card.type))
        }
        if (scenario.messages.isNotEmpty()) {
            requireSuccessfulImport(
                id = scenario.id,
                imported = world.importProviderRows(providerRows(scenario)),
                label = "import",
            )
        }
        for (row in scenario.seedRows) {
            seed(world, row)
        }
    }

    suspend fun read(world: DashboardLedgerWorld, scenario: GoldenLedgerScenario): GoldenLedgerSnapshot {
        val period = FinancialPeriodPolicy.periodContaining(LocalDate.parse(scenario.periodAnchor))
        val projection = world.dashboardService().loadProjection(period)
        val rawIds = world.rawRepo.listIdsByReceivedAt()
        val rawRows = rawIds.mapNotNull { world.rawRepo.getById(it) }
        val parsed = world.parsedRepo.listAll().map { record ->
            parsedLine(
                GoldenParsedFact(
                    family = record.event.messageFamily.name,
                    parseStatus = record.event.parseStatus.name,
                    amount = record.event.amount?.let(::moneyText),
                    currency = record.event.amount?.currency?.name,
                    bank = record.event.bank.id,
                    sourceAccount = record.event.sourceAccountRef?.maskedNumber,
                    destinationAccount = record.event.destinationAccountRef?.maskedNumber,
                    cardLast4 = record.event.cardRef?.last4,
                ),
            )
        }
        val persisted = world.ftRepo.listAll()
        val linkCounts = persisted.associate { tx -> tx.id to world.ftRepo.listRawSmsIds(tx.id).size }
        val transactions = persisted.map { tx -> movementLine(movementFact(tx, linkCounts[tx.id] ?: 0)) }
        val displayed = projection.transactions.map { tx ->
            movementLine(movementFact(tx, linkCounts[tx.id] ?: tx.linkedParsedEventIds.size))
        }
        val accountLines = projection.perAccount.map { summary ->
            accountLine(
                GoldenAccountTotals(
                    bank = summary.bank.id,
                    masked = summary.maskedNumber,
                    selfTransfersIn = moneyText(summary.summary.inflow.selfTransfersIn),
                    selfTransfersOut = moneyText(summary.summary.outflow.selfTransfersOut),
                    posPurchases = moneyText(summary.summary.outflow.posPurchases),
                    externalTransfersOut = moneyText(summary.summary.outflow.externalTransfersOut),
                    externalTransfersIn = moneyText(summary.summary.inflow.externalTransfersIn),
                    salary = moneyText(summary.summary.inflow.salary),
                    otherIncome = moneyText(summary.summary.inflow.otherIncome),
                    cashPosition = signedText(summary.summary.accountFlow().accountSummary().remaining),
                ),
            )
        }
        val fleet = projection.accountsFleet
        val metrics = linkedMapOf(
            "rawSmsCount" to rawRows.size.toString(),
            "parsedCount" to parsed.size.toString(),
            "transactionCount" to transactions.size.toString(),
            "displayedRowCount" to displayed.size.toString(),
            "accountsFleetInflow" to (fleet.totalInflow?.let(::moneyText) ?: ""),
            "accountsFleetOutflow" to (fleet.totalOutflow?.let(::moneyText) ?: ""),
            "accountsFleetRemaining" to (fleet.totalRemaining?.let(::signedText) ?: ""),
            "spendingGross" to moneyText(projection.summary.spendingGross),
            "excludedOtherCurrencyCount" to projection.summary.excludedOtherCurrencyCount.toString(),
        )
        projection.perAccount.forEach { summary ->
            val prefix = "account:${summary.bank.id}:${summary.maskedNumber}"
            metrics["$prefix:selfTransfersIn"] = moneyText(summary.summary.inflow.selfTransfersIn)
            metrics["$prefix:selfTransfersOut"] = moneyText(summary.summary.outflow.selfTransfersOut)
            metrics["$prefix:posPurchases"] = moneyText(summary.summary.outflow.posPurchases)
            metrics["$prefix:externalTransfersOut"] = moneyText(summary.summary.outflow.externalTransfersOut)
            metrics["$prefix:externalTransfersIn"] = moneyText(summary.summary.inflow.externalTransfersIn)
            metrics["$prefix:salary"] = moneyText(summary.summary.inflow.salary)
            metrics["$prefix:otherIncome"] = moneyText(summary.summary.inflow.otherIncome)
            metrics["$prefix:cashPosition"] = signedText(summary.summary.accountFlow().accountSummary().remaining)
        }
        return GoldenLedgerSnapshot(
            rawSmsCount = rawRows.size,
            bodyHashes = rawRows.map { it.bodyHash },
            parsed = parsed,
            transactions = transactions,
            displayedRows = displayed,
            accountsFleetInflow = fleet.totalInflow?.let(::moneyText),
            accountsFleetOutflow = fleet.totalOutflow?.let(::moneyText),
            accountsFleetRemaining = fleet.totalRemaining?.let(::signedText),
            spendingGross = moneyText(projection.summary.spendingGross),
            excludedOtherCurrencyCount = projection.summary.excludedOtherCurrencyCount,
            accounts = accountLines,
            metrics = metrics,
        )
    }

    fun providerRows(scenario: GoldenLedgerScenario): List<ProviderSmsRecord> =
        scenario.messages.map(::toProviderRow)

    fun requireSuccessfulImport(
        id: String,
        imported: LedgerImportOutcome,
        label: String,
    ) {
        require(imported.ingest.isNotEmpty()) {
            "$id $label produced no ingest outcomes"
        }
        requireCompletedIngestion(id, imported.ingest, label)
        require(imported.derived is HistoricalBatchDerivedResult.Succeeded) {
            "$id $label did not finish derived work: ${imported.derived}"
        }
    }

    fun requireSuccessfulReprocess(
        id: String,
        results: List<SmsIngestionResult>,
    ) {
        require(results.isNotEmpty()) {
            "$id reprocessing produced no outcomes"
        }
        requireCompletedIngestion(id, results, "reprocessing")
    }

    private fun requireCompletedIngestion(
        id: String,
        results: List<SmsIngestionResult>,
        label: String,
    ) {
        results.forEachIndexed { index, result ->
            when (result) {
                is SmsIngestionResult.Failed ->
                    error("$id $label[$index] failed: ${result.message}")
                is SmsIngestionResult.DerivedIncomplete ->
                    error("$id $label[$index] derived incomplete at ${result.stage}")
                else -> Unit
            }
        }
    }

    fun expectedMetric(expected: GoldenExpected, key: String): String? = when (key) {
        "rawSmsCount" -> expected.rawSmsCount.toString()
        "parsedCount" -> expected.parsed.size.toString()
        "transactionCount" -> expected.transactions.size.toString()
        "displayedRowCount" -> expected.displayedRows.size.toString()
        "accountsFleetInflow" -> expected.accountsFleetInflow
        "accountsFleetOutflow" -> expected.accountsFleetOutflow
        "accountsFleetRemaining" -> expected.accountsFleetRemaining
        "spendingGross" -> expected.spendingGross
        "excludedOtherCurrencyCount" -> expected.excludedOtherCurrencyCount?.toString()
        else -> accountMetric(expected, key)
    }

    private fun accountMetric(expected: GoldenExpected, key: String): String? {
        val match = accountMetricPattern.matchEntire(key) ?: return null
        val account = expected.accounts.singleOrNull {
            it.bank == match.groupValues[1] && it.masked == match.groupValues[2]
        } ?: return null
        return when (match.groupValues[3]) {
            "selfTransfersIn" -> account.selfTransfersIn
            "selfTransfersOut" -> account.selfTransfersOut
            "posPurchases" -> account.posPurchases
            "externalTransfersOut" -> account.externalTransfersOut
            "externalTransfersIn" -> account.externalTransfersIn
            "salary" -> account.salary
            "otherIncome" -> account.otherIncome
            "cashPosition" -> account.cashPosition
            else -> null
        }
    }

    private suspend fun seed(world: DashboardLedgerWorld, row: GoldenSeedRow) {
        val raw = AndroidSmsMapper.toRawSms(toProviderRow(row))
        world.rawRepo.insertIfAbsent(raw)
        val bank = Bank.fromId(row.bank)
        val occurredAt = Instant.parse(row.occurredAt)
        val local = LocalDateTime.ofInstant(occurredAt, zone)
        val eventId = "evt-${raw.id}"
        world.parsedRepo.save(
            ParsedEvent(
                id = eventId,
                rawSmsId = raw.id,
                bank = bank,
                messageFamily = MessageFamily.valueOf(row.family),
                direction = MoneyDirection.valueOf(row.direction),
                amount = Money.of(row.amount, Currency.valueOf(row.currency)),
                purchaseChannel = if (row.family == MessageFamily.PURCHASE.name) PurchaseChannel.POS else null,
                sourceAccountRef = row.sourceAccount?.let { AccountReference(bank, it) },
                destinationAccountRef = row.destinationAccount?.let { AccountReference(bank, it) },
                cardRef = row.cardLast4?.let { CardReference(bank, it) },
                merchant = row.merchant,
                counterparty = null,
                occurredAt = occurredAt,
                bankNetworkType = null,
                confidence = Confidence(0.95, listOf("golden_seed")),
                parseStatus = ParseStatus.valueOf(row.parseStatus),
            ),
            ParsedEventDetails(occurredAtLocal = local),
        )
        val source = row.sourceAccount?.let { FinancialContainerIdFactory.accountId(bank, it) }
            ?: row.cardLast4?.let { FinancialContainerIdFactory.cardId(bank, it) }
        val destination = row.destinationAccount?.let { FinancialContainerIdFactory.accountId(bank, it) }
        world.ftRepo.save(
            FinancialTransaction(
                id = TransactionIdFactory.fromRawSmsIds(listOf(raw.id)),
                type = FinancialTransactionType.valueOf(row.transactionType),
                amount = Money.of(row.amount, Currency.valueOf(row.currency)),
                occurredAt = occurredAt,
                sourceContainerId = source,
                destinationContainerId = destination,
                merchant = row.merchant,
                counterparty = null,
                categoryId = null,
                linkedParsedEventIds = listOf(eventId),
                occurredAtZone = zone.id,
            ),
            listOf(raw.id),
        )
    }

    private fun toProviderRow(message: GoldenMessage): ProviderSmsRecord =
        ProviderSmsRecord(
            providerMessageId = message.providerMessageId,
            sender = message.sender,
            body = message.body,
            receivedAt = Instant.parse(message.receivedAt),
        )

    private fun toProviderRow(row: GoldenSeedRow): ProviderSmsRecord =
        ProviderSmsRecord(
            providerMessageId = row.providerMessageId,
            sender = row.sender,
            body = row.body,
            receivedAt = Instant.parse(row.receivedAt),
        )

    private fun movementFact(tx: FinancialTransaction, linkedRawSmsCount: Int): GoldenMovementFact =
        GoldenMovementFact(
            type = tx.type.name,
            amount = moneyText(tx.amount),
            currency = tx.amount.currency.name,
            source = tx.sourceContainerId,
            destination = tx.destinationContainerId,
            linkedRawSmsCount = linkedRawSmsCount,
        )

}

internal fun parsedLine(fact: GoldenParsedFact): String =
    listOf(
        fact.family,
        fact.parseStatus,
        fact.amount ?: "-",
        fact.currency ?: "-",
        fact.bank,
        fact.sourceAccount ?: "-",
        fact.destinationAccount ?: "-",
        fact.cardLast4 ?: "-",
    ).joinToString("|")

internal fun movementLine(fact: GoldenMovementFact): String =
    listOf(
        fact.type,
        fact.amount,
        fact.currency,
        fact.source ?: "-",
        fact.destination ?: "-",
        fact.linkedRawSmsCount.toString(),
    ).joinToString("|")

internal fun accountLine(account: GoldenAccountTotals): String =
    listOf(
        account.bank,
        account.masked,
        account.selfTransfersIn,
        account.selfTransfersOut,
        account.posPurchases,
        account.externalTransfersOut,
        account.externalTransfersIn,
        account.salary,
        account.otherIncome,
        account.cashPosition,
    ).joinToString("|")

private fun moneyText(money: Money): String =
    money.amount.setScale(Money.SCALE, RoundingMode.HALF_EVEN).toPlainString()

private fun signedText(amount: SignedMoneyAmount): String =
    amount.amount.setScale(Money.SCALE, RoundingMode.HALF_EVEN).toPlainString()
