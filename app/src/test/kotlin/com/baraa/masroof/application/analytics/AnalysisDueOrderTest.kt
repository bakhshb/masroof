package com.baraa.masroof.application.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisDueOrderTest {
    private fun due(id: String, day: Long?, confirmed: Boolean = true) = AnalysisDue(id, id, 100, day, confirmed)

    @Test
    fun nearestFirst_ordersByDueDate_keepsConfirmedAheadOfSameDayEstimate_andUndatedLast() {
        // Input arrives in name order, as the commitments overview builds it.
        val dues = listOf(due("chatgpt", 21), due("undated", null), due("electricity", 5), due("stc", 5), due("youtube", 6)) +
            listOf(due("estimate", 5, confirmed = false))

        assertEquals(listOf("electricity", "stc", "estimate", "youtube", "chatgpt", "undated"), dues.nearestFirst().map { it.id })
    }
}
