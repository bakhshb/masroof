package com.baraa.masroof.integration.learning

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.data.learning.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LearningPersistenceTest {
    @Test fun learningAndProvenanceReopenWithoutChangingTheFinancialTables() = runBlocking {
        val host=ApplicationProvider.getApplicationContext<Context>()
        val scoped=TestStorageContext(host)
        scoped.deleteDatabase("learning.db")
        fun open()=Room.databaseBuilder(scoped,LearningDatabase::class.java,"learning.db").build()
        val label=LearningLabelRow("human-one","raw-one","BANK_ALJAZIRA","FEE","template","{}","bank-family-title-v1",1L,"USER_MESSAGE_FAMILY","hash")
        val audit=EngineInterpretationRow("attempt","raw-one","BANK_ALJAZIRA","engine","rules","model","HUMAN_TEMPLATE","FEE","SUCCESS","","hash",1L,2L)
        val first=open()
        try {first.learning().labels(listOf(label));first.learning().record(audit)} finally {first.close()}
        val db=open()
        try {
            assertEquals(listOf(label),db.learning().labels())
            assertEquals(listOf(audit),db.learning().history("raw-one"))
            db.openHelper.readableDatabase.query("PRAGMA user_version").use {it.moveToFirst();assertEquals(1,it.getInt(0))}
            db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%'").use {assertEquals(3,it.count)}
        }
        finally {db.close()}
        assertFalse(host.getDatabasePath("learning.db").exists())
        assertTrue(scoped.getDatabasePath("learning.db").exists())
    }
}
