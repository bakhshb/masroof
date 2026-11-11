package com.baraa.masroof.instrumentation

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.data.room.PreservingOpenHelperFactory
import com.baraa.masroof.testsupport.TestStorageContext
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Hold the first connection before invalidation initialization requests Room's close lock. */
class StartupQueryShutdownTest {
    @Test(timeout = 30_000)
    fun shutdownWaitsForFirstConnectionWithoutCancellingItsRoomQuery() = assertShutdownDrains(startup = true)

    @Test(timeout = 30_000)
    fun shutdownDrainsAdmittedApplicationQueryWithoutCancellingItsConnection() = assertShutdownDrains(startup = false)

    @Test(timeout = 30_000)
    fun closeReleasesAnAnalyticsRefreshWaitingForStartup() = runBlocking(Dispatchers.Default) {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        val context = TestStorageContext(host, "analytics-before-startup-close")
        val c = AppContainer(context)
        try {
            val refresh = launch(start = CoroutineStart.UNDISPATCHED) { c.analytics.refresh() }
            assertFalse("Refresh is suspended on the startup authority", refresh.isCompleted)
            c.close()
            refresh.join()
            assertEquals("CLOSED", c.shutdownStage)
        } finally {
            c.close()
            File(context.filesDir.parentFile!!.absolutePath).deleteRecursively()
        }
    }

    @Test(timeout = 30_000)
    fun restoreCanCloseAfterRetirementWhileAnApplicationReaderIsQueued() = runBlocking {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        val context = TestStorageContext(host, "retired-restore-close")
        val c = AppContainer(context)
        try {
            c.maintenance.runStartupMaintenance()
            c.maintenance.awaitPostStartupBackgroundWorkForTesting()
            c.storage.databaseAccessGate.withRestore {
                val queued = c.storage.applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    c.storage.databaseAccessGate.withAccess { error("A queued reader cannot enter retired Room") }
                }
                assertFalse("Reader is parked behind exclusive restore", queued.isCompleted)
                c.storage.databaseAccessGate.retire()
                c.close()
                assertTrue(queued.isCancelled)
            }
            assertEquals("CLOSED", c.shutdownStage)
        } finally {
            c.close()
            File(context.filesDir.parentFile!!.absolutePath).deleteRecursively()
        }
    }

    private fun assertShutdownDrains(startup: Boolean) {
        val host = InstrumentationRegistry.getInstrumentation().targetContext
        val context = TestStorageContext(host, "startup-query-shutdown")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = AtomicBoolean()
        val factory = SupportSQLiteOpenHelper.Factory { config ->
            val original = config.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(original.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = original.onConfigure(db)
                override fun onCreate(db: SupportSQLiteDatabase) = original.onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = original.onUpgrade(db, oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Hold the first connection inside SQLiteOpenHelper's open callback. Room's own
                    // open-time SQL path is internal (2.7+ no longer uses SupportSQLiteDatabase.execSQL).
                    if (once.compareAndSet(false, true)) {
                        entered.countDown()
                        check(release.await(20, TimeUnit.SECONDS)) { "First connection barrier was not released" }
                    }
                    original.onOpen(db)
                }
            }
            PreservingOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(config.context)
                .name(config.name).callback(callback).build())
        }
        val c = AppContainer(context, databaseOpenHelperFactory = factory)
        val closed = CountDownLatch(1)
        val closing = Thread { try { c.close() } finally { closed.countDown() } }.apply { isDaemon = true }
        try {
            val query = if (startup) { c.maintenance.runStartupMaintenance(); null }
                else c.storage.applicationScope.launch { c.storage.rawSmsRepository.listIdsByReceivedAt() }
            assertTrue("First connection reached the barrier", entered.await(10, TimeUnit.SECONDS))
            closing.start()
            val drainingStage = if (startup) "STARTUP_DRAINING" else "APPLICATION_DRAINING"
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((c.shutdownStage != drainingStage || closing.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED)) &&
                c.shutdownStage !in setOf("DATABASE_CLOSING", "CLOSED") && System.nanoTime() < deadline) {
                Thread.yield()
            }
            if (c.maintenance.startupCancelled) {
                // On the unsafe implementation, let close take Room's write lock while
                // SQLiteOpenHelper still owns the connection callback monitor.
                val closingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while ((c.shutdownStage != "DATABASE_CLOSING" || closing.state != Thread.State.BLOCKED) && System.nanoTime() < closingDeadline) Thread.yield()
            }
            assertEquals("Admitted connection must drain before any database close",
                drainingStage, c.shutdownStage)
            assertFalse("Application queries must also finish normally", query?.isCancelled == true)
            assertFalse("Cancelling a Room continuation does not stop the connection it is opening", c.maintenance.startupCancelled)
            assertEquals(1L, closed.count)
        } finally {
            release.countDown()
            if (closing.state == Thread.State.NEW) closing.start()
            val finished = closed.await(5, TimeUnit.SECONDS)
            if (!finished) Thread.getAllStackTraces().forEach { (thread, stack) ->
                android.util.Log.e("StartupShutdown", "${thread.name} ${thread.state}\n${stack.joinToString("\n")}")
            }
            if (finished) File(host.filesDir, "startup-query-shutdown").deleteRecursively()
        }
        assertEquals("CLOSED", c.shutdownStage)
        assertEquals(0L, closed.count)
    }
}
