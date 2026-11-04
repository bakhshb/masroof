package com.baraa.masroof.instrumentation

import com.baraa.masroof.application.dashboard.*
import com.baraa.masroof.application.notification.*
import com.baraa.masroof.application.logging.*
import com.baraa.masroof.application.update.*
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.presentation.dashboard.*
import com.baraa.masroof.presentation.settings.*
import com.baraa.masroof.presentation.review.*
import com.baraa.masroof.presentation.notification.*
import com.baraa.masroof.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Synthetic facts from the approved prototype; never reads the installed ledger. */
internal object FidelityFixtures {
    private val bank = Bank.BANK_ALJAZIRA
    private val zone = ZoneId.of("Asia/Riyadh")
    private val period = FinancialPeriod(LocalDate.parse("2026-09-27"), LocalDate.parse("2026-10-27"))
    private fun money(value: String) = Money.of(value, Currency.SAR)
    private fun signed(value: String) = SignedMoneyAmount.of(money(value))
    private fun account(number: String) = FinancialContainerIdFactory.accountId(bank, number)
    private fun card(number: String) = FinancialContainerIdFactory.cardId(bank, number)
    private data class Row(val id: Int, val name: String, val type: FinancialTransactionType, val amount: String,
        val day: String, val account: String? = null, val card: String? = null, val destination: String? = null)
    private val rows = listOf(
        Row(1,"الراتب",FinancialTransactionType.INCOME,"12000","2026-09-27","3001"),
        Row(2,"حوالة واردة",FinancialTransactionType.EXTERNAL_TRANSFER_IN,"1000","2026-09-28","3001"),
        Row(3,"سداد البطاقة الائتمانية",FinancialTransactionType.CREDIT_CARD_PAYMENT,"2200","2026-09-29","3001","7271"),
        Row(4,"القرض الشخصي",FinancialTransactionType.LOAN_REPAYMENT,"1800","2026-09-27","3001"),
        Row(5,"AMAZON",FinancialTransactionType.EXPENSE,"900","2026-10-03","3001","4421"),
        Row(6,"AMAZON",FinancialTransactionType.EXPENSE,"500","2026-10-07","3001","4421"),
        Row(7,"AMAZON",FinancialTransactionType.REFUND,"150","2026-10-09","3001","4421"),
        Row(8,"TAMIMI",FinancialTransactionType.EXPENSE,"200","2026-10-04","3001","4421"),
        Row(9,"TAMIMI",FinancialTransactionType.EXPENSE,"200","2026-10-08","3001","4421"),
        Row(10,"NOON",FinancialTransactionType.EXPENSE,"800","2026-10-02",card="7271"),
        Row(11,"NOON",FinancialTransactionType.EXPENSE,"600","2026-10-06",card="8842"),
        Row(12,"فاتورة الإنترنت",FinancialTransactionType.BILL_PAYMENT,"200","2026-10-05","3001"),
        Row(13,"فاتورة الكهرباء",FinancialTransactionType.BILL_PAYMENT,"400","2026-10-09","3001"),
        Row(14,"حوالة صادرة",FinancialTransactionType.EXTERNAL_TRANSFER_OUT,"500","2026-10-01","3001"),
        Row(15,"سحب نقدي",FinancialTransactionType.CASH_WITHDRAWAL,"400","2026-10-06","3001","4421"),
        Row(16,"رسوم بنكية",FinancialTransactionType.FEE,"50","2026-10-02","3001"),
        Row(17,"من الجاري إلى الادخار",FinancialTransactionType.SELF_TRANSFER,"1000","2026-10-08","3001",destination="5002"),
    )
    private fun transaction(row: Row): FinancialTransaction {
        val inward = row.type in listOf(FinancialTransactionType.INCOME, FinancialTransactionType.EXTERNAL_TRANSFER_IN, FinancialTransactionType.REFUND)
        val source = if (inward) null else row.account?.let(::account) ?: row.card?.let(::card)
        val destination = if (inward) row.account?.let(::account) ?: row.card?.let(::card) else row.destination?.let(::account)
        return FinancialTransaction("ref-${row.id}",row.type,money(row.amount),LocalDate.parse(row.day).atStartOfDay(zone).toInstant(),
            source,destination,if (row.name in listOf("AMAZON","NOON","TAMIMI")) row.name else null,null,null,emptyList(), occurredAtZone = zone.id)
    }
    private val transactions = rows.map(::transaction)
    private fun trend(selected: List<FinancialTransaction>, outflows: Boolean = false) = DailySpendingTrendBuilder.build(
        period, selected, emptyList(), Currency.SAR, emptyMap(), zone, LocalDate.parse("2026-10-10"), outflows)
    private fun summary(saving: Boolean = false) = CurrentAccountSummary.of(Currency.SAR,
        salary = money(if(saving)"0" else "12000"), otherIncome=money("0"), externalTransfersIn=money(if(saving)"0" else "1000"),
        selfTransfersIn=money(if(saving)"1000" else "0"), creditCardPayments=money(if(saving)"0" else "2200"),
        billPayments=money(if(saving)"0" else "600"), externalTransfersOut=money(if(saving)"0" else "500"),
        cashWithdrawals=money(if(saving)"0" else "400"), posPurchases=money(if(saving)"0" else "1800"),
        fees=money(if(saving)"0" else "50"), loanRepayments=money(if(saving)"0" else "1800"),
        selfTransfersOut=money(if(saving)"0" else "1000"), accountRefunds=money(if(saving)"0" else "150"))

    fun financial(): DashboardUiState {
        val current = summary()
        val saving = summary(true)
        val ordinary = trend(transactions)
        val overall = trend(transactions, true)
        val registryAccounts = listOf(AccountRegistryEntry.forTest(bank, "3001"), AccountRegistryEntry.forTest(bank, "5002"))
        val accountContext = DashboardProjectionContext(period, period.endDateExclusive.atStartOfDay(zone).toInstant(),
            LocalDate.parse("2026-10-10"), DashboardEvidence.EMPTY, transactions, emptyMap(), 0, registryAccounts,
            setOf(account("3001"), account("5002")), emptyList(), emptyList(),
            DebitCardScopeFacts(setOf(card("4421")), mapOf(card("4421") to account("3001"))), java.util.Locale.ENGLISH, zone, Currency.SAR)
        val snapshot = CreditCardBalanceSnapshot(money("5000"),money("2200"),LocalDate.parse("2026-10-15"),
            Instant.parse("2026-10-02T07:00:00Z"),Instant.parse("2026-10-02T07:00:00Z"))
        val primary = CreditCardDashboardRow(bank,"7271",signed("800"),signed("800"),"2 أكتوبر — 1 نوفمبر",snapshot)
        val child = primary.copy(last4="8842",statementSpendingNet=signed("600"),salaryPeriodSpendingNet=signed("600"),snapshot=null)
        val facility = CreditFacilityOverview(bank,primary,listOf(child),StatementDueSnapshot(money("2200"),snapshot.updatedAt,snapshot.dueDate),
            signed("1400"),signed("1400"),primary.statementPeriodLabel,null,Currency.SAR)
        val mada = DebitCardOverview(bank,"4421","مدى •• 4421","الجاري •• 3001","3001",CardNetwork.MADA,signed("2050"),null)
        val allCards = CreditFacilitiesOverview(listOf(facility),listOf(mada),Currency.SAR)
        val configurableTrends = DashboardSpendingTrendsBuilder.build(accountContext,
            AccountsDashboardProjection.build(accountContext), CardsDashboardSection(allCards,
                rows.associate { "ref-${it.id}" to listOfNotNull(it.card?.let { number -> "${bank.id}:$number" }).toSet() },
                rows.associate { "ref-${it.id}" to if (it.card == "4421") setOf("${bank.id}:4421") else emptySet() }))
        val previews = rows.mapIndexed { i, row ->
            val tx = transactions[i]
            val inward = tx.type in listOf(FinancialTransactionType.INCOME,FinancialTransactionType.EXTERNAL_TRANSFER_IN,FinancialTransactionType.REFUND)
            TransactionPreviewUi(tx.id,row.name,tx.amount,LocalDate.parse(row.day),row.amount,row.day,tx.type,tx.type,
                if(tx.type==FinancialTransactionType.SELF_TRANSFER)TransactionDirectionUi.NEUTRAL else if(inward)TransactionDirectionUi.INCOME else TransactionDirectionUi.OUTWARD,
                row.card,tx.sourceContainerId,tx.destinationContainerId,row.name.lowercase())
        }
        val commitment = CommitmentDashboardRow("internet",CommitmentDashboardSource.USER,"الإنترنت",money("200"),LocalDate.parse("2026-10-05"),CommitmentPaymentStatus.PAID,userCommitmentId="internet")
        val obligations = CommitmentsOverview(listOf(
            CommitmentDashboardRow("credit",CommitmentDashboardSource.CREDIT_CARD,"المبلغ المستحق على البطاقة •• 7271",money("2200"),LocalDate.parse("2026-10-15"),CommitmentPaymentStatus.UNPAID,creditFacilityKey="${bank.id}:7271"),
            CommitmentDashboardRow("loan",CommitmentDashboardSource.LOAN,"قسط القرض الشخصي",money("1800"),LocalDate.parse("2026-09-27"),CommitmentPaymentStatus.PAID,loanKey="${bank.id}:PERSONAL"),commitment),
            money("4200"),money("2000"),money("2200"),Currency.SAR)
        val merchant = MerchantSpendingOverview(listOf(
            MerchantSpendingRow("noon","NOON",signed("1400"),2,setOf("ref-10","ref-11")),
            MerchantSpendingRow("amazon","AMAZON",signed("1250"),2,setOf("ref-5","ref-6","ref-7")),
            MerchantSpendingRow("tamimi","TAMIMI",signed("400"),2,setOf("ref-8","ref-9"))))
        return DashboardUiState(loading=false, period=period,
            summary=MonthlyFinancialSummary(period,Currency.SAR,money("3850"),money("150"),signed("3700"),money("12000"),money("1000"),money("500"),money("2200"),money("400"),money("1000"),17,2),
            flowDetailGrouping=CurrentAccountFlowDetailGrouper.group(transactions,emptyList(),Currency.SAR,emptyMap(),
                setOf(account("3001"),account("5002"))),
            currentAccount=CurrentAccountSummary.aggregate(listOf(current,saving)), accountsFleet=AccountsSummary.fromSummaries(listOf(bank to "3001",bank to "5002"),listOf(current,saving)),
            ownedAccounts=listOf(OwnedAccountUi(bank,"3001","الجاري •• 3001",current),OwnedAccountUi(bank,"5002","الادخار •• 5002",saving)),
            ownedCards=listOf(OwnedCardUi(bank,"7271","البطاقة الأساسية •• 7271",CardNetwork.VISA,CardRole.PRIMARY),OwnedCardUi(bank,"8842","البطاقة الفرعية •• 8842",CardNetwork.VISA,CardRole.SUPPLEMENTARY,"7271"),OwnedCardUi(bank,"4421","مدى •• 4421",CardNetwork.MADA)),
            creditFacilities=allCards, loansOverview=LoansOverview(listOf(LoanOverview(bank,LoanType.PERSONAL,"القرض الشخصي",money("84200"),Instant.parse("2026-09-27T07:00:00Z"),money("1800"),Instant.parse("2026-09-27T07:00:00Z"),signed("1800"),null)),null,Currency.SAR),
            commitmentsOverview=obligations,merchantSpending=merchant,dailySpendingTrend=ordinary,
            spendingTrends=configurableTrends,
            recentTransactions=previews.sortedByDescending { it.localDate }.take(3),allTransactions=previews.sortedByDescending { it.localDate },
            transactionAccountInvolvement=rows.associate { "ref-${it.id}" to listOfNotNull(it.account?.let(::account),it.destination?.let(::account)).toSet() },
            transactionCardInvolvement=rows.associate { "ref-${it.id}" to listOfNotNull(it.card?.let { number -> "${bank.id}:$number" }).toSet() },
            transactionDebitSpendInvolvement=rows.associate { "ref-${it.id}" to if(it.card=="4421")setOf("${bank.id}:4421") else emptySet() },
            transactionLoanInvolvement=mapOf("ref-4" to setOf(FinancialContainerIdFactory.loanId(bank,LoanType.PERSONAL))),
            smsPermissionGranted=true)
    }

    fun settings(language: String): SettingsUiState {
        val accounts=listOf(ManagedAccountUi("a1",bank,"3001",OwnershipStatus.OWNED,"الجاري •• 3001",AccountType.CURRENT),
            ManagedAccountUi("a2",bank,"5002",OwnershipStatus.OWNED,"الادخار •• 5002",AccountType.SAVINGS))
        val cards=listOf(ManagedCardUi("c1",bank,"7271",OwnershipStatus.OWNED,"البطاقة الأساسية •• 7271",CardNetwork.VISA,CardType.CREDIT,CardRole.PRIMARY),
            ManagedCardUi("c2",bank,"8842",OwnershipStatus.OWNED,"البطاقة الفرعية •• 8842",CardNetwork.VISA,CardType.CREDIT,CardRole.SUPPLEMENTARY,"7271"),
            ManagedCardUi("c3",bank,"4421",OwnershipStatus.OWNED,"مدى •• 4421",CardNetwork.MADA,CardType.DEBIT,linkedAccountMaskedNumber="3001"))
        val loan=ManagedLoanUi("loan",bank,LoanType.PERSONAL,OwnershipStatus.OWNED,"قرض شخصي")
        val commitment=ManagedCommitmentUi("internet","الإنترنت",money("200"),LocalDate.parse("2026-10-05"),CommitmentRecurrence.MONTHLY,null,true,"ref-12")
        return SettingsUiState(loading=false,followedAccounts=accounts,followedCards=cards,loans=listOf(loan),activeCommitments=listOf(commitment),commitmentsLoaded=true,
            bankSummaries=listOf(SettingsBankSummaryUi(bank,2,0,0,3,0,0,1)),appVersion="1.0.0",languageTag=language,
            updateState=AppUpdateUiState.Available(UpdateManifest(2,"1.0.1","masroof.apk","0".repeat(64),"تحسين تجربة عرض البيانات.")))
    }
    fun review() = ReviewUiState(loading=false,items=listOf(
        ReviewListItemUi("review-transfer",ReviewKind.PENDING_MATCH,R.string.review_kind_pending_match,"حوالة تحتاج مطابقة","حوالة تحتاج مطابقة","750 ر.س","8 أكتوبر · بنك الجزيرة","multiple_candidates"),
        ReviewListItemUi("review-card",ReviewKind.NEEDS_REVIEW,R.string.review_kind_needs_review,"بطاقة غير مؤكدة الملكية","بطاقة غير مؤكدة الملكية",null,"8 أكتوبر · بنك الجزيرة","unknown_ownership")))
    fun notifications()=NotificationCenterUiState(false,listOf(
        NotificationItem("review",NotificationType.REVIEW_REQUIRED,2,action=NotificationAction.OPEN_REVIEW),
        NotificationItem("card",NotificationType.UNREGISTERED_CARDS,1,action=NotificationAction.OPEN_SETTINGS_CARDS),
        NotificationItem("update",NotificationType.APP_UPDATE_AVAILABLE,updateVersion="1.0.1",action=NotificationAction.OPEN_SETTINGS_ABOUT)),3)
    fun logs()=listOf(
        AppLogEntry(1,Instant.parse("2026-10-10T07:20:00Z").toEpochMilli(),"sms",AppLogLevel.INFO,"اكتملت معالجة الرسائل المستوردة."),
        AppLogEntry(2,Instant.parse("2026-10-10T07:18:00Z").toEpochMilli(),"sms",AppLogLevel.WARN,"حوالة غير مؤكدة تحتاج مطابقة."),
        AppLogEntry(3,Instant.parse("2026-10-10T06:55:00Z").toEpochMilli(),"sms",AppLogLevel.ERROR,"لم تكتمل معالجة إحدى الرسائل؛ حُفظت لإعادة المحاولة."))
}
