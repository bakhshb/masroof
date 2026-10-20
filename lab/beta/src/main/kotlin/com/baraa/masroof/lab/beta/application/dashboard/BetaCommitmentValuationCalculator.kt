package com.baraa.masroof.lab.beta.application.dashboard

import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.dashboard.producer.DashboardLedgerRow
import com.baraa.masroof.lab.finance.dashboard.DisplayPeriod
import com.baraa.masroof.lab.finance.posting.PostingKey
import com.baraa.masroof.lab.finance.projection.*
import com.baraa.masroof.lab.finance.value.*
import java.math.RoundingMode

internal data class BetaValuedCommitments(val draft: BetaDashboardConfigurationDraft,val complete: Boolean)

/** Source-bound valuation: native currency is retained; the SAR hint rounds once at the money boundary. */
internal object BetaCommitmentValuationCalculator {
    fun resolve(draft: BetaDashboardConfigurationDraft,sources: List<DashboardLedgerRow>,fees: List<LinkedInternationalFee>,
        knownFees: Set<PostingKey>,period: DisplayPeriod?): BetaValuedCommitments {
        val byId=sources.filter {it.active}.associateBy {it.posting.command.key.transactionId}
        val linked=fees.associateBy {it.key};var complete=true
        val definitions=draft.commitments.mapNotNull {definition ->
            if(definition.amount.currency==Currency.SAR || BetaCommitmentOccurrenceCalculator.scheduled(draft,definition,period).isEmpty()) return@mapNotNull definition
            val source=byId[definition.sourceTransactionId]?.posting
            val input=source?.classification?.input
            val value=if(input==null || input.amount.currency!=definition.amount.currency || input.amount.amount.signum()<=0 ||
                (input.appliedFx?.source=="SMS" && input.key !in knownFees)) null
                else (PersistedSarProjection.project(input,linked[input.key]) as? SarRowProjection.Available)?.sarAmount
            if(value==null) {complete=false;null} else {
                val converted=value.amount.multiply(definition.amount.amount).divide(input!!.amount.amount,2,RoundingMode.HALF_EVEN)
                definition.copy(amount=Money.of(converted,Currency.SAR))
            }
        }
        return BetaValuedCommitments(draft.copy(commitments=definitions),complete)
    }
}
