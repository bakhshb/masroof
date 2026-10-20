package com.baraa.masroof.lab.beta.application.dashboard

import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.value.Environment
import com.baraa.masroof.lab.finance.value.Money
import com.baraa.masroof.lab.finance.posting.FinancialType
import java.util.Locale
import java.time.*
import java.time.temporal.ChronoUnit

/** Persisted current payment facts; never a financial posting command. */
internal data class BetaCommitmentPayment(val id: String,val type: FinancialType,val amount: Money,val at: Instant,
    val merchant: String?=null,val counterparty: String?=null)
/** Source recurrence, payment consumption and salary-cycle pause rules over persisted facts. */
internal object BetaCommitmentOccurrenceCalculator {
    fun rows(draft: BetaDashboardConfigurationDraft,period: DisplayPeriod?,payments: List<BetaCommitmentPayment> = emptyList()): List<CommitmentRow> {
        if(period==null || draft.commitments.isEmpty()) return emptyList()
        val first=period.from.atZone(period.zone).toLocalDate();val end=period.until.atZone(period.zone).toLocalDate()
        require(ChronoUnit.DAYS.between(first,end) in 1..95)
        require(payments.map {it.id}.distinct().size==payments.size) {"Duplicate commitment payment identity"}
        val consumed=hashSetOf<String>()
        return BetaCommitmentLists.ordered(draft.commitments).flatMap {row ->
            val occurrences=if(!visible(row,first,period.zone,draft.salary?.day ?: 27)) emptyList() else dates(row,first,end)
            if(occurrences.isEmpty()) emptyList() else {
                val matching=payments.filter {payment ->
                    payment.at>=period.from && payment.at<period.until && payment.id !in consumed &&
                        payment.type in setOf(FinancialType.EXPENSE,FinancialType.FEE,FinancialType.BILL_PAYMENT) &&
                        payment.amount.amount.signum()>0 && payment.amount.currency==row.amount.currency &&
                        (payment.id==row.sourceTransactionId || (payment.merchant?.trim() ?: payment.counterparty?.trim())
                            ?.lowercase(Locale.ROOT)==row.label.trim().lowercase(Locale.ROOT))
                }.sortedBy {it.at}
                // Source consumes every matching payment once, even when there are fewer occurrences.
                consumed.addAll(matching.map {it.id})
                occurrences.mapIndexed {index,date ->
                    val month=YearMonth.from(date)
                    val payment=matching.getOrNull(index)
                    val paid=payment!=null || (row.recurrence==BetaCommitmentRecurrence.MONTHLY && month in row.paidMonths) ||
                        (row.recurrence==null && row.sourceTransactionId!=null && row.anchorDate>=first && row.anchorDate<end)
                    CommitmentRow(Environment.BETA,"user:${row.id}:${if(row.transactionDate==null && row.recurrence==BetaCommitmentRecurrence.MONTHLY) month else date}",row.label,
                        payment?.amount ?: row.amount,payment?.at?.atZone(period.zone)?.toLocalDate() ?: row.dueDate ?: date,
                        if(paid) PaymentStatus.PAID else PaymentStatus.UNPAID,userCommitmentId=row.id)
                }
            }
        }
    }

    fun dates(row: BetaRecurringCommitment,first: LocalDate,end: LocalDate): List<LocalDate> {
        require(first<end && ChronoUnit.DAYS.between(first,end)<=95)
        val anchor=row.anchorDate
        val result=when(row.recurrence) {
            null->listOf(anchor)
            BetaCommitmentRecurrence.WEEKLY-> {
                val weeks=if(first<=anchor) 0L else (ChronoUnit.DAYS.between(anchor,first)+6)/7
                generateSequence(anchor.plusWeeks(weeks)) {it.plusWeeks(1)}.takeWhile {it<end}.toList()
            }
            else-> {
                val step=if(row.recurrence==BetaCommitmentRecurrence.YEARLY) 12L else 1L
                generateSequence(YearMonth.from(first)) {it.plusMonths(1)}.takeWhile {it<=YearMonth.from(end.minusDays(1))}.mapNotNull {month ->
                    val offset=ChronoUnit.MONTHS.between(YearMonth.from(anchor),month)
                    if(offset<0 || offset%step!=0L) null else month.atDay(minOf(row.transactionDate?.dayOfMonth ?: row.dueDay,month.lengthOfMonth()))
                }.toList()
            }
        }
        return result.filter {it>=first && it<end && YearMonth.from(it)>=row.starts && (row.ends==null || YearMonth.from(it)<=row.ends)}
    }
    private fun visible(row: BetaRecurringCommitment,start: LocalDate,zone: ZoneId,day: Int): Boolean {
        if(!row.active && row.pauseIntervals.isEmpty()) return false
        fun cycle(date: LocalDate): LocalDate {
            fun at(month: YearMonth): LocalDate {
                val raw=month.atDay(minOf(day,month.lengthOfMonth()))
                return if(day!=27) raw else when(raw.dayOfWeek) {DayOfWeek.FRIDAY->raw.minusDays(1);DayOfWeek.SATURDAY->raw.plusDays(1);else->raw}
            }
            val month=YearMonth.from(date);val candidate=at(month)
            return if(date>=candidate) candidate else at(month.minusMonths(1))
        }
        return row.pauseIntervals.none {pause -> val from=cycle(pause.pausedAt.atZone(zone).toLocalDate())
            val until=pause.resumedAt?.let {cycle(it.atZone(zone).toLocalDate())}
            start>=from && (until==null || start<until)
        }
    }
}
