package com.baraa.masroof.data.learning

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.testsupport.TestStorageContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan 5.2: the open never deletes a corrupt `learning.db`; the store reports it, then deletes the derived cache itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LearningStoreCorruptionTest {
    @Test
    fun aCorruptFileIsReportedBeforeItIsDeleted_andTheNextUseStartsEmpty() = runBlocking {
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "learning-corrupt-${java.util.UUID.randomUUID()}")
        val file = context.getDatabasePath(LearningStore.NAME).also { it.parentFile?.mkdirs() }
        val garbage = ByteArray(8192) { (it * 31).toByte() }
        file.writeBytes(garbage)
        val reported = mutableListOf<ByteArray?>()
        val store = LearningStore(context) { reported += file.takeIf { it.isFile }?.readBytes() }
        try {
            assertThrows(SQLiteDatabaseCorruptException::class.java) { runBlocking { store.feedback() } }
            // When the store was told, the open had left the file exactly as it was.
            assertArrayEquals(garbage, reported.single())
            assertFalse("the store's explicit recovery removed the derived cache", file.exists())
            assertEquals(emptyList<LearningFeedbackRow>(), store.feedback())
            assertTrue(file.isFile)
        } finally {
            store.close()
            file.parentFile?.parentFile?.deleteRecursively()
        }
    }
}
