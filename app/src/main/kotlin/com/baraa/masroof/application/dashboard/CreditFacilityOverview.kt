package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.parsing.repository.ParsedEventRecord

data class CreditFacilityOverview(
    val bank: Bank,
    val primary: CreditCardDashboardRow,
    val supplementaries: List<CreditCardDashboardRow>,
    val facilityDue: StatementDueSnapshot?,
    val facilitySalaryPeriodSpending: SignedMoneyAmount,
    val facilityStatementSpending: SignedMoneyAmount,
    val aggregateStatementPeriodLabel: String?,
    val salaryPeriodLabel: String?,
    val currency: Currency,
    /** Latest available-balance reading across this facility's cards, by message time. */
    val latestAvailableBalance: Money? = null,
    val latestAvailableBalanceAt: java.time.Instant? = null,
    /** Set from the commitments decision ([CommitmentsOverview.paidCreditFacilityKeys]) so both screens agree. */
    val facilityDuePaid: Boolean = false,
) {
    val facilityKey: String get() = "${bank.id}:$primaryLast4"

    val allCards: List<CreditCardDashboardRow>
        get() = listOf(primary) + supplementaries

    val primaryLast4: String get() = primary.last4

    private val cardContainerIds: Set<String> by lazy {
        allCards.map { FinancialContainerIdFactory.cardId(it.bank, it.last4) }.toSet()
    }

    /** A card payment counts toward [due] when it reaches one of this facility's cards on or after the due update. */
    fun isPaymentToward(due: StatementDueSnapshot, transaction: FinancialTransaction): Boolean =
        transaction.type == FinancialTransactionType.CREDIT_CARD_PAYMENT &&
            transaction.destinationContainerId in cardContainerIds &&
            !transaction.occurredAt.isBefore(due.updatedAt)
}

data class CreditFacilitiesOverview(
    val facilities: List<CreditFacilityOverview>,
    val debitCards: List<DebitCardOverview>,
    val currency: Currency,
) {
    val cardCount: Int get() = facilities.sumOf { it.allCards.size } + debitCards.size

    /** Every facility contributes once. Unknown or mixed-currency dues are not guessed. */
    val totalFacilityDue: Money?
        get() {
            val unique = facilities.distinctBy { it.facilityKey }
            if (unique.isEmpty() || unique.any { it.facilityDue == null }) return null
            val amounts = unique.map { it.facilityDue!!.amount }
            if (amounts.any { it.currency != amounts.first().currency }) return null
            return amounts.reduce { total, amount -> total + amount }
        }
    /** Like [totalFacilityDue], over the facilities whose statement due is not paid yet. */
    val unpaidFacilityDue: Money?
        get() {
            val unpaid = facilities.distinctBy { it.facilityKey }.filterNot { it.facilityDuePaid }
            if (unpaid.isEmpty()) return Money.of("0", currency)
            if (unpaid.any { it.facilityDue == null }) return null
            val amounts = unpaid.map { it.facilityDue!!.amount }
            if (amounts.any { it.currency != amounts.first().currency }) return null
            return amounts.reduce { total, amount -> total + amount }
        }
    val hasContent: Boolean
        get() = facilities.isNotEmpty() || debitCards.isNotEmpty()

    /** True only when every facility has a statement due and each one is paid. */
    val allFacilityDuesPaid: Boolean
        get() = facilities.isNotEmpty() && facilities.all { it.facilityDue != null && it.facilityDuePaid }

    fun withPaidDues(paidFacilityKeys: Set<String>): CreditFacilitiesOverview =
        copy(facilities = facilities.map { it.copy(facilityDuePaid = it.facilityKey in paidFacilityKeys) })
}

data class DebitCardOverview(
    val bank: Bank,
    val last4: String,
    val displayLabel: String,
    val linkedAccountLabel: String?,
    val linkedAccountMaskedNumber: String?,
    val network: com.baraa.masroof.domain.model.CardNetwork?,
    val salaryPeriodSpendingNet: SignedMoneyAmount,
    val salaryPeriodLabel: String?,
)

object CreditFacilityOverviewBuilder {
    fun build(
        overview: CreditCardsOverview,
        registryCards: List<CardRegistryEntry>,
        registryAccounts: List<com.baraa.masroof.domain.model.AccountRegistryEntry> = emptyList(),
        debitSpendingByCardKey: Map<String, SignedMoneyAmount> = emptyMap(),
        debitSalaryPeriodLabel: String? = overview.salaryPeriodLabel,
        parsedRecords: List<ParsedEventRecord> = emptyList(),
    ): CreditFacilitiesOverview {
        val ownedCredit = registryCards.filter {
            it.ownership.isOwned() &&
                CardRegistryDebitClassifier.isCreditRegistryEntry(
                    it,
                    parsedRecords = parsedRecords,
                )
        }
        val debitCards = registryCards
            .filter {
                it.ownership.isOwned() &&
                    CardRegistryDebitClassifier.isDebitRegistryEntry(
                        it,
                        parsedRecords = parsedRecords,
                    )
            }
            .map { entry ->
                val cardKey = CardTransactionInvolvementResolver.cardKey(entry.bank.id, entry.last4)
                DebitCardOverview(
                    bank = entry.bank,
                    last4 = entry.last4,
                    displayLabel = RegistryDisplayLabels.cardLabel(entry),
                    linkedAccountLabel = resolveLinkedAccountLabel(
                        entry = entry,
                        registryAccounts = registryAccounts,
                        parsedRecords = parsedRecords,
                    ),
                    linkedAccountMaskedNumber = entry.linkedAccountMaskedNumber
                        ?: DebitLinkedAccountInferrer.inferAccountLast4(
                            bank = entry.bank,
                            cardLast4 = entry.last4,
                            parsedRecords = parsedRecords,
                        ),
                    network = entry.cardNetwork,
                    salaryPeriodSpendingNet = debitSpendingByCardKey[cardKey]
                        ?: SignedMoneyAmount.zero(overview.currency),
                    salaryPeriodLabel = debitSalaryPeriodLabel,
                )
            }

        val rowByBankAndLast4 = overview.cards.associateBy { it.bank to it.last4 }
        val primaryEntries = ownedCredit.filter { it.cardRole == CardRole.PRIMARY }
        val supplementaryEntries = ownedCredit.filter { it.cardRole == CardRole.SUPPLEMENTARY }
        val standaloneEntries = ownedCredit.filter {
            it.cardRole == CardRole.STANDALONE || it.cardRole == null
        }

        val facilities = buildList {
            for (primaryEntry in primaryEntries) {
                val primaryRow = rowByBankAndLast4[primaryEntry.bank to primaryEntry.last4]
                    ?: placeholderRow(primaryEntry, overview)
                val supplements = supplementaryEntries
                    .filter {
                        it.bank == primaryEntry.bank && it.parentCardLast4 == primaryEntry.last4
                    }
                    .mapNotNull {
                        rowByBankAndLast4[it.bank to it.last4] ?: placeholderRow(it, overview)
                    }
                add(buildFacility(overview, primaryRow, supplements))
            }
            for (standalone in standaloneEntries) {
                val row = rowByBankAndLast4[standalone.bank to standalone.last4]
                    ?: placeholderRow(standalone, overview)
                add(buildFacility(overview, row, emptyList()))
            }
            val groupedKeys = (
                primaryEntries.map { it.bank to it.last4 } +
                    standaloneEntries.map { it.bank to it.last4 }
            ).toSet()
            val orphanSupplements = supplementaryEntries.filter { entry ->
                val parentKey = entry.bank to entry.parentCardLast4.orEmpty()
                parentKey !in groupedKeys
            }
            for (orphan in orphanSupplements) {
                val row = rowByBankAndLast4[orphan.bank to orphan.last4]
                    ?: placeholderRow(orphan, overview)
                add(buildFacility(overview, row, emptyList()))
            }
        }

        if (facilities.isEmpty() && debitCards.isEmpty()) {
            return CreditFacilitiesOverview(
                facilities = emptyList(),
                debitCards = emptyList(),
                currency = overview.currency,
            )
        }

        return CreditFacilitiesOverview(
            facilities = facilities,
            debitCards = debitCards,
            currency = overview.currency,
        )
    }

    private fun buildFacility(
        overview: CreditCardsOverview,
        primary: CreditCardDashboardRow,
        supplementaries: List<CreditCardDashboardRow>,
    ): CreditFacilityOverview {
        val all = listOf(primary) + supplementaries
        val latestAvailable = all.mapNotNull { it.snapshot }
            .filter { it.availableBalance != null && it.availableBalanceAt != null }
            .maxByOrNull { it.availableBalanceAt!! }
        return CreditFacilityOverview(
            bank = primary.bank,
            primary = primary,
            supplementaries = supplementaries,
            facilityDue = resolveLatestStatementDue(all),
            facilitySalaryPeriodSpending = sumCreditCardSpending(all) { it.salaryPeriodSpendingNet },
            facilityStatementSpending = sumCreditCardSpending(all) { it.statementSpendingNet },
            aggregateStatementPeriodLabel = primary.statementPeriodLabel
                ?: overview.aggregateStatementPeriodLabel,
            salaryPeriodLabel = overview.salaryPeriodLabel,
            currency = overview.currency,
            latestAvailableBalance = latestAvailable?.availableBalance,
            latestAvailableBalanceAt = latestAvailable?.availableBalanceAt,
        )
    }

    private fun placeholderRow(
        entry: CardRegistryEntry,
        overview: CreditCardsOverview,
    ): CreditCardDashboardRow =
        CreditCardDashboardRow(
            bank = entry.bank,
            last4 = entry.last4,
            statementSpendingNet = SignedMoneyAmount.zero(overview.currency),
            salaryPeriodSpendingNet = SignedMoneyAmount.zero(overview.currency),
            statementPeriodLabel = null,
            snapshot = null,
        )

    private fun com.baraa.masroof.domain.model.OwnershipStatus.isOwned(): Boolean =
        this == com.baraa.masroof.domain.model.OwnershipStatus.OWNED

    private fun resolveLinkedAccountLabel(
        entry: CardRegistryEntry,
        registryAccounts: List<com.baraa.masroof.domain.model.AccountRegistryEntry>,
        parsedRecords: List<ParsedEventRecord>,
    ): String? {
        val masked = entry.linkedAccountMaskedNumber
            ?: DebitLinkedAccountInferrer.inferAccountLast4(
                bank = entry.bank,
                cardLast4 = entry.last4,
                parsedRecords = parsedRecords,
            )
            ?: return null
        val accountEntry = registryAccounts.find {
            it.bank == entry.bank &&
                (it.maskedNumber == masked || it.maskedNumber.endsWith(masked))
        } ?: com.baraa.masroof.domain.model.AccountRegistryEntry.forTest(
            bank = entry.bank,
            maskedNumber = masked,
            ownership = entry.ownership,
            firstSeenRawSmsId = null,
            lastSeenRawSmsId = null,
        )
        return RegistryDisplayLabels.accountLabel(accountEntry)
    }
}
