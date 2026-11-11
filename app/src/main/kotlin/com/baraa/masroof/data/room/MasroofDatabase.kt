package com.baraa.masroof.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
import com.baraa.masroof.data.room.entity.RawSmsProviderAliasEntity
import com.baraa.masroof.data.room.entity.ReviewItemEntity
import com.baraa.masroof.data.room.entity.UserCorrectionEntity

/**
 * Masroof Room persistence schema (current version [VERSION]).
 *
 * Opens [BASELINE_VERSION] up to [VERSION] through additive [MIGRATIONS]. Older, newer and unknown
 * schemas are rejected by Room with the file untouched; there is never a destructive fallback.
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
    ],
    version = 18,
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

    companion object {
        /**
         * Live database file. [com.baraa.masroof.application.backup.DatabaseRestoreRecovery]
         * must finish before Room opens this name.
         */
        const val NAME: String = "masroof.db"
        const val VERSION: Int = 18

        /** Oldest schema that opens; anything older has no migration and is rejected. */
        const val BASELINE_VERSION: Int = 18

        /** Must match app/schemas/.../18.json identityHash. Filled after Room export. */
        const val IDENTITY_HASH: String = "71f1193767b3aa12bcb6cc6e7ce77147"

        /**
         * The identity hash of every schema from [BASELINE_VERSION] to [VERSION], the only pairs a backup may carry.
         * A version bump keeps the old entry as a literal and adds the new one; the exported JSON files are checked against it.
         */
        val IDENTITY_HASHES: Map<Int, String> = mapOf(VERSION to IDENTITY_HASH)

        /** One additive step per version from [BASELINE_VERSION] to [VERSION], each tested from the v18 fixture. */
        val MIGRATIONS: List<Migration> = emptyList()

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
