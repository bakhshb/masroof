package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Inputs every dashboard section projection reads, loaded once per projection by
 * [DashboardProjectionBuilder]. Sections never re-read what is here.
 */
data class DashboardProjectionContext(
    val period: FinancialPeriod,
    val periodEndExclusive: Instant,
    val today: LocalDate,
    /** Evidence for the selected period ([DashboardEvidenceSource.load]). */
    val evidence: DashboardEvidence,
    /** Displayed period transactions: merchant-enriched, rates applied in memory, self-transfers deduped. */
    val transactions: List<FinancialTransaction>,
    val sarEquivalents: Map<String, Money>,
    val reviewRequiredCount: Int,
    val ownedAccounts: List<AccountRegistryEntry>,
    val ownedAccountContainerIds: Set<String>,
    val ownedAccountLast4s: Set<String>,
    val cardRegistry: List<CardRegistryEntry>,
    val loans: List<LoanRegistryEntry>,
    val debitCardScope: DebitCardScopeFacts,
    val displayLocale: Locale,
    val zoneId: ZoneId,
    val primaryCurrency: Currency,
) {
    val parsedRecords: List<ParsedEventRecord> get() = evidence.parsedRecords
    val rawSmsById: Map<String, RawSms> get() = evidence.rawSmsById
    val transactionIds: Set<String> by lazy { transactions.mapTo(mutableSetOf()) { it.id } }
}
