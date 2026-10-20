package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.value.*
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class BetaDashboardConfigurationWorkflowTest {
    private val month = YearMonth.of(2026, 10)
    private val base = BetaDashboardConfigurationDraft(commitments = listOf(BetaRecurringCommitment("rent", "Rent", Money.of("1200", Currency.SAR), 31, month)))
    @Test fun qualifiedStatementChangesPreserveSameLocalIdAtAnotherBankAndExistingCommitments() {
        val a = AssetId(Environment.BETA, BankId("A"), "same")
        val b = AssetId(Environment.BETA, BankId("B"), "same")
        val first = BetaDashboardConfigurationWorkflow.statement(base, a, "31")
        val second = BetaDashboardConfigurationWorkflow.statement(first, b, "15")
        assertEquals(setOf(a, b), second.statements.map { it.facilityId }.toSet())
        val cleared = BetaDashboardConfigurationWorkflow.statement(second, a, "")
        assertEquals(listOf(BetaFacilityCycle(b, 15)), cleared.statements)
        assertEquals(base.commitments, cleared.commitments)
        assertNull(BetaDashboardConfigurationWorkflow.salary(base, "").salary)
    }
    @Test fun invalidDaysNeverCreateACycleDraft() {
        for(day in listOf("0","32","x")) {
            assertThrows(IllegalArgumentException::class.java) {BetaDashboardConfigurationWorkflow.salary(base,day)}
            assertThrows(IllegalArgumentException::class.java) {BetaDashboardConfigurationWorkflow.statement(base,AssetId(Environment.BETA,BankId("A"),"facility"),day)}
        }
        assertEquals(1,base.commitments.size)
    }
}
