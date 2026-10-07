package com.baraa.masroof.sms.receiver

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class LiveReceiptTimestampTest {
    private val deviceNow = Instant.parse("2026-08-10T15:30:00Z")

    @Test
    fun usesProviderTimestampWhenValid() {
        val provider = Instant.parse("2026-08-10T15:29:58Z")
        assertEquals(
            provider,
            LiveReceiptTimestamp.resolve(listOf(provider.toEpochMilli()), deviceNow),
        )
    }

    @Test
    fun multipart_usesEarliestValidTimestamp() {
        val earlier = Instant.parse("2026-08-10T15:29:57Z")
        val later = Instant.parse("2026-08-10T15:29:59Z")
        assertEquals(
            earlier,
            LiveReceiptTimestamp.resolve(
                listOf(later.toEpochMilli(), 0L, earlier.toEpochMilli()),
                deviceNow,
            ),
        )
    }

    @Test
    fun invalidTimestamps_fallBackToDeviceClock() {
        assertEquals(deviceNow, LiveReceiptTimestamp.resolve(emptyList(), deviceNow))
        assertEquals(deviceNow, LiveReceiptTimestamp.resolve(listOf(0L, -1L), deviceNow))
    }
}
