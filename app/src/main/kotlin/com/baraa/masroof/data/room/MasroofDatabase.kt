package com.baraa.masroof.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import com.baraa.masroof.data.room.dao.AccountRegistryDao
import com.baraa.masroof.data.room.dao.BankRegistryDao
import com.baraa.masroof.data.room.dao.CardRegistryDao
import com.baraa.masroof.data.room.dao.CreditFacilityDao
import com.baraa.masroof.data.room.dao.CommitmentDao
import com.baraa.masroof.data.room.dao.FinancialTransactionDao
import com.baraa.masroof.data.room.dao.LoanRegistryDao
import com.baraa.masroof.data.room.dao.ParsedEventDao
import com.baraa.masroof.data.room.dao.ProcessingRetryDao
import com.baraa.masroof.data.room.dao.RawSmsDao
import com.baraa.masroof.data.room.dao.ReviewItemDao
import com.baraa.masroof.data.room.dao.UserCorrectionDao
import com.baraa.masroof.data.room.entity.AccountRegistryEntity
import com.baraa.masroof.data.room.entity.BankRegistryEntity
import com.baraa.masroof.data.room.entity.CardRegistryEntity
import com.baraa.masroof.data.room.entity.CreditFacilityEntity
import com.baraa.masroof.data.room.entity.LoanRegistryEntity
import com.baraa.masroof.data.room.entity.CommitmentEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionRawSmsLinkEntity
import com.baraa.masroof.data.room.entity.ParsedEventEntity
import com.baraa.masroof.data.room.entity.ProcessingRetryEntity
import com.baraa.masroof.data.room.entity.RawSmsEntity
import com.baraa.masroof.data.room.entity.ReviewItemEntity
import com.baraa.masroof.data.room.entity.UserCorrectionEntity
import com.baraa.masroof.data.room.migration.MIGRATION_1_2
import com.baraa.masroof.data.room.migration.MIGRATION_2_3
import com.baraa.masroof.data.room.migration.MIGRATION_3_4
import com.baraa.masroof.data.room.migration.MIGRATION_4_5
import com.baraa.masroof.data.room.migration.MIGRATION_5_6
import com.baraa.masroof.data.room.migration.MIGRATION_6_7
import com.baraa.masroof.data.room.migration.MIGRATION_8_9
import com.baraa.masroof.data.room.migration.MIGRATION_9_10
import com.baraa.masroof.data.room.migration.MIGRATION_10_11
import com.baraa.masroof.data.room.migration.MIGRATION_11_12
import com.baraa.masroof.data.room.migration.MIGRATION_12_13
import com.baraa.masroof.data.room.migration.MIGRATION_13_14
import com.baraa.masroof.data.room.migration.MIGRATION_14_15
import com.baraa.masroof.data.room.migration.MIGRATION_15_16
import com.baraa.masroof.data.room.migration.MIGRATION_16_17
import com.baraa.masroof.data.room.migration.MIGRATION_7_8

/**
 * Masroof Room persistence schema (current version [VERSION]).
 *
 * Migrations: 1→2 ownership registries; 2→3 financial transactions; 3→4 review workflow;
 * 4→5 exchange rates; 5→6 registry display names and card relationships;
 * 6→7 bank hierarchy (bank_registry, credit_facility, loan_registry, account types);
 * 7→8 opaque registry entity ids; 8→9 loan registry composite key; 9→10 parsed-event dashboard facts;
 * 10→11 bank-neutral parse facts (loan type, debit source account, salary wording);
 * 11→12 user commitments;
 * 12→13 commitment pause history intervals;
 * 13→14 backfill open pause intervals for legacy inactive commitments;
 * 14→15 processing-retry markers;
 * 15→16 transaction timezone provenance;
 * 16→17 query-shape indexes (arrival ordering, typed ranges, batch corrections, card facts).
 * Does not use destructive migration.
 */
@Database(
    entities = [
        RawSmsEntity::class,
        ParsedEventEntity::class,
        BankRegistryEntity::class,
        AccountRegistryEntity::class,
        CardRegistryEntity::class,
        CreditFacilityEntity::class,
        LoanRegistryEntity::class,
        FinancialTransactionEntity::class,
        FinancialTransactionRawSmsLinkEntity::class,
        CommitmentEntity::class,
        ReviewItemEntity::class,
        UserCorrectionEntity::class,
        ProcessingRetryEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class MasroofDatabase : RoomDatabase() {
    abstract fun rawSmsDao(): RawSmsDao

    abstract fun parsedEventDao(): ParsedEventDao

    abstract fun bankRegistryDao(): BankRegistryDao

    abstract fun accountRegistryDao(): AccountRegistryDao

    abstract fun cardRegistryDao(): CardRegistryDao

    abstract fun creditFacilityDao(): CreditFacilityDao

    abstract fun loanRegistryDao(): LoanRegistryDao

    abstract fun financialTransactionDao(): FinancialTransactionDao

    abstract fun commitmentDao(): CommitmentDao

    abstract fun reviewItemDao(): ReviewItemDao

    abstract fun processingRetryDao(): ProcessingRetryDao

    abstract fun userCorrectionDao(): UserCorrectionDao

    companion object {
        const val NAME: String = "masroof.db"
        const val VERSION: Int = 17

        /** Must match app/schemas/.../17.json identityHash. */
        const val IDENTITY_HASH: String = "f18e84acb2bf790d0355fdb694267a1a"

        /** Previous production schema (v16). */
        const val PREVIOUS_VERSION: Int = 16

        /** Must match app/schemas/.../16.json identityHash. */
        const val PREVIOUS_IDENTITY_HASH: String = "9ca34d4e25b5d478101c5b89add2cc70"

        /** Legacy v15 backups (transaction timezone provenance). */
        const val LEGACY_VERSION_15: Int = 15

        /** Must match app/schemas/.../15.json identityHash. */
        const val LEGACY_IDENTITY_HASH_15: String = "50ee81de5b142c23e1fe6afe86cae53d"

        /** Legacy v14 backups (processing-retry markers). */
        const val LEGACY_VERSION_14: Int = 14

        /** Must match app/schemas/.../14.json identityHash. */
        const val LEGACY_IDENTITY_HASH_14: String = "91c2c7a8f0b96cf4a9dc6088ba92e085"

        /** v13 backups share the v14 identity hash; the migration only backfilled rows. */
        const val LEGACY_VERSION_13: Int = 13

        const val LEGACY_IDENTITY_HASH_13: String = "91c2c7a8f0b96cf4a9dc6088ba92e085"

        /** Legacy v12 backups (user commitments). */
        const val LEGACY_VERSION_12: Int = 12

        /** Must match app/schemas/.../12.json identityHash. */
        const val LEGACY_IDENTITY_HASH_12: String = "7e4054824f833d3869883fe0565893db"

        /** Legacy v11 backups (bank-neutral parse facts). */
        const val LEGACY_VERSION_11: Int = 11

        /** Must match app/schemas/.../11.json identityHash. */
        const val LEGACY_IDENTITY_HASH_11: String = "7ab199e3e48084cea08345da07a0daeb"

        /** Legacy v10 backups (pre bank-neutral parse facts). */
        const val LEGACY_VERSION_10: Int = 10

        /** Must match app/schemas/.../10.json identityHash. */
        const val LEGACY_IDENTITY_HASH_10: String = "7e3a9698a7d188a855b1e6736a1fd073"

        /** Legacy v9 backups (pre parsed-event dashboard facts). */
        const val LEGACY_VERSION_9: Int = 9

        /** Must match app/schemas/.../9.json identityHash. */
        const val LEGACY_IDENTITY_HASH_9: String = "a673ee53e423dc6952d5514ed2d14206"

        /** Legacy v8 backups (opaque registry entity ids). */
        const val LEGACY_VERSION_8: Int = 8

        /** Must match app/schemas/.../8.json identityHash. */
        const val LEGACY_IDENTITY_HASH_8: String = "051d1cf4633e66c7ae6b851428871ab4"

        /** Legacy v7 backups (bank hierarchy). */
        const val LEGACY_VERSION_7: Int = 7

        /** Must match app/schemas/.../7.json identityHash. */
        const val LEGACY_IDENTITY_HASH_7: String = "5b63e94003a7d853d7896f020bbec1ec"

        /** Legacy v5 backups (pre card registry metadata). */
        const val LEGACY_VERSION_5: Int = 5

        /** Must match app/schemas/.../5.json identityHash. */
        const val LEGACY_IDENTITY_HASH_5: String = "d192ca81655b31f5e6c203239148ad42"

        /** Legacy v6 backups (pre bank hierarchy). */
        const val LEGACY_VERSION_6: Int = 6

        /** Must match app/schemas/.../6.json identityHash. */
        const val LEGACY_IDENTITY_HASH_6: String = "bae309478a1209a31e06cfcbde30b56f"

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
        )

        /** Room versions accepted by [com.baraa.masroof.application.backup.DatabaseBackupService]. */
        val IMPORTABLE_BACKUP_VERSIONS: Map<Int, String> = mapOf(
            LEGACY_VERSION_5 to LEGACY_IDENTITY_HASH_5,
            LEGACY_VERSION_6 to LEGACY_IDENTITY_HASH_6,
            LEGACY_VERSION_7 to LEGACY_IDENTITY_HASH_7,
            LEGACY_VERSION_8 to LEGACY_IDENTITY_HASH_8,
            LEGACY_VERSION_9 to LEGACY_IDENTITY_HASH_9,
            LEGACY_VERSION_10 to LEGACY_IDENTITY_HASH_10,
            LEGACY_VERSION_11 to LEGACY_IDENTITY_HASH_11,
            LEGACY_VERSION_12 to LEGACY_IDENTITY_HASH_12,
            LEGACY_VERSION_13 to LEGACY_IDENTITY_HASH_13,
            LEGACY_VERSION_14 to LEGACY_IDENTITY_HASH_14,
            LEGACY_VERSION_15 to LEGACY_IDENTITY_HASH_15,
            PREVIOUS_VERSION to PREVIOUS_IDENTITY_HASH,
            VERSION to IDENTITY_HASH,
        )
    }
}
