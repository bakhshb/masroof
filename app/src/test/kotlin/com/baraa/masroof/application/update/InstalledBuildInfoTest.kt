package com.baraa.masroof.application.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledBuildInfoTest {
    @Test
    fun isNightlyBuild_detectsNightlyVersionNames() {
        assertTrue(InstalledBuildInfo.isNightlyBuild("0.3.18-nightly.1"))
        assertTrue(InstalledBuildInfo.isNightlyBuild("1.0.0-nightly.12"))
    }

    @Test
    fun isNightlyBuild_rejectsStableVersionNames() {
        assertFalse(InstalledBuildInfo.isNightlyBuild("0.3.18"))
        assertFalse(InstalledBuildInfo.isNightlyBuild("0.3.18-debug"))
    }
}
