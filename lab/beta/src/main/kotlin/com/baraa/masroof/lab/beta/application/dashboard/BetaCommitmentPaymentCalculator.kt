package com.baraa.masroof.lab.beta.application.dashboard

import com.baraa.masroof.lab.finance.dashboard.producer.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.projection.*
import com.baraa.masroof.lab.finance.value.Currency

/** Uses the same immutable rate/fee projection as dashboard totals. No SMS or external rate lookup. */
internal object BetaCommitmentPaymentCalculator {
    fun payments(rows: List<DashboardLedgerRow>,facts: List<DashboardPostingFacts>,fees: List<LinkedInternationalFee>,
        knownFees: Set<PostingKey>): List<BetaCommitmentPayment> {
        val labels=facts.associateBy {it.key};val linkedFees=fees.associateBy {it.key}
        return rows.filter {it.active}.mapNotNull {row ->
            val posting=row.posting;val input=posting.classification.input
            if(posting.classification.effectiveType !in setOf(FinancialType.EXPENSE,FinancialType.FEE,FinancialType.BILL_PAYMENT)) return@mapNotNull null
            if(input.amount.currency!=Currency.SAR && input.appliedFx?.source=="SMS" && input.key !in knownFees) return@mapNotNull null
            val projection=PersistedSarProjection.project(input,linkedFees[input.key]) as? SarRowProjection.Available ?: return@mapNotNull null
            val label=labels[input.key]?.takeIf {it.revision==input.revision}
            BetaCommitmentPayment(input.key.transactionId,posting.classification.effectiveType,projection.sarAmount,input.time.instant,label?.merchant,label?.counterparty)
        }
    }
}
