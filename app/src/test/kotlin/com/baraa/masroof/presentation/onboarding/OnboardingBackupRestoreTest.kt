package com.baraa.masroof.presentation.onboarding

import android.net.Uri
import com.baraa.masroof.application.backup.BackupImportOutcome
import com.baraa.masroof.application.backup.BackupPackageKind
import com.baraa.masroof.application.backup.DatabaseBackupGateway
import com.baraa.masroof.application.onboarding.HistoricalImportGateway
import com.baraa.masroof.application.onboarding.OnboardingPreferencesRepository
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ReviewItem
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.repository.NoOpLoanRegistryRepository
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.testsupport.NoOpCardRegistryRepository
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.application.sms.SmsScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingBackupRestoreTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun restoreBackup_invalidPackage_setsError() = runTest {
        val backup = FakeBackupGateway(BackupImportOutcome.InvalidPackage)
        val accounts = EmptyAccountRepo()
        val cards = EmptyCardRepo()
        val vm = OnboardingViewModel(
            onboardingPrefs = object : OnboardingPreferencesRepository {
                override fun isOnboardingStarted() = false
                override fun setOnboardingStarted(started: Boolean) = Unit
                override fun isOnboardingCompleted() = false
                override fun setOnboardingCompleted(completed: Boolean) = Unit
                override fun getHistoricalImportStartEpochMillis(): Long? = null
                override fun setHistoricalImportStartEpochMillis(epochMillis: Long?) = Unit
                override fun isHistoricalImportCompleted() = false
                override fun setHistoricalImportCompleted(completed: Boolean) = Unit
            },
            historicalImportGateway = HistoricalImportGateway {
                com.baraa.masroof.application.onboarding.HistoricalImportResult(scanned = 0, parsed = 0, duplicates = 0)
            },
            onboardingOwnershipWorkflow = com.baraa.masroof.testsupport.PresentationWorkflowTestSupport
                .onboardingOwnershipWorkflow(
                    accounts = accounts,
                    cards = cards,
                    reviewRepository = EmptyReviewRepo(),
                ),
            discoverFromStoredEvents = { 0 },
            refreshReviewQueue = {},
            databaseBackupService = backup,
            permissionStateProvider = { false },
            zoneId = ZoneId.systemDefault(),
            clock = Clock.fixed(Instant.parse("2026-08-11T08:00:00Z"), ZoneId.systemDefault()),
        )
        advanceUntilIdle()
        vm.restoreBackup(Uri.parse("content://backup/invalid.masroof"))
        advanceUntilIdle()
        assertEquals(OnboardingError.BACKUP_RESTORE_INVALID, vm.uiState.value.error)
        assertFalse(vm.uiState.value.restoringBackup)
    }

    private class FakeBackupGateway(
        private val outcome: BackupImportOutcome,
        private val kind: BackupPackageKind = BackupPackageKind.UNRECOGNIZED,
    ) : DatabaseBackupGateway {
        override suspend fun exportTo(destination: Uri, passphrase: CharArray): Result<Unit> = Result.success(Unit)

        override suspend fun inspect(source: Uri): BackupPackageKind = kind

        override suspend fun importFrom(
            source: Uri,
            passphrase: CharArray,
            confirmLegacyPlaintext: Boolean,
        ): BackupImportOutcome = outcome
    }

    private class EmptyAccountRepo : AccountRegistryRepository {
        override suspend fun observe(reference: AccountReference, rawSmsId: String) = Unit
        override suspend fun setOwnership(reference: AccountReference, status: OwnershipStatus) = Unit
        override suspend fun resolve(reference: AccountReference) = OwnershipStatus.UNKNOWN
        override suspend fun get(reference: AccountReference): AccountRegistryEntry? = null
        override suspend fun listAll(): List<AccountRegistryEntry> = emptyList()
        override suspend fun updateDisplayName(reference: AccountReference, displayName: String?) = Unit
            override suspend fun updateAccountType(reference: com.baraa.masroof.domain.model.AccountReference, accountType: com.baraa.masroof.domain.model.AccountType) = Unit
    }

    private class EmptyCardRepo : NoOpCardRegistryRepository()

    private class EmptyReviewRepo : ReviewRepository {
        override suspend fun getById(id: String): ReviewItem? = null
        override suspend fun findByRawSmsId(rawSmsId: String): ReviewItem? = null
        override suspend fun listRequired(): List<ReviewItem> = emptyList()
        override suspend fun listIgnored(): List<ReviewItem> = emptyList()
        override suspend fun listAll(): List<ReviewItem> = emptyList()
        override suspend fun upsertRequired(
            rawSmsId: String,
            kind: ReviewKind,
            reasons: List<String>,
            now: Instant,
        ): ReviewItem = error("unused")
        override suspend fun markResolved(
            id: String,
            resolutionKind: ReviewResolutionKind,
            resolvedAt: Instant,
            resolvedTransactionId: String?,
        ): ReviewItem? = null
    }
}
