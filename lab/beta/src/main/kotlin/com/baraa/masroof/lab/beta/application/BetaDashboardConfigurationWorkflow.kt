package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId

/** Validates user input and modifies only an explicit configuration draft; never posts payments. */
object BetaDashboardConfigurationWorkflow {
    fun salary(base: BetaDashboardConfigurationDraft, day: String): BetaDashboardConfigurationDraft =
        base.copy(salary = day.takeIf(String::isNotBlank)?.let { BetaSalaryCycle(validDay(it)) })

    fun statement(base: BetaDashboardConfigurationDraft, facility: AssetId, day: String): BetaDashboardConfigurationDraft =
        base.copy(statements = base.statements.filterNot { it.facilityId == facility } +
            listOfNotNull(day.takeIf(String::isNotBlank)?.let { BetaFacilityCycle(facility, validDay(it)) }))

    private fun validDay(value: String): Int = requireNotNull(value.toIntOrNull()?.takeIf { it in 1..31 })
}
