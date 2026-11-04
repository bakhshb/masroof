package com.baraa.masroof.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CommitmentDatePickerMillisTest {
    @Test
    fun usesUtcMidnightWhateverTheDeviceZone() {
        val date = LocalDate.of(2026, 11, 4)
        val utcMidnight = 1_793_750_400_000L // 2026-11-04T00:00:00Z
        assertEquals(utcMidnight, commitmentDateToPickerMillis(date))
        assertEquals(date, commitmentPickerMillisToDate(utcMidnight))
    }
}
