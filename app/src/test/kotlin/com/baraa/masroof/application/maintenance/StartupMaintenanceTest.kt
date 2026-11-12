package com.baraa.masroof.application.maintenance

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StartupMaintenanceTest {
    @Test
    fun startupIsReadyOnceTheFinancialStoreOpens() = runBlocking<Unit> {
        var opened = 0
        assertEquals(StartupMaintenanceOutcome.READY, StartupMaintenance { opened++ }.runBlockingPhase())
        assertEquals(1, opened)
    }

    @Test
    fun aStoreThatCannotOpenFailsClosed() {
        // MaintenanceContainer turns the throw into BLOCKED; a retry opens the store again.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { StartupMaintenance { error("file is not a database") }.runBlockingPhase() }
        }
    }
}
