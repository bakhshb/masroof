package com.baraa.masroof.lab.beta.application.dashboard

import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.FinancialType
import com.baraa.masroof.lab.finance.value.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class BetaCommitmentPaymentTest {
    private val zone=ZoneId.of("Asia/Riyadh")
    private val period=DisplayPeriod(LocalDate.of(2026,10,1).atStartOfDay(zone).toInstant(),LocalDate.of(2026,10,27).atStartOfDay(zone).toInstant(),zone,PeriodKind.SALARY,null)
    private fun row(id: String="one",name: String="Internet",weekly: Boolean=false)=BetaRecurringCommitment(id,name,Money.of("100",Currency.SAR),1,YearMonth.of(2026,10),
        recurrence=if(weekly) BetaCommitmentRecurrence.WEEKLY else BetaCommitmentRecurrence.MONTHLY,transactionDate=LocalDate.of(2026,10,1),sourceTransactionId="source")
    private fun payment(id: String="p",date: Int=5,amount: String="120",type: FinancialType=FinancialType.BILL_PAYMENT,name: String?=" Internet ")=
        BetaCommitmentPayment(id,type,Money.of(amount,Currency.SAR),LocalDate.of(2026,10,date).atStartOfDay(zone).toInstant(),name)
    private fun rows(definitions: List<BetaRecurringCommitment>,payments: List<BetaCommitmentPayment>)=BetaCommitmentOccurrenceCalculator.rows(BetaDashboardConfigurationDraft(commitments=definitions),period,payments)
    @Test fun namedPaymentUsesActualAmountAndLocalDateRatherThanExpectedAmount() {
        val result=rows(listOf(row()),listOf(payment())).single()
        assertEquals(PaymentStatus.PAID,result.status);assertEquals(Money.of("120",Currency.SAR),result.amount)
        assertEquals(LocalDate.of(2026,10,5),result.expectedDate)
    }
    @Test fun paymentIsConsumedOnceAcrossDefinitionsAndWeeklyOccurrencesArePaidChronologically() {
        val definitions=listOf(row("z"),row("a",weekly=true))
        val result=rows(definitions,listOf(payment("late",15,"130"),payment("early",3,"110")))
        assertEquals(listOf(Money.of("110",Currency.SAR),Money.of("130",Currency.SAR)),result.filter {it.status==PaymentStatus.PAID}.map {it.amount})
        assertEquals(2,result.count {it.status==PaymentStatus.PAID})
        assertEquals(PaymentStatus.UNPAID,result.single {it.userCommitmentId=="z"}.status)
    }
    @Test fun sourceIdMatchesRenamedDefinitionAndRefundTransferAndBoundaryRowsCannotPayIt() {
        val definition=row(name="Renamed")
        val candidates=listOf(payment("source",name="Old label"),payment("refund",type=FinancialType.REFUND),
            payment("transfer",type=FinancialType.EXTERNAL_TRANSFER_OUT),payment("boundary",27))
        assertEquals(PaymentStatus.PAID,rows(listOf(definition),candidates).single().status)
        assertEquals(PaymentStatus.UNPAID,rows(listOf(row()),candidates.drop(1)).single().status)
    }
    @Test fun aHistoricalMonthlyDecisionCannotMarkEveryNewWeeklyOccurrencePaid() {
        val definition=row(weekly=true).copy(paidMonths=setOf(YearMonth.of(2026,10)))
        val result=rows(listOf(definition),emptyList())
        assertEquals(4,result.size);assertTrue(result.all {it.status==PaymentStatus.UNPAID})
        assertEquals(setOf(YearMonth.of(2026,10)),definition.paidMonths)
    }
    @Test fun aPausedCycleDoesNotConsumeThePaymentOfAnotherVisibleDefinition() {
        val stopped=row("a").copy(active=false,pauseIntervals=listOf(BetaCommitmentPause(period.from)))
        val result=rows(listOf(stopped,row("z")),listOf(payment()))
        assertEquals(1,result.size);assertEquals("z",result.single().userCommitmentId);assertEquals(PaymentStatus.PAID,result.single().status)
    }
}
