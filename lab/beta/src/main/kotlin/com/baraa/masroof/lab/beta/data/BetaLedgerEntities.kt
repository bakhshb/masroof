package com.baraa.masroof.lab.beta.data

import androidx.room.*

/** Payloads are explicit versioned financial facts, never SMS text or parser archives. */
@Entity(tableName = "beta_ledger_current", primaryKeys = ["environment", "transactionId"])
internal data class BetaLedgerCurrentEntity(
    val environment: String, val transactionId: String,
    val correction: Long, val interpretation: Long, val payload: String,
)

@Entity(tableName = "beta_ledger_history", primaryKeys = ["environment", "transactionId", "correction", "interpretation"],
    foreignKeys = [ForeignKey(entity = BetaLedgerCurrentEntity::class, parentColumns = ["environment", "transactionId"],
        childColumns = ["environment", "transactionId"], onDelete = ForeignKey.RESTRICT)])
internal data class BetaLedgerHistoryEntity(
    val environment: String, val transactionId: String,
    val correction: Long, val interpretation: Long, val payload: String,
)

/** Claims are retained even when a later revision drops a link. No REPLACE or delete API. */
@Entity(tableName = "beta_ledger_evidence_claim", primaryKeys = ["environment", "evidenceId"],
    foreignKeys = [ForeignKey(entity = BetaLedgerCurrentEntity::class, parentColumns = ["environment", "transactionId"],
        childColumns = ["environment", "transactionId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["environment", "transactionId"])])
internal data class BetaLedgerEvidenceClaimEntity(val environment: String, val evidenceId: String, val transactionId: String)

@Entity(tableName = "beta_ledger_proof_claim", primaryKeys = ["environment", "kind", "issuer", "reference"],
    foreignKeys = [ForeignKey(entity = BetaLedgerCurrentEntity::class, parentColumns = ["environment", "transactionId"],
        childColumns = ["environment", "transactionId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["environment", "transactionId"])])
internal data class BetaLedgerProofClaimEntity(
    val environment: String, val kind: String, val issuer: String, val reference: String, val transactionId: String,
)

@Dao
internal interface BetaLedgerDao {
    @Query("SELECT * FROM beta_ledger_current WHERE environment=:environment AND transactionId=:transactionId")
    suspend fun current(environment: String, transactionId: String): BetaLedgerCurrentEntity?

    @Query("SELECT * FROM beta_ledger_history WHERE environment=:environment AND transactionId=:transactionId ORDER BY correction, interpretation")
    suspend fun history(environment: String, transactionId: String): List<BetaLedgerHistoryEntity>

    @Query("SELECT transactionId FROM beta_ledger_evidence_claim WHERE environment=:environment AND evidenceId=:evidenceId")
    suspend fun evidenceOwner(environment: String, evidenceId: String): String?

    @Query("SELECT transactionId FROM beta_ledger_proof_claim WHERE environment=:environment AND kind=:kind AND issuer=:issuer AND reference=:reference")
    suspend fun proofOwner(environment: String, kind: String, issuer: String, reference: String): String?

    @Upsert suspend fun writeCurrent(row: BetaLedgerCurrentEntity)
    @Insert suspend fun appendHistory(row: BetaLedgerHistoryEntity)
    @Insert suspend fun claimEvidence(row: BetaLedgerEvidenceClaimEntity)
    @Insert suspend fun claimProof(row: BetaLedgerProofClaimEntity)
}
