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

    override fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> = sequence {
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

        try {
            cursor.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val dateSentIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)

                while (c.moveToNext()) {
                    yield(readRow(c, idIdx, addressIdx, bodyIdx, dateIdx, dateSentIdx))
                }
            }
        } catch (e: SmsPermissionException) {
            throw e
        } catch (e: SmsProviderException) {
            throw e
        } catch (e: Exception) {
            throw SmsProviderException("SMS provider cursor iteration failed", e)
        }
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
