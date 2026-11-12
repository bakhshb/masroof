package com.baraa.masroof.ledger

import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.ReviewKind
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

    /**
     * A correction-closed leg posted as a single-leg self-transfer is not re-opened when its counterpart arrives: the
     * stored row stays, and the counterpart stays a pending match (the old pass counted the posted leg as a pending
     * counterpart and never upgraded a protected leg).
     */
    @Test
    fun aProtectedSingleLegIsNotReopened_andItsCounterpartStaysPending() {
        val leg = storedRow("a", type = FinancialTransactionType.SELF_TRANSFER, source = "account:BANK_ALJAZIRA:1111")
        val result = project(
            listOf(intraBank("a", MessageFamily.TRANSFER_OUT), intraBank("b", MessageFamily.TRANSFER_IN)),
            registry = owned, stored = StoredFacts(rows = listOf(leg), correctionClosedRawSmsIds = setOf("a")),
        )
        assertEquals(listOf(leg), result.rows)
        assertEquals(listOf(LedgerReviewCandidate("b", ReviewKind.PENDING_MATCH, listOf("transfer_pending_match"))), result.reviewCandidates)
        assertEquals(setOf("a"), result.settledRawSmsIds)
    }

    /** A stored FEE whose amount differs from its installment stays as stored: no stored amount changes. */
    @Test
    fun aStoredFeeWithAnotherAmountThanItsInstallmentStaysAFee() {
        val loan = LoanReference(AJ, LoanType.PERSONAL)
        val fee = storedRow("loan", type = FinancialTransactionType.FEE, source = "account:BANK_ALJAZIRA:1111")
        val installment = evidence(event("loan", MessageFamily.FINANCING_INSTALLMENT, amount = "300", source = ownedA), T0, loanType = LoanType.PERSONAL)
        val result = project(listOf(installment), registry = owned.copy(loans = mapOf(loan to OwnershipStatus.OWNED)), stored = StoredFacts(rows = listOf(fee)))
        assertKept(result, fee)
    }

    private fun leg(id: String, family: MessageFamily, network: BankNetworkType, reference: String? = null) =
        evidence(event(id, family, source = ownedA, destination = ownedB, network = network), T0, transactionReference = reference)

    private fun LedgerProjection.sets() = rows.map { it.rawSmsIds to it.transaction.type }.toSet()

    /**
     * A transfer split in two by a daily limit: the stored pair [a1,b] is never broken up. A second out-leg a2 is
     * posted on its own as a single-leg self-transfer, as the old pass posted it (b was linked, so never in its
     * matching pool), and joins its own in-leg b2 when that arrives (the old upgrade of a posted single leg).
     * [reference] null: inter-bank legs without a reference never match, so a2 and b2 stay single, as before.
     */
    private fun splitTransfer(reference: (String) -> String?, expectedSecond: Set<Pair<List<String>, FinancialTransactionType>>) {
        val net = BankNetworkType.INTER_BANK
        fun legs(vararg ids: String) = ids.map { id ->
            leg(id, if (id.startsWith("a")) MessageFamily.TRANSFER_OUT else MessageFamily.TRANSFER_IN, net, reference(id))
        }
        val pair = storedRow("a1", "b", type = FinancialTransactionType.SELF_TRANSFER)
        val first = project(legs("a1", "b", "a2"), registry = owned, stored = StoredFacts(rows = listOf(pair)))
        assertEquals(
            setOf(listOf("a1", "b") to FinancialTransactionType.SELF_TRANSFER, listOf("a2") to FinancialTransactionType.SELF_TRANSFER),
            first.sets(),
        )
        val second = project(legs("a1", "b", "a2", "b2"), registry = owned, stored = StoredFacts(rows = first.rows))
        assertEquals(expectedSecond, second.sets())
        // Scoped = full for the new leg.
        val scope = LedgerProjector.scope(listOf("b2"), legs("a1", "b", "a2", "b2"), emptyMap(), StoredFacts(rows = first.rows))
        val scoped = project(legs("a1", "b", "a2", "b2").filter { it.event.rawSmsId in scope }, registry = owned,
            stored = StoredFacts(rows = first.rows.filter { row -> row.rawSmsIds.any { it in scope } }))
        assertEquals(second.rows.filter { row -> row.rawSmsIds.any { it in scope } }, scoped.rows)
    }

    @Test
    fun aStoredPairIsNeverSplitByALaterLeg_interBankWithReferences() = splitTransfer(
        reference = { if (it.endsWith("2")) "R2" else "R1" },
        expectedSecond = setOf(
            listOf("a1", "b") to FinancialTransactionType.SELF_TRANSFER, listOf("a2", "b2") to FinancialTransactionType.SELF_TRANSFER,
        ),
    )

    @Test
    fun aStoredPairIsNeverSplitByALaterLeg_interBankWithoutReferences() = splitTransfer(
        reference = { null },
        expectedSecond = setOf(
            listOf("a1", "b") to FinancialTransactionType.SELF_TRANSFER, listOf("a2") to FinancialTransactionType.SELF_TRANSFER,
            listOf("b2") to FinancialTransactionType.SELF_TRANSFER,
        ),
    )

    /** Intra-bank: the third leg cannot pair with the stored pair's legs either; the stored pair stays. */
    @Test
    fun aStoredPairIsNeverSplitByALaterLeg_intraBank() {
        val net = BankNetworkType.INTRA_BANK
        val pair = storedRow("a1", "b", type = FinancialTransactionType.SELF_TRANSFER)
        val result = project(
            listOf(leg("a1", MessageFamily.TRANSFER_OUT, net), leg("b", MessageFamily.TRANSFER_IN, net), leg("a2", MessageFamily.TRANSFER_OUT, net)),
            registry = owned, stored = StoredFacts(rows = listOf(pair)),
        )
        assertTrue(listOf("a1", "b") to FinancialTransactionType.SELF_TRANSFER in result.sets())
        assertTrue(result.rows.none { "a2" in it.rawSmsIds && it.rawSmsIds.size > 1 })
    }

    /** A stored pair that re-derives as itself with the same type and amount still takes today's detail. */
    @Test
    fun aStoredPairThatReDerivesAsItselfTakesTodaysDetail() {
        val net = BankNetworkType.INTER_BANK
        val pair = storedRow("a1", "b", zone = null, type = FinancialTransactionType.SELF_TRANSFER)
        val result = project(
            listOf(leg("a1", MessageFamily.TRANSFER_OUT, net, "R1"), leg("b", MessageFamily.TRANSFER_IN, net, "R1")),
            registry = owned, stored = StoredFacts(rows = listOf(pair)),
        )
        val row = result.rows.single()
        assertEquals(listOf("a1", "b"), row.rawSmsIds)
        assertEquals(null, row.transaction.merchant)
        assertTrue(row.transaction.occurredAtZone != null)
    }

    /** A stored expense over three purchase SMS (several messages about one purchase) stays one row. */
    @Test
    fun aStoredRowOfThreeNonTransferSmsIsKeptVerbatim() {
        val row = storedRow("p1", "p2", "p3")
        val result = project(listOf(purchase("p1"), purchase("p2"), purchase("p3")), registry = owned, stored = StoredFacts(rows = listOf(row)))
        assertKept(result, row)
    }

    /** A stored self-transfer {a,b,c} whose third SMS is a second message about a, all at the row's time, stays one row. */
    @Test
    fun aStoredSelfTransferOfThreeSmsAtItsOwnTimeIsKeptVerbatim() {
        val net = BankNetworkType.INTRA_BANK
        val row = storedRow("a", "b", "c", type = FinancialTransactionType.SELF_TRANSFER)
        val result = project(
            listOf(leg("a", MessageFamily.TRANSFER_OUT, net), leg("b", MessageFamily.TRANSFER_IN, net), leg("c", MessageFamily.TRANSFER_OUT, net)),
            registry = owned, stored = StoredFacts(rows = listOf(row)),
        )
        assertKept(result, row)
    }

    /** The old stale-link release: legs of a stored self-transfer whose time no longer matches it leave the row. */
    @Test
    fun aWronglyMergedSelfTransferReleasesOnlyItsStaleLegs() {
        val net = BankNetworkType.INTRA_BANK
        val later = T0.plusSeconds(86_400 * 30L)
        val row = storedRow("a1", "b1", "a2", "b2", type = FinancialTransactionType.SELF_TRANSFER, zone = "UTC")
        val legs = listOf(
            leg("a1", MessageFamily.TRANSFER_OUT, net), leg("b1", MessageFamily.TRANSFER_IN, net),
            evidence(event("a2", MessageFamily.TRANSFER_OUT, source = ownedA, destination = ownedB, network = net, occurredAt = later), later),
            evidence(event("b2", MessageFamily.TRANSFER_IN, source = ownedA, destination = ownedB, network = net, occurredAt = later), later),
        )
        val result = project(legs, registry = owned, stored = StoredFacts(rows = listOf(row)))
        assertEquals(setOf(listOf("a1", "b1"), listOf("a2", "b2")), result.rows.map { it.rawSmsIds }.toSet())
        assertEquals(row.transaction.id, result.rows.single { "a1" in it.rawSmsIds }.transaction.id)
    }

    /**
     * The old upgrade joins a stored external leg only when the pair is mutually unique among the posted and unlinked
     * legs: d, a stored external in-leg with the same reference, makes a ambiguous, so b is posted on its own.
     */
    @Test
    fun aStoredExternalLegIsNotJoinedWhenTheOldUpgradeFoundItAmbiguous() {
        val unknown = account("9999")
        val external = account("8888")
        val registry = owned.copy(accounts = owned.accounts + (external to OwnershipStatus.EXTERNAL))
        fun transfer(id: String, family: MessageFamily, source: AccountReference, destination: AccountReference) =
            evidence(event(id, family, source = source, destination = destination, network = BankNetworkType.INTER_BANK), T0, transactionReference = "R1")
        val a = storedRow("a", type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT)
        val d = storedRow("d", type = FinancialTransactionType.EXTERNAL_TRANSFER_IN)
        val result = project(
            listOf(transfer("a", MessageFamily.TRANSFER_OUT, ownedA, unknown), transfer("b", MessageFamily.TRANSFER_IN, unknown, ownedB),
                transfer("d", MessageFamily.TRANSFER_IN, external, ownedB)),
            registry = registry, stored = StoredFacts(rows = listOf(a, d)),
        )
        assertTrue(result.rows.none { it.rawSmsIds.size > 1 }, "rows: ${result.sets()}")
        assertTrue(a in result.rows)
    }

    /**
     * A leg whose counterpart is linked to a stored row that cannot be joined is assembled on its own, as the old pass
     * did (the counterpart was never in its pool): an owned out-leg to an unknown account posts as an external transfer.
     */
    @Test
    fun aLegWhoseCounterpartStaysStoredIsPostedOnItsOwn() {
        val unknown = account("9999")
        val b = storedRow("b", type = FinancialTransactionType.INCOME)
        val result = project(
            listOf(
                evidence(event("a", MessageFamily.TRANSFER_OUT, source = ownedA, destination = unknown, network = BankNetworkType.INTER_BANK), T0, transactionReference = "R1"),
                evidence(event("b", MessageFamily.TRANSFER_IN, source = ownedA, destination = ownedB, network = BankNetworkType.INTER_BANK), T0, transactionReference = "R1"),
            ),
            registry = owned, stored = StoredFacts(rows = listOf(b)),
        )
        assertTrue(b in result.rows)
        assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_OUT, result.rows.single { it.rawSmsIds == listOf("a") }.transaction.type)
        assertEquals(emptyList<LedgerReviewCandidate>(), result.reviewCandidates)
    }

    /**
     * A transfer leg its own stored row links (here a type edited only on the row) stays out of the matching pool, as
     * the old pass left a linked leg alone: it does not make a later pair of other legs ambiguous.
     */
    @Test
    fun aLegLinkedByItsOwnStoredRowDoesNotCompeteForALaterPair() {
        val net = BankNetworkType.INTER_BANK
        val edited = storedRow("x", type = FinancialTransactionType.INCOME)
        val result = project(
            listOf(leg("x", MessageFamily.TRANSFER_OUT, net, "R1"), leg("in", MessageFamily.TRANSFER_IN, net, "R1"),
                leg("out", MessageFamily.TRANSFER_OUT, net, "R1")),
            registry = owned, stored = StoredFacts(rows = listOf(edited)),
        )
        assertTrue(edited in result.rows)
        assertTrue(listOf("in", "out") to FinancialTransactionType.SELF_TRANSFER in result.sets(), "rows: ${result.sets()}")
    }

    /**
     * A stale leg released from a stored self-transfer of three SMS is unlinked, as the old `unlinkRawSms` left it, so the
     * upgrade can join it with a posted external leg: [a,c] stays, [b,d] becomes a self-transfer.
     */
    @Test
    fun aLegReleasedFromAMergedRowJoinsAPostedExternalLeg() {
        val external = account("9999")
        val registry = owned.copy(accounts = owned.accounts + (external to OwnershipStatus.EXTERNAL))
        val later = T0.plusSeconds(3_600)
        val row = storedRow("a", "b", "c", type = FinancialTransactionType.SELF_TRANSFER, zone = "UTC")
        val d = storedRow("d", type = FinancialTransactionType.EXTERNAL_TRANSFER_OUT).let {
            it.copy(transaction = it.transaction.copy(occurredAt = later))
        }
        val result = project(
            listOf(
                evidence(event("a", MessageFamily.TRANSFER_OUT, source = ownedA, destination = ownedB, network = BankNetworkType.INTRA_BANK), T0),
                evidence(event("b", MessageFamily.TRANSFER_IN, source = ownedA, destination = ownedB, network = BankNetworkType.INTRA_BANK, occurredAt = later), later, transactionReference = "R1"),
                purchase("c"),
                evidence(event("d", MessageFamily.TRANSFER_OUT, source = ownedA, destination = external, network = BankNetworkType.INTRA_BANK, occurredAt = later), later, transactionReference = "R1"),
            ),
            registry = registry, stored = StoredFacts(rows = listOf(row, d)),
        )
        assertEquals(
            setOf(listOf("a", "c") to FinancialTransactionType.SELF_TRANSFER, listOf("b", "d") to FinancialTransactionType.SELF_TRANSFER),
            result.sets(),
        )
    }

    /** The release is decided per raw SMS: a protected leg stays, an unprotected stale leg of the same row leaves. */
    @Test
    fun aMergedRowWithAProtectedLegStillReleasesItsOtherStaleLegs() {
        val later = T0.plusSeconds(86_400 * 30L)
        val row = storedRow("a1", "b1", "a2", type = FinancialTransactionType.SELF_TRANSFER, zone = "UTC")
        val result = project(
            listOf(
                leg("a1", MessageFamily.TRANSFER_OUT, BankNetworkType.INTRA_BANK), leg("b1", MessageFamily.TRANSFER_IN, BankNetworkType.INTRA_BANK),
                evidence(event("a2", MessageFamily.TRANSFER_OUT, source = ownedA, destination = ownedB, network = BankNetworkType.INTRA_BANK, occurredAt = later), later),
            ),
            registry = owned, stored = StoredFacts(rows = listOf(row), correctionClosedRawSmsIds = setOf("a1")),
        )
        assertEquals(listOf("a1", "b1"), result.rows.single { it.transaction.id == row.transaction.id }.rawSmsIds)
        assertTrue(result.rows.any { it.rawSmsIds == listOf("a2") })
    }
}
