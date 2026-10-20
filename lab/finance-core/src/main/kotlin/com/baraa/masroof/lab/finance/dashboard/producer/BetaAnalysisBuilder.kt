package com.baraa.masroof.lab.finance.dashboard.producer

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.projection.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Collections
import java.util.Locale

/** Pure bounded projection of already persisted financial and involvement facts. */
internal object BetaAnalysisBuilder {
    fun build(batch: DashboardReadBatch, summary: DashboardFinancialSummary,
        transactions: List<TransactionFacts>, projected: List<SarRowProjection.Available>?): AnalysisSummary {
        val period = DisplayPeriod(batch.query.from, batch.query.until, batch.query.zone, PeriodKind.SELECTED, null)
        val byKey = projected?.associateBy { it.native.input.key }
        val creditIds = batch.assets.filter { it.kind == AssetKind.CREDIT_CARD }.mapTo(hashSetOf()) { it.id }
        val registry = batch.assets.associateBy { it.id }
        val details = batch.postingFacts.associateBy { it.key }
        val firstDate = period.from.atZone(period.zone).toLocalDate()
        val lastDate = minOf(period.until.minusNanos(1).atZone(period.zone).toLocalDate(), batch.revision.asOf.atZone(period.zone).toLocalDate())
        val dayCount = (java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate) + 1).coerceAtLeast(0)
        require(dayCount <= 3661 && dayCount * (batch.assets.size + TrendScope.entries.size + ChartCategory.entries.size) <= 250_000) {
            "Dashboard trend projection exceeds its bounded point budget"
        }
        fun eligible(tx: TransactionFacts, outflows: Boolean): Boolean = when (tx.effectiveType) {
            FinancialType.EXPENSE, FinancialType.BILL_PAYMENT, FinancialType.FEE, FinancialType.REFUND -> true
            FinancialType.EXTERNAL_TRANSFER_OUT, FinancialType.CASH_WITHDRAWAL, FinancialType.LOAN_REPAYMENT -> outflows
            else -> false
        }
        fun purchase(tx: TransactionFacts): Boolean {
            val family = details[tx.key]?.messageFamily ?: return false
            if (tx.effectiveType == FinancialType.LOAN_REPAYMENT || family in setOf(DashboardEvidenceFamily.CARD_PAYMENT,
                    DashboardEvidenceFamily.BILL_PAYMENT, DashboardEvidenceFamily.FINANCING_INSTALLMENT)) return false
            return when (tx.effectiveType) {
                FinancialType.CASH_WITHDRAWAL, FinancialType.EXTERNAL_TRANSFER_OUT -> true
                FinancialType.EXPENSE -> family == DashboardEvidenceFamily.WITHDRAWAL || (tx.cards.isNotEmpty() && family != DashboardEvidenceFamily.FEE)
                FinancialType.REFUND -> tx.cards.isNotEmpty()
                FinancialType.FEE -> family == DashboardEvidenceFamily.WITHDRAWAL
                else -> false
            }
        }
        val outBuckets = setOf(FlowBucket.EXTERNAL_OUT, FlowBucket.CREDIT_SETTLEMENT, FlowBucket.CASH_WITHDRAWAL,
            FlowBucket.BILLS, FlowBucket.POS_PURCHASES, FlowBucket.FEES, FlowBucket.LOAN)
        fun accountContribution(tx: TransactionFacts, account: AssetId?): BigDecimal? {
            val row = byKey?.get(tx.key) ?: return null
            val movements = row.accountMovements.filter { account == null || it.account == account }
            val included = movements.filter { it.bucket in outBuckets || it.bucket == FlowBucket.ACCOUNT_REFUNDS ||
                (account != null && it.bucket == FlowBucket.INTERNAL_OUT) }
            if (included.isEmpty()) return null
            return included.fold(BigDecimal.ZERO) { sum, movement -> sum +
                if (movement.bucket == FlowBucket.ACCOUNT_REFUNDS) movement.amount.amount.negate() else movement.amount.amount }
        }
        var chartPointBudget = 0L
        fun trend(scope: TrendScope, asset: AssetId? = null, category: ChartCategory? = null,
            chartCategory: Boolean = false): SpendingTrend {
            fun contribution(tx: TransactionFacts): BigDecimal? {
                val amount = byKey?.get(tx.key)?.sarAmount?.amount ?: return null
                val ordinary = if (tx.effectiveType == FinancialType.REFUND) amount.negate() else amount
                return when (scope) {
                    TrendScope.ACCOUNTS -> accountContribution(tx, null)
                    TrendScope.ASSET -> when (registry[asset]?.kind) {
                        AssetKind.ACCOUNT -> accountContribution(tx, asset)
                        AssetKind.CREDIT_CARD -> ordinary.takeIf { asset in tx.cards && eligible(tx, false) }
                        AssetKind.DEBIT_CARD -> ordinary.takeIf { asset in tx.debitSpendingCards && eligible(tx, true) }
                        else -> null
                    }
                    TrendScope.OVERALL -> ordinary.takeIf { eligible(tx, false) }
                    TrendScope.FINANCIAL_OUTFLOWS -> ordinary.takeIf { eligible(tx, true) }
                    TrendScope.CREDIT_CARDS -> ordinary.takeIf { tx.cards.any { it in creditIds } && eligible(tx, false) }
                    TrendScope.DEBIT_CARDS -> ordinary.takeIf { tx.debitSpendingCards.isNotEmpty() && eligible(tx, true) }
                    TrendScope.PURCHASES -> ordinary.takeIf { purchase(tx) }
                }
            }
            val relevant = transactions.filter { tx ->
                (category == null || if (!chartCategory) category in tx.categories else category == when {
                    tx.effectiveType == FinancialType.REFUND -> ChartCategory.UNATTRIBUTED_REFUNDS
                    (scope == TrendScope.ACCOUNTS || (scope == TrendScope.ASSET && registry[asset]?.kind == AssetKind.ACCOUNT)) &&
                        tx.effectiveType == FinancialType.EXPENSE -> ChartCategory.ACCOUNT_PURCHASES
                    scope == TrendScope.ACCOUNTS || (scope == TrendScope.ASSET && registry[asset]?.kind == AssetKind.ACCOUNT) -> tx.categories.singleOrNull()
                    details[tx.key]?.messageFamily == DashboardEvidenceFamily.WITHDRAWAL -> ChartCategory.CASH_WITHDRAWALS
                    ChartCategory.ACCOUNT_PURCHASES in tx.categories -> ChartCategory.OTHER_EXPENSES
                    else -> tx.categories.singleOrNull()
                }) && contribution(tx) != null
            }
            val last = minOf(period.until.minusNanos(1).atZone(period.zone).toLocalDate(), batch.revision.asOf.atZone(period.zone).toLocalDate())
            val first = period.from.atZone(period.zone).toLocalDate()
            require(java.time.temporal.ChronoUnit.DAYS.between(first, last) <= 3660) { "Trend window exceeds ten years" }
            val grouped = relevant.filter { !it.time.instant.atZone(period.zone).toLocalDate().isAfter(last) }
                .groupBy { it.time.instant.atZone(period.zone).toLocalDate() }
            // Unproved account-backed Mada involvement cannot become a known empty card trend.
            val debitAssets = batch.assets.filter { it.kind == AssetKind.DEBIT_CARD && (scope == TrendScope.DEBIT_CARDS ||
                (scope == TrendScope.ASSET && it.id == asset)) }
            val unknownDebit = transactions.any { tx ->
                tx.effectiveType in setOf(FinancialType.EXPENSE, FinancialType.CASH_WITHDRAWAL, FinancialType.REFUND) &&
                    tx.debitSpendingCards.isEmpty() && debitAssets.any { card -> tx.accounts.any { account ->
                        card.parentId == account || (card.parentId == null && card.id.bank == account.bank) } }
            }
            val known = byKey != null && !unknownDebit &&
                (scope != TrendScope.PURCHASES || transactions.all { details[it.key]?.messageFamily != null })
            val days = if (first > last) emptyList() else generateSequence(first) { it.plusDays(1).takeIf { day -> day <= last } }.toList()
            // Category facts remain sparse, so an all-history recovery read does not multiply
            // every empty calendar day by every available category.
            val projectedDays = if (chartCategory) grouped.keys.sorted() else days
            if (chartCategory) {
                chartPointBudget += projectedDays.size
                require(chartPointBudget <= 250_000) { "Dashboard category projection exceeds its bounded point budget" }
            }
            val points = projectedDays.map { date ->
                val rows = grouped[date].orEmpty()
                fun amount(refunds: Boolean): SignedAmount? = if (!known) null else signed(rows.mapNotNull(::contribution)
                    .filter { (it.signum() < 0) == refunds }.fold(BigDecimal.ZERO) { sum, value -> sum + value.abs() })
                val gross = amount(false); val refunds = amount(true)
                DailySpending(date, if (gross == null || refunds == null) null else signed(gross.amount - refunds.amount), gross, refunds,
                    freezeSet(rows.mapTo(linkedSetOf()) { it.key }))
            }
            val total = if (!known) null else signed(points.fold(BigDecimal.ZERO) { sum, point -> sum + requireNotNull(point.net).amount })
            val average = total?.let { signed(if (days.isEmpty()) BigDecimal.ZERO else
                it.amount.divide(days.size.toBigDecimal(), 2, RoundingMode.HALF_EVEN)) }
            val beforeRefunds = if (!known) null else signed(points.fold(BigDecimal.ZERO) { sum, point -> sum + requireNotNull(point.beforeRefunds).amount })
            val refunds = if (!known) null else signed(points.fold(BigDecimal.ZERO) { sum, point -> sum + requireNotNull(point.refunds).amount })
            return SpendingTrend(asset, category, period, Currency.SAR, freezeList(points), average, scope, total, beforeRefunds, refunds)

        }
        // Approved production chart scopes. All-selected always retains the original full-scope trend.
        val purchases = setOf(ChartCategory.CREDIT_PURCHASES, ChartCategory.MADA_PURCHASES,
            ChartCategory.CASH_WITHDRAWALS, ChartCategory.EXTERNAL_TRANSFERS, ChartCategory.UNATTRIBUTED_REFUNDS)
        val credit = setOf(ChartCategory.CREDIT_PURCHASES, ChartCategory.BILLS, ChartCategory.FEES,
            ChartCategory.CASH_WITHDRAWALS, ChartCategory.UNATTRIBUTED_REFUNDS)
        val debit = setOf(ChartCategory.MADA_PURCHASES, ChartCategory.CASH_WITHDRAWALS,
            ChartCategory.BILLS, ChartCategory.FEES, ChartCategory.UNATTRIBUTED_REFUNDS)
        val accounts = setOf(ChartCategory.EXTERNAL_TRANSFERS, ChartCategory.CREDIT_SETTLEMENTS,
            ChartCategory.CASH_WITHDRAWALS, ChartCategory.BILLS, ChartCategory.ACCOUNT_PURCHASES,
            ChartCategory.FEES, ChartCategory.LOAN_REPAYMENTS, ChartCategory.UNATTRIBUTED_REFUNDS)
        fun configurable(scope: TrendScope, asset: AssetId? = null): SpendingTrend {
            val allowed = when (scope) {
                TrendScope.CREDIT_CARDS -> credit
                TrendScope.DEBIT_CARDS -> debit
                TrendScope.ACCOUNTS -> accounts
                TrendScope.PURCHASES -> purchases
                TrendScope.OVERALL -> purchases + setOf(ChartCategory.OTHER_EXPENSES, ChartCategory.BILLS,
                    ChartCategory.FEES, ChartCategory.LOAN_REPAYMENTS)
                TrendScope.ASSET -> when (registry[asset]?.kind) {
                    AssetKind.ACCOUNT -> accounts + ChartCategory.INTERNAL_TRANSFERS
                    AssetKind.CREDIT_CARD -> credit
                    else -> debit
                }
                TrendScope.FINANCIAL_OUTFLOWS -> purchases + setOf(ChartCategory.OTHER_EXPENSES, ChartCategory.BILLS,
                    ChartCategory.FEES, ChartCategory.LOAN_REPAYMENTS)
            }
            return trend(scope, asset).copy(categoryTrends = java.util.Collections.unmodifiableMap(
                allowed.associateWith { trend(scope, asset, it, chartCategory = true) }))
        }
        val trends = TrendScope.entries.filter { it != TrendScope.ASSET }.map { configurable(it) } +
            batch.assets.filter { it.kind in setOf(AssetKind.ACCOUNT, AssetKind.CREDIT_CARD, AssetKind.DEBIT_CARD) }
                .map { configurable(TrendScope.ASSET, it.id) } + ChartCategory.entries.map { trend(TrendScope.FINANCIAL_OUTFLOWS, category = it) }
        // Preserve the existing merchant policy: NFC/case/spacing only, two purchases minimum,
        // refunds subtract, deterministic label. Original context uses fee-inclusive SMS SAR amounts;
        // the separate TransactionFacts.sarEquivalent intentionally remains fee-free for row display.
        fun merchantKey(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
            .replace(Regex("\\s+"), " ").replace(Regex("\\s*([,،._-])\\s*"), "$1").lowercase(Locale.ROOT)
        val merchants = if (!batch.coverage.completeMerchantFacts || projected == null) emptyList() else transactions
            .filter { it.key in byKey.orEmpty() && it.effectiveType in setOf(FinancialType.EXPENSE, FinancialType.REFUND) && !it.merchant.isNullOrBlank() }
            .groupBy { merchantKey(requireNotNull(it.merchant)) }.filterKeys { it.isNotEmpty() }
            .values.filter { group -> group.count { it.effectiveType == FinancialType.EXPENSE } >= 2 }.map { group ->
                val label = group.groupingBy { requireNotNull(it.merchant).trim() }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
                MerchantSpending(label, signed(group.fold(BigDecimal.ZERO) { sum, tx ->
                    val amount = requireNotNull(byKey!![tx.key]).sarAmount.amount
                    sum + if (tx.effectiveType == FinancialType.REFUND) amount.negate() else amount }),
                    group.count { it.effectiveType == FinancialType.EXPENSE },merchantKey(label),freezeSet(group.map {it.key}.toSet()))
            }.sortedWith(compareByDescending<MerchantSpending> { it.net.amount }.thenBy { it.merchant.lowercase(Locale.ROOT) }.thenBy { merchantKey(it.merchant) })
        return AnalysisSummary(Currency.SAR, summary.metrics, summary.transactionCount, summary.reviewRequiredCount,
            summary.excludedOtherCurrencyCount, freezeList(trends), freezeList(merchants))
    }

    fun commitments(batch: DashboardReadBatch, facilities: List<FacilitySummary>, loans: List<LoanSummary>,
        salary: List<SarRowProjection.Available>?, settlements: Map<AssetId, List<SarRowProjection.Available>?> = emptyMap()): CommitmentsSummary? {
        val period = batch.periods.salary ?: return null
        if (salary == null) return null // PaymentStatus has no UNKNOWN; never invent UNPAID on incomplete rows.
        val from = period.from.atZone(period.zone).toLocalDate()
        val until = period.until.minusNanos(1).atZone(period.zone).toLocalDate()
        // Occurrences are scoped by the application calculator. An explicit due date is
        // display metadata and may be outside the occurrence's salary cycle, as in production.
        require(batch.commitmentOccurrences.all { it.environment == batch.query.environment && it.userCommitmentId != null })
        val rows = batch.commitmentOccurrences.toMutableList()
        val coveredFacilities = hashSetOf<AssetId>()
        for (facility in facilities) {
            if (facility.facilityId in batch.unavailableObligationAssets) continue
            val due = facility.statementDue ?: continue
            if (due.currency != Currency.SAR) continue
            val date = facility.dueDate ?: facility.statementIssuedAt?.atZone(period.zone)?.toLocalDate() ?: continue
            if (date !in from..until) continue
            val issued = facility.statementIssuedAt ?: continue
            // An earlier statement requires proved settlement coverage beyond the display windows.
            val settlementWindow = batch.settlementPeriods[facility.facilityId]
            val completeWindow = settlementWindow != null && settlementWindow.from == issued &&
                settlementWindow.until == batch.revision.asOf.plusNanos(1)
            val paymentRows = if (settlementWindow != null) settlements[facility.facilityId].takeIf { completeWindow } else salary.takeIf { issued >= period.from }
            if (paymentRows == null) continue
            coveredFacilities += facility.facilityId
            val payments = paymentRows.filter { row -> row.native.effectiveType == FinancialType.CREDIT_CARD_PAYMENT &&
                row.native.input.time.instant >= issued && row.native.input.time.instant <= batch.revision.asOf && (row.native.input.destination as? com.baraa.masroof.lab.finance.classification.FinancialEndpoint.Owned)?.id.let { id ->
                    id == facility.facilityId || facility.cards.any { it.cardId == id } } }
            val paid = payments.fold(BigDecimal.ZERO) { sum, row -> sum + row.sarAmount.amount } >= due.amount
            rows += CommitmentRow(batch.query.environment, "facility:${facility.facilityId}:$issued", facility.facilityId.localId,
                due, date, if (paid) PaymentStatus.PAID else PaymentStatus.UNPAID, facilityId = facility.facilityId)
        }
        for (loan in loans) {
            if (loan.id in batch.unavailableObligationAssets) continue
            val payment = signed(salary.filter { it.loan == loan.id && it.native.input.time.instant <= batch.revision.asOf }.fold(BigDecimal.ZERO) { sum, row -> sum + row.sarAmount.amount })
            val paid = payment != null && payment.amount.signum() > 0
            if (!paid && loan.remaining?.amount?.signum() == 0) continue
            val amount = if (paid) Money.of(requireNotNull(payment).amount, Currency.SAR) else loan.latestInstallment ?: continue
            if (amount.currency != Currency.SAR) continue
            rows += CommitmentRow(batch.query.environment, "loan:${loan.id}:${period.from}", loan.label.ifBlank { loan.id.localId },
                amount, null, if (paid) PaymentStatus.PAID else PaymentStatus.UNPAID, loanId = loan.id)
        }
        val known = batch.coverage.completeUserCommitments && facilities.all { facility ->
            val due = facility.statementDue
            val issued = facility.statementIssuedAt
            val date = facility.dueDate ?: issued?.atZone(period.zone)?.toLocalDate()
            facility.facilityId !in batch.unavailableObligationAssets && due?.currency == Currency.SAR && issued != null && date != null && (date !in from..until || facility.facilityId in coveredFacilities)
        } && loans.all { it.id !in batch.unavailableObligationAssets && it.remaining?.currency == Currency.SAR &&
            (it.latestInstallment?.currency == Currency.SAR || it.remaining.amount.signum() == 0) }
        fun sum(predicate: (CommitmentRow) -> Boolean) = if (!known) null else Money.of(rows.filter(predicate)
            .fold(BigDecimal.ZERO) { sum, row -> sum + row.amount.amount }, Currency.SAR)
        return CommitmentsSummary(Currency.SAR, freezeList(rows.sortedBy { it.label.lowercase(Locale.ROOT) }), sum { true },
            sum { it.status == PaymentStatus.PAID }, sum { it.status == PaymentStatus.UNPAID })
    }
}

private fun signed(value: BigDecimal) = SignedAmount(value, Currency.SAR)
private fun <T> freezeList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <T> freezeSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
