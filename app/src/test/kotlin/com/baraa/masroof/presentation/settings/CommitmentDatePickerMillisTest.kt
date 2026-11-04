package com.baraa.masroof.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class CommitmentDatePickerMillisTest {
    @Test
    fun usesUtcMidnightWhateverTheDeviceZone() {
        // A device zone east of UTC (Riyadh) is where the old systemDefault() conversion shifted the day.
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))
        try {
            val date = LocalDate.of(2026, 11, 4)
            val utcMidnight = 1_793_750_400_000L // 2026-11-04T00:00:00Z
            assertEquals(utcMidnight, commitmentDateToPickerMillis(date))
            assertEquals(date, commitmentPickerMillisToDate(utcMidnight))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
