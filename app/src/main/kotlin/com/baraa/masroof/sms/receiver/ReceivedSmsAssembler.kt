package com.baraa.masroof.sms.receiver

/**
 * Production multipart / PDU assembly used by [IncomingSmsReceiver].
 *
 * Joins part bodies in order with no inserted separators. One logical sender.
 */
object ReceivedSmsAssembler {
    data class Part(
        val sender: String?,
        val body: String?,
        val providerTimestampMillis: Long? = null,
    )

    data class Assembled(
        val sender: String,
        val body: String,
        val providerTimestampsMillis: List<Long> = emptyList(),
    )

    /**
     * @return assembled message, or null when the part set is empty/invalid.
     */
    fun assemble(parts: List<Part>): Assembled? {
        if (parts.isEmpty()) return null
        val sender = parts.firstOrNull()?.sender?.takeIf { it.isNotBlank() } ?: return null
        val body = parts.joinToString(separator = "") { it.body.orEmpty() }
        if (body.isEmpty()) return null
        val timestamps = parts.mapNotNull { it.providerTimestampMillis }
        return Assembled(
            sender = sender,
            body = body,
            providerTimestampsMillis = timestamps,
        )
    }
}
