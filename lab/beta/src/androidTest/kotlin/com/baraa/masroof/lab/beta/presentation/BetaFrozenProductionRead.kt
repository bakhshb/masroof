package com.baraa.masroof.lab.beta.presentation

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

/** Read-only UI fixture from production FidelityFixtures at 0d86c5d3. Not an extraction or ledger oracle. */
internal object BetaFrozenProductionRead {
    private val bank=BankId("BANK_ALJAZIRA")
    private fun id(value: String)=AssetId(Environment.BETA,bank,value)
    private fun money(value: String)=Money.of(value,Currency.SAR)
    private fun signed(value: String)=SignedAmount(BigDecimal(value),Currency.SAR)
    private data class Row(val id: Int,val title: String,val type: FinancialType,val amount: String,val date: String,
        val account: String?=null,val card: String?=null,val destination: String?=null)
    private val rows=listOf(
        Row(1,"الراتب",FinancialType.INCOME,"12000","2026-09-27","3001"),
        Row(2,"حوالة واردة",FinancialType.EXTERNAL_TRANSFER_IN,"1000","2026-09-28","3001"),
        Row(3,"سداد البطاقة الائتمانية",FinancialType.CREDIT_CARD_PAYMENT,"2200","2026-09-29","3001","7271"),
        Row(4,"القرض الشخصي",FinancialType.LOAN_REPAYMENT,"1800","2026-09-27","3001"),
        Row(5,"AMAZON",FinancialType.EXPENSE,"900","2026-10-03","3001","4421"),
        Row(6,"AMAZON",FinancialType.EXPENSE,"500","2026-10-07","3001","4421"),
        Row(7,"AMAZON",FinancialType.REFUND,"150","2026-10-09","3001","4421"),
        Row(8,"TAMIMI",FinancialType.EXPENSE,"200","2026-10-04","3001","4421"),
        Row(9,"TAMIMI",FinancialType.EXPENSE,"200","2026-10-08","3001","4421"),
        Row(10,"NOON",FinancialType.EXPENSE,"800","2026-10-02",card="7271"),
        Row(11,"NOON",FinancialType.EXPENSE,"600","2026-10-06",card="8842"),
        Row(12,"فاتورة الإنترنت",FinancialType.BILL_PAYMENT,"200","2026-10-05","3001"),
        Row(13,"فاتورة الكهرباء",FinancialType.BILL_PAYMENT,"400","2026-10-09","3001"),
        Row(14,"حوالة صادرة",FinancialType.EXTERNAL_TRANSFER_OUT,"500","2026-10-01","3001"),
        Row(15,"سحب نقدي",FinancialType.CASH_WITHDRAWAL,"400","2026-10-06","3001","4421"),
        Row(16,"رسوم بنكية",FinancialType.FEE,"50","2026-10-02","3001"),
        Row(17,"من الجاري إلى الادخار",FinancialType.SELF_TRANSFER,"1000","2026-10-08","3001",destination="5002"))
    fun snapshot(query: DashboardQuery): DashboardSnapshot {
        val period=DisplayPeriod(query.from,query.until,query.zone,PeriodKind.SALARY,null)
        val transactions=rows.map {r ->
            val inward=r.type in setOf(FinancialType.INCOME,FinancialType.EXTERNAL_TRANSFER_IN,FinancialType.REFUND)
            val merchant=r.title.takeIf {it in setOf("AMAZON","NOON","TAMIMI")}
            val category=when(r.type) {
                FinancialType.EXPENSE -> if(r.card=="4421") ChartCategory.MADA_PURCHASES else ChartCategory.CREDIT_PURCHASES
                FinancialType.REFUND -> ChartCategory.UNATTRIBUTED_REFUNDS
                FinancialType.CASH_WITHDRAWAL -> ChartCategory.CASH_WITHDRAWALS
                FinancialType.BILL_PAYMENT -> ChartCategory.BILLS
                FinancialType.FEE -> ChartCategory.FEES
                FinancialType.LOAN_REPAYMENT -> ChartCategory.LOAN_REPAYMENTS
                FinancialType.EXTERNAL_TRANSFER_IN,FinancialType.EXTERNAL_TRANSFER_OUT -> ChartCategory.EXTERNAL_TRANSFERS
                FinancialType.CREDIT_CARD_PAYMENT -> ChartCategory.CREDIT_SETTLEMENTS
                FinancialType.SELF_TRANSFER -> ChartCategory.INTERNAL_TRANSFERS
                else -> null
            }
            TransactionFacts(PostingKey(Environment.BETA,"ref-${r.id}"),PostingRevision(0,1),
                TransactionTime(LocalDate.parse(r.date).atStartOfDay(query.zone).toInstant(),query.zone),r.type,r.type,money(r.amount),merchant,
                r.title.takeIf {merchant==null},r.card,setOfNotNull(r.account?.let(::id),r.destination?.let(::id)),setOfNotNull(r.card?.let(::id)),
                setOfNotNull(r.card?.takeIf {it=="4421"}?.let(::id)),if(r.id==4) setOf(id("personal")) else emptySet(),setOfNotNull(category),
                null,null,null,emptySet(),if(inward) null else r.account?.let(::id) ?: r.card?.let(::id),
                if(inward) r.account?.let(::id) ?: r.card?.let(::id) else if(r.id==4) id("personal") else r.destination?.let(::id))
        }
        fun flow(saving: Boolean=false): AccountFlow {
            val buckets=FlowBucket.entries.associateWith {b -> money(when(b) {
                FlowBucket.SALARY -> if(saving) "0" else "12000"
                FlowBucket.EXTERNAL_IN -> if(saving) "0" else "1000"
                FlowBucket.ACCOUNT_REFUNDS -> if(saving) "0" else "150"
                FlowBucket.INTERNAL_IN -> if(saving) "1000" else "0"
                FlowBucket.INTERNAL_OUT -> if(saving) "0" else "1000"
                FlowBucket.CREDIT_SETTLEMENT -> if(saving) "0" else "2200"
                FlowBucket.LOAN -> if(saving) "0" else "1800"
                FlowBucket.BILLS -> if(saving) "0" else "600"
                FlowBucket.EXTERNAL_OUT -> if(saving) "0" else "500"
                FlowBucket.CASH_WITHDRAWAL -> if(saving) "0" else "400"
                FlowBucket.POS_PURCHASES -> if(saving) "0" else "1800"
                FlowBucket.FEES -> if(saving) "0" else "50"
                else -> "0"
            })}
            return if(saving) AccountFlow(Currency.SAR,buckets,money("1000"),money("0"),money("0"),money("0"),signed("0"),signed("1000"))
                else AccountFlow(Currency.SAR,buckets,money("13150"),money("8350"),money("13150"),money("7350"),signed("5800"),signed("4800"))
        }
        val statement=period.copy(kind=PeriodKind.STATEMENT)
        fun card(number: String,role: CreditCardRole,value: String)=CreditCardSummary(id(number),role,
            (if(role==CreditCardRole.PRIMARY) "البطاقة الأساسية" else "البطاقة الفرعية")+" •• $number",number,null,
            PeriodSpending(period,signed(value)),PeriodSpending(statement,signed(value)),
            if(role==CreditCardRole.PRIMARY) signed("5000") else null,
            if(role==CreditCardRole.PRIMARY) Instant.parse("2026-10-02T07:00:00Z") else null)
        val facility=FacilitySummary(id("facility"),money("2200"),LocalDate.parse("2026-10-15"),Instant.parse("2026-10-02T07:00:00Z"),
            signed("1400"),signed("1400"),listOf(card("7271",CreditCardRole.PRIMARY,"800"),card("8842",CreditCardRole.SUPPLEMENTARY,"600")))
        val loan=LoanSummary(id("personal"),"PERSONAL","القرض الشخصي",money("84200"),Instant.parse("2026-09-27T07:00:00Z"),
            money("1800"),Instant.parse("2026-09-27T07:00:00Z"),signed("1800"),period)
        val purchases=setOf(ChartCategory.CREDIT_PURCHASES,ChartCategory.MADA_PURCHASES,ChartCategory.CASH_WITHDRAWALS,
            ChartCategory.EXTERNAL_TRANSFERS,ChartCategory.UNATTRIBUTED_REFUNDS)
        val credit=setOf(ChartCategory.CREDIT_PURCHASES,ChartCategory.BILLS,ChartCategory.FEES,ChartCategory.CASH_WITHDRAWALS,ChartCategory.UNATTRIBUTED_REFUNDS)
        val allCategories=purchases+setOf(ChartCategory.OTHER_EXPENSES,ChartCategory.BILLS,ChartCategory.FEES,ChartCategory.LOAN_REPAYMENTS)
        fun trend(scope: TrendScope,asset: AssetId?=null,category: ChartCategory?=null): SpendingTrend {
            val selected=transactions.filter {tx ->
                val allowed=when(scope) {
                    TrendScope.CREDIT_CARDS -> tx.primaryCardLast4 in setOf("7271","8842")
                    TrendScope.ASSET -> if(asset?.localId in setOf("3001","5002")) asset in tx.accounts else asset in tx.cards
                    TrendScope.PURCHASES -> tx.effectiveType in setOf(FinancialType.CASH_WITHDRAWAL,FinancialType.EXTERNAL_TRANSFER_OUT) ||
                        (tx.cards.isNotEmpty() && tx.effectiveType in setOf(FinancialType.EXPENSE,FinancialType.REFUND))
                    TrendScope.ACCOUNTS -> tx.accounts.isNotEmpty()
                    else -> true
                }
                val accountScope=scope==TrendScope.ACCOUNTS || (scope==TrendScope.ASSET && asset?.localId in setOf("3001","5002"))
                val financial=tx.effectiveType in setOf(FinancialType.EXPENSE,FinancialType.REFUND,FinancialType.BILL_PAYMENT,FinancialType.FEE) ||
                    ((scope in setOf(TrendScope.PURCHASES,TrendScope.FINANCIAL_OUTFLOWS,TrendScope.ACCOUNTS) ||
                        (scope==TrendScope.ASSET && asset?.localId in setOf("3001","5002","4421"))) &&
                        tx.effectiveType in setOf(FinancialType.CASH_WITHDRAWAL,FinancialType.EXTERNAL_TRANSFER_OUT,FinancialType.LOAN_REPAYMENT)) ||
                    (accountScope && tx.effectiveType==FinancialType.CREDIT_CARD_PAYMENT) ||
                    (scope==TrendScope.ASSET && asset?.localId in setOf("3001","5002") && tx.effectiveType==FinancialType.SELF_TRANSFER && tx.source==asset)
                val chartCategory=if(accountScope && tx.effectiveType==FinancialType.EXPENSE) ChartCategory.ACCOUNT_PURCHASES else tx.categories.singleOrNull()
                allowed && financial && (category==null || category==chartCategory)
            }
            val points=(0L..13L).map {offset ->
                val date=LocalDate.parse("2026-09-27").plusDays(offset)
                val values=selected.filter {it.time.instant.atZone(query.zone).toLocalDate()==date}
                val gross=values.filter {it.effectiveType!=FinancialType.REFUND}.fold(BigDecimal.ZERO) {sum,tx -> sum+tx.amount.amount}
                val refunds=values.filter {it.effectiveType==FinancialType.REFUND}.fold(BigDecimal.ZERO) {sum,tx -> sum+tx.amount.amount}
                DailySpending(date,SignedAmount(gross-refunds,Currency.SAR),SignedAmount(gross,Currency.SAR),SignedAmount(refunds,Currency.SAR),values.map {it.key}.toSet())
            }
            val total=points.fold(BigDecimal.ZERO) {sum,p -> sum+p.net!!.amount}
            return SpendingTrend(asset,category,period,Currency.SAR,points,SignedAmount(total.divide(BigDecimal("14"),2,RoundingMode.HALF_EVEN),Currency.SAR),scope,
                SignedAmount(total,Currency.SAR),SignedAmount(points.fold(BigDecimal.ZERO) {sum,p -> sum+p.beforeRefunds!!.amount},Currency.SAR),
                SignedAmount(points.fold(BigDecimal.ZERO) {sum,p -> sum+p.refunds!!.amount},Currency.SAR))
        }
        fun configurable(scope: TrendScope,asset: AssetId?=null): SpendingTrend {
            val accounts=setOf(ChartCategory.EXTERNAL_TRANSFERS,ChartCategory.CREDIT_SETTLEMENTS,ChartCategory.CASH_WITHDRAWALS,
                ChartCategory.BILLS,ChartCategory.ACCOUNT_PURCHASES,ChartCategory.FEES,ChartCategory.LOAN_REPAYMENTS,ChartCategory.UNATTRIBUTED_REFUNDS)
            val categories=when(scope) {TrendScope.ACCOUNTS -> accounts;TrendScope.CREDIT_CARDS -> credit
                TrendScope.ASSET -> when(asset?.localId) {"3001","5002" -> accounts+ChartCategory.INTERNAL_TRANSFERS
                    "4421" -> setOf(ChartCategory.MADA_PURCHASES,ChartCategory.CASH_WITHDRAWALS,ChartCategory.BILLS,ChartCategory.FEES,ChartCategory.UNATTRIBUTED_REFUNDS)
                    else -> credit}
                TrendScope.PURCHASES -> purchases;else -> allCategories}
            return trend(scope,asset).copy(categoryTrends=categories.associateWith {trend(scope,asset,it)})
        }
        val metrics=SummaryMetric.entries.associateWith {m -> when(m) {SummaryMetric.SPENDING_NET -> signed("3700");SummaryMetric.SPENDING_GROSS -> signed("3850")
            SummaryMetric.REFUNDS -> signed("150");SummaryMetric.INCOME -> signed("12000");else -> null}}
        val merchants=listOf(Triple("NOON","1400",setOf(10,11)),Triple("AMAZON","1250",setOf(5,6,7)),Triple("TAMIMI","400",setOf(8,9))).map {(name,value,ids) ->
            MerchantSpending(name,signed(value),2,name.lowercase(),ids.map {PostingKey(Environment.BETA,"ref-$it")}.toSet())}
        return DashboardSnapshot(query,listOf(AccountSummary(id("3001"),null,"الجاري •• 3001",AccountCategory.CURRENT,last4="3001",flow=flow()),
            AccountSummary(id("5002"),null,"الادخار •• 5002",AccountCategory.SAVINGS,last4="5002",flow=flow(true))),
            listOf(DebitSpending(id("4421"),signed("2050"),id("3001"),"مدى •• 4421",last4="4421",network="MADA",salaryPeriod=period)),listOf(facility),loans=listOf(loan),transactions=transactions,
            analysis=AnalysisSummary(Currency.SAR,metrics,17,2,0,listOf(configurable(TrendScope.OVERALL),configurable(TrendScope.PURCHASES),
                configurable(TrendScope.FINANCIAL_OUTFLOWS),configurable(TrendScope.CREDIT_CARDS),configurable(TrendScope.ASSET,id("7271")),
                configurable(TrendScope.ASSET,id("8842")),configurable(TrendScope.ASSET,id("4421")),configurable(TrendScope.ACCOUNTS),
                configurable(TrendScope.ASSET,id("3001")),configurable(TrendScope.ASSET,id("5002"))),merchants),
            commitments=CommitmentsSummary(Currency.SAR,listOf(
                CommitmentRow(Environment.BETA,"credit","المبلغ المستحق على البطاقة •• 7271",money("2200"),LocalDate.parse("2026-10-15"),PaymentStatus.UNPAID,facilityId=id("facility")),
                CommitmentRow(Environment.BETA,"loan","قسط القرض الشخصي",money("1800"),LocalDate.parse("2026-09-27"),PaymentStatus.PAID,loanId=id("personal")),
                CommitmentRow(Environment.BETA,"internet","الإنترنت",money("200"),LocalDate.parse("2026-10-05"),PaymentStatus.PAID,userCommitmentId="internet")),money("4200"),money("2000"),money("2200")),
            fleetFlow=flow().let {current -> current.copy(buckets=current.buckets+(FlowBucket.INTERNAL_IN to money("1000")),
                totalIn=money("14150"),cashPosition=signed("5800"))},
            totals=PortfolioTotals(Currency.SAR,money("13150"),money("7350"),signed("5800"),signed("5800"),money("2200"),signed("1400"),signed("2050"),money("84200"),signed("1800"),signed("1400")))
    }
}
