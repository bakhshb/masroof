package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.corrections.FinancialDecision
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.BetaDashboardBuilder
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.time.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Physical SQLite proof for source-bound native currency and old, separately scoped FX facts. */
class BetaForeignCommitmentDeviceTest {
    @Test fun createAndEditRetainNativeMoneyWhileOldSourceValuesOnlyTheCurrentDefinition()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();BetaContainer.resetOffline(context)
        val queries=CopyOnWriteArrayList<String>()
        val db=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME)
            .setQueryCallback({sql,_ ->queries+=sql},Executor {it.run()}).build()
        try {
            val bank=BankId("BANK_ALJAZIRA");val zone=ZoneId.of("Asia/Riyadh")
            val clock=Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"),zone)
            val account=OwnedAsset(AssetId(Environment.BETA,bank,"foreign-owner"),AssetKind.ACCOUNT,"3001")
            db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value,"ENABLED"));BetaAssetRegistry(db).register(account)
            val key=PostingKey(Environment.BETA,"old-foreign-source")
            val input=FinancialClassificationInput(key,PostingRevision(0,1),FinancialType.EXPENSE,Money.of("20",Currency.USD),
                TransactionTime(Instant.parse("2020-01-05T12:00:00Z"),zone),FinancialEndpoint.Owned(account.id),FinancialEndpoint.External,
                mapOf(account.id to account),setOf("foreign-evidence"),true,appliedFx=AppliedFx("3.75".toBigDecimal(),"HISTORICAL_MERCHANT"))
            val ledger=RoomBetaFinancialLedger(db);assertTrue(ledger.post(input) is FinancialPostingResult.Applied)
            val review=BetaReviewWorkflow(db)
            assertTrue(review.save(review.detail(key)!!.token,"name",FinancialDecision.ResolveFinancialType(FinancialType.EXPENSE),mapOf("merchant" to "Internet")) is BetaCorrectionResult.Applied)
            val history=ledger.history(key);val workflow=BetaCommitmentCreationWorkflow(db,clock)
            val preview=workflow.preview(key)!!
            assertTrue(workflow.create(preview) is BetaCommitmentCreationResult.Created)
            assertTrue(workflow.create(preview) is BetaCommitmentCreationResult.AlreadyExists)
            val controls=RoomBetaDashboardConfiguration(db);var config=controls.read()
            assertEquals(Currency.USD,config.draft.commitments.single().amount.currency)
            config=(controls.save(config.revision,config.draft.copy(salary=BetaSalaryCycle(27),commitments=config.draft.commitments.map {it.copy(amount=Money.of("25",Currency.USD))})) as BetaDashboardConfigurationWrite.Saved).configuration
            val query=DashboardQuery(Environment.BETA,Instant.parse("2026-09-30T21:00:00Z"),Instant.parse("2026-10-31T21:00:00Z"),zone,Currency.SAR)
            queries.clear()
            val read=RoomBetaDashboardSource(db,clock=clock).load(query)
            val summary=BetaDashboardBuilder.build(read).snapshot.commitments!!
            assertTrue(read.rows.isEmpty());assertTrue(read.coverage.completeUserCommitments)
            assertEquals(Money.of("93.75",Currency.SAR),summary.remaining)
            assertFalse(queries.any {it.trimStart().matches(Regex("(?is)(INSERT|UPDATE|DELETE|ALTER|CREATE|REPLACE).*"))})
            assertEquals(history,ledger.history(key));assertEquals(Money.of("25",Currency.USD),controls.read().draft.commitments.single().amount)
            val small=controls.read()
            assertTrue(controls.save(small.revision,small.draft.copy(commitments=small.draft.commitments.map {it.copy(amount=Money.of("0.01",Currency.USD))})) is BetaDashboardConfigurationWrite.Saved)
            assertEquals(Money.of("0.04",Currency.SAR),BetaDashboardBuilder.build(RoomBetaDashboardSource(db,clock=clock).load(query)).snapshot.commitments!!.remaining)
            val latest=review.detail(key)!!.token
            assertTrue(review.save(latest,"suppress",FinancialDecision.SuppressAsNonFinancial) is BetaCorrectionResult.Applied)
            val missing=RoomBetaDashboardSource(db,clock=clock).load(query)
            assertTrue(missing.rows.isEmpty());assertFalse(missing.coverage.completeUserCommitments)
            assertNull(BetaDashboardBuilder.build(missing).snapshot.commitments!!.total)
        } finally {db.close();BetaContainer.resetOffline(context)}
    }
}
