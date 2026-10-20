package com.baraa.masroof.lab.beta.data

import androidx.room.*

/** An anchor exists even for suppression before any financial posting. No fake ledger row. */
@Entity(tableName = "beta_correction_current", primaryKeys = ["environment", "transactionId"])
internal data class BetaCorrectionCurrentEntity(
    val environment: String, val transactionId: String, val correction: Long, val interpretation: Long,
    val transition: Long, val activity: String, val payload: String,
    val occurredAtEpochMillis: Long? = null,
)

internal data class BetaCorrectionAuditEntity(val environment: String,val transactionId: String,val transition: Long,
    val decisionId: String?,val operation: String,val payload: String)
@DatabaseView(viewName="beta_correction_evidence_claim",value="SELECT environment,evidenceId,transactionId FROM beta_financial_transaction_raw_sms_link WHERE correctionClaim=1")
internal data class BetaCorrectionEvidenceClaimEntity(val environment: String,val evidenceId: String,val transactionId: String)
internal data class BetaCorrectionProofClaimEntity(val environment: String,val kind: String,val issuer: String,val reference: String,val transactionId: String)

@Dao internal interface BetaCorrectionDao : BetaAuditWriter {
    @Query("SELECT createdAt FROM beta_audit_log WHERE eventKind='CORRECTION_REVISION' AND environment=:environment AND targetId=:id AND decisionId=:decisionId ORDER BY transition DESC LIMIT 1")
    suspend fun decisionRecordedAt(environment: String,id: String,decisionId: String): String?

    @Query("SELECT * FROM beta_correction_current WHERE environment=:environment AND transactionId=:id")
    suspend fun current(environment: String, id: String): BetaCorrectionCurrentEntity?
    @Query("SELECT environment,targetId AS transactionId,transition,decisionId,operation,payload FROM beta_audit_log WHERE eventKind='CORRECTION_REVISION' AND environment=:environment AND decisionId=:id")
    suspend fun decision(environment: String, id: String): BetaCorrectionAuditEntity?
    @Query("SELECT environment,targetId AS transactionId,transition,decisionId,operation,payload FROM beta_audit_log WHERE eventKind='CORRECTION_REVISION' AND environment=:environment AND targetId=:id ORDER BY transition")
    suspend fun history(environment: String, id: String): List<BetaCorrectionAuditEntity>
    @Query("SELECT transactionId FROM beta_financial_transaction_raw_sms_link WHERE environment=:environment AND evidenceId=:id AND correctionClaim=1")
    suspend fun evidenceOwner(environment: String, id: String): String?
    @Query("SELECT targetId FROM beta_audit_log WHERE eventKind='CORRECTION_PROOF' AND environment=:environment AND proofKind=:kind AND issuer=:issuer AND reference=:reference")
    suspend fun proofOwner(environment: String, kind: String, issuer: String, reference: String): String?
    @Upsert suspend fun saveCurrent(row: BetaCorrectionCurrentEntity)
    suspend fun current(row: BetaCorrectionCurrentEntity) {
        val snapshot=com.baraa.masroof.lab.finance.corrections.CorrectionSnapshots.export(BetaCorrectionCodec.decode(row.payload).overlay)
        saveCurrent(row.copy(occurredAtEpochMillis=(snapshot.financial ?: snapshot.latestObservation.input).time.instant.toEpochMilli()))
    }
    suspend fun append(row: BetaCorrectionAuditEntity) = appendAudit(BetaAuditRecord(
        auditId("CORRECTION_REVISION",row.environment,row.transactionId,row.transition),"CORRECTION_REVISION",row.environment,row.transactionId,
        transition=row.transition,decisionId=row.decisionId,operation=row.operation,payload=row.payload))
    @Query("SELECT * FROM beta_financial_transaction_raw_sms_link WHERE environment=:environment AND evidenceId=:id")
    suspend fun reservation(environment: String,id: String): BetaEvidenceLinkRecord?
    @Insert suspend fun insertReservation(row: BetaEvidenceLinkRecord)
    @Query("UPDATE beta_financial_transaction_raw_sms_link SET correctionClaim=1,correctionTransactionId=:owner WHERE environment=:environment AND evidenceId=:id AND transactionId=:owner")
    suspend fun markCorrection(environment: String,id: String,owner: String): Int
    suspend fun evidence(row: BetaCorrectionEvidenceClaimEntity) {
        val old=reservation(row.environment,row.evidenceId)
        require(old==null || old.transactionId==row.transactionId) { "Evidence already reserved" }
        if(old==null) insertReservation(BetaEvidenceLinkRecord(row.environment,row.evidenceId,row.transactionId,correctionClaim=true))
        else check(markCorrection(row.environment,row.evidenceId,row.transactionId)==1)
    }
    suspend fun proof(row: BetaCorrectionProofClaimEntity) = appendAudit(BetaAuditRecord(
        auditId("CORRECTION_PROOF",row.environment,row.kind,row.issuer,row.reference),"CORRECTION_PROOF",row.environment,row.transactionId,
        proofKind=row.kind,issuer=row.issuer,reference=row.reference))
}
