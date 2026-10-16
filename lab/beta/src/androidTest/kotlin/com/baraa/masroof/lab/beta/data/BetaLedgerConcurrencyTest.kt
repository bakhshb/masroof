package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android SQLite locking, not Robolectric's legacy sqlite4java connection emulation. */
@RunWith(AndroidJUnit4::class)
class BetaLedgerConcurrencyTest {
    @Test fun independentRoomDatabaseInstancesRaceForEvidenceWithoutPartialFinancialRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BetaContainer.resetOffline(context)
        val first = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        val second = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        val executor = Executors.newFixedThreadPool(4)
        val asset = OwnedAsset(AssetId(Environment.BETA, BankId("BANK_ALJAZIRA"), "account"), AssetKind.ACCOUNT, "1234")
        fun input(index: Int) = FinancialClassificationInput(PostingKey(Environment.BETA, "race-$index"), PostingRevision(0, 0),
            FinancialType.EXPENSE, Money.of("42.35", Currency.SAR), TransactionTime(Instant.parse("2026-10-17T12:00:00Z"), ZoneId.of("Asia/Riyadh")),
            FinancialEndpoint.Owned(asset.id), FinancialEndpoint.External, mapOf(asset.id to asset), setOf("shared-evidence"), true)
        try {
            runBlocking { BetaAssetRegistry(first).register(asset) }
            // Both helpers are opened before contention; schema creation is not the race under test.
            second.openHelper.writableDatabase
            val results = executor.invokeAll((0 until 16).map { index -> Callable {
                runBlocking { RoomBetaFinancialLedger(if (index % 2 == 0) first else second).post(input(index)) }
            } }).map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is FinancialPostingResult.Applied })
            assertEquals(15, results.count { it == FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED) })
            runBlocking {
                val winner = (results.single { it is FinancialPostingResult.Applied } as FinancialPostingResult.Applied).posting.command.key.transactionId
                assertEquals(winner, first.ledgerDao().evidenceOwner("BETA", "shared-evidence"))
                for (index in 0 until 16) assertEquals(if (winner == "race-$index") 1 else 0, RoomBetaFinancialLedger(second).history(input(index).key).size)
            }
            first.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            first.openHelper.readableDatabase.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
        } finally { executor.shutdownNow(); first.close(); second.close(); BetaContainer.resetOffline(context) }
    }
}
