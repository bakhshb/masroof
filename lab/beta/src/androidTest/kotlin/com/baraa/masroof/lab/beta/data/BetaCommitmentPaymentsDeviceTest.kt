package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
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

/** Native SQLite: current posted money pays the definition; reading never creates a payment. */
class BetaCommitmentPaymentsDeviceTest {
    @Test fun sarSourcePaymentUsesActualAmountWithoutFinancialWrites()=verify(Currency.SAR,null,"125","125")
    @Test fun storedForeignRatePaysInSarWithoutLookingUpAnotherRate()=verify(Currency.USD,AppliedFx("3.75".toBigDecimal(),"HISTORICAL_MERCHANT"),"20","75")
    @Test fun unresolvedForeignPaymentKeepsSummaryUnavailableInsteadOfInventingUnpaid()=verify(Currency.USD,null,"20",null)
    @Test fun smsRateWithoutFeeEvidenceCannotBecomeAnAssumedCompletePayment()=
        verify(Currency.USD,AppliedFx("3.75".toBigDecimal(),"SMS"),"20",null)
    @Test fun explicitDueOutsideSalaryDoesNotCrashOrExtendTheFinancialRead()=
        verify(Currency.SAR,null,"125","100",noPayment=true,dueDate=LocalDate.of(2026,11,5))
    private fun verify(currency: Currency,fx: AppliedFx?,amount: String,expected: String?,noPayment: Boolean=false,dueDate: LocalDate?=null)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();BetaContainer.resetOffline(context)
        val sql=CopyOnWriteArrayList<String>()
        val db=Room.databaseBuilder(context,BetaDatabase::class.java,BetaDatabase.NAME)
            .setQueryCallback({query,_ ->sql+=query},Executor {it.run()}).build()
        try {
            val zone=ZoneId.of("Asia/Riyadh");val bank=BankId("BANK_ALJAZIRA")
            val account=OwnedAsset(AssetId(Environment.BETA,bank,"owned"),AssetKind.ACCOUNT,"3001")
            db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value,"ENABLED"));BetaAssetRegistry(db).register(account)
            val key=PostingKey(Environment.BETA,"source")
            val input=FinancialClassificationInput(key,PostingRevision(0,1),FinancialType.EXPENSE,Money.of(amount,currency),
                TransactionTime(Instant.parse("2026-09-30T12:00:00Z"),zone),FinancialEndpoint.Owned(account.id),FinancialEndpoint.External,
                mapOf(account.id to account),setOf("evidence"),true,appliedFx=fx)
            if(!noPayment) assertTrue(RoomBetaFinancialLedger(db).post(input) is FinancialPostingResult.Applied)
            val definition=BetaRecurringCommitment("rent","Renamed source",Money.of("100",Currency.SAR),5,YearMonth.of(2026,10),sourceTransactionId=key.transactionId,dueDate=dueDate)
            assertTrue(RoomBetaDashboardConfiguration(db).save(0,BetaDashboardConfigurationDraft(BetaSalaryCycle(27),commitments=listOf(definition))) is BetaDashboardConfigurationWrite.Saved)
            val query=DashboardQuery(Environment.BETA,Instant.parse("2026-09-30T21:00:00Z"),Instant.parse("2026-10-31T21:00:00Z"),zone,Currency.SAR)
            val source=RoomBetaDashboardSource(db,clock=Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"),zone))
            sql.clear()
            repeat(2) {
                val snapshot=BetaDashboardBuilder.build(source.load(query)).snapshot
                val summary=snapshot.commitments
                if(noPayment) {
                    assertEquals(PaymentStatus.UNPAID,summary!!.rows.single().status)
                    assertEquals(dueDate,summary.rows.single().expectedDate)
                    assertEquals(Money.of("100",Currency.SAR),summary.remaining)
                    assertTrue(snapshot.transactions.isEmpty())
                } else if(expected==null) assertNull(summary) else {
                    assertEquals(PaymentStatus.PAID,summary!!.rows.single().status)
                    assertEquals(Money.of(expected,Currency.SAR),summary.paid)
                    assertEquals(LocalDate.of(2026,9,30),summary.rows.single().expectedDate)
                }
            }
            assertFalse(sql.any {it.trimStart().matches(Regex("(?is)(INSERT|UPDATE|DELETE|ALTER|CREATE|REPLACE).*"))})
        } finally {db.close();BetaContainer.resetOffline(context)}
    }
}
