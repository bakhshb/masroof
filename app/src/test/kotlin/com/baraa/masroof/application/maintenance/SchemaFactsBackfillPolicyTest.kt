package com.baraa.masroof.application.maintenance

import com.baraa.masroof.data.room.MasroofDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaFactsBackfillPolicyTest {

    @Test
    fun everySchemaVersion_declaresItsRequirement() {
        val undeclared = (1..MasroofDatabase.VERSION) - SchemaFactsBackfillPolicy.requirementBySchemaVersion.keys
        assertTrue("Declare re-parse requirement for schema versions $undeclared", undeclared.isEmpty())
        assertEquals(
            MasroofDatabase.ALL_MIGRATIONS.maxOf { it.endVersion },
            SchemaFactsBackfillPolicy.requirementBySchemaVersion.keys.max(),
        )
    }

    @Test
    fun parseFactColumnVersions_areBlocking() {
        assertEquals(MaintenanceRequirement.BLOCKING, SchemaFactsBackfillPolicy.requirementBySchemaVersion[10])
        assertEquals(MaintenanceRequirement.BLOCKING, SchemaFactsBackfillPolicy.requirementBySchemaVersion[11])
    }

    @Test
    fun upToDate_hasNoRequirement() {
        assertNull(
            SchemaFactsBackfillPolicy.requirementFor(
                lastReparsedVersion = MasroofDatabase.VERSION,
                currentVersion = MasroofDatabase.VERSION,
            ),
        )
        assertNull(SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 18, currentVersion = MasroofDatabase.VERSION))
    }

    @Test
    fun pendingRangeWithoutParseFactColumns_isBackground() {
        assertEquals(
            MaintenanceRequirement.BACKGROUND,
            SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 11, currentVersion = 14),
        )
        assertEquals(
            MaintenanceRequirement.BACKGROUND,
            SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 13, currentVersion = 14),
        )
        assertEquals(
            MaintenanceRequirement.BACKGROUND,
            SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 14, currentVersion = 15),
        )
        assertEquals(
            MaintenanceRequirement.BACKGROUND,
            SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 15, currentVersion = 16),
        )
        assertEquals(
            MaintenanceRequirement.BACKGROUND,
            SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 16, currentVersion = 17),
        )
    }

    @Test
    fun pendingRangeCrossingParseFactColumns_isBlocking() {
        assertEquals(
            MaintenanceRequirement.BLOCKING,
            SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 9, currentVersion = 14),
        )
        assertEquals(
            MaintenanceRequirement.BLOCKING,
            SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 10, currentVersion = 12),
        )
        assertEquals(
            MaintenanceRequirement.BLOCKING,
            SchemaFactsBackfillPolicy.requirementFor(lastReparsedVersion = 0, currentVersion = 14),
        )
    }

    @Test
    fun undeclaredFutureVersion_isBlocking() {
        assertEquals(
            MaintenanceRequirement.BLOCKING,
            SchemaFactsBackfillPolicy.requirementFor(
                lastReparsedVersion = MasroofDatabase.VERSION,
                currentVersion = MasroofDatabase.VERSION + 1,
            ),
        )
    }
}
