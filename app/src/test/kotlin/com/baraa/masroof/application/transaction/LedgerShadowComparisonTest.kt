package com.baraa.masroof.application.transaction

import com.baraa.masroof.application.transaction.LedgerShadowComparison.Kind
import com.baraa.masroof.application.transaction.LedgerShadowComparison.Reason
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.EffectiveUserDecisions
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import com.baraa.masroof.ledger.LedgerEvidence
import com.baraa.masroof.ledger.LedgerProjection
import com.baraa.masroof.ledger.LedgerReviewCandidate
import com.baraa.masroof.ledger.LedgerRow
import com.baraa.masroof.ledger.OwnershipFacts
import com.baraa.masroof.ledger.StoredFacts
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerShadowComparisonTest {
    private val t0 = Instant.parse("2026-08-01T09:00:00Z")

    private fun event(rawSmsId: String, family: MessageFamily = MessageFamily.PURCHASE, amount: String = "100") = ParsedEvent(
        id = "e-$rawSmsId", rawSmsId = rawSmsId, bank = Bank.BANK_ALJAZIRA, messageFamily = family, direction = null,
        amount = Money.of(amount, Currency.SAR), purchaseChannel = null, sourceAccountRef = null,
        destinationAccountRef = null, cardRef = null, merchant = null, counterparty = null, occurredAt = t0,
        bankNetworkType = null, confidence = Confidence(1.0), parseStatus = ParseStatus.SUCCESS,
    )

    private fun row(vararg rawSmsIds: String, type: FinancialTransactionType = FinancialTransactionType.EXPENSE, id: String? = null) =
        LedgerRow(
            FinancialTransaction(
                id = id ?: TransactionIdFactory.fromRawSmsIds(rawSmsIds.toList()), type = type,
                amount = Money.of("100", Currency.SAR), occurredAt = t0, sourceContainerId = "account:BANK_ALJAZIRA:9001",
                destinationContainerId = null, merchant = null, counterparty = null, categoryId = null,
                linkedParsedEventIds = rawSmsIds.map { "e-$it" }, occurredAtZone = "Asia/Riyadh",
            ),
            rawSmsIds.sorted(),
        )

    private fun inputs(
        old: List<LedgerRow>,
        events: List<ParsedEvent>,
        decisions: Map<String, EffectiveUserDecisions> = emptyMap(),
        reviews: List<ReviewItem> = emptyList(),
    ) = LedgerInputs(
        events = events.map { LedgerEvidence(it, t0, null, null, null, automationConfirmed = false) },
        decisions = decisions, registry = OwnershipFacts(), storedFacts = StoredFacts(), zone = ZoneOffset.UTC,
        storedRows = old, reviews = reviews,
    )

    private fun projection(rows: List<LedgerRow>, reviews: List<LedgerReviewCandidate> = emptyList()) =
        LedgerProjection(rows, reviews, emptySet(), emptySet(), emptySet())

    @Test
    fun equalRowsHaveNoDifference() {
        val rows = listOf(row("a"), row("b"))
        val result = LedgerShadowComparison.compare(inputs(rows, listOf(event("a"), event("b"))), projection(rows))
        assertTrue(result.rows.isEmpty())
        assertTrue(result.reviews.isEmpty())
    }

    @Test
    fun eachKindAndKnownReasonIsTold() {
        val events = listOf(
            event("id"), event("fx"), event("type"), event("stale", amount = "90"),
            event("out", MessageFamily.TRANSFER_OUT), event("in", MessageFamily.TRANSFER_IN), event("decided"), event("odd"),
        )
        val fxOld = row("fx").let { it.copy(transaction = it.transaction.copy(appliedExchangeRate = BigDecimal("3.75"), exchangeRateSource = ExchangeRateSource.SMS)) }
        val old = listOf(
            row("id", id = "transaction:kept"), fxOld, row("type", type = FinancialTransactionType.INCOME), row("stale"),
            row("out", type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT), row("in", type = FinancialTransactionType.EXTERNAL_TRANSFER_IN),
            row("odd"),
        )
        val new = listOf(
            row("id"), row("fx"), row("type"), row("stale", type = FinancialTransactionType.INCOME),
            row("in", "out", type = FinancialTransactionType.SELF_TRANSFER), row("decided"),
        )
        val decision = UserDecision.posting(UserDecisionKind.FINANCIAL_TYPE, "decided", row("decided").transaction, t0)
        val result = LedgerShadowComparison.compare(
            inputs(old, events, mapOf("decided" to EffectiveUserDecisions(outcome = decision))), projection(new),
        )
        val byFirst = result.rows.associateBy { it.rawSmsIds.first() }
        assertEquals(Kind.CHANGED to Reason.ID_ONLY, byFirst.getValue("id").let { it.kind to it.reason })
        assertEquals(listOf("exchangeRate", "exchangeRateSource"), byFirst.getValue("fx").changedFields)
        assertEquals(Reason.EXCHANGE_RATE, byFirst.getValue("fx").reason)
        assertEquals(Reason.ROW_TYPE_OR_OWNERSHIP, byFirst.getValue("type").reason)
        assertEquals(Reason.STALE_EVIDENCE, byFirst.getValue("stale").reason)
        assertEquals(Kind.GROUPING, byFirst.getValue("in").kind)
        assertEquals(listOf("in", "out"), byFirst.getValue("in").rawSmsIds)
        assertEquals(2, byFirst.getValue("in").oldRows.size)
        assertEquals(Kind.ONLY_NEW to Reason.DECIDED_NOT_STORED, byFirst.getValue("decided").let { it.kind to it.reason })
        assertEquals(Kind.ONLY_OLD to Reason.UNEXPLAINED, byFirst.getValue("odd").let { it.kind to it.reason })
        assertEquals(result.rows.sortedBy { it.rawSmsIds.first() }, result.rows)
    }

    @Test
    fun reportMasksIdsAndLongNumbersAndIsDeterministic() {
        val oldRow = row("android-sms:123456789").let {
            it.copy(transaction = it.transaction.copy(counterparty = "SA0380000000608010167519", type = FinancialTransactionType.INCOME))
        }
        val review = ReviewItem("r", "android-sms:123456789", ReviewKind.NEEDS_REVIEW, ReviewStatus.REQUIRED, emptyList(), t0, t0, null, null, null)
        val inputs = inputs(listOf(oldRow), listOf(event("android-sms:123456789")), reviews = listOf(review))
        val report = LedgerShadowComparison.report(LedgerShadowComparison.compare(inputs, projection(listOf(row("android-sms:123456789")))))
        assertFalse(report.contains("123456789"))
        assertFalse(report.contains("0380000000608010167519"))
        assertTrue(report.contains("counterparty=SA…7519"))
        assertTrue(report.contains("ROW_TYPE_OR_OWNERSHIP: 1"))
        assertTrue(report.contains("NEEDS_REVIEW -> none  stored=REQUIRED decision=-"))
        assertEquals(report, LedgerShadowComparison.report(LedgerShadowComparison.compare(inputs, projection(listOf(row("android-sms:123456789"))))))
    }
}
