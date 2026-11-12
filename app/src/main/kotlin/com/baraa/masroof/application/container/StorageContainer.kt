package com.baraa.masroof.application.container

import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.baraa.masroof.application.backup.DatabaseRestoreRecovery
import com.baraa.masroof.application.locale.AppLocaleRepository
import com.baraa.masroof.application.logging.AppLogService
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.application.sms.DebugProcessHaltProbe
import com.baraa.masroof.application.theme.ThemePreferencesRepository
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.preferences.SharedPrefsAppLocaleRepository
import com.baraa.masroof.data.preferences.SharedPrefsOnboardingPreferencesRepository
import com.baraa.masroof.data.preferences.SharedPrefsThemePreferencesRepository
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomCommitmentRepository
import com.baraa.masroof.data.repository.RoomFinancialTransactionRepository
import com.baraa.masroof.data.repository.RoomLoanRegistryRepository
import com.baraa.masroof.data.repository.RoomParsedEventRepository
import com.baraa.masroof.data.repository.RoomProcessingRetryRepository
import com.baraa.masroof.data.repository.RoomRawSmsRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.repository.RoomUserCorrectionRepository
import com.baraa.masroof.data.repository.RoomUserDecisionRepository
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.CommitmentRepository
import com.baraa.masroof.domain.repository.FinancialTransactionRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import com.baraa.masroof.domain.repository.ProcessingRetryRepository
import com.baraa.masroof.domain.repository.RawSmsRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.domain.repository.UserCorrectionRepository
import com.baraa.masroof.domain.repository.UserDecisionRepository
import com.baraa.masroof.parsing.repository.ParsedEventRepository
import com.baraa.masroof.sms.time.InstantClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Everything persistent that the other containers share: restore recovery, the database, its access gate,
 * repositories, preferences, the pattern store and the application scope. Built first, once.
 * Construction order matters: restore recovery runs before the pattern store and Room are created.
 */
class StorageContainer(
    context: Context,
    /** Production installs the bundled bank patterns; a test with its own bank adapter does not. */
    installBundledPatterns: Boolean,
    databaseOpenHelperFactory: SupportSQLiteOpenHelper.Factory,
) {
    val appContext: Context = context.applicationContext

    /** Room must not open until an interrupted restore has chosen a valid file. */
    private val databaseRestoreRecovered: Unit = DatabaseRestoreRecovery.recover(appContext)

    val clock: InstantClock = InstantClock.System

    internal val applicationJob = SupervisorJob()
    val applicationScope: CoroutineScope = CoroutineScope(applicationJob + Dispatchers.IO)

    val appLogService: AppLogService = AppLogService(appContext)

    /** Keeps a device-test seed invisible to live processing until the review row exists. */
    suspend fun <R> withDatabaseTransaction(block: suspend () -> R): R =
        databaseAccessGate.withAccess { database.withTransaction(block) }

    val databaseAccessGate: DatabaseAccessGate = DatabaseAccessGate()

    val bundledPatternBanks = appContext.assets.list("bank-patterns").orEmpty().filter { it.endsWith(".patterns.json") }.sorted().flatMap { file ->
        MessagePatternConfigurationCodec.decode(
            appContext.assets.open("bank-patterns/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }).banks
    }

    // Restore recovery has finished and this container has not admitted work yet.
    val messagePatternStore = MessagePatternStore(appContext).also {
        if (installBundledPatterns) it.installMissingBanks(bundledPatternBanks, setOf(Bank.BANK_ALJAZIRA.id))
    }

    internal val database: MasroofDatabase =
        databaseRestoreRecovered.let {
            MasroofDatabase.builder(appContext, MasroofDatabase::class.java, MasroofDatabase.NAME, databaseOpenHelperFactory)
                .build()
        }

    val rawSmsRepository: RawSmsRepository =
        RoomRawSmsRepository(database.rawSmsDao(), accessGate = databaseAccessGate)

    val parsedEventRepository: ParsedEventRepository =
        RoomParsedEventRepository(database.parsedEventDao(), accessGate = databaseAccessGate)

    val accountRegistryRepository: AccountRegistryRepository =
        RoomAccountRegistryRepository.from(database, databaseAccessGate)

    val cardRegistryRepository: CardRegistryRepository =
        RoomCardRegistryRepository.from(database, databaseAccessGate)

    val loanRegistryRepository: LoanRegistryRepository =
        RoomLoanRegistryRepository.from(database, databaseAccessGate)

    val commitmentRepository: CommitmentRepository =
        RoomCommitmentRepository.from(database, databaseAccessGate)

    val financialTransactionRepository: FinancialTransactionRepository =
        RoomFinancialTransactionRepository(
            dao = database.financialTransactionDao(),
            parsedEventDao = database.parsedEventDao(),
            accessGate = databaseAccessGate,
        )

    val reviewRepository: ReviewRepository =
        RoomReviewRepository(database.reviewItemDao(), accessGate = databaseAccessGate)

    val userDecisionRepository: UserDecisionRepository = RoomUserDecisionRepository(database, databaseAccessGate)

    val processingRetryRepository: ProcessingRetryRepository =
        RoomProcessingRetryRepository(database.processingRetryDao(), accessGate = databaseAccessGate)

    val userCorrectionRepository: UserCorrectionRepository =
        RoomUserCorrectionRepository(database.userCorrectionDao(), accessGate = databaseAccessGate)

    val onboardingPreferencesRepository: OnboardingPreferencesRepository =
        SharedPrefsOnboardingPreferencesRepository(
            appContext.getSharedPreferences(
                SharedPrefsOnboardingPreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val appLocaleRepository: AppLocaleRepository =
        SharedPrefsAppLocaleRepository(
            appContext.getSharedPreferences(
                SharedPrefsAppLocaleRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    val themePreferencesRepository: ThemePreferencesRepository =
        SharedPrefsThemePreferencesRepository(
            appContext.getSharedPreferences(
                SharedPrefsThemePreferencesRepository.PREFS_NAME,
                Context.MODE_PRIVATE,
            ),
        )

    /** One probe, shared by the review writes and the SMS pipeline. */
    val debugProcessHaltProbe: DebugProcessHaltProbe = DebugProcessHaltProbe(appContext.filesDir)
}
