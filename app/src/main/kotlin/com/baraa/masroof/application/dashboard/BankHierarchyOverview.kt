package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.model.LoanType
import com.baraa.masroof.domain.model.OwnershipStatus

data class BankHierarchyOverview(
    val banks: List<BankTreeOverview>,
) {
    val hasContent: Boolean get() = banks.any { it.hasContent }
}

data class BankTreeOverview(
    val bank: Bank,
    val currentAccounts: List<CurrentAccountTreeNode>,
    val savingsAccounts: List<AccountTreeNode>,
    val walletAccounts: List<AccountTreeNode>,
    val creditFacilities: List<CreditFacilityOverview>,
    val unlinkedDebitCards: List<DebitCardOverview>,
    val loans: List<LoanTreeNode>,
) {
    val hasContent: Boolean
        get() = currentAccounts.isNotEmpty() ||
            savingsAccounts.isNotEmpty() ||
            walletAccounts.isNotEmpty() ||
            creditFacilities.isNotEmpty() ||
            unlinkedDebitCards.isNotEmpty() ||
            loans.isNotEmpty()
}

data class CurrentAccountTreeNode(
    val bank: Bank,
    val maskedNumber: String,
    val displayLabel: String,
    val ownedAccount: OwnedAccount?,
    val debitCards: List<DebitCardOverview>,
)

data class AccountTreeNode(
    val bank: Bank,
    val maskedNumber: String,
    val displayLabel: String,
    val ownedAccount: OwnedAccount?,
)

data class LoanTreeNode(
    val id: String,
    val bank: Bank,
    val loanType: LoanType,
    val displayLabel: String,
)

object BankHierarchyBuilder {
    fun build(
        ownedAccounts: List<AccountRegistryEntry>,
        accountsFleet: AccountsSummary,
        creditFacilities: CreditFacilitiesOverview,
        loans: List<LoanRegistryEntry> = emptyList(),
    ): BankHierarchyOverview {
        val ownedByBank = ownedAccounts
            .filter { it.bank != Bank.UNKNOWN && it.ownership == OwnershipStatus.OWNED }
            .groupBy { it.bank }

        val fleetByMasked = accountsFleet.accounts.associateBy { it.bank to it.maskedNumber }

        val facilityByBank = creditFacilities.facilities.groupBy { it.bank }
        val allDebitCards = creditFacilities.debitCards
        val assignedDebitKeys = mutableSetOf<String>()

        fun ownedAccountFor(entry: AccountRegistryEntry): OwnedAccount? =
            fleetByMasked[entry.bank to entry.maskedNumber]

        val bankIds = (
            ownedByBank.keys +
                facilityByBank.keys +
                allDebitCards.map { it.bank } +
                loans.map { it.bank }
        ).distinct()

        val trees = bankIds.map { bank ->
            val accounts = ownedByBank[bank].orEmpty()
            val currentEntries = accounts.filter { it.accountType == AccountType.CURRENT }
            val savingsEntries = accounts.filter { it.accountType == AccountType.SAVINGS }
            val walletEntries = accounts.filter { it.accountType == AccountType.WALLET }

            val bankDebitCards = allDebitCards.filter { it.bank == bank }

            val currentNodes = currentEntries.map { entry ->
                val matchedDebit = bankDebitCards.filter { debit ->
                    val cardKey = CardTransactionInvolvementResolver.cardKey(bank.id, debit.last4)
                    cardKey !in assignedDebitKeys && debitLinkMatchesAccount(debit.linkedAccountMaskedNumber, entry.maskedNumber)
                }.also { matched ->
                    matched.forEach { debit ->
                        assignedDebitKeys.add(CardTransactionInvolvementResolver.cardKey(bank.id, debit.last4))
                    }
                }
                CurrentAccountTreeNode(
                    bank = bank,
                    maskedNumber = entry.maskedNumber,
                    displayLabel = RegistryDisplayLabels.accountLabel(entry),
                    ownedAccount = ownedAccountFor(entry),
                    debitCards = matchedDebit,
                )
            }

            val unlinkedDebit = bankDebitCards.filter { debit ->
                CardTransactionInvolvementResolver.cardKey(bank.id, debit.last4) !in assignedDebitKeys
            }

            BankTreeOverview(
                bank = bank,
                currentAccounts = currentNodes,
                savingsAccounts = savingsEntries.map { entry ->
                    AccountTreeNode(
                        bank = bank,
                        maskedNumber = entry.maskedNumber,
                        displayLabel = RegistryDisplayLabels.accountLabel(entry),
                        ownedAccount = ownedAccountFor(entry),
                    )
                },
                walletAccounts = walletEntries.map { entry ->
                    AccountTreeNode(
                        bank = bank,
                        maskedNumber = entry.maskedNumber,
                        displayLabel = RegistryDisplayLabels.accountLabel(entry),
                        ownedAccount = ownedAccountFor(entry),
                    )
                },
                creditFacilities = facilityByBank[bank].orEmpty(),
                unlinkedDebitCards = unlinkedDebit,
                loans = loans
                    .filter { it.bank == bank && it.ownership == OwnershipStatus.OWNED }
                    .map { loan ->
                        LoanTreeNode(
                            id = loan.id,
                            bank = loan.bank,
                            loanType = loan.loanType,
                            displayLabel = RegistryDisplayLabels.loanLabel(loan),
                        )
                    },
            )
        }.filter { it.hasContent }

        return BankHierarchyOverview(banks = trees)
    }
}

/** One rule for Home and Settings: a debit card sits under the current account its linked number names. */
internal fun debitLinkMatchesAccount(linked: String?, maskedNumber: String): Boolean =
    linked != null && (maskedNumber == linked || maskedNumber.endsWith(linked) || linked.endsWith(maskedNumber.takeLast(4)))
