package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.BetaEnvironment
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.matching.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomBetaFinancialLedgerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BetaDatabase
    private val bank = BankId("BANK_ALJAZIRA")
    private val account = OwnedAsset(AssetId(Environment.BETA, bank, "account"), AssetKind.ACCOUNT, "1234")
    private val other = OwnedAsset(AssetId(Environment.BETA, bank, "other"), AssetKind.ACCOUNT, "5678")
    private val assets = listOf(account, other).associateBy { it.id }
    private fun input(id: String = "transaction", revision: PostingRevision = PostingRevision(0, 0), evidence: Set<String> = setOf("evidence-$id")) =
        FinancialClassificationInput(PostingKey(Environment.BETA, id), revision, FinancialType.EXPENSE, Money.of("42.35", Currency.SAR),
            TransactionTime(Instant.parse("2026-10-17T12:00:00.123456789Z"), ZoneId.of("Asia/Riyadh")),
            FinancialEndpoint.Owned(account.id), FinancialEndpoint.External, assets, evidence, true)

    @Before fun open() = runBlocking {
        BetaContainer.resetOffline(context)
        db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        assets.values.forEach { BetaAssetRegistry(db).register(it) }
    }
    @After fun close() { db.close(); BetaContainer.resetOffline(context) }
    private fun reopen() { db.close(); db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build() }

    @Test fun referenceAndRoomAgreeOnAppliedRetryConflictStaleAndManualFxPolicy() = runBlocking {
        val reference = InMemoryFinancialPostingLedger()
        val room = RoomBetaFinancialLedger(db)
        val base = input().copy(appliedFx = AppliedFx(BigDecimal("3.750000001234567890"), "persisted-user-rate"))
        val manual = base.copy(revision = PostingRevision(1, 0), amount = Money.of("43", Currency.SAR), authority = CorrectionAuthority.USER_FINANCIAL_TYPE)
        val requests = listOf(base, base, base.copy(amount = Money.of("41", Currency.SAR)),
            base.copy(revision = PostingRevision(0, 1), appliedFx = null), manual,
            manual.copy(revision = PostingRevision(1, 1), authority = CorrectionAuthority.NONE),
            manual.copy(revision = PostingRevision(1, 1), amount = Money.of("44", Currency.SAR)),
            manual.copy(revision = PostingRevision(1, 1)), base,
            manual.copy(revision = PostingRevision(2, 0), appliedFx = AppliedFx(BigDecimal("3.7600"), "explicit-correction")))
        for (request in requests) assertEquals(reference.post(request), room.post(request))
        assertEquals(reference.read(base.key), room.read(base.key))
        assertEquals(reference.history(base.key), room.history(base.key))
        assertEquals(4, room.history(base.key).size)
    }

    @Test fun reopenPreservesCompleteSnapshotsMetricsPrecisionAndIdempotency() = runBlocking {
        val request = input().copy(appliedFx = AppliedFx(BigDecimal("3.75000000012345678900"), "unrounded-provenance"))
        val stored = (RoomBetaFinancialLedger(db).post(request) as FinancialPostingResult.Applied).posting
        reopen()
        val room = RoomBetaFinancialLedger(db)
        assertEquals(stored, room.read(request.key))
        assertEquals(FinancialPostingResult.AlreadyApplied(stored), room.post(request))
        assertEquals(listOf(stored), room.history(request.key))
        assertEquals(BigDecimal("3.75000000012345678900"), room.read(request.key)!!.command.appliedFx!!.rateToSar)
    }

    @Test fun bothAdaptersRejectRetainedFxWhenCurrencyChangesAndAcceptExplicitNewPair() = runBlocking {
        val reference = InMemoryFinancialPostingLedger()
        val base = input().copy(amount = Money.of("42.35", Currency.USD),
            appliedFx = AppliedFx(BigDecimal("3.750000000123456789"), "usd-rate"))
        assertEquals(reference.post(base), RoomBetaFinancialLedger(db).post(base))
        reopen()
        val room = RoomBetaFinancialLedger(db)
        for (currency in listOf(Currency.EUR, Currency.SAR)) for (authority in listOf(CorrectionAuthority.NONE, CorrectionAuthority.USER_FINANCIAL_TYPE)) {
            val changed = base.copy(revision = PostingRevision(1, 0), authority = authority, amount = Money.of("42.35", currency))
            assertEquals(FinancialPostingResult.Conflict(PostingConflict.IMMUTABLE_FX), room.post(changed))
            assertEquals(reference.post(changed), room.post(changed))
        }
        val sameCurrency = base.copy(revision = PostingRevision(0, 1), amount = Money.of("43", Currency.USD))
        assertEquals(reference.post(sameCurrency), room.post(sameCurrency))
        val newPair = base.copy(revision = PostingRevision(1, 0), authority = CorrectionAuthority.USER_FINANCIAL_TYPE,
            amount = Money.of("42.35", Currency.EUR), appliedFx = AppliedFx(BigDecimal("4.100000000123456789"), "new-eur-rate"))
        assertEquals(reference.post(newPair), room.post(newPair))
        assertEquals(3, room.history(base.key).size)
    }

    @Test fun claimsFromOlderRevisionsRemainReservedAcrossReopen() = runBlocking {
        val room = RoomBetaFinancialLedger(db)
        val base = input(evidence = setOf("old", "kept"))
        assertTrue(room.post(base) is FinancialPostingResult.Applied)
        assertTrue(room.post(base.copy(revision = PostingRevision(0, 1), evidenceIds = setOf("kept"))) is FinancialPostingResult.Applied)
        reopen()
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED),
            RoomBetaFinancialLedger(db).post(input("independent", evidence = setOf("old"))))
        assertEquals(2, RoomBetaFinancialLedger(db).history(base.key).size)
    }

    private fun pair(prefix: String, user: Boolean = false): MatchedSelfTransfer {
        val first = input("$prefix-out").copy(storedType = FinancialType.SELF_TRANSFER, destination = FinancialEndpoint.Owned(other.id))
        val second = first.copy(key = PostingKey(Environment.BETA, "$prefix-in"), evidenceIds = setOf("$prefix-in-evidence"),
            time = first.time.copy(instant = first.time.instant.plusSeconds(30)))
        val reference = ConfirmedBankTransferReference(bank, "bank-proof")
        val proof = if (user) TransferMatchProof.UserPair("user-proof", first.key, first.revision, first.evidenceIds,
            second.key, second.revision, second.evidenceIds) else TransferMatchProof.BankReference(reference)
        return (SelfTransferMatcher.match(listOf(TransferObservation(first, TransferDirection.OUTBOUND, reference),
            TransferObservation(second, TransferDirection.INBOUND, reference)), proof) as TransferMatchResult.Matched).transfer
    }

    @Test fun bankPairProvenanceAndProofClaimsSurviveReopenAndRevision() = runBlocking {
        val transfer = pair("first")
        val reference = InMemoryFinancialPostingLedger()
        val room = RoomBetaFinancialLedger(db)
        assertEquals(reference.post(transfer), room.post(transfer))
        reopen()
        assertEquals(reference.read(transfer.input.key), RoomBetaFinancialLedger(db).read(transfer.input.key))
        assertEquals(reference.post(pair("second")), RoomBetaFinancialLedger(db).post(pair("second")))
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.MATCH_ALREADY_CLAIMED), RoomBetaFinancialLedger(db).post(pair("third")))
        // An explicit correction changes the current type, but never releases the old proof.
        val correction = transfer.input.copy(revision = PostingRevision(1, 0), authority = CorrectionAuthority.USER_FINANCIAL_TYPE,
            storedType = FinancialType.EXPENSE, destination = FinancialEndpoint.External)
        assertTrue(RoomBetaFinancialLedger(db).post(correction) is FinancialPostingResult.Applied)
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.MATCH_ALREADY_CLAIMED), RoomBetaFinancialLedger(db).post(pair("fourth")))
        assertEquals(2, RoomBetaFinancialLedger(db).history(transfer.input.key).size)
    }

    @Test fun userPairNullIssuerIsAnActualUniqueClaimAcrossInstances() = runBlocking {
        val first = pair("first", user = true)
        assertTrue(RoomBetaFinancialLedger(db).post(first) is FinancialPostingResult.Applied)
        reopen()
        assertEquals(FinancialPostingResult.Conflict(PostingConflict.MATCH_ALREADY_CLAIMED), RoomBetaFinancialLedger(db).post(pair("second", user = true)))
        assertEquals("first-out", db.ledgerDao().proofOwner("BETA", "USER_PAIR", "", "user-proof"))
    }

    @Test fun exceptionAfterEveryWriteRollsBackClaimsCurrentHistoryAndCanRetry() = runBlocking {
        val transfer = pair("failure")
        val failure = RoomBetaFinancialLedger(db) { error("injected before commit") }
        try { failure.post(transfer); fail("Expected injected failure") } catch (_: IllegalStateException) { }
        reopen()
        val room = RoomBetaFinancialLedger(db)
        assertNull(room.read(transfer.input.key)); assertTrue(room.history(transfer.input.key).isEmpty())
        transfer.input.evidenceIds.forEach { assertNull(db.ledgerDao().evidenceOwner("BETA", it)) }
        assertNull(db.ledgerDao().proofOwner("BETA", "BANK_REFERENCE", bank.value, "bank-proof"))
        assertTrue(room.post(transfer) is FinancialPostingResult.Applied)
        assertTrue(room.post(transfer) is FinancialPostingResult.AlreadyApplied)
        assertEquals(1, room.history(transfer.input.key).size)
    }

    @Test fun failedCorrectionPreservesPreviousManualResultAndDoesNotReserveNewEvidence() = runBlocking {
        val room = RoomBetaFinancialLedger(db)
        val first = input().copy(revision = PostingRevision(1, 0), authority = CorrectionAuthority.USER_FINANCIAL_TYPE)
        val stored = (room.post(first) as FinancialPostingResult.Applied).posting
        val next = first.copy(revision = PostingRevision(2, 0), evidenceIds = setOf("new"), amount = Money.of("91", Currency.SAR))
        try { RoomBetaFinancialLedger(db) { error("rollback update") }.post(next); fail() } catch (_: IllegalStateException) { }
        reopen()
        assertEquals(stored, RoomBetaFinancialLedger(db).read(first.key))
        assertEquals(listOf(stored), RoomBetaFinancialLedger(db).history(first.key))
        assertNull(db.ledgerDao().evidenceOwner("BETA", "new"))
        assertTrue(RoomBetaFinancialLedger(db).post(next) is FinancialPostingResult.Applied)
        assertEquals("transaction", db.ledgerDao().evidenceOwner("BETA", first.evidenceIds.single()))
    }

    @Test fun codecPreservesLoanIncomeCardParentNegativeMetricsAndIndependentPairObservations() = runBlocking {
        val card = OwnedAsset(account.id.copy(localId = "card"), AssetKind.DEBIT_CARD, "9999", account.id)
        val loan = OwnedAsset(account.id.copy(localId = "loan"), AssetKind.LOAN, "8888")
        BetaAssetRegistry(db).register(card); BetaAssetRegistry(db).register(loan)
        val registry = assets + mapOf(card.id to card, loan.id to loan)
        val cases = listOf(
            input("refund").copy(storedType = FinancialType.REFUND, source = FinancialEndpoint.External,
                destination = FinancialEndpoint.Owned(card.id), assets = registry),
            input("loan").copy(storedType = FinancialType.FEE, financingLoan = loan.id, assets = registry),
            input("income").copy(storedType = FinancialType.EXTERNAL_TRANSFER_IN, source = FinancialEndpoint.External,
                destination = FinancialEndpoint.Owned(account.id), incomeAttribution = IncomeAttribution.OTHER, assets = registry))
        val reference = InMemoryFinancialPostingLedger()
        for (request in cases) {
            val result = reference.post(request)
            assertTrue(result is FinancialPostingResult.Applied)
            assertEquals(result, RoomBetaFinancialLedger(db).post(request))
        }
        val transfer = pair("paired")
        assertEquals(reference.post(transfer), RoomBetaFinancialLedger(db).post(transfer))
        reopen()
        for (request in cases) assertEquals(reference.read(request.key), RoomBetaFinancialLedger(db).read(request.key))
        assertEquals(reference.read(transfer.input.key), RoomBetaFinancialLedger(db).read(transfer.input.key))
        assertTrue(RoomBetaFinancialLedger(db).read(cases.first().key)!!.classification.metrics.values.any { it.amount.signum() < 0 })
    }

    @Test fun independentAdaptersRaceForSameEvidenceWithoutPartialResults() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((0 until 16).map { index -> Callable {
                runBlocking { RoomBetaFinancialLedger(db).post(input("race-$index", evidence = setOf("shared"))) }
            } }).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is FinancialPostingResult.Applied })
            assertEquals(15, results.count { it == FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED) })
            runBlocking {
                val winner = (results.single { it is FinancialPostingResult.Applied } as FinancialPostingResult.Applied).posting
                assertEquals(winner.command.key.transactionId, db.ledgerDao().evidenceOwner("BETA", "shared"))
                for (index in 0 until 16) assertEquals(if (winner.command.key.transactionId == "race-$index") 1 else 0,
                    RoomBetaFinancialLedger(db).history(input("race-$index").key).size)
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun simultaneousIdenticalRetriesCreateOneRevision() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val request = input()
            val results = executor.invokeAll((0 until 16).map { Callable { runBlocking { RoomBetaFinancialLedger(db).post(request) } } }).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is FinancialPostingResult.Applied })
            assertEquals(15, results.count { it is FinancialPostingResult.AlreadyApplied })
            runBlocking { assertEquals(1, RoomBetaFinancialLedger(db).history(request.key).size) }
        } finally { executor.shutdownNow() }
    }

    @Test fun rejectedOrUnregisteredInputsNeverWriteAndCannotCreateOwnership() = runBlocking {
        val room = RoomBetaFinancialLedger(db)
        assertEquals(FinancialPostingResult.Review(setOf(ReviewReason.VALIDATION)), room.post(input().copy(validatedParse = false)))
        assertEquals(FinancialPostingResult.Review(setOf(ReviewReason.AMBIGUOUS_MATCH)), room.post(input().copy(storedType = FinancialType.SELF_TRANSFER)))
        val forged = OwnedAsset(account.id.copy(localId = "not-confirmed"), AssetKind.ACCOUNT, "1234")
        val request = input().copy(source = FinancialEndpoint.Owned(forged.id), assets = mapOf(forged.id to forged))
        assertEquals(FinancialPostingResult.Review(setOf(ReviewReason.OWNERSHIP)), room.post(request))
        assertNull(room.read(request.key)); assertTrue(room.history(request.key).isEmpty())
        assertNull(db.ledgerDao().evidenceOwner("BETA", request.evidenceIds.single()))
        try { room.post(input().copy(key = PostingKey(Environment.MIGRATION_REHEARSAL, "forbidden"))); fail() } catch (_: IllegalArgumentException) { }
        val wrongNamespace = Room.databaseBuilder(context, BetaDatabase::class.java, "ledger-not-beta-test.db").build()
        try {
            try { RoomBetaFinancialLedger(wrongNamespace); fail("Non-Beta database accepted") } catch (_: IllegalArgumentException) { }
        } finally { wrongNamespace.close(); context.deleteDatabase("ledger-not-beta-test.db") }
    }

    @Test fun decodedCollectionsAndCallerCollectionsCannotMutateStoredFinancialFacts() = runBlocking {
        val evidence = mutableSetOf("original")
        val registry = assets.toMutableMap()
        val request = input(evidence = evidence).copy(assets = registry)
        val room = RoomBetaFinancialLedger(db)
        room.post(request); evidence += "later"; registry.clear()
        val stored = room.read(request.key)!!
        assertEquals(setOf("original"), stored.command.evidenceIds)
        try { (stored.classification.input.assets as MutableMap<AssetId, OwnedAsset>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (stored.command.evidenceIds as MutableSet<String>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (stored.classification.metrics as MutableMap<SummaryMetric, SignedAmount>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (stored.classification.accountMovements as MutableList<AccountMovement>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        try { (room.history(request.key) as MutableList<StoredFinancialPosting>).clear(); fail() } catch (_: UnsupportedOperationException) { }
        assertEquals(stored, room.read(request.key))
    }

    @Test fun corruptedOrUnknownPayloadFailsClosedWithoutOverwritingExistingResult() = runBlocking {
        val request = input()
        val room = RoomBetaFinancialLedger(db)
        room.post(request)
        val original = db.ledgerDao().current("BETA", "transaction")!!
        for (payload in listOf(original.payload.replace("BETA_LEDGER_1", "BETA_LEDGER_999"), original.payload.dropLast(1) + "X")) {
            db.ledgerDao().writeCurrent(original.copy(payload = payload))
            try { room.read(request.key); fail("Expected corruption rejection") } catch (_: IllegalStateException) { }
            try { room.post(request.copy(revision = PostingRevision(0, 1))); fail("Expected corruption rejection") } catch (_: IllegalStateException) { }
            assertEquals(payload, db.ledgerDao().current("BETA", "transaction")!!.payload)
            assertEquals(1, room.history(request.key).size)
        }
    }

    @Test fun betaBackupRestoreIncludesHistoryAndOldClaimsAndResetClearsOnlyBeta() = runBlocking {
        db.close()
        val request = input(evidence = setOf("old", "kept"))
        BetaContainer(context).use { beta ->
            assertTrue(beta.postFinancial(request) is FinancialPostingResult.Applied)
            assertTrue(beta.postFinancial(request.copy(revision = PostingRevision(1, 0), evidenceIds = setOf("kept"),
                authority = CorrectionAuthority.USER_FINANCIAL_TYPE)) is FinancialPostingResult.Applied)
            val current = beta.financialPosting(request.key)
            beta.backup()
            assertTrue(beta.postFinancial(input("after-backup")) is FinancialPostingResult.Applied)
            assertTrue(beta.restore())
            assertEquals(current, beta.financialPosting(request.key))
            assertEquals(2, beta.financialHistory(request.key).size)
            assertNull(beta.financialPosting(input("after-backup").key))
            assertEquals(FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED), beta.postFinancial(input("reuse", evidence = setOf("old"))))
            beta.reset(); assertNull(beta.financialPosting(request.key)); assertTrue(beta.financialHistory(request.key).isEmpty())
            beta.registerAsset(account); beta.registerAsset(other)
            assertTrue(beta.postFinancial(request) is FinancialPostingResult.Applied)
        }
        db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
    }
}
