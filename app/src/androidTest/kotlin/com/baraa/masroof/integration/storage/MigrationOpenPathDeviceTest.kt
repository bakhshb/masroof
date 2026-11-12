package com.baraa.masroof.integration.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.data.room.MigrationOpenPathContract
import com.baraa.masroof.data.room.UserDecisionBackfillContract
import com.baraa.masroof.testsupport.TestStorageContext
import org.junit.Test
import org.junit.runner.RunWith

/** Native SQLite run of the open-path contract on the committed v18 fixture (shared from test resources). */
@RunWith(AndroidJUnit4::class)
class MigrationOpenPathDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = TestStorageContext(instrumentation.targetContext)
    private val fixture = { instrumentation.context.assets.open("ledger_fixtures/v18/masroof.db") }

    @Test
    fun v18Fixture_upgradesToTheProductionVersionWithEveryRowIntactAndItsDecisionsBackfilled() =
        MigrationOpenPathContract.productionUpgradeKeepsEveryRow(context, fixture)

    @Test
    fun v18Fixture_upgradesToATestOnlyNextVersionWithEveryRowIntact() =
        MigrationOpenPathContract.upgradeKeepsEveryRow(context, fixture)

    @Test
    fun v20File_upgradesAddingOnlyTheEmptyPatternTable() =
        MigrationOpenPathContract.v20UpgradeAddsOnlyThePatternTable(context, fixture)

    @Test
    fun olderSchema_isRejectedAndTheFileIsUntouched() =
        MigrationOpenPathContract.olderSchemaIsRejectedUntouched(context, fixture)

    @Test
    fun unknownIdentityHash_isRejectedAndTheFileIsUntouched() =
        MigrationOpenPathContract.unknownIdentityHashIsRejectedUntouched(context, fixture)

    @Test
    fun unknownIdentityHashAfterUpgrade_isRejectedAndTheFileIsUntouched() =
        MigrationOpenPathContract.unknownIdentityHashAfterUpgradeIsRejectedUntouched(context, fixture)

    @Test
    fun unknownBaselineStructure_isRejectedAndTheFileIsUntouched() =
        MigrationOpenPathContract.unknownBaselineStructureIsRejectedUntouched(context, fixture)

    @Test
    fun v19File_backfillsEveryDecisionKindOnUpgrade() = UserDecisionBackfillContract.everyKindIsBackfilled(context, fixture)

    @Test
    fun v19File_keepsDualWriteRowsLatestWithoutDuplicates() =
        UserDecisionBackfillContract.dualWriteRowsStayLatestAndAreNotDuplicated(context, fixture)

    @Test
    fun unknownV19IdentityHash_isRejectedAndTheFileIsUntouched() =
        UserDecisionBackfillContract.unknownV19IdentityHashIsRejectedUntouched(context, fixture)
}
