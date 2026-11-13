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
 * Masroof Room persistence schema, version [VERSION]. Any other version or identity hash is rejected by Room with
 * the file untouched; there is never a destructive fallback. A later schema change adds its step to [MIGRATIONS].
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
        const val VERSION: Int = 1

        /** Must match data/schemas/.../1.json identityHash. */
        const val IDENTITY_HASH: String = "5349c1b666aed8239c225512f4498078"

        /** Additive, tested steps from version 1 on; none yet. */
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
