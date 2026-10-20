package com.baraa.masroof.lab.beta.data

import androidx.room.*

@Entity(tableName = "beta_raw_evidence", indices = [Index(value = ["namespace", "providerId"], unique = true), Index(value=["receivedAtEpochMillis"]), Index(value=["sender", "receivedAt"])])
internal data class BetaRawEvidenceEntity(
    @PrimaryKey val id: String, val namespace: String, val providerId: String, val origin: String,
    val sender: String, val body: String, val receivedAt: String, val timeSource: String,
    val capturedAt: String, val captureRoute: String, val captureSelections: String, val historyBanks: String,
    val receivedAtEpochMillis: Long = java.time.Instant.parse(receivedAt).toEpochMilli(),
) { override fun toString() = "BetaRawEvidenceEntity(redacted)" }

@DatabaseView(viewName="beta_evidence_outcome",value="SELECT rawId,disposition,route,reasons,baselineJson,candidateJson,updatedAt,attempts FROM beta_parsed_event")
internal data class BetaEvidenceOutcomeEntity(val rawId: String, val disposition: String,
    val route: String, val reasons: String, val baselineJson: String, val candidateJson: String, val updatedAt: String,
    val attempts: Int) {
    override fun toString() = "BetaEvidenceOutcomeEntity(redacted)"
}

@DatabaseView(viewName="beta_evidence_retry",value="SELECT rawId,stage,attempts,code,updatedAt FROM beta_processing_retry WHERE kind='INTERPRETATION'")
internal data class BetaEvidenceRetryEntity(val rawId: String, val stage: String,
    val attempts: Int, val code: String, val updatedAt: String)

internal data class BetaEvidenceDecisionEntity(val rawId: String, val revision: Long,
    val decision: String, val updatedAt: String)

@Dao
internal interface BetaEvidenceDao : BetaAuditWriter {
    @Query("""SELECT id FROM (SELECT MIN(raw.id) AS id FROM beta_raw_evidence raw
        LEFT JOIN beta_evidence_outcome outcome ON outcome.rawId = raw.id
        LEFT JOIN beta_evidence_retry retry ON retry.rawId = raw.id
        LEFT JOIN beta_financial_adoption adoption ON adoption.evidenceId = raw.id
        LEFT JOIN beta_financial_approval_retry approvalRetry ON approvalRetry.evidenceId = raw.id
        LEFT JOIN (SELECT claim.evidenceId,current.transactionId FROM beta_correction_evidence_claim claim JOIN beta_correction_current current
            ON current.environment=claim.environment AND current.transactionId=claim.transactionId
            WHERE claim.environment='BETA' AND current.activity='REVIEW') correctionReview ON correctionReview.evidenceId=raw.id
        LEFT JOIN beta_ownership_exclusion excluded ON excluded.rawId = raw.id
        WHERE excluded.rawId IS NULL AND NOT EXISTS(SELECT 1 FROM beta_correction_evidence_claim claim JOIN beta_correction_current correction ON correction.environment=claim.environment AND correction.transactionId=claim.transactionId WHERE claim.environment='BETA' AND claim.evidenceId=raw.id AND correction.activity='SUPPRESSED') AND COALESCE((SELECT d.operation FROM beta_audit_log d WHERE d.eventKind='EVIDENCE_DECISION' AND d.targetId=raw.id ORDER BY d.transition DESC LIMIT 1),'') != 'USER_NON_FINANCIAL' AND (retry.rawId IS NOT NULL OR approvalRetry.evidenceId IS NOT NULL OR outcome.rawId IS NULL OR outcome.disposition != 'INTERPRETED' OR adoption.status = 'REVIEW' OR correctionReview.transactionId IS NOT NULL)
        GROUP BY CASE WHEN correctionReview.transactionId IS NOT NULL THEN 'correction|' || correctionReview.transactionId WHEN adoption.reason IN ('CONFIRM_OWNERSHIP','LINK_DEBIT_ACCOUNT','CONFIRM_CARD','CONFIGURE_CARD') THEN (SELECT 'ownership|' || COALESCE(p.bankId,'') || '|' || COALESCE(p.sourceAccountLast4,'') || '|' || COALESCE(p.destinationAccountLast4,'') || '|' || COALESCE(p.cardLast4,'') FROM beta_parsed_event p WHERE p.rawId=raw.id) ELSE raw.id END)
        WHERE id > :afterId ORDER BY id LIMIT :limit""")
    suspend fun reviewIdsAfter(afterId: String, limit: Int): List<String>

    @Query("""SELECT COUNT(*) AS total, COUNT(DISTINCT CASE WHEN excluded.rawId IS NULL AND NOT EXISTS(SELECT 1 FROM beta_correction_evidence_claim claim JOIN beta_correction_current correction ON correction.environment=claim.environment AND correction.transactionId=claim.transactionId WHERE claim.environment='BETA' AND claim.evidenceId=raw.id AND correction.activity='SUPPRESSED') AND COALESCE((SELECT d.operation FROM beta_audit_log d WHERE d.eventKind='EVIDENCE_DECISION' AND d.targetId=raw.id ORDER BY d.transition DESC LIMIT 1),'') != 'USER_NON_FINANCIAL' AND retry.rawId IS NULL AND (approvalRetry.evidenceId IS NOT NULL OR outcome.disposition != 'INTERPRETED' OR adoption.status = 'REVIEW' OR correctionReview.transactionId IS NOT NULL) THEN CASE WHEN correctionReview.transactionId IS NOT NULL THEN 'correction|' || correctionReview.transactionId WHEN adoption.reason IN ('CONFIRM_OWNERSHIP','LINK_DEBIT_ACCOUNT','CONFIRM_CARD','CONFIGURE_CARD') THEN (SELECT 'ownership|' || COALESCE(p.bankId,'') || '|' || COALESCE(p.sourceAccountLast4,'') || '|' || COALESCE(p.destinationAccountLast4,'') || '|' || COALESCE(p.cardLast4,'') FROM beta_parsed_event p WHERE p.rawId=raw.id) ELSE raw.id END ELSE NULL END) AS needsReview,
        COALESCE(SUM(CASE WHEN excluded.rawId IS NULL AND NOT EXISTS(SELECT 1 FROM beta_correction_evidence_claim claim JOIN beta_correction_current correction ON correction.environment=claim.environment AND correction.transactionId=claim.transactionId WHERE claim.environment='BETA' AND claim.evidenceId=raw.id AND correction.activity='SUPPRESSED') AND COALESCE((SELECT d.operation FROM beta_audit_log d WHERE d.eventKind='EVIDENCE_DECISION' AND d.targetId=raw.id ORDER BY d.transition DESC LIMIT 1),'') != 'USER_NON_FINANCIAL' AND retry.rawId IS NULL AND approvalRetry.evidenceId IS NULL AND outcome.disposition = 'INTERPRETED' AND adoption.status = 'REVIEW' AND adoption.reason IN ('CONFIRM_OWNERSHIP', 'LINK_DEBIT_ACCOUNT', 'CONFIRM_CARD', 'CONFIGURE_CARD') THEN 1 ELSE 0 END), 0) AS needsOwnership,
        COALESCE(SUM(CASE WHEN retry.rawId IS NOT NULL OR outcome.rawId IS NULL THEN 1 ELSE 0 END),0) AS processing
        FROM beta_raw_evidence raw
        LEFT JOIN beta_evidence_outcome outcome ON outcome.rawId = raw.id
        LEFT JOIN beta_evidence_retry retry ON retry.rawId = raw.id
        LEFT JOIN beta_financial_adoption adoption ON adoption.evidenceId = raw.id
        LEFT JOIN beta_financial_approval_retry approvalRetry ON approvalRetry.evidenceId = raw.id
        LEFT JOIN (SELECT claim.evidenceId,current.transactionId FROM beta_correction_evidence_claim claim JOIN beta_correction_current current
            ON current.environment=claim.environment AND current.transactionId=claim.transactionId
            WHERE claim.environment='BETA' AND current.activity='REVIEW') correctionReview ON correctionReview.evidenceId=raw.id
        LEFT JOIN beta_ownership_exclusion excluded ON excluded.rawId = raw.id""")
    suspend fun inboxCounts(): com.baraa.masroof.lab.beta.application.evidence.EvidenceInboxCounts

    @Query("SELECT EXISTS(SELECT 1 FROM beta_raw_evidence candidate WHERE id != :id AND sender=:sender AND body=:body AND (receivedAt=:receivedAt OR (origin IN ('LIVE','EXPLICIT_HISTORY') AND abs(receivedAtEpochMillis-(SELECT receivedAtEpochMillis FROM beta_raw_evidence WHERE id=:id)) <= 120000)))")
    suspend fun hasDuplicate(id: String, sender: String, body: String, receivedAt: String): Boolean
    @Query("SELECT id FROM beta_raw_evidence WHERE id > :afterId ORDER BY id LIMIT :limit") suspend fun idsAfter(afterId: String, limit: Int): List<String>
    @Query("SELECT * FROM beta_raw_evidence WHERE id=:id") suspend fun raw(id: String): BetaRawEvidenceEntity?
    @Query("SELECT rawSmsId FROM beta_raw_sms_provider_alias WHERE providerMessageId=:id") suspend fun providerAlias(id: String): String?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertProviderAlias(row: BetaProviderAliasRecord): Long
    @Query("SELECT * FROM beta_raw_evidence WHERE sender=:sender AND body=:body AND receivedAt=:at AND (origin='LIVE' OR (origin='EXPLICIT_HISTORY' AND namespace='beta-device-inbox-v1')) ORDER BY id LIMIT 2")
    suspend fun deviceReceipt(sender: String,body: String,at: String): List<BetaRawEvidenceEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRaw(raw: BetaRawEvidenceEntity): Long
    @Query("SELECT * FROM beta_evidence_outcome WHERE rawId=:id") suspend fun outcome(id: String): BetaEvidenceOutcomeEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM beta_audit_log WHERE id=:id)") suspend fun auditExists(id: String): Boolean
    @Upsert suspend fun parsed(row: BetaParsedRecord)
    @Query("SELECT * FROM beta_parsed_event WHERE rawId=:id") suspend fun parsedRecord(id: String): BetaParsedRecord?
    suspend fun outcome(outcome: BetaEvidenceOutcomeEntity) {
        val j=try { org.json.JSONObject(outcome.candidateJson) } catch (_: org.json.JSONException) { org.json.JSONObject() }
        val f=j.optJSONObject("facts") ?: org.json.JSONObject()
        fun text(o: org.json.JSONObject,key: String): String? = if(o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
        fun reference(key: String)=f.optJSONObject(key)?.let { text(it,"number")?.takeLast(4) }
        val amount=f.optJSONObject("amount")
        val bank=text(j,"bank")
        val local=text(f,"occurredAtLocal")
        val at=try { text(f,"occurredAt")?.let(java.time.Instant::parse)?.toEpochMilli()
            ?: if(bank=="BANK_ALJAZIRA") local?.let(java.time.LocalDateTime::parse)?.atZone(java.time.ZoneId.of("Asia/Riyadh"))?.toInstant()?.toEpochMilli() else null
        } catch (_: java.time.DateTimeException) { null }
        val record=BetaParsedRecord(outcome.rawId,outcome.disposition,outcome.route,outcome.reasons,outcome.baselineJson,outcome.candidateJson,outcome.updatedAt,outcome.attempts,
            bankId=bank,messageFamily=text(f,"family"),parseStatus=text(j,"status"),amountDecimal=amount?.let { text(it,"amount") },amountCurrency=amount?.let { text(it,"currency") },
            direction=text(f,"direction"),occurredAtEpochMillis=at,occurredAtLocal=local,cardLast4=reference("card"),sourceAccountLast4=reference("sourceAccount"),destinationAccountLast4=reference("destinationAccount"),
            merchant=text(f,"merchant"),counterparty=text(f,"counterparty"),engineVersion=text(j,"engineVersion"),rulesVersion=text(j,"rulesVersion"),contractVersion=j.optInt("contractVersion").takeIf { it>0 })
        val old=parsedRecord(outcome.rawId)
        parsed(if(old==null) record else record.copy(f_epochMillis=old.f_epochMillis,a_epochMillis=old.a_epochMillis,f_present=old.f_present,a_present=old.a_present,f_transactionId=old.f_transactionId,f_bankId=old.f_bankId,f_archiveFingerprint=old.f_archiveFingerprint,f_observationRevision=old.f_observationRevision,f_correction=old.f_correction,f_interpretation=old.f_interpretation,f_boundInput=old.f_boundInput,f_observedAt=old.f_observedAt,f_feeKnown=old.f_feeKnown,f_feeAmount=old.f_feeAmount,f_feeCurrency=old.f_feeCurrency,f_balanceAsset=old.f_balanceAsset,f_balanceAmount=old.f_balanceAmount,f_balanceCurrency=old.f_balanceCurrency,f_facilityAsset=old.f_facilityAsset,f_dueAmount=old.f_dueAmount,f_dueCurrency=old.f_dueCurrency,f_dueDate=old.f_dueDate,f_merchant=old.f_merchant,f_counterparty=old.f_counterparty,f_debitCardAsset=old.f_debitCardAsset,f_debitCardLast4=old.f_debitCardLast4,f_debitCardParent=old.f_debitCardParent,f_messageFamily=old.f_messageFamily,a_bankId=old.a_bankId,a_archiveFingerprint=old.a_archiveFingerprint,a_outcomeAttempt=old.a_outcomeAttempt,a_outcomeAt=old.a_outcomeAt,a_nonFinancial=old.a_nonFinancial,a_registryBinding=old.a_registryBinding,a_boundInput=old.a_boundInput,a_observedAt=old.a_observedAt,a_balanceAsset=old.a_balanceAsset,a_balanceAmount=old.a_balanceAmount,a_balanceCurrency=old.a_balanceCurrency,a_facilityAsset=old.a_facilityAsset,a_dueAmount=old.a_dueAmount,a_dueCurrency=old.a_dueCurrency,a_dueDate=old.a_dueDate,a_loanAsset=old.a_loanAsset,a_loanType=old.a_loanType,a_loanRemaining=old.a_loanRemaining,a_loanRemainingCurrency=old.a_loanRemainingCurrency,a_loanInstallment=old.a_loanInstallment,a_loanInstallmentCurrency=old.a_loanInstallmentCurrency,a_statementProved=old.a_statementProved))
        val auditKey=auditId("INTERPRETATION",outcome.rawId,outcome.attempts,outcome.candidateJson)
        if(!auditExists(auditKey)) appendAudit(BetaAuditRecord(auditKey,"INTERPRETATION",targetId=outcome.rawId,
            interpretation=outcome.attempts.toLong(),payload=outcome.candidateJson,createdAt=outcome.updatedAt))
    }
    @Query("SELECT * FROM beta_evidence_retry WHERE rawId=:id") suspend fun retry(id: String): BetaEvidenceRetryEntity?
    @Upsert suspend fun retryRecord(row: BetaRetryRecord)
    suspend fun retry(retry: BetaEvidenceRetryEntity) = retryRecord(BetaRetryRecord(retry.rawId,"INTERPRETATION",retry.stage,retry.attempts,retry.code,retry.updatedAt))
    @Query("DELETE FROM beta_processing_retry WHERE rawId=:id AND kind='INTERPRETATION'") suspend fun clearRetry(id: String)
    @Query("SELECT rawId FROM beta_evidence_retry ORDER BY updatedAt,rawId LIMIT 1000") suspend fun pending(): List<String>
    @Query("SELECT rawId FROM beta_evidence_retry WHERE rawId>:afterId ORDER BY rawId LIMIT :limit")
    suspend fun pendingAfter(afterId: String,limit: Int): List<String>
    @Query("SELECT targetId AS rawId,transition AS revision,operation AS decision,createdAt AS updatedAt FROM beta_audit_log WHERE eventKind='EVIDENCE_DECISION' AND targetId=:id ORDER BY transition DESC LIMIT 1") suspend fun decision(id: String): BetaEvidenceDecisionEntity?
    suspend fun decision(decision: BetaEvidenceDecisionEntity) = appendAudit(BetaAuditRecord(
        auditId("EVIDENCE_DECISION",decision.rawId,decision.revision),"EVIDENCE_DECISION",targetId=decision.rawId,transition=decision.revision,operation=decision.decision,createdAt=decision.updatedAt))
}
