package com.baraa.masroof.testsupport

import com.baraa.masroof.domain.model.MessageFamily
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Hand-reviewed financial oracle for one anonymized scenario.
 *
 * Expected amounts are written here. They are not produced by running the
 * function under test. [pendingFix] names a later milestone whose known defect
 * still disagrees with this oracle; [pendingAssertions] are the only checks
 * allowed to miss until that milestone lands.
 */
@Serializable
data class GoldenScenario(
    val id: String,
    val evidence: String,
    val pendingFix: String? = null,
    val pendingAssertions: List<String> = emptyList(),
    val periodAnchor: String,
    val mode: String = MODE_SMS_REPLAY,
    val reimport: Boolean = false,
    val restart: Boolean = false,
    val ownership: GoldenOwnership = GoldenOwnership(),
    val messages: List<GoldenMessage> = emptyList(),
    val facts: List<GoldenPersistedFact> = emptyList(),
    val expected: GoldenExpected,
) {
    companion object {
        const val MODE_SMS_REPLAY: String = "SMS_REPLAY"
        const val MODE_PERSISTED_FACTS: String = "PERSISTED_FACTS"
    }
}

@Serializable
data class GoldenOwnership(
    val accounts: List<GoldenOwnedAccount> = emptyList(),
    val cards: List<GoldenOwnedCard> = emptyList(),
)

@Serializable
data class GoldenOwnedAccount(
    val bank: String,
    val masked: String,
)

@Serializable
data class GoldenOwnedCard(
    val bank: String,
    val last4: String,
    val type: String? = null,
)

@Serializable
data class GoldenMessage(
    val id: String,
    val sender: String,
    val body: String,
    val receivedAt: String,
    /** Message family the SMS is intended to be, independent of the parser result. */
    val intendedFamily: String,
    val live: Boolean = false,
)

/**
 * Already-persisted facts for a bank the AlJazira parser cannot produce.
 * The body is retained as evidence; it is not parsed.
 */
@Serializable
data class GoldenPersistedFact(
    val messageId: String,
    val sender: String,
    val body: String,
    val receivedAt: String,
    val bank: String,
    val messageFamily: String,
    val direction: String,
    val amount: String,
    val currency: String,
    val transactionType: String,
    val occurredAt: String,
    val sourceKind: String? = null,
    val sourceBank: String? = null,
    val sourceRef: String? = null,
    val destinationKind: String? = null,
    val destinationBank: String? = null,
    val destinationRef: String? = null,
    val cardLast4: String? = null,
    val cardSmsChannel: String? = null,
    val merchant: String? = null,
)

@Serializable
data class GoldenExpected(
    val rawSmsCount: Int? = null,
    val persistedTransactions: List<GoldenTransactionExpectation>? = null,
    val displayedTransactions: List<GoldenTransactionExpectation>? = null,
    val accounts: List<GoldenAccountExpectation> = emptyList(),
    val cards: List<GoldenCardExpectation> = emptyList(),
    val fleetTotalInflow: String? = null,
    val fleetTotalOutflow: String? = null,
    val rates: List<GoldenRateExpectation> = emptyList(),
    val reimportStable: Boolean? = null,
)

@Serializable
data class GoldenTransactionExpectation(
    val type: String,
    val amount: String,
    val currency: String,
    val direction: String,
    val evidenceMessageIds: List<String> = emptyList(),
    val sourceKind: String? = null,
    val sourceBank: String? = null,
    val sourceRef: String? = null,
    val destinationKind: String? = null,
    val destinationBank: String? = null,
    val destinationRef: String? = null,
)

@Serializable
data class GoldenAccountExpectation(
    val bank: String,
    val masked: String,
    val selfTransfersIn: String? = null,
    val selfTransfersOut: String? = null,
    val externalTransfersIn: String? = null,
    val externalTransfersOut: String? = null,
    val posPurchases: String? = null,
    val salary: String? = null,
    val otherIncome: String? = null,
    val cashPosition: String? = null,
)

@Serializable
data class GoldenCardExpectation(
    val bank: String,
    val last4: String,
    val salaryPeriodSpendingNet: String,
)

@Serializable
data class GoldenRateExpectation(
    val messageId: String,
    /** Decimal rate. Ignored when [absent] is true. */
    val rate: String? = null,
    val source: String? = null,
    val sarEquivalent: String? = null,
    val absent: Boolean = false,
)

object GoldenLedgerFixtureLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun loadAll(): List<GoldenScenario> {
        val root = resolveRoot()
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.path }
            .toList()
        require(files.isNotEmpty()) { "No golden ledger fixtures under $root" }
        val scenarios = files.map { file ->
            json.decodeFromString(GoldenScenario.serializer(), file.readText())
        }
        val duplicateIds = scenarios.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate golden scenario ids: $duplicateIds" }
        scenarios.forEach { scenario ->
            require(scenario.evidence.isNotBlank()) { "${scenario.id} needs an evidence note" }
            require(scenario.pendingFix == null || scenario.pendingAssertions.isNotEmpty()) {
                "${scenario.id} marks ${scenario.pendingFix} but lists no pending assertions"
            }
            when (scenario.mode) {
                GoldenScenario.MODE_SMS_REPLAY -> {
                    require(scenario.messages.isNotEmpty()) { "${scenario.id} has no SMS messages" }
                    scenario.messages.forEach { message ->
                        require(message.intendedFamily.isNotBlank()) {
                            "${scenario.id} message ${message.id} needs an intended family"
                        }
                        val family = runCatching { MessageFamily.valueOf(message.intendedFamily) }.getOrNull()
                        require(family != null) {
                            "${scenario.id} message ${message.id} has unknown family ${message.intendedFamily}"
                        }
                    }
                }
                GoldenScenario.MODE_PERSISTED_FACTS ->
                    require(scenario.facts.isNotEmpty()) { "${scenario.id} has no persisted facts" }
                else -> error("${scenario.id} has unknown mode ${scenario.mode}")
            }
        }
        return scenarios
    }

    fun load(id: String): GoldenScenario =
        loadAll().first { it.id == id }

    fun resolveRoot(): File {
        val candidates = listOf(
            File("src/test/resources/testdata/golden_ledger"),
            File("app/src/test/resources/testdata/golden_ledger"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate testdata/golden_ledger")
    }
}
