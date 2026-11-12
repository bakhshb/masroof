package com.baraa.masroof.ledger

import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.UserDecision
import com.baraa.masroof.domain.model.UserDecisionKind
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The keep-stored rule set the owner decided at gate 3.2 (2026-11-12; docs/ARCHITECTURE.md, "Derived ledger"). */
class LedgerProjectorKeepStoredTest {
    private val ownedA = account("1111")
    private val ownedB = account("2222")
    private val card = CardReference(AJ, "7271")
    private val owned = OwnershipFacts(
        accounts = mapOf(ownedA to OwnershipStatus.OWNED, ownedB to OwnershipStatus.OWNED),
        cards = mapOf(card to OwnershipStatus.OWNED),
    )

    private fun purchase(id: String, status: ParseStatus = ParseStatus.SUCCESS) =
        evidence(event(id, MessageFamily.PURCHASE, card = card, status = status), T0)

    private fun intraBank(id: String, family: MessageFamily, status: ParseStatus = ParseStatus.SUCCESS) =
        evidence(event(id, family, source = ownedA, destination = ownedB, network = BankNetworkType.INTRA_BANK, status = status), T0)

    private fun assertKept(result: LedgerProjection, vararg rows: LedgerRow) {
        assertEquals(rows.toList().sortedBy { it.transaction.id }, result.rows)
        assertEquals(emptyList<LedgerReviewCandidate>(), result.reviewCandidates)
        assertTrue(result.settledRawSmsIds.containsAll(rows.flatMap { it.rawSmsIds }))
    }

    @Test
    fun aDecidedRowStaysAsStored_withOrWithoutPostingColumns() {
        val bare = storedRow("bare", zone = null, type = FinancialTransactionType.INCOME)
        val complete = storedRow("complete", type = FinancialTransactionType.INCOME)
        val external = storedRow("ext", zone = null, type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT)
        val decisions = fold(
            listOf(
                UserDecision("bare", UserDecisionKind.FINANCIAL_TYPE, T0),
                // Complete columns that disagree with the stored row: the stored row still wins.
                UserDecision(
                    "complete", UserDecisionKind.RECLASSIFY, T0, transactionType = FinancialTransactionType.EXPENSE,
                    occurredAt = Instant.parse("2026-07-01T00:00:00Z"), occurredAtZone = "UTC",
                ),
                UserDecision("ext", UserDecisionKind.EXTERNAL_TRANSFER, T0),
            ),
        )
        val result = project(
            listOf(purchase("bare"), purchase("complete"), evidence(event("ext", MessageFamily.TRANSFER_OUT, source = ownedA), T0)),
            decisions, owned, StoredFacts(rows = listOf(bare, complete, external)),
        )
        assertKept(result, bare, complete, external)
    }

    @Test
    fun aStoredPairIsKeptWhicheverLegCarriesTheDecision() {
        val pair = storedRow("a", "b", type = FinancialTransactionType.SELF_TRANSFER)
        for (decided in listOf("a", "b")) {
            val decisions = fold(listOf(UserDecision(decided, UserDecisionKind.FINANCIAL_TYPE, T0)))
            val result = project(listOf(purchase("a"), purchase("b")), decisions, owned, StoredFacts(rows = listOf(pair)))
            assertKept(result, pair)
            assertEquals(emptySet<String>(), result.retryRawSmsIds)
        }
    }

    @Test
    fun aRowThatNoLongerDerivesStaysAsStored_staleEvidenceOrOwnership() {
        val stale = storedRow("stale")
        val disowned = storedRow("disowned", source = "card:BANK_ALJAZIRA:7271")
        val events = listOf(purchase("stale", ParseStatus.REVIEW_REQUIRED), purchase("disowned"))
        val registry = owned.copy(cards = mapOf(card to OwnershipStatus.EXTERNAL))
        // "stale" is not automation-eligible today and "disowned" uses a card that is no longer owned.
        assertKept(project(events, registry = registry, stored = StoredFacts(rows = listOf(stale, disowned))), stale, disowned)
    }

    @Test
    fun aTypeOrAmountEditedOnlyOnTheRowIsKept_butOtherFieldsAreRederived() {
        val fee = storedRow("fee", type = FinancialTransactionType.FEE)
        assertKept(project(listOf(purchase("fee")), registry = owned, stored = StoredFacts(rows = listOf(fee))), fee)

        val merchant = storedRow("m")
        val row = project(listOf(purchase("m")), registry = owned, stored = StoredFacts(rows = listOf(merchant))).rows.single()
        assertEquals(FinancialTransactionType.EXPENSE, row.transaction.type)
        assertEquals(null, row.transaction.merchant)
    }

    @Test
    fun aStoredRowWithoutLinksIsKept() {
        val unlinked = LedgerRow(storedRow("gone").transaction.copy(id = "transaction:unlinked"), emptyList())
        assertKept(project(listOf(purchase("p")), registry = owned, stored = StoredFacts(rows = listOf(unlinked))).let {
            it.copy(rows = it.rows.filter { row -> row.rawSmsIds.isEmpty() })
        }, unlinked)
    }

    @Test
    fun aKeptDecidedRowWinsOverAnotherSmsPostingThatNamesIt_inEitherOrder() {
        val b = storedRow("b", type = FinancialTransactionType.INCOME)
        val decisions = fold(
            listOf(
                UserDecision(
                    "a", UserDecisionKind.SELF_TRANSFER_PAIR, T0, pairedRawSmsId = "b",
                    transactionType = FinancialTransactionType.SELF_TRANSFER, occurredAt = T0, occurredAtZone = "UTC",
                ),
                UserDecision("b", UserDecisionKind.FINANCIAL_TYPE, T0),
            ),
        )
        val events = listOf(purchase("a"), purchase("b"))
        val stored = StoredFacts(rows = listOf(b))
        val result = project(events, decisions, owned, stored)
        assertEquals(listOf(b), result.rows)
        assertEquals(setOf("a"), result.retryRawSmsIds)
        assertEquals(result, project(events.reversed(), decisions, owned, stored))
        val swapped = events.map { it.copy(event = it.event.copy(id = if (it.event.rawSmsId == "a") "e-z" else it.event.id)) }
        assertEquals(listOf(b), project(swapped, decisions, owned, stored).rows)
    }

    @Test
    fun aRowTheProjectionStillDerivesTakesTodaysDerivation() {
        val stored = storedRow("p", zone = null)
        val row = project(listOf(purchase("p")), registry = owned, stored = StoredFacts(rows = listOf(stored))).rows.single()
        assertNotEquals(stored, row)
        assertEquals(null, row.transaction.merchant)
        assertEquals(stored.transaction.id, row.transaction.id)
    }

    @Test
    fun aStoredPairWithOneLegLeftKeepsItsGrouping_butACompleteDerivedGroupingWins() {
        val pair = storedRow("in", "out", type = FinancialTransactionType.SELF_TRANSFER)
        val oneLeg = project(
            listOf(intraBank("out", MessageFamily.TRANSFER_OUT), intraBank("in", MessageFamily.TRANSFER_IN, ParseStatus.REVIEW_REQUIRED)),
            registry = owned, stored = StoredFacts(rows = listOf(pair)),
        )
        assertKept(oneLeg, pair)

        val separate = StoredFacts(
            rows = listOf(
                storedRow("out", type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT),
                storedRow("in", type = FinancialTransactionType.EXTERNAL_TRANSFER_IN),
            ),
        )
        val joined = project(
            listOf(intraBank("out", MessageFamily.TRANSFER_OUT), intraBank("in", MessageFamily.TRANSFER_IN)),
            registry = owned, stored = separate,
        )
        assertEquals(listOf(listOf("in", "out")), joined.rows.map { it.rawSmsIds })
        assertEquals(FinancialTransactionType.SELF_TRANSFER, joined.rows.single().transaction.type)
    }

    @Test
    fun aNonFinancialDecisionKeepsTheStoredRow_andAnInformationalFamilyRemovesIt() {
        val decisions = fold(
            listOf(
                UserDecision("nf", UserDecisionKind.NON_FINANCIAL, T0), UserDecision("ig", UserDecisionKind.IGNORE, T0),
                UserDecision("rv", UserDecisionKind.RESTORE_REVERTED, T0),
            ),
        )
        val kept = listOf(storedRow("nf"), storedRow("ig"), storedRow("rv"))
        val stored = StoredFacts(rows = kept + storedRow("otp"))
        val result = project(
            listOf(purchase("nf"), purchase("ig"), purchase("rv"), evidence(event("otp", MessageFamily.OTP, card = card), T0)),
            decisions, owned, stored,
        )
        // The old pass skipped a decided SMS and left its row; only the decision's own writer deletes it.
        assertEquals(kept.sortedBy { it.transaction.id }, result.rows)
        assertEquals(setOf("nf", "ig", "rv", "otp"), result.settledRawSmsIds)
        assertEquals(emptyList<LedgerReviewCandidate>(), result.reviewCandidates)
    }

    @Test
    fun aNonFinancialDecisionWithoutAStoredRowDerivesNothing() {
        val decisions = fold(listOf(UserDecision("nf", UserDecisionKind.NON_FINANCIAL, T0)))
        val result = project(listOf(purchase("nf")), decisions, owned)
        assertEquals(emptyList<LedgerRow>(), result.rows)
        assertEquals(setOf("nf"), result.settledRawSmsIds)
    }

    @Test
    fun theScopeIncludesEveryLegOfAStoredRow() {
        val stored = StoredFacts(rows = listOf(storedRow("a", "b")))
        assertEquals(setOf("a", "b"), LedgerProjector.scope(listOf("a"), listOf(purchase("a"), purchase("b")), emptyMap(), stored))
    }
}
