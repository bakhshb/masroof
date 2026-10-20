package com.baraa.masroof.lab.beta.application.evidence

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.data.*
import java.time.Instant
import org.json.JSONObject

/** User-facing review facts; operational retries are separate from human decisions. */
data class BetaInboxRow(val id: String, val sender: String, val receivedAt: Instant,
    val bank: String?, val family: String?, val amount: String?, val currency: String?,
    val description: String?, val reason: String?, val canApprove: Boolean, val body: String, val dismissed: Boolean = false, val financiallySuppressed: Boolean = false)
data class BetaInboxPage(val rows: List<BetaInboxRow>, val next: String?, val processing: Int)

class BetaReviewInboxWorkflow(private val database: BetaDatabase) {
    suspend fun resolveNonFinancial(id: String, restore: Boolean, type: com.baraa.masroof.lab.finance.posting.FinancialType? = null) = database.withTransaction {
        val dao=database.evidenceDao()
        requireNotNull(dao.raw(id))
        val owner=database.correctionDao().evidenceOwner("BETA",id)
        if(restore && owner!=null && database.correctionDao().current("BETA",owner)?.activity=="SUPPRESSED") {
            val workflow=com.baraa.masroof.lab.beta.application.corrections.BetaReviewWorkflow(database)
            val detail=requireNotNull(workflow.detail(com.baraa.masroof.lab.finance.posting.PostingKey(com.baraa.masroof.lab.finance.value.Environment.BETA,owner)))
            val restoredType=type ?: detail.current?.overlay?.let {com.baraa.masroof.lab.finance.corrections.CorrectionSnapshots.export(it).financial}?.storedType ?: detail.observation.input.storedType
            val result=workflow.save(detail.token,java.util.UUID.randomUUID().toString(),com.baraa.masroof.lab.finance.corrections.FinancialDecision.ResolveFinancialType(restoredType))
            check(result is com.baraa.masroof.lab.beta.application.corrections.BetaCorrectionResult.Applied && result.current.activity==com.baraa.masroof.lab.beta.application.corrections.BetaLedgerActivity.ACTIVE)
            return@withTransaction
        }
        check(database.ledgerDao().evidenceOwner("BETA",id)==null && database.financialAdoptionDao().retry(id)==null)
        val previous=dao.decision(id)
        dao.decision(BetaEvidenceDecisionEntity(id,(previous?.revision ?: 0)+1,
            (if(restore) EvidenceReviewDecision.RESTORE_AUTOMATIC else EvidenceReviewDecision.USER_NON_FINANCIAL).name,
            Instant.now().toString()))
    }

    /** Persist restoration and explicit approval together before the independently retryable post. */
    suspend fun restoreFinancial(request: BetaFinancialApproval): BetaFinancialAdoptionResult =
        RoomBetaFinancialAdoption(database).approve(request,restoreIgnored=true)

    suspend fun page(after: String = "", ignored: Boolean = false): BetaInboxPage = database.withTransaction {
        val dao=database.evidenceDao()
        val store=RoomBetaEvidenceStore(database)
        val rows=mutableListOf<BetaInboxRow>()
        val seenOwnership=mutableSetOf<String>()
        val seenSuppressed=mutableSetOf<String>()
        var cursor=after
        val processing=dao.inboxCounts().processing
        var more=true
        // Bound work per page; cursor advances over non-review evidence as well.
        repeat(8) {
            if(!more || rows.size>=25) return@repeat
            val ids=if(ignored) dao.idsAfter(cursor,25) else dao.reviewIdsAfter(cursor,25)
            if(ids.isEmpty()) {more=false;return@repeat}
            for(id in ids) {
                if(rows.size>=25) break
                cursor=id
                val raw=dao.raw(id) ?: continue
                val summary=store.summary(id)
                if(summary.retryStage!=null) {continue}
                val outcome=dao.outcome(id)
                val archive=outcome?.candidateJson?.let {runCatching {JSONObject(it)}.getOrNull()}
                val facts=archive?.optJSONObject("facts")
                val family=facts?.optString("family")
                val correctionOwner=database.correctionDao().evidenceOwner("BETA",id)
                val suppressed=correctionOwner!=null && database.correctionDao().current("BETA",correctionOwner)?.activity=="SUPPRESSED"
                val dismissed=summary.manualDecision==EvidenceReviewDecision.USER_NON_FINANCIAL || suppressed
                if(ignored && suppressed && !seenSuppressed.add(checkNotNull(correctionOwner))) continue
                val informational=family in setOf("OTP","NON_FINANCIAL","BALANCE_NOTICE","STATEMENT")
                val pending=summary.disposition!=EvidenceDisposition.INTERPRETED || summary.financialReviewReason!=null
                if(if(ignored) !informational && !dismissed else (!pending || informational || dismissed)) continue
                if(!ignored && summary.financialReviewReason in setOf("CONFIRM_OWNERSHIP","LINK_DEBIT_ACCOUNT","CONFIRM_CARD","CONFIGURE_CARD")) {
                    val identity=listOf(archive?.optString("bank"),facts?.optJSONObject("sourceAccount")?.optString("number"),facts?.optJSONObject("destinationAccount")?.optString("number"),facts?.optJSONObject("card")?.optString("number")).joinToString("|")
                    if(!seenOwnership.add(identity)) continue
                }
                val money=facts?.optJSONObject("amount")
                val reason=summary.financialReviewReason ?: summary.reasonCodes.sorted().firstOrNull() ?: "UNSAFE_INTERPRETATION"
                rows+=BetaInboxRow(id,raw.sender,Instant.parse(raw.receivedAt),archive?.optString("bank"),family,
                    money?.optString("amount")?.takeIf {it.toBigDecimalOrNull()!=null},money?.optString("currency")?.takeIf {code -> com.baraa.masroof.lab.finance.value.Currency.entries.any {it.name==code}},
                    facts?.optString("merchant")?.takeUnless {it in setOf("","null")} ?: facts?.optString("counterparty")?.takeUnless {it in setOf("","null")},
                    reason, !dismissed && !informational && (summary.financialReviewReason=="FINANCIAL_APPROVAL_PENDING" || (archive?.optString("status")=="SUCCESS" && summary.financialReviewReason!=null)),raw.body,dismissed,suppressed)
            }
            if(ids.size<25) more=false
        }
        BetaInboxPage(rows,if(more) cursor else null,processing)
    }
}
