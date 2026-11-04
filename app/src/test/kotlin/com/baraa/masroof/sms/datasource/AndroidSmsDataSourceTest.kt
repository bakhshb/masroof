package com.baraa.masroof.sms.datasource

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidSmsDataSourceTest {
    @Test
    fun scanStoppedEarly_closesTheCursor() = runBlocking {
        val provider = Robolectric.setupContentProvider(InboxProvider::class.java, Telephony.Sms.Inbox.CONTENT_URI.authority)
        val source = AndroidSmsDataSource(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)

        source.withInbox { it.first() }
        assertTrue(provider.cursor!!.isClosed)

        runCatching { source.withInbox { rows -> rows.first(); error("ingest failed") } }
        assertTrue(provider.cursor!!.isClosed)
    }

    class InboxProvider : ContentProvider() {
        var cursor: MatrixCursor? = null

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection).apply {
                repeat(3) { addRow(arrayOf<Any>("$it", "BANK", "body $it", 1_700_000_000_000L, 1_700_000_000_000L)) }
            }.also { cursor = it }

        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
