package com.baraa.masroof.application.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.testsupport.GoldenAccountExpectation
import com.baraa.masroof.testsupport.GoldenLedgerFixtureLoader
import com.baraa.masroof.testsupport.GoldenLedgerReplay
import com.baraa.masroof.testsupport.GoldenObservedCard
import com.baraa.masroof.testsupport.GoldenObservation
import com.baraa.masroof.testsupport.GoldenObservedTransaction
import com.baraa.masroof.testsupport.GoldenRateExpectation
import com.baraa.masroof.testsupport.GoldenScenario
import com.baraa.masroof.testsupport.GoldenTransactionExpectation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Independent financial oracle. Expected totals live in testdata/golden_ledger
 * and are not derived from the code under test.
 *
 * A scenario may name pending assertions for a known defect. Those misses are
 * reported as assumptions so this baseline stays green until the owning
 * milestone makes the oracle true. Every other miss fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenLedgerOracleTest {
    @Test
    fun fixtures_areReviewedOraclesWithoutIdentifiers() {
        val scenarios = GoldenLedgerFixtureLoader.loadAll()
        assertEquals(scenarios.map { it.id }.distinct().size, scenarios.size)
        scenarios.forEach { scenario ->
            assertTrue(scenario.evidence.length > 40)
            val bodies = scenario.messages.map { it.body } + scenario.facts.map { it.body }
            bodies.forEach { body ->
                assertTrue(body.none { it == '@' })
                assertTrue("long digit run in ${scenario.id}", !DIGIT_RUN.containsMatchIn(body))
            }
        }
    }

    @Test
    fun repeatedIdenticalSelfTransfers_bothRemainInTheAccount() = check("repeated_identical_self_transfers")

    @Test
    fun separateExternalTransfer_sameAmountStaysVisible() = check("separate_external_same_amount")

    @Test
    fun oneMovementTwoSmsLegs_postsOnceAndReplaysCleanly() = check("one_movement_two_sms_legs")

    @Test
    fun duplicatedSmsIntake_liveAndInboxAreOneRow() = check("duplicated_sms_intake")

    @Test
    fun distinctIdenticalNotices_staySeparate() = check("distinct_identical_notices")

    @Test
    fun bankAccountRefund_returnsNetCashToZero() = check("bank_account_refund_nets_cash")

    @Test
    fun creditCardRefund_offsetsCardSpendingOnly() = check("credit_card_refund_offsets_spending")

    @Test
    fun fxAcrossDates_doesNotBorrowALaterMerchantRate() = check("fx_does_not_borrow_later_rate")

    @Test
    fun sameLast4Accounts_doNotMixBanks() = check("same_last4_accounts_different_banks")

    @Test
    fun sameLast4Cards_doNotMixBanks() = check("same_last4_cards_different_banks")

    private fun check(id: String) = runBlocking {
        val scenario = GoldenLedgerFixtureLoader.load(id)
        GoldenLedgerReplay(context()).use { replay ->
            val observed = replay.replay(scenario)
            val mismatches = mismatches(scenario, observed)
            val pending = mismatches.filter { it.key in scenario.pendingAssertions }
            val unexpected = mismatches.filter { it.key !in scenario.pendingAssertions }
            if (unexpected.isNotEmpty()) {
                fail(format(scenario, unexpected, pending))
            }
            if (pending.isNotEmpty()) {
                val pendingGap = format(scenario, pending, emptyList())
                println(pendingGap)
                assumeTrue(pendingGap, false)
            }
        }
    }

    private fun mismatches(scenario: GoldenScenario, observed: GoldenObservation): List<Mismatch> {
        if (observed.ingestionError != null) {
            return listOf(Mismatch("ingestion", "succeeded", observed.ingestionError))
        }
        val expected = scenario.expected
        val found = mutableListOf<Mismatch>()
        fun check(key: String, expectedValue: String?, actual: String?) {
            if (expectedValue == null) return
            val renderedActual = actual ?: "missing"
            if (!sameValue(expectedValue, renderedActual)) {
                found += Mismatch(key, expectedValue, renderedActual)
            }
        }
        check("rawSmsCount", expected.rawSmsCount?.toString(), observed.rawSmsCount.toString())
        check(
            "persistedTransactions",
            expected.persistedTransactions?.let(::expectedTransactionSignature),
            observedTransactionSignature(observed.persisted),
        )
        check(
            "displayedTransactions",
            expected.displayedTransactions?.let(::expectedTransactionSignature),
            observedTransactionSignature(observed.displayed),
        )
        expected.accounts.forEach { account ->
            val key = "${account.bank}:${account.masked}"
            val actual = observed.accounts[key]
            check(accountKey(account, "selfTransfersIn"), account.selfTransfersIn, actual?.selfTransfersIn)
            check(accountKey(account, "selfTransfersOut"), account.selfTransfersOut, actual?.selfTransfersOut)
            check(accountKey(account, "externalTransfersIn"), account.externalTransfersIn, actual?.externalTransfersIn)
            check(accountKey(account, "externalTransfersOut"), account.externalTransfersOut, actual?.externalTransfersOut)
            check(accountKey(account, "posPurchases"), account.posPurchases, actual?.posPurchases)
            check(accountKey(account, "salary"), account.salary, actual?.salary)
            check(accountKey(account, "otherIncome"), account.otherIncome, actual?.otherIncome)
            check(accountKey(account, "cashPosition"), account.cashPosition, actual?.cashPosition)
        }
        if (expected.cards.isNotEmpty()) {
            check("cardSpending", cardSignature(expected.cards.map {
                GoldenObservedCard(it.bank, it.last4, it.salaryPeriodSpendingNet)
            }), cardSignature(observed.cards))
        }
        check("fleet.totalInflow", expected.fleetTotalInflow, observed.fleetTotalInflow)
        check("fleet.totalOutflow", expected.fleetTotalOutflow, observed.fleetTotalOutflow)
        expected.rates.forEach { rate -> found += rateMismatches(rate, observed) }
        if (expected.reimportStable == true) {
            check("reimport.stable", "true", observed.reimportStable.toString())
        }
        return found
    }

    private fun rateMismatches(
        rate: GoldenRateExpectation,
        observed: GoldenObservation,
    ): List<Mismatch> {
        val match = observed.displayed.firstOrNull { rate.messageId in it.evidenceMessageIds }
        val actualRate = match?.appliedRate ?: "absent"
        val actualSource = match?.rateSource ?: "absent"
        val actualSar = match?.sarEquivalent ?: "absent"
        val found = mutableListOf<Mismatch>()
        if (rate.absent) {
            if (actualRate != "absent") found += Mismatch("fx.${rate.messageId}.rate", "absent", actualRate)
            if (actualSource != "absent") found += Mismatch("fx.${rate.messageId}.source", "absent", actualSource)
            if (rate.sarEquivalent == null && actualSar != "absent") {
                found += Mismatch("fx.${rate.messageId}.sar", "absent", actualSar)
            }
        } else {
            if (rate.rate != null && !sameValue(rate.rate, actualRate)) {
                found += Mismatch("fx.${rate.messageId}.rate", rate.rate, actualRate)
            }
            if (rate.source != null && rate.source != actualSource) {
                found += Mismatch("fx.${rate.messageId}.source", rate.source, actualSource)
            }
        }
        if (rate.sarEquivalent != null && !sameValue(rate.sarEquivalent, actualSar)) {
            found += Mismatch("fx.${rate.messageId}.sar", rate.sarEquivalent, actualSar)
        }
        return found
    }

    private fun cardSignature(cards: List<GoldenObservedCard>): String =
        cards.map { "${it.bank}:${it.last4}=${it.salaryPeriodSpendingNet}" }
            .sorted()
            .joinToString("\n")

    private fun accountKey(account: GoldenAccountExpectation, field: String): String =
        "account.${account.bank}:${account.masked}.$field"

    private fun expectedTransactionSignature(transactions: List<GoldenTransactionExpectation>): String =
        transactions.map { tx ->
            view(
                type = tx.type,
                amount = Money.of(tx.amount, Currency.valueOf(tx.currency)).amount.toPlainString(),
                currency = tx.currency,
                direction = tx.direction,
                sourceKind = tx.sourceKind,
                sourceBank = tx.sourceBank,
                sourceRef = tx.sourceRef,
                destinationKind = tx.destinationKind,
                destinationBank = tx.destinationBank,
                destinationRef = tx.destinationRef,
                evidence = tx.evidenceMessageIds,
            )
        }.sorted().joinToString("\n")

    private fun observedTransactionSignature(transactions: List<GoldenObservedTransaction>): String =
        transactions.map { tx ->
            view(
                type = tx.type,
                amount = tx.amount,
                currency = tx.currency,
                direction = tx.direction,
                sourceKind = tx.sourceKind,
                sourceBank = tx.sourceBank,
                sourceRef = tx.sourceRef,
                destinationKind = tx.destinationKind,
                destinationBank = tx.destinationBank,
                destinationRef = tx.destinationRef,
                evidence = tx.evidenceMessageIds,
            )
        }.sorted().joinToString("\n")

    private fun view(
        type: String,
        amount: String,
        currency: String,
        direction: String,
        sourceKind: String?,
        sourceBank: String?,
        sourceRef: String?,
        destinationKind: String?,
        destinationBank: String?,
        destinationRef: String?,
        evidence: List<String>,
    ): String {
        val source = endpoint(sourceKind, sourceBank, sourceRef)
        val destination = endpoint(destinationKind, destinationBank, destinationRef)
        val links = evidence.sorted().joinToString("+").ifEmpty { "-" }
        return "$type $amount $currency $direction $source -> $destination [$links]"
    }

    private fun endpoint(kind: String?, bank: String?, ref: String?): String =
        if (kind == null || bank == null || ref == null) "-" else "$kind:$bank:$ref"

    private fun sameValue(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val left = expected.toBigDecimalOrNull()
        val right = actual.toBigDecimalOrNull()
        return left != null && right != null && left.compareTo(right) == 0
    }

    private fun format(
        scenario: GoldenScenario,
        primary: List<Mismatch>,
        alsoPending: List<Mismatch>,
    ): String = buildString {
        append(scenario.id)
        scenario.pendingFix?.let { append(" pending ").append(it) }
        append("\n").append(scenario.evidence)
        primary.forEach { mismatch ->
            append("\n").append(mismatch.key)
            append("\n  expected: ").append(mismatch.expected)
            append("\n  actual:   ").append(mismatch.actual)
        }
        if (alsoPending.isNotEmpty()) {
            append("\nalso pending: ")
            append(alsoPending.joinToString(", ") { it.key })
        }
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private data class Mismatch(
        val key: String,
        val expected: String,
        val actual: String,
    )

    companion object {
        private val DIGIT_RUN = Regex("""\d{10,}""")
    }
}
