package com.baraa.masroof.lab.beta.data

import androidx.room.*

/** Isolated lab schema. Only the immediately preceding lab version has an explicit metadata upgrade. */
@Entity(tableName = "beta_raw_sms_provider_alias", indices = [Index("rawSmsId")], foreignKeys = [ForeignKey(entity=BetaRawEvidenceEntity::class, parentColumns=["id"], childColumns=["rawSmsId"], onDelete=ForeignKey.RESTRICT)])
internal data class BetaProviderAliasRecord(@PrimaryKey val providerMessageId: String, val rawSmsId: String)

@Entity(tableName = "beta_parsed_event", indices = [Index(value=["bankId", "occurredAtEpochMillis"]), Index(value=["messageFamily"]), Index(value=["cardLast4", "bankId"]), Index(value=["f_epochMillis"]),Index(value=["a_epochMillis"]),Index(value=["f_bankId","f_balanceAsset","f_epochMillis"]),Index(value=["a_bankId","a_facilityAsset","a_epochMillis"]),Index(value=["a_bankId","a_balanceAsset","a_epochMillis"]),Index(value=["a_bankId","a_loanAsset","a_epochMillis"])],
 foreignKeys=[ForeignKey(entity=BetaRawEvidenceEntity::class,parentColumns=["id"],childColumns=["rawId"],onDelete=ForeignKey.RESTRICT)])
internal data class BetaParsedRecord(@PrimaryKey val rawId: String, val disposition: String, val route: String,
    val reasons: String, val baselineJson: String, val candidateJson: String, val updatedAt: String, val attempts: Int,
    val bankId: String? = null, val messageFamily: String? = null, val parseStatus: String? = null,
    val amountDecimal: String? = null, val amountCurrency: String? = null, val direction: String? = null,
    val occurredAtEpochMillis: Long? = null, val occurredAtLocal: String? = null,
    val cardLast4: String? = null, val sourceAccountLast4: String? = null, val destinationAccountLast4: String? = null,
    val merchant: String? = null, val counterparty: String? = null,
    val engineVersion: String? = null, val rulesVersion: String? = null, val contractVersion: Int? = null,
    val f_epochMillis: Long? = null, val a_epochMillis: Long? = null,
    val ownershipExcluded: Boolean = false, val f_present: Boolean = false, val a_present: Boolean = false,
    val f_transactionId: String? = null,
    val f_bankId: String? = null,
    val f_archiveFingerprint: String? = null,
    val f_observationRevision: Long = 0,
    val f_correction: Long = 0,
    val f_interpretation: Long = 0,
    val f_boundInput: String? = null,
    val f_observedAt: String? = null,
    val f_feeKnown: Boolean = false,
    val f_feeAmount: String? = null,
    val f_feeCurrency: String? = null,
    val f_balanceAsset: String? = null,
    val f_balanceAmount: String? = null,
    val f_balanceCurrency: String? = null,
    val f_facilityAsset: String? = null,
    val f_dueAmount: String? = null,
    val f_dueCurrency: String? = null,
    val f_dueDate: String? = null,
    val f_merchant: String? = null,
    val f_counterparty: String? = null,
    val f_debitCardAsset: String? = null,
    val f_debitCardLast4: String? = null,
    val f_debitCardParent: String? = null,
    val f_messageFamily: String? = null,
    val a_bankId: String? = null,
    val a_archiveFingerprint: String? = null,
    val a_outcomeAttempt: Int = 0,
    val a_outcomeAt: String? = null,
    val a_nonFinancial: Boolean = false,
    val a_registryBinding: String? = null,
    val a_boundInput: String? = null,
    val a_observedAt: String? = null,
    val a_balanceAsset: String? = null,
    val a_balanceAmount: String? = null,
    val a_balanceCurrency: String? = null,
    val a_facilityAsset: String? = null,
    val a_dueAmount: String? = null,
    val a_dueCurrency: String? = null,
    val a_dueDate: String? = null,
    val a_loanAsset: String? = null,
    val a_loanType: String? = null,
    val a_loanRemaining: String? = null,
    val a_loanRemainingCurrency: String? = null,
    val a_loanInstallment: String? = null,
    val a_loanInstallmentCurrency: String? = null,
    val a_statementProved: Boolean = false,
)

@Entity(tableName = "beta_account_registry", primaryKeys = ["environment", "bankId", "localId"], indices = [Index(value = ["bankId", "last4"])])
internal data class BetaAccountRecord(val environment: String, val bankId: String, val localId: String,
    val last4: String? = null,  val displayName: String? = null, val ownershipStatus: String = "OWNED", val network: String?=null,val accountType: String?=null,val loanType: String?=null,val metadataRevision: Long=0)

@Entity(tableName = "beta_card_registry", primaryKeys = ["environment", "bankId", "localId"],
    indices = [Index(value = ["bankId", "last4"]),Index(value=["environment","bankId","linkedAccountLocalId"]),Index(value=["environment","bankId","facilityLocalId"])],
    foreignKeys=[ForeignKey(entity=BetaAccountRecord::class,parentColumns=["environment","bankId","localId"],childColumns=["environment","bankId","linkedAccountLocalId"],onDelete=ForeignKey.RESTRICT),
        ForeignKey(entity=BetaCreditFacilityRecord::class,parentColumns=["environment","bankId","localId"],childColumns=["environment","bankId","facilityLocalId"],onDelete=ForeignKey.RESTRICT)])
internal data class BetaCardRecord(val environment: String, val bankId: String, val localId: String,
    val last4: String? = null, val kind: String, val parentLocalId: String? = null, val creditCardRole: String? = null, val displayName: String? = null, val ownershipStatus: String = "OWNED",
    val linkedAccountLocalId: String? = parentLocalId.takeIf { kind=="DEBIT_CARD" },
    val facilityLocalId: String? = parentLocalId.takeIf { kind=="CREDIT_CARD" },val network: String?=null,val accountType: String?=null,val loanType: String?=null,val metadataRevision: Long=0)

@Entity(tableName = "beta_credit_facility", primaryKeys = ["environment", "bankId", "localId"], indices = [Index(value = ["bankId", "last4"])])
internal data class BetaCreditFacilityRecord(val environment: String, val bankId: String, val localId: String,
    val last4: String? = null,  val displayName: String? = null, val ownershipStatus: String = "OWNED", val network: String?=null,val accountType: String?=null,val loanType: String?=null,val metadataRevision: Long=0)

@Entity(tableName = "beta_loan_registry", primaryKeys = ["environment", "bankId", "localId"], indices = [Index(value = ["bankId", "last4"])])
internal data class BetaLoanRecord(val environment: String, val bankId: String, val localId: String,
    val last4: String? = null,  val displayName: String? = null, val ownershipStatus: String = "OWNED", val network: String?=null,val accountType: String?=null,val loanType: String?=null,val metadataRevision: Long=0)

@Entity(tableName="beta_review_item", indices=[Index(value=["recordKind", "status"]),Index(value=["bankId"]),Index(value=["evidenceId"],unique=true),Index(value=["transactionId"],unique=true)],
    foreignKeys=[ForeignKey(entity=BetaRawEvidenceEntity::class,parentColumns=["id"],childColumns=["evidenceId"],onDelete=ForeignKey.RESTRICT)])
internal data class BetaReviewRecord(@PrimaryKey val id: String, val recordKind: String,
    val evidenceId: String? = null, val transactionId: String? = null, val bankId: String,
    val status: String, val reason: String = "", val archiveFingerprint: String = "", val approvedInput: String = "",
    val correction: Long = 0, val interpretation: Long = 0, val approvalRequest: String = "", val observationRevision: Long = 0,
    val referenceKind: String? = null, val suffix: String? = null, val assetLocalId: String? = null, val revision: Long = 0)

@Entity(tableName="beta_processing_retry", primaryKeys=["rawId", "kind"], indices=[Index(value=["kind", "updatedAt"])], foreignKeys=[ForeignKey(entity=BetaRawEvidenceEntity::class,parentColumns=["id"],childColumns=["rawId"],onDelete=ForeignKey.RESTRICT)])
internal data class BetaRetryRecord(val rawId: String, val kind: String, val stage: String = "", val attempts: Int = 0,
    val code: String = "", val updatedAt: String = "", val request: String? = null)

@Entity(tableName="beta_commitment", indices=[Index("active")])
internal data class BetaCommitmentRecord(@PrimaryKey val id: String, val label: String, val amountDecimal: String,
    val amountCurrency: String, val dueDay: Int, val starts: String, val ends: String? = null,
    val active: Boolean = true, val paidMonths: String = "[]", val pauseIntervals: String = "[]", val sourceTransactionId: String? = null,
    @ColumnInfo(defaultValue="'MONTHLY'") val recurrence: String? = "MONTHLY",
    val transactionDate: String? = null, val dueDate: String? = null)

/** Append-only decisions, revisions, aliases and retained financial proof claims. */
@Entity(tableName="beta_audit_log", indices=[Index(value=["eventKind", "environment", "targetId", "transition"]),
    Index(value=["eventKind", "environment", "decisionId"],unique=true),
    Index(value=["eventKind", "environment", "proofKind", "issuer", "reference"],unique=true),
    Index(value=["eventKind", "bankId", "aliasKind", "aliasValue"]), Index("relatedId")])
internal data class BetaAuditRecord(@PrimaryKey val id: String, val eventKind: String,
    val environment: String = "BETA", val targetId: String, val correction: Long = 0, val interpretation: Long = 0,
    val transition: Long = 0, val decisionId: String? = null, val operation: String = "", val payload: String = "",
    val proofKind: String? = null, val issuer: String? = null, val reference: String? = null,
    val bankId: String? = null, val aliasKind: String? = null, val aliasValue: String? = null,
    val evidenceId: String? = null, val relatedId: String? = null, val createdAt: String = java.time.Instant.now().toString())

internal interface BetaAuditWriter {
    @Insert suspend fun appendAudit(record: BetaAuditRecord)
}

internal fun auditId(vararg fields: Any?): String {
    val digest=java.security.MessageDigest.getInstance("SHA-256")
    fields.forEach { field -> val bytes=field?.toString()?.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array()); if(bytes!=null) digest.update(bytes) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
