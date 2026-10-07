package com.baraa.masroof.sms.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the production [ReceivedSmsAssembler] used by [IncomingSmsReceiver].
 */
class MultipartSmsAssemblyTest {
    @Test
    fun joinsPartsInOrderWithoutExtraSeparators() {
        val assembled = ReceivedSmsAssembler.assemble(
            listOf(
                ReceivedSmsAssembler.Part("AlJazira", "شراء عبر الانترنت\n"),
                ReceivedSmsAssembler.Part("AlJazira", "بطاقة: 7271\n"),
                ReceivedSmsAssembler.Part("AlJazira", "بمبلغ: 51.99 SAR"),
            ),
        )!!
        assertEquals("AlJazira", assembled.sender)
        assertEquals(
            "شراء عبر الانترنت\nبطاقة: 7271\nبمبلغ: 51.99 SAR",
            assembled.body,
        )
    }

    @Test
    fun usesFirstPartSenderConsistently() {
        val assembled = ReceivedSmsAssembler.assemble(
            listOf(
                ReceivedSmsAssembler.Part("AlJazira", "a"),
                ReceivedSmsAssembler.Part(null, "b"),
            ),
        )!!
        assertEquals("AlJazira", assembled.sender)
        assertEquals("ab", assembled.body)
        assertEquals(emptyList<Long>(), assembled.providerTimestampsMillis)
    }

    @Test
    fun keepsEveryPartTimestampForReceiptPolicy() {
        val assembled = ReceivedSmsAssembler.assemble(
            listOf(
                ReceivedSmsAssembler.Part("AlJazira", "aa", providerTimestampMillis = 2_000L),
                ReceivedSmsAssembler.Part("AlJazira", "bb", providerTimestampMillis = 1_000L),
                ReceivedSmsAssembler.Part("AlJazira", "cc", providerTimestampMillis = null),
            ),
        )!!
        assertEquals(listOf(2_000L, 1_000L), assembled.providerTimestampsMillis)
    }

    @Test
    fun emptyOrInvalidPartSet_rejected() {
        assertNull(ReceivedSmsAssembler.assemble(emptyList()))
        assertNull(
            ReceivedSmsAssembler.assemble(
                listOf(ReceivedSmsAssembler.Part(null, "body")),
            ),
        )
        assertNull(
            ReceivedSmsAssembler.assemble(
                listOf(ReceivedSmsAssembler.Part("AlJazira", null)),
            ),
        )
        assertNull(
            ReceivedSmsAssembler.assemble(
                listOf(ReceivedSmsAssembler.Part("AlJazira", "")),
            ),
        )
    }
}
