package com.baraa.masroof.lab.beta.application.corrections

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.corrections.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.Environment
import java.time.Instant

/** Explicit transaction detail reads, separate from the dashboard projection load path. */
data class BetaTransactionEvidence(val sender: String,val body: String,val receivedAt: Instant)
data class BetaTransactionActionDetail(val token: BetaReviewToken,val allowedTypes: List<FinancialType>,val evidence: List<BetaTransactionEvidence>)

class BetaTransactionDetailWorkflow(private val database: BetaDatabase) {
    private val review=BetaReviewWorkflow(database)
    suspend fun detail(key: PostingKey): BetaTransactionActionDetail? = database.withTransaction {
        val d=review.detail(key) ?: return@withTransaction null
        val input=d.stored?.classification?.input ?: return@withTransaction null
        val types=if(d.stored.matchIdentity!=null) emptyList() else FinancialType.entries.filter {type ->
            type !in setOf(FinancialType.UNKNOWN,FinancialType.ADJUSTMENT,FinancialType.SELF_TRANSFER) &&
                FinancialImpactClassifier.classify(reclassified(input,type)) is FinancialClassification.Classified &&
                (d.current==null || reclassified(input,type).incomeAttribution==input.incomeAttribution)
        }
        BetaTransactionActionDetail(d.token,types,input.evidenceIds.sorted().mapNotNull {id ->database.evidenceDao().raw(id)?.let {BetaTransactionEvidence(it.sender,it.body,Instant.parse(it.receivedAt))}})
    }
    private fun reclassified(input: FinancialClassificationInput,type: FinancialType): FinancialClassificationInput {
        val owner=(input.source as? FinancialEndpoint.Owned) ?: (input.destination as? FinancialEndpoint.Owned)
        val incoming=type in setOf(FinancialType.INCOME,FinancialType.REFUND,FinancialType.EXTERNAL_TRANSFER_IN)
        return input.copy(storedType=type,authority=CorrectionAuthority.USER_FINANCIAL_TYPE,
            source=if(incoming) FinancialEndpoint.External else owner ?: FinancialEndpoint.External,
            destination=if(incoming) owner ?: FinancialEndpoint.External else FinancialEndpoint.External,
            incomeAttribution=if(type in setOf(FinancialType.INCOME,FinancialType.EXTERNAL_TRANSFER_IN)) input.incomeAttribution ?: IncomeAttribution.OTHER else null)
    }
    /** All intermediate overlay changes are in one Room transaction; only the final state commits. */
    suspend fun resolve(token: BetaReviewToken,decisionId: String,type: FinancialType): Boolean = database.withTransaction {
        val current=review.detail(token.key) ?: return@withTransaction false
        val input=current.stored?.classification?.input ?: return@withTransaction false
        if((current.current==null || current.current.activity==BetaLedgerActivity.ACTIVE) && input.storedType==type && current.token.interpretation==token.interpretation && current.token.evidence==token.evidence) return@withTransaction true
        if(current.token!=token) return@withTransaction false
        if(type !in detail(token.key)!!.allowedTypes) return@withTransaction false
        val desired=reclassified(input,type)
        if(current.current==null && token.key.transactionId.startsWith("evidence:")) {
            val id=token.key.transactionId.removePrefix("evidence:")
            val adopter=RoomBetaFinancialAdoption(database)
            val preview=adopter.preview(id) ?: return@withTransaction false
            if(preview.correctionOwned || preview.blockedReason!=null || preview.amount!=input.amount || preview.time!=input.time) return@withTransaction false
            val request=BetaFinancialApproval(id,preview.item.archiveRevision,preview.item.postingRevision,type,
                (desired.source as? FinancialEndpoint.Owned)?.id,(desired.destination as? FinancialEndpoint.Owned)?.id,
                desired.incomeAttribution,preview.registryRevision)
            return@withTransaction adopter.approve(request) is BetaFinancialAdoptionResult.Posted
        }
        // ResolveFinancialType preserves original endpoints; a direction change needs a bound edit.
        var nextToken=token
        if(input.source!=desired.source || input.destination!=desired.destination) {
            check(review.save(nextToken,"$decisionId:seed",FinancialDecision.ResolveFinancialType(input.storedType)) is BetaCorrectionResult.Applied)
            nextToken=requireNotNull(review.detail(token.key)).token
            check(review.save(nextToken,"$decisionId:endpoints",FinancialDecision.EditFacts(source=desired.source,destination=desired.destination)) is BetaCorrectionResult.Applied)
            nextToken=requireNotNull(review.detail(token.key)).token
        }
        val result=review.save(nextToken,"$decisionId:type",FinancialDecision.ResolveFinancialType(type))
        check(result is BetaCorrectionResult.Applied && result.current.activity==BetaLedgerActivity.ACTIVE) {"Classification write incomplete"}
        true
    }
}
