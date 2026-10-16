package com.baraa.masroof.lab.beta.processdeath

import android.content.Context
import android.os.Process
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.matching.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** External script force-stops the actual synthetic test process, before/after Room COMMIT. */
@RunWith(AndroidJUnit4::class)
class BetaLedgerProcessDeathTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val asset = OwnedAsset(AssetId(Environment.BETA, BankId("BANK_ALJAZIRA"), "ledger-account"), AssetKind.ACCOUNT, "1234")
    private val request = FinancialClassificationInput(PostingKey(Environment.BETA, "durable-transaction"), PostingRevision(1, 0),
        FinancialType.EXPENSE, Money.of("42.35", Currency.USD),
        TransactionTime(Instant.parse("2026-10-17T12:00:00.123456789Z"), ZoneId.of("Asia/Riyadh")),
        FinancialEndpoint.Owned(asset.id), FinancialEndpoint.External, mapOf(asset.id to asset), setOf("synthetic-ledger-evidence"),
        false, CorrectionAuthority.USER_FINANCIAL_TYPE, appliedFx = AppliedFx(BigDecimal("3.750000012345678900"), "confirmed-rate"))
    private val other = OwnedAsset(asset.id.copy(localId = "second-account"), AssetKind.ACCOUNT, "5678")
    private fun transfer(): MatchedSelfTransfer {
        val out = request.copy(storedType = FinancialType.SELF_TRANSFER, destination = FinancialEndpoint.Owned(other.id),
            assets = mapOf(asset.id to asset, other.id to other))
        val incoming = out.copy(key = PostingKey(Environment.BETA, "incoming-observation"), evidenceIds = setOf("incoming-evidence"))
        val reference = ConfirmedBankTransferReference(asset.id.bank, "durable-proof")
        return (SelfTransferMatcher.match(listOf(TransferObservation(out, TransferDirection.OUTBOUND, reference),
            TransferObservation(incoming, TransferDirection.INBOUND, reference)), TransferMatchProof.BankReference(reference)) as TransferMatchResult.Matched).transfer
    }
    private fun markPid() {
        check(context.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("beta_ledger_arm_pid", Process.myPid().toString()).commit())
    }
    private fun assertNewProcess() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("betaResume"))
        assertNotEquals(context.getSharedPreferences(BetaEnvironment.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("beta_ledger_arm_pid", null), Process.myPid().toString())
    }

    @Test fun armBeforeCommit() {
        BetaContainer.resetOffline(context)
        BetaContainer(context).use { it.registerAsset(asset); it.registerAsset(other) }
        markPid()
        val entered = CountDownLatch(1)
        val db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        Thread {
            runBlocking { RoomBetaFinancialLedger(db) {
                entered.countDown()
                CountDownLatch(1).await() // The script kills the UID with all four writes uncommitted.
            }.post(transfer()) }
        }.apply { isDaemon = true; start() }
        assertTrue(entered.await(15, TimeUnit.SECONDS))
    }

    @Test fun resumeBeforeCommit() {
        assertNewProcess()
        val db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        try { runBlocking {
            assertNull(db.ledgerDao().proofOwner("BETA", "BANK_REFERENCE", asset.id.bank.value, "durable-proof"))
            assertNull(db.ledgerDao().evidenceOwner("BETA", "incoming-evidence"))
            assertNull(db.ledgerDao().evidenceOwner("BETA", "synthetic-ledger-evidence"))
        } } finally { db.close() }
        BetaContainer(context).use { beta ->
            assertNull(beta.financialPosting(request.key)); assertTrue(beta.financialHistory(request.key).isEmpty())
            assertTrue(beta.postFinancial(transfer()) is FinancialPostingResult.Applied)
            assertTrue(beta.postFinancial(transfer()) is FinancialPostingResult.AlreadyApplied)
            assertEquals(1, beta.financialHistory(request.key).size)
        }
    }

    @Test fun armAfterCommit() {
        BetaContainer.resetOffline(context)
        BetaContainer(context).use { beta ->
            beta.registerAsset(asset)
            assertTrue(beta.postFinancial(request) is FinancialPostingResult.Applied)
            beta.backup()
        }
        markPid()
    }

    @Test fun resumeAfterCommit() {
        assertNewProcess()
        BetaContainer(context).use { beta ->
            val stored = beta.financialPosting(request.key)!!
            assertEquals(request, stored.classification.input)
            assertEquals(FinancialPostingResult.AlreadyApplied(stored), beta.postFinancial(request))
            assertEquals(1, beta.financialHistory(request.key).size)
            assertEquals(FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED),
                beta.postFinancial(request.copy(key = PostingKey(Environment.BETA, "duplicate"))))
            assertTrue(beta.restore())
            assertEquals(stored, beta.financialPosting(request.key))
            assertTrue(beta.postFinancial(request) is FinancialPostingResult.AlreadyApplied)
            assertEquals(1, beta.financialHistory(request.key).size)
        }
    }
}
