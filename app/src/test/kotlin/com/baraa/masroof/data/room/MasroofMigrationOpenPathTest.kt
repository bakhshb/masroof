package com.baraa.masroof.data.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.testsupport.TestStorageContext
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The production builder against the committed v18 fixture; the device twin is `MigrationOpenPathDeviceTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MasroofMigrationOpenPathTest {
    private val context: Context = TestStorageContext(ApplicationProvider.getApplicationContext(), "migration-open-path")
    private val file = File(if (File("src/test").isDirectory) "." else "app", "src/test/resources/testdata/ledger_fixtures/v18/masroof.db")
    private val fixture = { file.inputStream() }

    @Test
    fun v18Fixture_upgradesToTheProductionVersionWithEveryRowIntactAndNoDecisions() =
        MigrationOpenPathContract.productionUpgradeKeepsEveryRow(context, fixture)

    @Test
    fun v18Fixture_upgradesToATestOnlyNextVersionWithEveryRowIntact() =
        MigrationOpenPathContract.upgradeKeepsEveryRow(context, fixture)

    @Test
    fun olderSchema_isRejectedAndTheFileIsUntouched() =
        MigrationOpenPathContract.olderSchemaIsRejectedUntouched(context, fixture)

    @Test
    fun unknownIdentityHash_isRejectedAndTheFileIsUntouched() =
        MigrationOpenPathContract.unknownIdentityHashIsRejectedUntouched(context, fixture)

    @Test
    fun unknownBaselineStructure_isRejectedAndTheFileIsUntouched() =
        MigrationOpenPathContract.unknownBaselineStructureIsRejectedUntouched(context, fixture)
}
