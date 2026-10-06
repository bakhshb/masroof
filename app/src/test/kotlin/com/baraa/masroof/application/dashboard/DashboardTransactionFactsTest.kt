package com.baraa.masroof.application.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.FinancialContainerIdParser
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.period.FinancialPeriodPolicy
import com.baraa.masroof.testsupport.DashboardLedgerWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DashboardTransactionFactsTest {
    private val bank = Bank.BANK_ALJAZIRA

    @Test
    fun cardContainer_winsOverParsedCardRef() {
        val tx = transaction(source = FinancialContainerIdFactory.cardId(bank, "7271"))

        val facts = single(tx, cardInvolvement = mapOf(tx.id to setOf("${bank.id}:2210")))

        assertEquals("7271", facts.primaryCardLast4)
    }

    @Test
    fun withoutCardContainer_usesSmallestParsedCardKey() {
        val tx = transaction(source = FinancialContainerIdFactory.accountId(bank, "3001"))

        val facts = single(tx, cardInvolvement = mapOf(tx.id to setOf("${bank.id}:8219", "${bank.id}:2210")))

        assertEquals("2210", facts.primaryCardLast4)
        assertNull(single(tx).primaryCardLast4)
    }

    @Test
    fun loanAttributedRow_isLoanRepayment() {
        val tx = transaction(type = FinancialTransactionType.EXPENSE)
        val loanId = FinancialContainerIdFactory.loanId(bank, LoanType.PERSONAL)

        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, single(tx, loanInvolvement = mapOf(tx.id to setOf(loanId))).effectiveType)
        assertEquals(FinancialTransactionType.EXPENSE, single(tx).effectiveType)
    }

    @Test
    fun appliedRate_givesSarEquivalentWithoutFee() {
        val usd = transaction(amount = Money.of("10.00", Currency.USD), rate = BigDecimal("3.7512"))

        assertEquals(Money.of("37.51", Currency.SAR), single(usd).sarEquivalent)
        assertNull(single(usd.copy(appliedExchangeRate = null)).sarEquivalent)
        assertNull(single(transaction(rate = BigDecimal("3.75"))).sarEquivalent)
    }

    /**
     * Characterization: the projection's facts equal what `DashboardViewModel.toPreview` and
     * `MasroofRoot` derived before the facts moved into the application layer.
     */
    @Test
    fun projectionFacts_matchFormerPresentationDerivation() = runBlocking<Unit> {
        DashboardLedgerWorld(ApplicationProvider.getApplicationContext<Context>()).use { world ->
            world.importFixtureCorpus()
            world.seedSyntheticHistory(DashboardLedgerWorld.SYNTHETIC_MONTHS)
            val service = world.dashboardService(marketRateProvider = { _, _ -> BigDecimal("3.80") })
            val registry = DashboardRegistryWorkflow(world.cards, world.accounts)
            val periods = generateSequence(YearMonth.of(2024, 12)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.of(2026, 12)) }
                .map { FinancialPeriodPolicy.periodContaining(it.atDay(10)) }
                .distinct()
                .toList()

            val overviews = periods.map { service.loadOverview(it) }
            val legacyOwnedIds = registry.listOwnedAccounts()
                .map { FinancialContainerIdFactory.accountId(it.bank, it.maskedNumber) }
                .toSet()
            overviews.forEach { overview ->
                assertEquals(overview.transactions.map { it.id }.toSet(), overview.transactionFacts.keys)
                overview.transactions.forEach { tx ->
                    assertEquals(
                        "transaction ${tx.id}",
                        legacyFacts(tx, overview.transactionCardInvolvement, overview.transactionLoanInvolvement),
                        overview.transactionFacts.getValue(tx.id),
                    )
                }
                assertEquals(legacyOwnedIds, overview.ownedAccountContainerIds)
            }

            val pairs = overviews.flatMap { o -> o.transactions.map { it to o.transactionFacts.getValue(it.id) } }
            assertTrue(pairs.any { (tx, facts) -> facts.effectiveType != tx.type })
            assertTrue(
                pairs.any { (tx, facts) ->
                    facts.primaryCardLast4 != null &&
                        FinancialContainerIdParser.cardLast4FromContainers(tx.sourceContainerId, tx.destinationContainerId) == null
                },
            )
            assertTrue(pairs.any { (_, facts) -> facts.sarEquivalent != null })
            assertTrue(legacyOwnedIds.isNotEmpty())
        }
    }

    private fun legacyFacts(
        tx: FinancialTransaction,
        cardInvolvement: Map<String, Set<String>>,
        loanInvolvement: Map<String, Set<String>>,
    ): DashboardTransactionFacts {
        val appliedExchangeRate = tx.appliedExchangeRate
        val sarEquivalent = if (tx.amount.currency.convertsToSar() && appliedExchangeRate != null) {
            ForeignPurchaseSarConverter.foreignToSar(
                foreignAmount = tx.amount,
                exchangeRate = appliedExchangeRate,
                internationalFee = null,
                targetCurrency = Currency.SAR,
            )
        } else {
            null
        }
        val containerCardLast4 = FinancialContainerIdParser.cardLast4FromContainers(
            sourceContainerId = tx.sourceContainerId,
            destinationContainerId = tx.destinationContainerId,
        )
        val parsedCardLast4 = CardTransactionInvolvementResolver
            .resolvePrimaryCardKey(tx, cardInvolvement)
            ?.substringAfter(':', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }
        val effectiveType = if (tx.id in loanInvolvement) FinancialTransactionType.LOAN_REPAYMENT else tx.type
        return DashboardTransactionFacts(
            primaryCardLast4 = containerCardLast4 ?: parsedCardLast4,
            effectiveType = effectiveType,
            sarEquivalent = sarEquivalent,
        )
    }

    private fun single(
        tx: FinancialTransaction,
        cardInvolvement: Map<String, Set<String>> = emptyMap(),
        loanInvolvement: Map<String, Set<String>> = emptyMap(),
    ): DashboardTransactionFacts =
        DashboardTransactionFactsBuilder.build(listOf(tx), cardInvolvement, loanInvolvement).getValue(tx.id)

    private fun transaction(
        type: FinancialTransactionType = FinancialTransactionType.EXPENSE,
        amount: Money = Money.of("10.00", Currency.SAR),
        source: String? = null,
        rate: BigDecimal? = null,
    ) = FinancialTransaction(
        id = "tx-1",
        type = type,
        amount = amount,
        occurredAt = Instant.parse("2026-08-05T11:05:00Z"),
        sourceContainerId = source,
        destinationContainerId = null,
        merchant = null,
        counterparty = null,
        categoryId = null,
        linkedParsedEventIds = emptyList(),
        appliedExchangeRate = rate,
        exchangeRateSource = rate?.let { ExchangeRateSource.SMS },
    )
}
