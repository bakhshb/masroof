package com.baraa.masroof.ledger

import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ids.TransactionIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.ExchangeRateSource
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One case per rule of the old reconciliation that the derived ledger must reproduce. */
class LedgerProjectorTest {
    private val ownedA = account("1111")
    private val ownedB = account("2222")
    private val card = CardReference(AJ, "7271")
    private val owned = OwnershipFacts(
        accounts = mapOf(ownedA to OwnershipStatus.OWNED, ownedB to OwnershipStatus.OWNED),
        cards = mapOf(card to OwnershipStatus.OWNED),
    )

    private fun purchase(id: String, status: ParseStatus = ParseStatus.SUCCESS) =
        evidence(event(id, MessageFamily.PURCHASE, card = card, status = status), T0)

    private fun intraBank(id: String, family: MessageFamily, at: Instant = T0, reference: String? = null) =
        evidence(
            event(id, family, source = ownedA, destination = ownedB, network = BankNetworkType.INTRA_BANK),
            at, transactionReference = reference,
        )

    @Test
    fun resolvedReviewShortCircuits() {
        val posted = Instant.parse("2026-07-31T21:00:00Z")
        val decisions = fold(
            listOf(
                UserDecision("p1", UserDecisionKind.NON_FINANCIAL, T0),
                UserDecision(
                    "p2", UserDecisionKind.FINANCIAL_TYPE, T0, transactionType = FinancialTransactionType.INCOME,
                    destinationContainerId = "dest", occurredAt = posted, occurredAtZone = "Asia/Riyadh",
                ),
                UserDecision("p3", UserDecisionKind.EXTERNAL_TRANSFER, T0),
                UserDecision("p4", UserDecisionKind.RESTORE, T0),
                UserDecision(
                    "out", UserDecisionKind.SELF_TRANSFER_PAIR, T0, pairedRawSmsId = "in",
                    transactionType = FinancialTransactionType.SELF_TRANSFER, occurredAt = posted,
                    occurredAtZone = "Asia/Riyadh",
                ),
            ),
        )
        val result = project(
            listOf(
                purchase("p1"), purchase("p2"), purchase("p3"), purchase("p4", ParseStatus.REVIEW_REQUIRED),
                intraBank("out", MessageFamily.TRANSFER_OUT), intraBank("in", MessageFamily.TRANSFER_IN),
            ),
            decisions, owned,
        )

        val byIds = result.rows.associateBy { it.rawSmsIds }
        assertEquals(setOf(listOf("p2"), listOf("p4"), listOf("in", "out")), byIds.keys)
        val decided = byIds.getValue(listOf("p2")).transaction
        assertEquals(FinancialTransactionType.INCOME, decided.type)
        assertEquals("dest", decided.destinationContainerId)
        assertEquals(posted, decided.occurredAt)
        assertEquals(FinancialTransactionType.EXPENSE, byIds.getValue(listOf("p4")).transaction.type)
        val pair = byIds.getValue(listOf("in", "out")).transaction
        assertEquals(TransactionIdFactory.fromRawSmsIds(listOf("in", "out")), pair.id)
        assertEquals(listOf("e-in", "e-out"), pair.linkedParsedEventIds)
        assertEquals(setOf("p1", "p2", "p3", "p4", "out", "in"), result.settledRawSmsIds)
        assertEquals(emptyList<LedgerReviewCandidate>(), result.reviewCandidates)
    }

    @Test
    fun informationalFamiliesAreIgnoredEvenWhenAStoredRowLinksThem() {
        val result = project(
            listOf(
                evidence(event("otp", MessageFamily.OTP, card = card), T0),
                evidence(event("note", MessageFamily.BALANCE_NOTICE, source = ownedA), T0),
                evidence(event("unknown", MessageFamily.UNKNOWN), T0),
            ),
            registry = owned,
            stored = StoredFacts(rows = listOf(StoredLedgerRow(setOf("otp"), "Asia/Riyadh"))),
        )

        assertEquals(emptyList<LedgerRow>(), result.rows)
        assertEquals(setOf("otp", "note"), result.settledRawSmsIds)
        assertEquals(
            listOf(LedgerReviewCandidate("unknown", ReviewKind.NEEDS_REVIEW, listOf("unknown_message_family"))),
            result.reviewCandidates,
        )
    }

    @Test
    fun aStoredSingleLegSelfTransferGivesWayToItsPair_andAnAmbiguousOneIsReleased() {
        val stored = StoredFacts(rows = listOf(StoredLedgerRow(setOf("out"), "Asia/Riyadh")))
        val paired = project(
            listOf(intraBank("out", MessageFamily.TRANSFER_OUT), intraBank("in", MessageFamily.TRANSFER_IN, T0.plusSeconds(60))),
            registry = owned, stored = stored,
        )
        assertEquals(listOf(listOf("in", "out")), paired.rows.map { it.rawSmsIds })
        assertEquals(FinancialTransactionType.SELF_TRANSFER, paired.rows.single().transaction.type)

        val ambiguous = project(
            listOf(
                intraBank("out", MessageFamily.TRANSFER_OUT),
                intraBank("in1", MessageFamily.TRANSFER_IN, T0.plusSeconds(60)),
                intraBank("in2", MessageFamily.TRANSFER_IN, T0.plusSeconds(60)),
            ),
            registry = owned, stored = stored,
        )
        assertEquals(emptyList<LedgerRow>(), ambiguous.rows)
        assertEquals(listOf("in1", "in2", "out"), ambiguous.reviewCandidates.map { it.rawSmsId })
        assertTrue(ambiguous.reviewCandidates.all { it.kind == ReviewKind.PENDING_MATCH })
    }

    @Test
    fun aLoneOwnedLegPostsAlone_butAnUnpairableCounterpartKeepsBothPending() {
        val lone = project(listOf(intraBank("out", MessageFamily.TRANSFER_OUT)), registry = owned)
        assertEquals(FinancialTransactionType.SELF_TRANSFER, lone.rows.single().transaction.type)
        assertEquals(listOf("out"), lone.rows.single().rawSmsIds)

        // Conflicting references never pair, but the intra-bank bridge still makes each the other's counterpart.
        val pending = project(
            listOf(
                intraBank("out", MessageFamily.TRANSFER_OUT, reference = "A"),
                intraBank("in", MessageFamily.TRANSFER_IN, T0.plusSeconds(60), reference = "B"),
            ),
            registry = owned,
        )
        assertEquals(emptyList<LedgerRow>(), pending.rows)
        assertEquals(
            listOf("in", "out").map { LedgerReviewCandidate(it, ReviewKind.PENDING_MATCH, listOf("transfer_pending_match")) },
            pending.reviewCandidates,
        )
    }

    @Test
    fun postedExternalLegsJoinIntoOnePair_unlessACorrectionClosureProtectsOne() {
        val externalDestination = account("99", D360)
        val ownedDestination = account("9999", D360)
        val registry = owned.copy(
            accounts = owned.accounts + mapOf(
                externalDestination to OwnershipStatus.EXTERNAL, ownedDestination to OwnershipStatus.OWNED,
            ),
        )
        val events = listOf(
            evidence(
                event("out", MessageFamily.TRANSFER_OUT, source = ownedA, destination = externalDestination,
                    network = BankNetworkType.INTER_BANK),
                T0, transactionReference = "REF1",
            ),
            evidence(
                event("in", MessageFamily.TRANSFER_IN, bank = D360, destination = ownedDestination,
                    network = BankNetworkType.INTER_BANK),
                T0.plusSeconds(120), transactionReference = "ref1",
            ),
        )

        val joined = project(events, registry = registry).rows.single()
        assertEquals(FinancialTransactionType.SELF_TRANSFER, joined.transaction.type)
        assertEquals(listOf("in", "out"), joined.rawSmsIds)
        assertEquals(FinancialContainerIdFactory.accountId(ownedDestination), joined.transaction.destinationContainerId)

        val protected = project(events, registry = registry, stored = StoredFacts(correctionClosedRawSmsIds = setOf("out")))
        assertEquals(
            listOf(FinancialTransactionType.EXTERNAL_TRANSFER_IN, FinancialTransactionType.EXTERNAL_TRANSFER_OUT),
            protected.rows.sortedBy { it.rawSmsIds.single() }.map { it.transaction.type },
        )
    }

    @Test
    fun aFinancingInstallmentRepaysAPreConfirmedLoan_whateverTheOrder() {
        val loan = LoanReference(AJ, LoanType.PERSONAL)
        fun installment(id: String, source: AccountReference, loanType: LoanType?) =
            evidence(event(id, MessageFamily.FINANCING_INSTALLMENT, source = source), T0, loanType = loanType)
        val events = listOf(
            installment("i1", account("5555"), LoanType.PERSONAL),
            installment("i2", ownedA, LoanType.PERSONAL),
            installment("i3", ownedA, null),
        )
        // i2 was stored as FEE by an older assembler.
        val stored = StoredFacts(rows = listOf(StoredLedgerRow(setOf("i2"), "Asia/Riyadh")))

        val result = project(events, registry = owned, stored = stored)
        assertEquals(setOf(loan), result.confirmedLoans)
        val repayment = result.rows.single()
        assertEquals(listOf("i2"), repayment.rawSmsIds)
        assertEquals(FinancialTransactionType.LOAN_REPAYMENT, repayment.transaction.type)
        assertEquals(FinancialContainerIdFactory.loanId(AJ, LoanType.PERSONAL), repayment.transaction.destinationContainerId)
        assertEquals(
            mapOf(
                "i1" to listOf("loan_repayment_ownership_unresolved"),
                "i3" to listOf("loan_repayment_missing_containers_or_type"),
            ),
            result.reviewCandidates.associate { it.rawSmsId to it.reasons },
        )
        assertEquals(result, project(events.reversed(), registry = owned, stored = stored))
        // An owned loan is not confirmed again.
        assertEquals(emptySet<LoanReference>(), project(events, registry = owned.copy(loans = mapOf(loan to OwnershipStatus.OWNED))).confirmedLoans)
    }

    @Test
    fun storedZoneAndCompleteExchangeRatePairAreInputs() {
        val d360Card = CardReference(D360, "4444")
        val evidence = evidence(
            event("fx", MessageFamily.PURCHASE, bank = D360, card = d360Card), T0,
            occurredAtLocal = LocalDateTime.parse("2026-08-01T10:00:00"),
        )
        val registry = OwnershipFacts(cards = mapOf(d360Card to OwnershipStatus.OWNED))
        val stored = StoredFacts(
            rows = listOf(StoredLedgerRow(setOf("fx"), "Asia/Dubai", BigDecimal("3.75"), ExchangeRateSource.SMS)),
        )

        val row = project(listOf(evidence), registry = registry, stored = stored).rows.single().transaction
        assertEquals(Instant.parse("2026-08-01T06:00:00Z"), row.occurredAt)
        assertEquals("Asia/Dubai", row.occurredAtZone)
        assertEquals(BigDecimal("3.75"), row.appliedExchangeRate)
        assertEquals(ExchangeRateSource.SMS, row.exchangeRateSource)

        val fresh = project(listOf(evidence), registry = registry).rows.single().transaction
        assertEquals(Instant.parse("2026-08-01T10:00:00Z"), fresh.occurredAt)
        assertEquals(null, fresh.appliedExchangeRate)

        val half = StoredFacts(rows = listOf(StoredLedgerRow(setOf("fx"), "Asia/Dubai", BigDecimal("3.75"), null)))
        assertEquals(null, project(listOf(evidence), registry = registry, stored = half).rows.single().transaction.appliedExchangeRate)
    }

    @Test
    fun aDecidedPairThatConflictsWithAnEarlierDecisionIsRetried() {
        val posted = Instant.parse("2026-07-31T21:00:00Z")
        val decisions = fold(
            listOf(
                UserDecision(
                    "b", UserDecisionKind.SELF_TRANSFER_PAIR, T0, pairedRawSmsId = "a",
                    transactionType = FinancialTransactionType.SELF_TRANSFER, occurredAt = posted, occurredAtZone = "UTC",
                ),
                UserDecision(
                    "a", UserDecisionKind.FINANCIAL_TYPE, T0, transactionType = FinancialTransactionType.EXPENSE,
                    occurredAt = posted, occurredAtZone = "UTC",
                ),
            ),
        )
        val result = project(listOf(purchase("a"), purchase("b")), decisions, owned)
        assertEquals(listOf(listOf("a")), result.rows.map { it.rawSmsIds })
        assertEquals(setOf("b"), result.retryRawSmsIds)
        assertEquals(setOf("a"), result.settledRawSmsIds)
    }
}
