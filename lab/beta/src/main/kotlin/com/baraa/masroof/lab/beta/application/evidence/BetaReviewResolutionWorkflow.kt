package com.baraa.masroof.lab.beta.application.evidence

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.IncomeAttribution
import com.baraa.masroof.lab.finance.posting.FinancialType
import org.json.JSONObject
import java.time.Instant

/** Display/action suggestions only. The financial writer revalidates every explicit request. */
data class BetaReviewDetail(val row: BetaInboxRow, val preview: BetaFinancialApprovalPreview?,
    val ownership: List<BetaOwnershipCandidate>, val actions: List<FinancialType>,
    val source: AssetId?, val destination: AssetId?, val income: IncomeAttribution?,
    val loanConfiguration: Boolean, val assetMetadata: Map<AssetId,BetaAssetMetadata>, val reasons: Set<String>, val suggestedType: FinancialType? = null, val resolvedAt: Instant? = null, val merchant: String? = null, val counterparty: String? = null,
    val correctionAction: com.baraa.masroof.lab.beta.application.corrections.BetaTransactionActionDetail? = null)

class BetaReviewResolutionWorkflow(private val database: BetaDatabase) {
    suspend fun detail(id: String): BetaReviewDetail = database.withTransaction {
        val dao=database.evidenceDao()
        val raw=requireNotNull(dao.raw(id))
        val summary=RoomBetaEvidenceStore(database).summary(id)
        val archive=dao.outcome(id)?.candidateJson?.let {runCatching {JSONObject(it)}.getOrNull()}
        val facts=archive?.optJSONObject("facts")
        fun text(key: String)=facts?.optString(key)?.takeUnless {it in setOf("","null")}
        val money=facts?.optJSONObject("amount")
        val correctionOwner=database.correctionDao().evidenceOwner("BETA",id)
        val suppressed=correctionOwner!=null && database.correctionDao().current("BETA",correctionOwner)?.activity=="SUPPRESSED"
        val dismissed=summary.manualDecision==EvidenceReviewDecision.USER_NON_FINANCIAL || suppressed
        val reason=summary.financialReviewReason ?: summary.reasonCodes.sorted().firstOrNull()
        val row=BetaInboxRow(id,raw.sender,Instant.parse(raw.receivedAt),archive?.optString("bank"),text("family"),
            money?.optString("amount")?.takeIf {it.toBigDecimalOrNull()!=null},money?.optString("currency"),
            text("merchant") ?: text("counterparty"),reason,false,raw.body,dismissed,suppressed,resolvedAt=if(dismissed) RoomBetaReviewTimes(database).ignoredAt(id,correctionOwner,suppressed) else null)
        val preview=RoomBetaFinancialAdoption(database).preview(id)
        val store=RoomBetaEvidenceStore(database)
        val refs=store.ownershipObservations(id).map {it.reference}.toSet()
        val ownership=BetaOwnershipWorkflow(database).snapshot().candidates.filter {it.reference in refs}
        val proposals=store.discoveryProposals(id)
        val assets=preview?.assets.orEmpty()
        suspend fun resolve(kind: AssetKind): OwnedAsset? {
            val candidates=proposals.filter {it.kind==kind}.mapNotNull {proposal ->
                val matches=assets.filter {it.id.bank==proposal.id.bank && it.last4==proposal.last4 && it.kind==kind}
                val chosen=store.proposalBinding(proposal)
                if(chosen!=null) matches.singleOrNull {it.id==chosen} else matches.singleOrNull()
            }.distinctBy {it.id}
            return candidates.singleOrNull()
        }
        val account=resolve(AssetKind.ACCOUNT)
        val debit=resolve(AssetKind.DEBIT_CARD)
        val credit=resolve(AssetKind.CREDIT_CARD)
        val owner=credit?.id ?: debit?.parentId ?: account?.id
        val metadata=database.assetDao().all().associate {entity ->
            val id=AssetId(com.baraa.masroof.lab.finance.value.Environment.valueOf(entity.environment),com.baraa.masroof.lab.finance.value.BankId(entity.bankId),entity.localId)
            id to BetaAssetMetadata(entity.displayName,entity.network,entity.accountType,entity.loanType,entity.metadataRevision)
        }
        val family=text("family")
        val type=when(family) {
            "PURCHASE" -> FinancialType.EXPENSE; "REFUND" -> FinancialType.REFUND
            "WITHDRAWAL" -> FinancialType.CASH_WITHDRAWAL; "FEE" -> FinancialType.FEE
            "BILL_PAYMENT" -> FinancialType.BILL_PAYMENT; "FINANCING_INSTALLMENT" -> FinancialType.LOAN_REPAYMENT
            "CARD_PAYMENT" -> FinancialType.CREDIT_CARD_PAYMENT; "TRANSFER_IN" -> FinancialType.EXTERNAL_TRANSFER_IN
            "TRANSFER_OUT" -> FinancialType.EXTERNAL_TRANSFER_OUT; else -> null
        }
        val loan=assets.filter {it.kind==AssetKind.LOAN && it.id.bank.value==row.bank && metadata[it.id]?.loanType==text("loanType")}.singleOrNull()
        val incoming=family in setOf("REFUND","TRANSFER_IN")
        val correctionKey=correctionOwner?.let {com.baraa.masroof.lab.finance.posting.PostingKey(com.baraa.masroof.lab.finance.value.Environment.BETA,it)}
        val correction=correctionKey?.let {com.baraa.masroof.lab.beta.application.corrections.BetaReviewWorkflow(database).detail(it)}
        val correctionAction=if(correction!=null && correction.current?.overlay?.safetyHold==false && !dismissed)
            com.baraa.masroof.lab.beta.application.corrections.BetaTransactionDetailWorkflow(database).detail(correction.token.key) else null
        BetaReviewDetail(row,preview,ownership,
            if(type==FinancialType.EXTERNAL_TRANSFER_IN) listOf(type,FinancialType.INCOME) else if(type==FinancialType.EXTERNAL_TRANSFER_OUT) listOf(type) else if(type!=null) listOf(FinancialType.EXPENSE,FinancialType.INCOME,FinancialType.CREDIT_CARD_PAYMENT,FinancialType.REFUND,FinancialType.CASH_WITHDRAWAL,FinancialType.BILL_PAYMENT,FinancialType.FEE,FinancialType.LOAN_REPAYMENT) else emptyList(),
            if(incoming) null else if(type in setOf(FinancialType.LOAN_REPAYMENT,FinancialType.CREDIT_CARD_PAYMENT)) account?.id else owner,
            if(type==FinancialType.LOAN_REPAYMENT) loan?.id else if(type==FinancialType.CREDIT_CARD_PAYMENT) credit?.id else if(incoming) owner else null,
            if(text("salaryIncomeWording")=="true") IncomeAttribution.SALARY else IncomeAttribution.OTHER,
            reason=="CONFIRM_LOAN",metadata,summary.reasonCodes+listOfNotNull(reason)+
                archive?.optJSONArray("diagnostics")?.let {ds -> (0 until ds.length()).map {ds.getJSONObject(it).optString("code")}}.orEmpty(), type, row.resolvedAt,text("merchant"),text("counterparty"),correctionAction)
    }
}
