package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.lab.beta.BetaBackupRecovery
import com.baraa.masroof.lab.beta.BetaContainer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Unrelated historical schemas stay rejected; only the explicit immediately preceding upgrade is supported. */
class BetaArchiveUpgradeTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BetaDatabase::class.java)
    @Test fun historicalSchemaIsRejectedWithoutModification() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        BetaContainer.resetOffline(context)
        try {
            helper.createDatabase(BetaDatabase.NAME,1).use {it.execSQL("INSERT INTO beta_shell_marker VALUES ('old-beta','Synthetic old lab')")}
            val file=context.getDatabasePath(BetaDatabase.NAME)
            val before=file.readBytes()
            assertThrows(IllegalArgumentException::class.java) {BetaBackupRecovery(context).publish(file,emptyMap())}
            assertArrayEquals(before,file.readBytes())
        } finally {BetaContainer.resetOffline(context)}
    }
}
