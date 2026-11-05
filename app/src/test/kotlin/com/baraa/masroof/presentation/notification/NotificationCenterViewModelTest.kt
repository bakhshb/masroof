package com.baraa.masroof.presentation.notification

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.notification.NotificationCenterMetricsWorkflow
import com.baraa.masroof.application.notification.NotificationCenterService
import com.baraa.masroof.application.notification.NotificationType
import com.baraa.masroof.data.repository.RoomAccountRegistryRepository
import com.baraa.masroof.data.repository.RoomCardRegistryRepository
import com.baraa.masroof.data.repository.RoomReviewRepository
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.repository.ReviewRepository
import com.baraa.masroof.testsupport.FakeNotificationPreferencesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationCenterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MasroofDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun olderSlowRefresh_doesNotOverwriteTheLatestOne() = runTest {
        val firstRead = CompletableDeferred<Unit>()
        var calls = 0
        val room = RoomReviewRepository(db.reviewItemDao())
        val reviews = object : ReviewRepository by room {
            override suspend fun countRequired(): Int {
                if (++calls == 1) firstRead.await()
                return room.countRequired()
            }
        }
        val vm = NotificationCenterViewModel(
            NotificationCenterMetricsWorkflow(reviews, RoomCardRegistryRepository.from(db), RoomAccountRegistryRepository.from(db)),
            NotificationCenterService(FakeNotificationPreferencesRepository()),
            permissionStateProvider = { true },
        )

        vm.refresh(NotificationCenterExternalState(excludedForeignCurrencyCount = 3, periodKey = "2026-07-01"))
        runCurrent()
        vm.refresh(NotificationCenterExternalState(excludedForeignCurrencyCount = 0, periodKey = "2026-08-01"))
        runCurrent()
        firstRead.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.items.none { it.type == NotificationType.FOREIGN_CURRENCY })
    }
}
