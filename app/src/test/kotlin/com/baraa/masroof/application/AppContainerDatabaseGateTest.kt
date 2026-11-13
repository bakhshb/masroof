package com.baraa.masroof.application

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppContainerDatabaseGateTest {
    @Test
    fun everyRepositoryRead_observesTheSameRetiredProcessBoundary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context as MasroofApplication).container
        val reads: List<suspend () -> Unit> = listOf(
            { container.storage.rawSmsRepository.listIdsByReceivedAt() },
            { container.storage.parsedEventRepository.listAll() },
            { container.storage.accountRegistryRepository.listAll() },
            { container.storage.cardRegistryRepository.listAll() },
            { container.storage.loanRegistryRepository.listAll() },
            { container.storage.commitmentRepository.listAll() },
            { container.storage.financialTransactionRepository.listAll() },
            { container.storage.reviewRepository.listAll() },
            { container.storage.processingRetryRepository.listRetryableRawSmsIds() },
            { container.storage.userDecisionRepository.listCorrections() },
            { container.storage.userDecisionRepository.effectiveFor("irrelevant") },
        )
        container.storage.databaseAccessGate.retire()
        reads.forEachIndexed { index, read ->
            try {
                read()
                fail("Repository $index reopened a retired process database")
            } catch (_: DatabaseRestartRequiredException) {
                // Cancellation is intentional: obsolete UI/worker jobs must not crash the process.
            }
        }
    }
}
