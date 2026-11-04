package com.baraa.masroof.sms.datasource

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.sms.receiver.LiveReceiptTimestamp
import java.time.Instant

/**
 * Reads the device SMS inbox via [Telephony.Sms.Inbox].
 *
 * Projection and column indexes are explicit. Sort order is DATE ASC
 * (oldest → newest) for stable later discovery/matching.
 *
 * Malformed rows are yielded as [InboxRow.Malformed] (not silently dropped).
 */
class AndroidSmsDataSource(
    private val contentResolver: ContentResolver,
) : SmsDataSource {

    override suspend fun <T> withInbox(receivedAfter: Instant?, block: suspend (rows: Sequence<InboxRow>) -> T): T {
        val selection: String?
        val selectionArgs: Array<String>?
        if (receivedAfter != null) {
            // The bound is on the stored receipt (carrier) time, which can follow DATE by up to the carrier skew.
            // Query from earlier; the scanner applies the exact receipt-time bound to each row.
            selection = "${Telephony.Sms.DATE} >= ?"
            selectionArgs = arrayOf(receivedAfter.minus(LiveReceiptTimestamp.MAX_CARRIER_SKEW).toEpochMilli().toString())
        } else {
            selection = null
            selectionArgs = null
        }

        val cursor = try {
            contentResolver.query(
                INBOX_URI,
                PROJECTION,
                selection,
                selectionArgs,
                SORT_ORDER,
            )
        } catch (se: SecurityException) {
            throw SmsPermissionException(cause = se)
        } catch (e: Exception) {
            throw SmsProviderException("SMS provider query failed", e)
        }

        if (cursor == null) {
            throw SmsProviderException("SMS provider returned null cursor")
        }

        return cursor.use { c ->
            val indexes = providerCall {
                intArrayOf(
                    c.getColumnIndexOrThrow(Telephony.Sms._ID),
                    c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS),
                    c.getColumnIndexOrThrow(Telephony.Sms.BODY),
                    c.getColumnIndexOrThrow(Telephony.Sms.DATE),
                    c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT),
                )
            }
            // Only cursor failures become provider errors; errors thrown by [block] pass through unchanged.
            block(
                sequence {
                    while (providerCall { c.moveToNext() }) {
                        yield(providerCall { readRow(c, indexes[0], indexes[1], indexes[2], indexes[3], indexes[4]) })
                    }
                },
            )
        }
    }

    private inline fun <R> providerCall(read: () -> R): R =
        try {
            read()
        } catch (e: Exception) {
            throw SmsProviderException("SMS provider cursor iteration failed", e)
        }

    private fun readRow(
        c: Cursor,
        idIdx: Int,
        addressIdx: Int,
        bodyIdx: Int,
        dateIdx: Int,
        dateSentIdx: Int,
    ): InboxRow {
        val id = c.getString(idIdx)
        val address = c.getString(addressIdx)
        val body = c.getString(bodyIdx)
        if (id.isNullOrBlank() || address.isNullOrBlank() || body == null) {
            return InboxRow.Malformed
        }
        return InboxRow.Valid(
            ProviderSmsRecord(
                providerMessageId = id,
                sender = address,
                body = body,
                // DATE is the phone's arrival time; DATE_SENT is the service-center time a live row stores.
                receivedAt = LiveReceiptTimestamp.resolve(
                    providerTimestampsMillis = listOf(c.getLong(dateSentIdx)),
                    deviceNow = Instant.ofEpochMilli(c.getLong(dateIdx)),
                ),
            ),
        )
    }

    companion object {
        private val INBOX_URI: Uri = Telephony.Sms.Inbox.CONTENT_URI
        private val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
        )
        /** Oldest → newest. */
        private const val SORT_ORDER = "${Telephony.Sms.DATE} ASC"
    }
}
