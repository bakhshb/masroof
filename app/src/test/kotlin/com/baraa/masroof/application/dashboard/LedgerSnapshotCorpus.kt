package com.baraa.masroof.application.dashboard

import android.content.Context
import com.baraa.masroof.application.commitment.CommitmentCreationResult
import com.baraa.masroof.application.commitment.CommitmentFromTransactionService
import com.baraa.masroof.bank.BankSmsAdapter
import com.baraa.masroof.bank.MessagePatternSmsAdapter
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import com.baraa.masroof.testsupport.bundledPatterns
import com.baraa.masroof.testsupport.testdataFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant

/**
 * Synthetic corpus behind the full ledger snapshot (plan 0.2) and the version 1 fixtures:
 * every supported bank through the production pattern assets, plus today's "legacy" ledger states.
 */
internal object LedgerSnapshotCorpus {
    /** One adapter per bank pattern asset in `bank-patterns/` (the seven supported banks). */
    val BANK_IDS = listOf(
        "BANK_ALINMA", "BANK_ALJAZIRA", "BANK_BARQ", "BANK_EMIRATES_NBD", "BANK_SAB", "BANK_SNB", "BANK_STC",
    )

    fun allBankAdapters(): List<BankSmsAdapter> = BANK_IDS.map { id ->
        val config = bundledPatterns(id)
        MessagePatternSmsAdapter(config.banks.single(), PatternFactInterpreter(config))
    }

    fun newAllBanksWorld(context: Context, databaseName: String? = null): DashboardLedgerWorld {
        val adapters = allBankAdapters()
        return DashboardLedgerWorld(context, adapters.first(), adapters.drop(1), databaseName)
    }

    /** The fictional multi-bank samples, STC samples and AlJazira samples, in a fixed order and time. */
    fun allBankRows(): List<ProviderSmsRecord> {
        val multibank = Json.parseToJsonElement(testdataFile("multibank/messages.json").readText()).jsonArray
            .map { row -> Triple(row.jsonObject.text("id"), row.jsonObject.text("sender"), row.jsonObject.text("body")) }
        val stc = listOf(
            Triple("stc-in", "STC Bank", "Internal incoming transfer\nAmount:100SAR\nFrom:OTHER PERSON\nAcc:321\nAt:15/10/26 12:30"),
            Triple("stc-out", "STC Bank", "Internal outward transfer\nAmount:25SAR\nTo:SECOND PERSON\nAcc:876\nAt:16/10/26 12:30"),
            Triple("stc-top-up", "STC Bank", "Adding money to account\nAmount: 300.00 SAR\nVia: *6789\nAt: 2026-10-12 18:06"),
        )
        val aljazira = GoldenLedgerCorpus.load("one_intra_pair").messages
            .map { Triple(it.providerMessageId ?: error("golden row without id"), it.sender, it.body) }
        return (multibank + stc + aljazira).mapIndexed { index, (id, sender, body) ->
            ProviderSmsRecord(
                providerMessageId = "all-banks-$id",
                sender = sender,
                body = body,
                receivedAt = BASE.plusSeconds(index * 180L),
            )
        }
    }

    /** Imports [allBankRows] with a few owned instruments so some rows post and the rest stay in review. */
    suspend fun populateAllBanks(world: DashboardLedgerWorld) {
        listOf("3001" to Bank.BANK_ALJAZIRA, "3002" to Bank.BANK_ALJAZIRA, "1234" to Bank.fromId("BANK_SNB")).forEach { (masked, bank) ->
            world.accounts.setOwnership(AccountReference(bank, masked), OwnershipStatus.OWNED)
        }
        GoldenLedgerRunner.requireSuccessfulImport("all_banks", world.importProviderRows(allBankRows()), "import")
    }

    /**
     * Fills what the plain import leaves empty: a persisted FX pair (USD purchase enriched through
     * [com.baraa.masroof.application.transaction.ExchangeRateEnrichmentWorkflow]), a commitment created from a
     * posted expense by [CommitmentFromTransactionService], and two resolved review items.
     */
    suspend fun populateExtras(world: DashboardLedgerWorld) {
        GoldenLedgerRunner.seed(world, GoldenSeedRow(
            providerMessageId = "extras-usd-purchase", sender = "AlJazira", body = "synthetic usd purchase",
            receivedAt = "2026-09-05T10:00:00Z", bank = Bank.BANK_ALJAZIRA.id, family = "PURCHASE",
            parseStatus = "SUCCESS", direction = "OUTGOING", amount = "20.00", currency = "USD",
            cardLast4 = "7271", merchant = "TEST ONLINE", transactionType = "EXPENSE",
            occurredAt = "2026-09-05T10:00:00Z",
        ))
        val enriched = world.exchangeRateEnrichmentWorkflow(ForeignSarMarketRateProvider { _, _ -> BigDecimal("3.75") })
            .enrichPending()
        require(enriched.persisted > 0) { "no exchange rate pair was persisted" }
        val expense = requireNotNull(world.ftRepo.findByRawSmsId("android-sms:all-banks-snb-pos"))
        val created = CommitmentFromTransactionService(world.commitments, world.ftRepo, world.zone, world.clock)
            .createFromTransaction(expense.id)
        require(created == CommitmentCreationResult.Success) { "commitment not created: $created" }
        val resolvedAt = Instant.parse("2026-09-16T09:00:00Z")
        mapOf(
            "android-sms:all-banks-bank_snb-unknown" to (ReviewResolutionKind.USER_NON_FINANCIAL to null),
            "android-sms:all-banks-bank_sab-unknown" to (ReviewResolutionKind.USER_FINANCIAL_TYPE to
                world.ftRepo.findByRawSmsId("android-sms:all-banks-sab-pos")?.id),
        ).forEach { (rawId, resolution) ->
            val review = requireNotNull(world.reviewRepo.findByRawSmsId(rawId))
            requireNotNull(world.reviewRepo.markResolved(review.id, resolution.first, resolvedAt, resolution.second))
        }
    }

    /**
     * Legacy and not-yet-posted states as old installs hold them: a stale single-leg self-transfer whose
     * counterpart arrived later, FEE rows from financing installments (one healable, one not), and
     * unposted evidence (a self-transfer pair and an external transfer).
     */
    suspend fun populateLegacy(world: DashboardLedgerWorld) {
        listOf("9001", "9002", "9003").forEach {
            world.accounts.setOwnership(AccountReference(Bank.BANK_ALJAZIRA, it), OwnershipStatus.OWNED)
        }
        GoldenLedgerRunner.seed(world, row("legacy-stale-out", "TRANSFER_OUT", "OUTGOING", "4445.67", "2026-08-07T09:00:00Z",
            source = "9001", destination = "9002", type = "SELF_TRANSFER", network = "INTRA_BANK"))
        GoldenLedgerRunner.seed(world, row("legacy-stale-in", "TRANSFER_IN", "INCOMING", "4445.67", "2026-08-07T09:02:00Z",
            source = "9001", destination = "9002", type = "SELF_TRANSFER", network = "INTRA_BANK"), post = false)
        GoldenLedgerRunner.seed(world, row("legacy-fee-loan", "FINANCING_INSTALLMENT", "OUTGOING", "3036.11", "2026-08-27T01:10:00Z",
            source = "9003", type = "FEE", loanType = "PERSONAL"))
        GoldenLedgerRunner.seed(world, row("legacy-fee-no-loan", "FINANCING_INSTALLMENT", "OUTGOING", "120.00", "2026-08-28T01:10:00Z",
            source = "9003", type = "FEE"))
        GoldenLedgerRunner.seed(world, row("legacy-pair-out", "TRANSFER_OUT", "OUTGOING", "700.00", "2026-08-10T08:00:00Z",
            source = "9001", destination = "9003", type = "SELF_TRANSFER", network = "INTRA_BANK"), post = false)
        GoldenLedgerRunner.seed(world, row("legacy-pair-in", "TRANSFER_IN", "INCOMING", "700.00", "2026-08-10T08:00:30Z",
            source = "9001", destination = "9003", type = "SELF_TRANSFER", network = "INTRA_BANK"), post = false)
        GoldenLedgerRunner.seed(world, row("legacy-external-out", "TRANSFER_OUT", "OUTGOING", "80.00", "2026-08-11T08:00:00Z",
            source = "9001", destination = "9999", type = "EXTERNAL_TRANSFER_OUT", network = "INTRA_BANK"), post = false)
        world.loans.observe(LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL), "legacy-fee-loan")
    }

    private fun row(
        id: String,
        family: String,
        direction: String,
        amount: String,
        at: String,
        source: String? = null,
        destination: String? = null,
        type: String,
        loanType: String? = null,
        network: String? = null,
    ) = GoldenSeedRow(
        providerMessageId = id,
        sender = "AlJazira",
        body = "synthetic $id",
        receivedAt = at,
        bank = Bank.BANK_ALJAZIRA.id,
        family = family,
        parseStatus = "SUCCESS",
        direction = direction,
        amount = amount,
        currency = "SAR",
        sourceAccount = source,
        destinationAccount = destination,
        transactionType = type,
        occurredAt = at,
        loanType = loanType,
        bankNetworkType = network,
    )

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private val BASE: Instant = Instant.parse("2026-09-15T09:00:00Z")
}
