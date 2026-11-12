package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.testsupport.parsedRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DebitLinkedAccountInferrerTest {
    @Test
    fun inferAccountLast4_fromPersistedFact() {
        val parsedRecords = listOf(
            parsedRecord(
                cardLast4 = "2210",
                debitSourceAccountLast4 = "3001",
                id = "evt-2210",
                rawSmsId = "sms-2210",
                amount = null,
            ),
        )

        assertEquals(
            "3001",
            DebitLinkedAccountInferrer.inferAccountLast4(
                bank = Bank.BANK_ALJAZIRA,
                cardLast4 = "2210",
                parsedRecords = parsedRecords,
            ),
        )
    }

    @Test
    fun inferAccountLast4_nullWhenFactMissing() {
        val parsedRecords = listOf(
            parsedRecord(
                cardLast4 = "8219",
                debitSourceAccountLast4 = null,
                id = "evt-8219",
                rawSmsId = "sms-8219",
                amount = null,
            ),
        )

        assertNull(
            DebitLinkedAccountInferrer.inferAccountLast4(
                bank = Bank.BANK_ALJAZIRA,
                cardLast4 = "8219",
                parsedRecords = parsedRecords,
            ),
        )
    }
}
