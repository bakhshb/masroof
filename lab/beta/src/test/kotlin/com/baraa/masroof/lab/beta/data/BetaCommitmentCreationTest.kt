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
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class BetaCommitmentCreationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BetaDatabase
    private val zone=ZoneId.of("Asia/Riyadh")
    private val clock=Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"),zone)
    private val bank=BankId("BANK_ALJAZIRA")
    private val account=OwnedAsset(AssetId(Environment.BETA,bank,"account"),AssetKind.ACCOUNT,"3001")
    private val key=PostingKey(Environment.BETA,"source")
    @Before fun open()=runBlocking {
        BetaContainer.resetOffline(context);db=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME).build()
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value,"ENABLED"));BetaAssetRegistry(db).register(account)
    }
    @After fun close() {db.close();BetaContainer.resetOffline(context)}
    private fun workflow()=BetaCommitmentCreationWorkflow(db,clock)
    private suspend fun seed(currency: Currency=Currency.SAR,type: FinancialType=FinancialType.EXPENSE,fx: AppliedFx?=null) {
        val incoming=type==FinancialType.REFUND
        val input=FinancialClassificationInput(key,PostingRevision(0,1),type,Money.of("20",currency),
            TransactionTime(Instant.parse("2020-01-05T12:00:00Z"),zone),if(incoming) FinancialEndpoint.External else FinancialEndpoint.Owned(account.id),
            if(incoming) FinancialEndpoint.Owned(account.id) else FinancialEndpoint.External,mapOf(account.id to account),setOf("evidence"),true,appliedFx=fx)
        assertTrue(RoomBetaFinancialLedger(db).post(input) is FinancialPostingResult.Applied)
        rename(" Internet ",type)
    }
    private suspend fun rename(name: String,type: FinancialType=FinancialType.EXPENSE) {
        val review=BetaReviewWorkflow(db);val token=review.detail(key)!!.token
        assertTrue(review.save(token,"name-$name",FinancialDecision.ResolveFinancialType(type),mapOf("merchant" to name)) is BetaCorrectionResult.Applied)
    }
    @Test fun concurrentCreationIsIdempotentAndPreservesNativeSourceDateAndLedger()=runBlocking {
        seed();val before=RoomBetaFinancialLedger(db).history(key)
        val preview=workflow().preview(key)!!;assertTrue(preview.eligible)
        val results=coroutineScope {listOf(async {workflow().create(preview)},async {workflow().create(preview)}).awaitAll()}
        assertEquals(1,results.count {it is BetaCommitmentCreationResult.Created})
        assertEquals(1,results.count {it is BetaCommitmentCreationResult.AlreadyExists})
        val row=RoomBetaDashboardConfiguration(db).read().draft.commitments.single()
        assertEquals("Internet",row.label);assertEquals(Money.of("20",Currency.SAR),row.amount)
        assertEquals(LocalDate.of(2020,1,5),row.transactionDate);assertEquals(key.transactionId,row.sourceTransactionId)
        assertEquals(BetaCommitmentRecurrence.MONTHLY,row.recurrence)
        assertEquals(before,RoomBetaFinancialLedger(db).history(key))
    }
    @Test fun changingTheSourceAfterOpeningConfirmationCannotCreateUsingStaleFacts()=runBlocking {
        seed();val preview=workflow().preview(key)!!;rename("Changed")
        assertTrue(workflow().create(preview) is BetaCommitmentCreationResult.Rejected)
        assertTrue(RoomBetaDashboardConfiguration(db).read().draft.commitments.isEmpty())
    }
    @Test fun aRefundCannotCreateACommitmentEvenWithANameAndPositiveMoney()=runBlocking {
        seed(type=FinancialType.REFUND);val preview=workflow().preview(key)!!
        assertFalse(preview.eligible);assertTrue(workflow().create(preview) is BetaCommitmentCreationResult.Rejected)
    }
    @Test fun aSuppressedAuditSnapshotCannotBeUsedToCreateACommitment()=runBlocking {
        seed();val preview=workflow().preview(key)!!
        assertTrue(BetaReviewWorkflow(db).save(preview.token,"suppress",FinancialDecision.SuppressAsNonFinancial) is BetaCorrectionResult.Applied)
        assertNotNull(RoomBetaFinancialLedger(db).readAudit(key))
        assertNull(workflow().preview(key));assertTrue(workflow().create(preview) is BetaCommitmentCreationResult.Rejected)
        assertTrue(RoomBetaDashboardConfiguration(db).read().draft.commitments.isEmpty())
    }
    @Test fun anUnnamedPaymentDoesNotInventACommitmentName()=runBlocking {
        seed();rename("   ");val preview=workflow().preview(key)!!
        assertTrue(preview.eligible);assertTrue(workflow().create(preview) is BetaCommitmentCreationResult.Rejected)
        assertTrue(RoomBetaDashboardConfiguration(db).read().draft.commitments.isEmpty())
    }
    private suspend fun currentRead()=RoomBetaDashboardSource(db,clock=clock).load(
        DashboardQuery(Environment.BETA,Instant.parse("2026-09-30T21:00:00Z"),Instant.parse("2026-10-31T21:00:00Z"),zone,Currency.SAR))
    @Test fun foreignDefinitionUsesItsOldSourceRateWithoutAddingOldSpendingToCurrentScope()=runBlocking {
        seed(Currency.USD,fx=AppliedFx("3.75".toBigDecimal(),"HISTORICAL_MERCHANT"))
        assertTrue(workflow().create(workflow().preview(key)!!) is BetaCommitmentCreationResult.Created)
        val controls=RoomBetaDashboardConfiguration(db);val config=controls.read()
        assertEquals(Currency.USD,config.draft.commitments.single().amount.currency)
        assertTrue(controls.save(config.revision,config.draft.copy(salary=BetaSalaryCycle(27),commitments=config.draft.commitments.map {it.copy(amount=Money.of("25",Currency.USD))})) is BetaDashboardConfigurationWrite.Saved)
        val read=currentRead();val summary=BetaDashboardBuilder.build(read).snapshot.commitments!!
        assertTrue("Only valuation sources must leave financial rows: ${read.rows.map {it.posting.command.key}}",read.rows.isEmpty())
        assertTrue("Expected valuation; stored FX=${RoomBetaFinancialLedger(db).readAudit(key)?.command?.appliedFx}",read.coverage.completeUserCommitments)
        assertEquals(Money.of("93.75",Currency.SAR),summary.remaining)
        assertEquals(Money.of("0",Currency.SAR),summary.paid)
    }
    @Test fun smallForeignDefinitionRoundsOnceInsteadOfBecomingZeroAtTheRatioStep()=runBlocking {
        seed(Currency.USD,fx=AppliedFx("3.75".toBigDecimal(),"HISTORICAL_MERCHANT"))
        assertTrue(workflow().create(workflow().preview(key)!!) is BetaCommitmentCreationResult.Created)
        val controls=RoomBetaDashboardConfiguration(db);val cfg=controls.read()
        assertTrue(controls.save(cfg.revision,cfg.draft.copy(salary=BetaSalaryCycle(27),commitments=cfg.draft.commitments.map {it.copy(amount=Money.of("0.01",Currency.USD))})) is BetaDashboardConfigurationWrite.Saved)
        val read=currentRead();assertTrue(read.rows.isEmpty())
        assertEquals(Money.of("0.04",Currency.SAR),BetaDashboardBuilder.build(read).snapshot.commitments!!.remaining)
    }
    @Test fun aPausedForeignDefinitionDoesNotBlockCurrentTotalsWhenItsSourceIsUnavailable()=runBlocking {
        seed(Currency.USD);assertTrue(workflow().create(workflow().preview(key)!!) is BetaCommitmentCreationResult.Created)
        val controls=RoomBetaDashboardConfiguration(db);val cfg=controls.read()
        assertTrue(controls.save(cfg.revision,cfg.draft.copy(salary=BetaSalaryCycle(27),commitments=cfg.draft.commitments.map {it.copy(active=false)})) is BetaDashboardConfigurationWrite.Saved)
        val read=currentRead();assertTrue(read.coverage.completeUserCommitments)
        assertEquals(Money.of("0",Currency.SAR),BetaDashboardBuilder.build(read).snapshot.commitments!!.total)
    }
    @Test fun missingSourceRateIsUnknownInsteadOfZeroOrAnImplicitSarDefinition()=runBlocking {
        seed(Currency.USD);assertTrue(workflow().create(workflow().preview(key)!!) is BetaCommitmentCreationResult.Created)
        val controls=RoomBetaDashboardConfiguration(db);val cfg=controls.read()
        assertTrue(controls.save(cfg.revision,cfg.draft.copy(salary=BetaSalaryCycle(27))) is BetaDashboardConfigurationWrite.Saved)
        val read=currentRead();assertFalse(read.coverage.completeUserCommitments);assertTrue(read.rows.isEmpty())
        val summary=BetaDashboardBuilder.build(read).snapshot.commitments!!
        assertNull(summary.total);assertNull(summary.paid);assertNull(summary.remaining)
        assertEquals(Currency.USD,controls.read().draft.commitments.single().amount.currency)
    }
}
