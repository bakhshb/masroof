package com.baraa.masroof.lab.finance.projection

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Collections

/** Already persisted, linked parsed-event fee. This is not a rate lookup or posting authority. */
data class LinkedInternationalFee(
    val key: PostingKey,
    val revision: PostingRevision,
    val evidenceId: String,
    val amount: Money,
)

enum class SarProjectionBlocker { FINANCIAL_REVIEW, MISSING_APPLIED_FX, UNKNOWN_FX_SOURCE, INVALID_FEE_LINK, NON_SAR_FEE }

sealed interface SarRowProjection {
    data class Unavailable(val key: PostingKey, val reason: SarProjectionBlocker) : SarRowProjection
    /** Native immutable facts remain available separately; SAR is a derived read-only projection. */
    data class Available(
        val native: FinancialClassification.Classified,
        val sarAmount: Money,
        val metrics: Map<SummaryMetric, SignedAmount>,
        val accountMovements: List<AccountMovement>,
        val creditFacility: AssetId?,
        val loan: AssetId?,
    ) : SarRowProjection
}

/** Mirrors the persisted-rate branch of TransactionSarEquivalentResolver, not its enrichment path.
 * A complete stored pair is authoritative. No SMS/historical/market fallback or implicit SAR.
 */
object PersistedSarProjection {
    fun project(input: FinancialClassificationInput, fee: LinkedInternationalFee? = null): SarRowProjection {
        fun blocked(reason: SarProjectionBlocker) = SarRowProjection.Unavailable(input.key, reason)
        val classified = FinancialImpactClassifier.classify(freezeProjectionInput(input))
        if (classified !is FinancialClassification.Classified) return blocked(SarProjectionBlocker.FINANCIAL_REVIEW)
        val native = classified.copy(input = freezeProjectionInput(classified.input),
            metrics = Collections.unmodifiableMap(LinkedHashMap(classified.metrics)),
            accountMovements = Collections.unmodifiableList(ArrayList(classified.accountMovements)))
        val facts = native.input
        val sar = if (facts.amount.currency == Currency.SAR) facts.amount else {
            val fx = facts.appliedFx ?: return blocked(SarProjectionBlocker.MISSING_APPLIED_FX)
            if (fx.source !in setOf("SMS", "HISTORICAL_MERCHANT", "MARKET")) {
                return blocked(SarProjectionBlocker.UNKNOWN_FX_SOURCE)
            }
            // Refunds never include purchase fees. Non-SMS resolutions never inherit SMS fees.
            val appliedFee = if (facts.storedType != FinancialType.REFUND && fx.source == "SMS") fee else null
            if (appliedFee != null && (appliedFee.key != facts.key || appliedFee.revision != facts.revision ||
                    appliedFee.evidenceId !in facts.evidenceIds)) return blocked(SarProjectionBlocker.INVALID_FEE_LINK)
            if (appliedFee != null && appliedFee.amount.currency != Currency.SAR) return blocked(SarProjectionBlocker.NON_SAR_FEE)
            val converted = facts.amount.amount.multiply(fx.rateToSar).setScale(2, RoundingMode.HALF_EVEN)
            Money.of(converted + (appliedFee?.amount?.amount ?: BigDecimal.ZERO), Currency.SAR)
        }
        // Reuse the existing resolved financial policy with a derived amount. Never write it back.
        val projected = FinancialImpactClassifier.classify(facts.copy(amount = sar)) as FinancialClassification.Classified
        fun owned(endpoint: FinancialEndpoint) = (endpoint as? FinancialEndpoint.Owned)?.id?.let(facts.assets::get)
        val endpoint = if (native.effectiveType == FinancialType.REFUND) owned(facts.destination) else owned(facts.source)
        val facility = when (endpoint?.kind) {
            AssetKind.CREDIT_FACILITY -> endpoint.id
            AssetKind.CREDIT_CARD -> endpoint.parentId
            else -> null
        }
        val loan = if (native.settlement == Settlement.LOAN) {
            facts.financingLoan ?: owned(facts.destination)?.takeIf { it.kind == AssetKind.LOAN }?.id
        } else null
        return SarRowProjection.Available(native, sar,
            Collections.unmodifiableMap(LinkedHashMap(projected.metrics)),
            Collections.unmodifiableList(ArrayList(projected.accountMovements)), facility, loan)
    }
}

data class SarPeriodProjection(
    val includedCount: Int,
    val unavailable: Map<PostingKey, SarProjectionBlocker>,
    /** Null if any in-period row is unresolved; no partial total masquerades as complete. */
    val totals: Map<SummaryMetric, SignedAmount>?,
    val facilitySpending: Map<AssetId, SignedAmount>?,
    val loanPayments: Map<AssetId, Money>?,
)

/** One current row per stable key, already scoped to the selected environment. No repository reads,
 * period guessing, evidence matching, statement due, outstanding balance or reconciliation here.
 */
object SarPeriodProjector {
    fun project(environment: Environment, period: DisplayPeriod, inputs: List<FinancialClassificationInput>,
        fees: List<LinkedInternationalFee> = emptyList()): SarPeriodProjection {
        val snapshot = inputs.toList().map(::freezeProjectionInput)
        val feeSnapshot = fees.toList()
        require(snapshot.all { it.key.environment == environment }) { "Mixed environment snapshot" }
        require(snapshot.map { it.key }.distinct().size == snapshot.size) { "Supply one current revision per key" }
        val evidence = snapshot.flatMap { it.evidenceIds }
        require(evidence.distinct().size == evidence.size) { "Evidence claimed by multiple current postings" }
        require(feeSnapshot.map { it.key }.distinct().size == feeSnapshot.size) { "Ambiguous fee facts" }
        require(feeSnapshot.all { it.key.environment == environment && snapshot.any { input -> input.key == it.key } })
        val feesByKey = feeSnapshot.associateBy { it.key }
        val rows = snapshot.filter { it.time.instant >= period.from && it.time.instant < period.until }
            .map { PersistedSarProjection.project(it, feesByKey[it.key]) }
        val available = rows.filterIsInstance<SarRowProjection.Available>()
        val unavailable = rows.filterIsInstance<SarRowProjection.Unavailable>().associate { it.key to it.reason }
        if (unavailable.isNotEmpty()) return SarPeriodProjection(available.size, Collections.unmodifiableMap(unavailable), null, null, null)
        val totals = SummaryMetric.entries.associateWith { metric ->
            SignedAmount(available.fold(BigDecimal("0.00")) { sum, row -> sum + row.metrics.getValue(metric).amount }, Currency.SAR)
        }
        val facilities = available.filter { it.creditFacility != null }.groupBy { requireNotNull(it.creditFacility) }
            .mapValues { (_, entries) -> SignedAmount(entries.fold(BigDecimal("0.00")) { sum, row ->
                sum + row.metrics.getValue(SummaryMetric.CREDIT_PURCHASES).amount }, Currency.SAR) }
        val loans = available.filter { it.loan != null }.groupBy { requireNotNull(it.loan) }.mapValues { (_, entries) ->
            Money.of(entries.fold(BigDecimal("0.00")) { sum, row -> sum + row.sarAmount.amount }, Currency.SAR) }
        return SarPeriodProjection(available.size, emptyMap(), Collections.unmodifiableMap(totals),
            Collections.unmodifiableMap(facilities), Collections.unmodifiableMap(loans))
    }
}

private fun freezeProjectionInput(input: FinancialClassificationInput) = input.copy(
    assets = Collections.unmodifiableMap(LinkedHashMap(input.assets)),
    evidenceIds = Collections.unmodifiableSet(LinkedHashSet(input.evidenceIds)),
    unresolved = Collections.unmodifiableSet(LinkedHashSet(input.unresolved)),
)
