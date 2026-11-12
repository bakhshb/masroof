package com.baraa.masroof.integration.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.application.container.LegacyPatternFileContract
import com.baraa.masroof.testsupport.TestStorageContext
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite run of the one-time pattern-file copy (plan 5.1) through the real live-database open. */
@RunWith(AndroidJUnit4::class)
class PatternUpgradeDeviceTest {
    @Test
    fun theOpenCopiesTheFileThenSeedsAndAFailedCopyFailsTheOpenKeepingTheFile() =
        LegacyPatternFileContract.upgradeCopiesTheFileOnOpen(
            TestStorageContext(InstrumentationRegistry.getInstrumentation().targetContext, "pattern-upgrade-${java.util.UUID.randomUUID()}"))
}
