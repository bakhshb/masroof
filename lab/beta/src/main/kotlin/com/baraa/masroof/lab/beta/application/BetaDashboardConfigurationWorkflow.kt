package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.value.Currency
import com.baraa.masroof.lab.finance.value.Money
import java.time.YearMonth
import java.util.UUID

/** Validates user input and modifies only an explicit configuration draft; never posts payments. */
object BetaDashboardConfigurationWorkflow {
    fun salary(base: BetaDashboardConfigurationDraft, day: String): BetaDashboardConfigurationDraft =
        base.copy(salary = day.takeIf(String::isNotBlank)?.let { BetaSalaryCycle(validDay(it)) })

    fun statement(base: BetaDashboardConfigurationDraft, facility: AssetId, day: String): BetaDashboardConfigurationDraft =
        base.copy(statements = base.statements.filterNot { it.facilityId == facility } +
            listOfNotNull(day.takeIf(String::isNotBlank)?.let { BetaFacilityCycle(facility, validDay(it)) }))

    fun commitment(base: BetaDashboardConfigurationDraft, id: String?, label: String, amount: String,
        day: String, starts: String, ends: String): BetaDashboardConfigurationDraft {
        require(label.isNotBlank() && label.length <= 120)
        require(amount.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")))
        val money = Money.of(amount, Currency.SAR); require(money.amount.signum() > 0 && money.amount.precision() <= 20)
        val from = YearMonth.parse(starts); val until = ends.takeIf(String::isNotBlank)?.let(YearMonth::parse)
        require(from.year in 1900..2200 && (until == null || until.year in 1900..2200))
        require(until == null || until >= from)
        val existing = id?.let { key -> base.commitments.single { it.id == key } }
        val paid = existing?.paidMonths.orEmpty()
        // A date-range edit may not silently erase a previous manual payment decision.
        require(paid.all { it >= from && (until == null || it <= until) })
        val row = existing?.copy(label=label.trim(),amount=money,dueDay=validDay(day),starts=from,ends=until,paidMonths=paid,
            transactionDate=existing.transactionDate?.let {from.atDay(minOf(validDay(day),from.lengthOfMonth()))})
            ?: BetaRecurringCommitment(UUID.randomUUID().toString(),label.trim(),money,validDay(day),from,until,paid)
        return base.copy(commitments = base.commitments.filterNot { it.id == row.id } + row)
    }

    fun remove(base: BetaDashboardConfigurationDraft, id: String) = base.copy(commitments = base.commitments.filterNot { it.id == id })

    fun paid(base: BetaDashboardConfigurationDraft, id: String, month: YearMonth, paid: Boolean): BetaDashboardConfigurationDraft =
        base.copy(commitments = base.commitments.map { row -> if (row.id != id) row else {
            require(month >= row.starts && (row.ends == null || month <= row.ends))
            row.copy(paidMonths = if (paid) row.paidMonths + month else row.paidMonths - month)
        } })

    private fun validDay(value: String): Int = requireNotNull(value.toIntOrNull()?.takeIf { it in 1..31 })
}
