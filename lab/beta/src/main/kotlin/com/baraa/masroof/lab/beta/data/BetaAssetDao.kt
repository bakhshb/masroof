package com.baraa.masroof.lab.beta.data

import androidx.room.*

@Dao
internal interface BetaAssetDao : BetaAuditWriter {
    @Query("SELECT environment,bankId,localId,ownershipStatus AS status,metadataRevision AS revision FROM beta_account_registry UNION ALL SELECT environment,bankId,localId,ownershipStatus AS status,metadataRevision AS revision FROM beta_card_registry UNION ALL SELECT environment,bankId,localId,ownershipStatus AS status,metadataRevision AS revision FROM beta_credit_facility UNION ALL SELECT environment,bankId,localId,ownershipStatus AS status,metadataRevision AS revision FROM beta_loan_registry ORDER BY bankId,localId")
    suspend fun tracking(): List<BetaAssetTrackingRow>
    @Query("UPDATE beta_account_registry SET ownershipStatus=:status,metadataRevision=metadataRevision+1 WHERE environment=:environment AND bankId=:bank AND localId=:id AND metadataRevision=:revision")
    suspend fun trackingAccount(environment: String,bank: String,id: String,status: String,revision: Long): Int
    @Query("UPDATE beta_card_registry SET ownershipStatus=:status,metadataRevision=metadataRevision+1 WHERE environment=:environment AND bankId=:bank AND localId=:id AND metadataRevision=:revision")
    suspend fun trackingCard(environment: String,bank: String,id: String,status: String,revision: Long): Int
    @Query("UPDATE beta_loan_registry SET ownershipStatus=:status,metadataRevision=metadataRevision+1 WHERE environment=:environment AND bankId=:bank AND localId=:id AND metadataRevision=:revision")
    suspend fun trackingLoan(environment: String,bank: String,id: String,status: String,revision: Long): Int
    @Query("UPDATE beta_account_registry SET displayName=:name,network=:network,accountType=:accountType,loanType=:loanType,metadataRevision=metadataRevision+1 WHERE environment=:environment AND bankId=:bank AND localId=:id AND metadataRevision=:revision")
    suspend fun metadataAccount(environment: String,bank: String,id: String,name: String?,network: String?,accountType: String?,loanType: String?,revision: Long): Int
    @Insert suspend fun account(row: BetaAccountRecord)
    @Query("UPDATE beta_card_registry SET displayName=:name,network=:network,accountType=:accountType,loanType=:loanType,metadataRevision=metadataRevision+1 WHERE environment=:environment AND bankId=:bank AND localId=:id AND metadataRevision=:revision")
    suspend fun metadataCard(environment: String,bank: String,id: String,name: String?,network: String?,accountType: String?,loanType: String?,revision: Long): Int
    @Insert suspend fun card(row: BetaCardRecord)
    @Query("UPDATE beta_credit_facility SET displayName=:name,network=:network,accountType=:accountType,loanType=:loanType,metadataRevision=metadataRevision+1 WHERE environment=:environment AND bankId=:bank AND localId=:id AND metadataRevision=:revision")
    suspend fun metadataFacility(environment: String,bank: String,id: String,name: String?,network: String?,accountType: String?,loanType: String?,revision: Long): Int
    @Insert suspend fun facility(row: BetaCreditFacilityRecord)
    @Query("UPDATE beta_loan_registry SET displayName=:name,network=:network,accountType=:accountType,loanType=:loanType,metadataRevision=metadataRevision+1 WHERE environment=:environment AND bankId=:bank AND localId=:id AND metadataRevision=:revision")
    suspend fun metadataLoan(environment: String,bank: String,id: String,name: String?,network: String?,accountType: String?,loanType: String?,revision: Long): Int
    @Insert suspend fun loan(row: BetaLoanRecord)
    @Query("UPDATE beta_account_registry SET last4=:suffix WHERE environment=:environment AND bankId=:bank AND localId=:id")
    suspend fun updateAccountFields(environment: String,bank: String,id: String,suffix: String?): Int
    suspend fun updateAccount(row: BetaAccountRecord): Int = updateAccountFields(row.environment,row.bankId,row.localId,row.last4)
    @Query("UPDATE beta_card_registry SET last4=:suffix,kind=:kind,parentLocalId=:parent,creditCardRole=:role,linkedAccountLocalId=CASE WHEN :kind='DEBIT_CARD' THEN :parent ELSE NULL END,facilityLocalId=CASE WHEN :kind='CREDIT_CARD' THEN :parent ELSE NULL END WHERE environment=:environment AND bankId=:bank AND localId=:id")
    suspend fun updateCardFields(environment: String,bank: String,id: String,suffix: String?,kind: String,parent: String?,role: String?): Int
    suspend fun updateCard(row: BetaCardRecord): Int = updateCardFields(row.environment,row.bankId,row.localId,row.last4,row.kind,row.parentLocalId,row.creditCardRole)
    @Query("UPDATE beta_credit_facility SET last4=:suffix WHERE environment=:environment AND bankId=:bank AND localId=:id")
    suspend fun updateFacilityFields(environment: String,bank: String,id: String,suffix: String?): Int
    suspend fun updateFacility(row: BetaCreditFacilityRecord): Int = updateFacilityFields(row.environment,row.bankId,row.localId,row.last4)
    @Query("UPDATE beta_loan_registry SET last4=:suffix WHERE environment=:environment AND bankId=:bank AND localId=:id")
    suspend fun updateLoanFields(environment: String,bank: String,id: String,suffix: String?): Int
    suspend fun updateLoan(row: BetaLoanRecord): Int = updateLoanFields(row.environment,row.bankId,row.localId,row.last4)

    suspend fun insert(asset: BetaAssetEntity) {
        check(get(asset.environment,asset.bankId,asset.localId)==null) { "Asset identity already exists" }
        when(asset.kind) {
            "ACCOUNT" -> account(BetaAccountRecord(asset.environment,asset.bankId,asset.localId,asset.last4))
            "DEBIT_CARD", "CREDIT_CARD" -> card(BetaCardRecord(asset.environment,asset.bankId,asset.localId,asset.last4,asset.kind,asset.parentLocalId,asset.creditCardRole))
            "CREDIT_FACILITY" -> facility(BetaCreditFacilityRecord(asset.environment,asset.bankId,asset.localId,asset.last4))
            "LOAN" -> loan(BetaLoanRecord(asset.environment,asset.bankId,asset.localId,asset.last4))
            else -> error("Unsupported asset kind")
        }
    }
    suspend fun update(asset: BetaAssetEntity): Int = when(asset.kind) {
        "ACCOUNT" -> updateAccount(BetaAccountRecord(asset.environment,asset.bankId,asset.localId,asset.last4))
        "DEBIT_CARD", "CREDIT_CARD" -> updateCard(BetaCardRecord(asset.environment,asset.bankId,asset.localId,asset.last4,asset.kind,asset.parentLocalId,asset.creditCardRole))
        "CREDIT_FACILITY" -> updateFacility(BetaCreditFacilityRecord(asset.environment,asset.bankId,asset.localId,asset.last4))
        "LOAN" -> updateLoan(BetaLoanRecord(asset.environment,asset.bankId,asset.localId,asset.last4))
        else -> error("Unsupported asset kind")
    }

    @Query("SELECT a.* FROM beta_audit_log a WHERE eventKind='ASSET_ALIAS' AND environment=:environment AND bankId=:bank AND targetId=:id AND aliasKind='LAST4' AND evidenceId='manual-owned-confirmation' AND NOT EXISTS(SELECT 1 FROM beta_audit_log removed WHERE removed.eventKind='ALIAS_REMOVED' AND removed.relatedId=a.id)")
    suspend fun manualAliases(environment: String, bank: String, id: String): List<BetaAuditRecord>
    suspend fun clearManualSuffix(environment: String, bank: String, id: String) {
        manualAliases(environment,bank,id).forEach { alias -> appendAudit(BetaAuditRecord(java.util.UUID.randomUUID().toString(),"ALIAS_REMOVED",environment,id,relatedId=alias.id)) }
    }
    @Query("SELECT EXISTS(SELECT 1 FROM beta_audit_log a WHERE eventKind='ASSET_ALIAS' AND environment=:environment AND bankId=:bank AND targetId=:id AND aliasKind=:kind AND aliasValue=:value AND evidenceId=:evidence AND NOT EXISTS(SELECT 1 FROM beta_audit_log r WHERE r.eventKind='ALIAS_REMOVED' AND r.relatedId=a.id))")
    suspend fun aliasExists(environment: String,bank: String,id: String,kind: String,value: String,evidence: String): Boolean
    suspend fun insertAlias(alias: BetaAssetAliasEntity) {
        require(get(alias.environment,alias.bankId,alias.assetLocalId)!=null) { "Alias needs a registered identity" }
        require(!aliasExists(alias.environment,alias.bankId,alias.assetLocalId,alias.kind,alias.value,alias.evidenceId)) { "Alias already exists" }
        appendAudit(BetaAuditRecord(java.util.UUID.randomUUID().toString(),"ASSET_ALIAS",alias.environment,alias.assetLocalId,
            bankId=alias.bankId,aliasKind=alias.kind,aliasValue=alias.value,evidenceId=alias.evidenceId))
    }
    @Query("SELECT EXISTS(SELECT 1 FROM beta_audit_log a WHERE eventKind='ASSET_ALIAS' AND environment=:environment AND bankId=:bank AND targetId=:id AND aliasKind='LAST4' AND aliasValue=:value AND evidenceId=:evidence AND NOT EXISTS(SELECT 1 FROM beta_audit_log r WHERE r.eventKind='ALIAS_REMOVED' AND r.relatedId=a.id))")
    suspend fun hasProposalAlias(environment: String, bank: String, id: String, value: String, evidence: String): Boolean
    @Query("SELECT DISTINCT a.* FROM beta_asset a JOIN beta_audit_log x ON x.eventKind='ASSET_ALIAS' AND a.environment=x.environment AND a.bankId=x.bankId AND a.localId=x.targetId WHERE x.environment=:environment AND x.bankId=:bank AND substr(x.evidenceId,1,length(:prefix))=:prefix AND NOT EXISTS(SELECT 1 FROM beta_audit_log r WHERE r.eventKind='ALIAS_REMOVED' AND r.relatedId=x.id) ORDER BY a.localId")
    suspend fun proposalBindings(environment: String, bank: String, prefix: String): List<BetaAssetEntity>
    @Query("SELECT * FROM beta_asset WHERE environment=:environment AND bankId=:bank AND localId=:id")
    suspend fun get(environment: String, bank: String, id: String): BetaAssetEntity?
    @Query("SELECT * FROM beta_asset ORDER BY environment,bankId,localId")
    suspend fun all(): List<BetaAssetEntity>
    @Query("SELECT DISTINCT a.* FROM beta_asset a JOIN beta_audit_log x ON x.eventKind='ASSET_ALIAS' AND a.environment=x.environment AND a.bankId=x.bankId AND a.localId=x.targetId WHERE x.environment=:environment AND x.bankId=:bank AND x.aliasKind=:kind AND x.aliasValue=:value AND NOT EXISTS(SELECT 1 FROM beta_audit_log r WHERE r.eventKind='ALIAS_REMOVED' AND r.relatedId=x.id) ORDER BY a.localId")
    suspend fun candidates(environment: String, bank: String, kind: String, value: String): List<BetaAssetEntity>
}

internal data class BetaAssetTrackingRow(val environment: String,val bankId: String,val localId: String,val status: String,val revision: Long)
