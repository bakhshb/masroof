package com.baraa.masroof.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.baraa.masroof.data.room.dao.AccountRegistryDao
import com.baraa.masroof.data.room.dao.BankRegistryDao
import com.baraa.masroof.data.room.dao.CardRegistryDao
import com.baraa.masroof.data.room.dao.CommitmentDao
import com.baraa.masroof.data.room.dao.FinancialTransactionDao
import com.baraa.masroof.data.room.dao.LoanRegistryDao
import com.baraa.masroof.data.room.dao.ParsedEventDao
import com.baraa.masroof.data.room.dao.ProcessingRetryDao
import com.baraa.masroof.data.room.dao.RawSmsDao
import com.baraa.masroof.data.room.dao.ReviewItemDao
import com.baraa.masroof.data.room.dao.UserCorrectionDao
import com.baraa.masroof.data.room.dao.UserDecisionDao
import com.baraa.masroof.data.room.entity.AccountRegistryEntity
import com.baraa.masroof.data.room.entity.BankRegistryEntity
import com.baraa.masroof.data.room.entity.CardRegistryEntity
import com.baraa.masroof.data.room.entity.CreditFacilityEntity
import com.baraa.masroof.data.room.entity.LoanRegistryEntity
import com.baraa.masroof.data.room.entity.CommitmentEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import com.baraa.masroof.data.room.entity.FinancialTransactionRawSmsLinkEntity
import com.baraa.masroof.data.room.entity.ParsedEventEntity
import com.baraa.masroof.data.room.entity.PatternConfigurationEntity
import com.baraa.masroof.data.room.entity.ProcessingRetryEntity
import com.baraa.masroof.data.room.entity.RawSmsEntity
import com.baraa.masroof.data.room.entity.RawSmsProviderAliasEntity
import com.baraa.masroof.data.room.entity.ReviewItemEntity
import com.baraa.masroof.data.room.entity.UserCorrectionEntity
import com.baraa.masroof.data.room.entity.UserDecisionEntity

/**
 * Masroof Room persistence schema (current version [VERSION]).
 *
 * Opens [BASELINE_VERSION] and [VERSION] only, the baseline through the one additive [MIGRATION_18_22]. Older, newer,
 * intermediate and unknown schemas are rejected by Room with the file untouched; there is never a destructive fallback.
 */
@Database(
    entities = [
        RawSmsEntity::class,
        RawSmsProviderAliasEntity::class,
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
        UserDecisionEntity::class,
        PatternConfigurationEntity::class,
    ],
    version = MasroofDatabase.VERSION,
    exportSchema = true,
)
abstract class MasroofDatabase : RoomDatabase() {
    abstract fun rawSmsDao(): RawSmsDao

    abstract fun parsedEventDao(): ParsedEventDao

    abstract fun bankRegistryDao(): BankRegistryDao

    abstract fun accountRegistryDao(): AccountRegistryDao

    abstract fun cardRegistryDao(): CardRegistryDao


    abstract fun loanRegistryDao(): LoanRegistryDao

    abstract fun financialTransactionDao(): FinancialTransactionDao

    abstract fun commitmentDao(): CommitmentDao

    abstract fun reviewItemDao(): ReviewItemDao

    abstract fun processingRetryDao(): ProcessingRetryDao

    abstract fun userCorrectionDao(): UserCorrectionDao

    abstract fun userDecisionDao(): UserDecisionDao

    companion object {
        /**
         * Live database file. [com.baraa.masroof.application.backup.DatabaseRestoreRecovery]
         * must finish before Room opens this name.
         */
        const val NAME: String = "masroof.db"
        const val VERSION: Int = 22

        /** Oldest schema that opens; anything older has no migration and is rejected. */
        const val BASELINE_VERSION: Int = 18

        /** Must match data/schemas/.../22.json identityHash. */
        const val IDENTITY_HASH: String = "5349c1b666aed8239c225512f4498078"

        /**
         * The only (version, identity hash) pairs that open or that a backup may carry: the v18 baseline and [VERSION].
         * v19 to v21 existed only in development builds and are refused like any unknown schema (owner, 2026-11-12).
         */
        val IDENTITY_HASHES: Map<Int, String> = mapOf(
            BASELINE_VERSION to "71f1193767b3aa12bcb6cc6e7ce77147",
            VERSION to IDENTITY_HASH,
        )

        /**
         * The single step from the v18 baseline (owner, 2026-11-12), additive only: creates the append-only `user_decision`
         * table in its final shape and backfills it from the legacy stores (plan 2.2, [UserDecisionBackfill]), and creates
         * the empty one-row `pattern_configuration` table (plan 5.1). The message-pattern file and the analytics
         * recurring answers are copied in when the live database opens, never here. Room checks only the tables after a
         * migration, so the step first refuses an unknown v18 hash, inside the upgrade transaction (the file stays untouched).
         */
        val MIGRATION_18_22: Migration = object : Migration(BASELINE_VERSION, VERSION) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val hash = db.query("SELECT identity_hash FROM room_master_table").use { if (it.moveToFirst()) it.getString(0) else null }
                check(hash == IDENTITY_HASHES.getValue(BASELINE_VERSION)) { "Unknown schema $BASELINE_VERSION identity hash" }
                // Copied from 22.json; Room validates the result on open.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_decision` (`sequence` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`rawSmsId` TEXT, `pairedRawSmsId` TEXT, `kind` TEXT NOT NULL, " +
                        "`decidedAtEpochMillis` INTEGER NOT NULL, `transactionType` TEXT, `sourceContainerId` TEXT, " +
                        "`destinationContainerId` TEXT, `occurredAtEpochMillis` INTEGER, `occurredAtZone` TEXT, " +
                        "`messageFamily` TEXT, `amountDecimal` TEXT, `amountCurrency` TEXT, " +
                        "`merchant` TEXT, `counterparty` TEXT, `bankId` TEXT, `subjectKey` TEXT, `answer` TEXT, `payload` TEXT, " +
                        "FOREIGN KEY(`rawSmsId`) REFERENCES `raw_sms`(`id`) ON UPDATE CASCADE ON DELETE RESTRICT , " +
                        "FOREIGN KEY(`pairedRawSmsId`) REFERENCES `raw_sms`(`id`) ON UPDATE CASCADE ON DELETE RESTRICT )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_decision_rawSmsId_sequence` ON `user_decision` (`rawSmsId`, `sequence`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_decision_pairedRawSmsId` ON `user_decision` (`pairedRawSmsId`)",
                )
                UserDecisionBackfill.run(db)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pattern_configuration` (`id` INTEGER NOT NULL, `generation` INTEGER NOT NULL, " +
                        "`document` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        val MIGRATIONS: List<Migration> = listOf(MIGRATION_18_22)

        /**
         * The builder for the live financial file. [databaseClass] fixes the target version; tests pass a
         * later test-only class and add its step, so production never names a future schema.
         */
        fun <T : RoomDatabase> builder(
            context: Context,
            databaseClass: Class<T>,
            name: String,
            openHelperFactory: SupportSQLiteOpenHelper.Factory = PreservingOpenHelperFactory(),
        ): RoomDatabase.Builder<T> =
            Room.databaseBuilder(context, databaseClass, name)
                .addMigrations(*MIGRATIONS.toTypedArray())
                .openHelperFactory(openHelperFactory)
    }
}
