package com.baraa.masroof.sms.datasource

import com.baraa.masroof.sms.model.ProviderSmsRecord
import java.time.Instant

/**
 * One inbox cursor row outcome so malformed provider rows remain countable.
 */
sealed interface InboxRow {
    data class Valid(
        val record: ProviderSmsRecord,
    ) : InboxRow

    /** Provider row missing required id/sender/body; does not abort the scan. */
    data object Malformed : InboxRow
}

/**
 * Abstraction over Android SMS inbox access for testability.
 *
 * Implementations must yield rows in deterministic oldest→newest order.
 */
interface SmsDataSource {
    /**
     * Opens the inbox, hands its rows to [block], and closes the inbox when [block] returns or throws,
     * so a scan that stops early never leaks the provider cursor. The rows are valid only inside [block].
     *
     * @param receivedAfter inclusive lower bound on DATE when non-null
     * @throws SmsPermissionException when READ_SMS is denied
     * @throws SmsProviderException on provider/cursor failures that abort the scan
     */
    suspend fun <T> withInbox(receivedAfter: Instant? = null, block: suspend (rows: Sequence<InboxRow>) -> T): T
}

/** READ_SMS not granted. */
class SmsPermissionException(
    message: String = "READ_SMS permission denied",
    cause: Throwable? = null,
) : Exception(message, cause)

/** Fatal SMS ContentProvider failure. */
class SmsProviderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
