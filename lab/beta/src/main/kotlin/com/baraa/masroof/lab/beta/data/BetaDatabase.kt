package com.baraa.masroof.lab.beta.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.baraa.masroof.lab.beta.BetaEnvironment

/** Lab-only schema. Version 14 gains nullable commitment facts without rewriting financial evidence. */
@Database(entities=[BetaRawEvidenceEntity::class,BetaProviderAliasRecord::class,BetaParsedRecord::class,
    BetaBankSelectionEntity::class,BetaAccountRecord::class,BetaCardRecord::class,BetaCreditFacilityRecord::class,BetaLoanRecord::class,
    BetaLedgerCurrentEntity::class,BetaEvidenceLinkRecord::class,BetaCommitmentRecord::class,BetaReviewRecord::class,
    BetaCorrectionCurrentEntity::class,BetaRetryRecord::class,BetaAuditRecord::class,BetaLearningLabelEntity::class],
    views=[BetaAssetEntity::class,BetaEvidenceOutcomeEntity::class,BetaEvidenceRetryEntity::class,
        BetaFinancialAdoptionEntity::class,BetaFinancialApprovalRetryEntity::class,BetaOwnershipDecisionEntity::class,
        BetaOwnershipExclusionEntity::class,BetaDashboardFactsEntity::class,BetaDashboardAuxFactsEntity::class,
        BetaCorrectionEvidenceClaimEntity::class],
    version=BetaDatabase.VERSION,exportSchema=true)
abstract class BetaDatabase : RoomDatabase() {
    abstract fun bankSelectionDao(): BetaBankSelectionDao
    internal abstract fun ownershipDao(): BetaOwnershipDao
    internal abstract fun assetDao(): BetaAssetDao
    internal abstract fun learningDao(): BetaLearningDao
    internal abstract fun evidenceDao(): BetaEvidenceDao
    internal abstract fun ledgerDao(): BetaLedgerDao
    internal abstract fun correctionDao(): BetaCorrectionDao
    internal abstract fun financialAdoptionDao(): BetaFinancialAdoptionDao
    internal abstract fun dashboardFactsDao(): BetaDashboardFactsDao
    internal abstract fun dashboardAuxFactsDao(): BetaDashboardAuxFactsDao
    internal abstract fun dashboardConfigurationDao(): BetaDashboardConfigurationDao
    internal abstract fun shellMarkerDao(): BetaShellMarkerDao
    companion object { const val NAME: String=BetaEnvironment.DATABASE_NAME; const val VERSION: Int=15 }
}
