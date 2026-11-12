package com.baraa.masroof.presentation.settings

import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.OwnershipStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A finished reparse or import retires its own busy refusal, never a real write failure. */
internal fun SettingsError?.withoutBusyRefusal(): SettingsError? = takeUnless { it == SettingsError.REGISTRY_BUSY }

internal fun applyRegistries(
    settingsState: MutableStateFlow<SettingsUiState>,
    cards: List<com.baraa.masroof.domain.model.CardRegistryEntry>,
    accounts: List<com.baraa.masroof.domain.model.AccountRegistryEntry>,
    loans: List<com.baraa.masroof.domain.model.LoanRegistryEntry>,
    commitments: List<Commitment>? = null,
) {
    val cardItems = cards
        .filter { it.bank != Bank.UNKNOWN }
        .map {
            ManagedCardUi(
                id = it.id,
                bank = it.bank,
                last4 = it.last4,
                ownership = it.ownership,
                displayName = it.displayName,
                cardNetwork = it.cardNetwork,
                cardType = it.cardType,
                cardRole = it.cardRole,
                parentCardLast4 = it.parentCardLast4,
                linkedAccountMaskedNumber = it.linkedAccountMaskedNumber,
            )
        }
        .sortedBy { it.last4 }
    val accountItems = accounts
        .filter { it.bank != Bank.UNKNOWN }
        .map {
            ManagedAccountUi(
                id = it.id,
                bank = it.bank,
                maskedNumber = it.maskedNumber,
                ownership = it.ownership,
                displayName = it.displayName,
                accountType = it.accountType,
            )
        }
        .sortedBy { it.maskedNumber }
    val loanItems = loans
        .filter { it.bank != Bank.UNKNOWN }
        .map {
            ManagedLoanUi(
                id = it.id,
                bank = it.bank,
                loanType = it.loanType,
                ownership = it.ownership,
                displayName = it.displayName,
            )
        }
    val followedCards = cardItems.filter { card -> card.ownership == OwnershipStatus.OWNED }
    val followedAccounts = accountItems.filter { account -> account.ownership == OwnershipStatus.OWNED }
    settingsState.update {
        it.copy(
            loading = false,
            followedCards = followedCards,
            unregisteredCards = cardItems.filter { card -> card.ownership == OwnershipStatus.UNKNOWN },
            stoppedCards = cardItems.filter { card -> card.ownership == OwnershipStatus.EXTERNAL },
            followedAccounts = followedAccounts,
            unregisteredAccounts = accountItems.filter { account -> account.ownership == OwnershipStatus.UNKNOWN },
            stoppedAccounts = accountItems.filter { account -> account.ownership == OwnershipStatus.EXTERNAL },
            loans = loanItems,
            bankTrees = buildBankTrees(
                cards = followedCards,
                accounts = followedAccounts,
                loans = loanItems,
            ),
            bankSummaries = buildBankSummaries(
                bankCatalog = settingsState.value.bankCatalog,
                accounts = accountItems,
                cards = cardItems,
                loans = loanItems,
            ),
            error = null,
        ).let { state -> if (commitments == null) state else state.withCommitments(commitments) }
    }
}

private fun buildBankSummaries(
    bankCatalog: com.baraa.masroof.application.patterns.PatternCatalog?,
    accounts: List<ManagedAccountUi>,
    cards: List<ManagedCardUi>,
    loans: List<ManagedLoanUi>,
): List<SettingsBankSummaryUi> {
    val bankIds = (
        accounts.map { it.bank.id } +
            cards.map { it.bank.id } +
            loans.map { it.bank.id } + bankCatalog?.banks.orEmpty().map { it.bankId }
    ).distinct().sorted()
    return bankIds.mapNotNull { bankId ->
        val bankAccounts = accounts.filter { it.bank.id == bankId }
        val bankCards = cards.filter { it.bank.id == bankId }
        val bankLoans = loans.filter { it.bank.id == bankId }
        SettingsBankSummaryUi(
            bank = Bank(bankId),
            followedAccountCount = bankAccounts.count { it.ownership == OwnershipStatus.OWNED },
            unregisteredAccountCount = bankAccounts.count { it.ownership == OwnershipStatus.UNKNOWN },
            stoppedAccountCount = bankAccounts.count { it.ownership == OwnershipStatus.EXTERNAL },
            followedCardCount = bankCards.count { it.ownership == OwnershipStatus.OWNED },
            unregisteredCardCount = bankCards.count { it.ownership == OwnershipStatus.UNKNOWN },
            stoppedCardCount = bankCards.count { it.ownership == OwnershipStatus.EXTERNAL },
            loanCount = bankLoans.size,
        )
    }
}

private fun buildBankTrees(
    cards: List<ManagedCardUi>,
    accounts: List<ManagedAccountUi>,
    loans: List<ManagedLoanUi>,
): List<SettingsBankTreeUi> {
    val bankIds = (
        cards.map { it.bank.id } +
            accounts.map { it.bank.id } +
            loans.map { it.bank.id }
    ).distinct().sorted()
    return bankIds.map { bankId ->
        val bank = Bank(bankId)
        val bankCards = cards.filter { it.bank.id == bankId }
        val bankAccounts = accounts.filter { it.bank.id == bankId }
        val currentAccounts = bankAccounts.filter { it.accountType == AccountType.CURRENT }
        val debitCards = bankCards.filter { it.isDebitRegistryCard() }
        val assignedDebitLast4s = mutableSetOf<String>()
        val currentNodes = currentAccounts.map { account ->
            val matchedDebit = debitCards.filter { debit ->
                debit.last4 !in assignedDebitLast4s &&
                    com.baraa.masroof.application.dashboard.debitLinkMatchesAccount(debit.linkedAccountMaskedNumber, account.maskedNumber)
            }
            matchedDebit.forEach { assignedDebitLast4s.add(it.last4) }
            SettingsCurrentAccountNodeUi(account = account, debitCards = matchedDebit)
        }
        val unlinkedDebit = debitCards.filter { it.last4 !in assignedDebitLast4s }
        SettingsBankTreeUi(
            bank = bank,
            currentAccountNodes = currentNodes,
            savingsAccounts = bankAccounts.filter { it.accountType == AccountType.SAVINGS },
            walletAccounts = bankAccounts.filter { it.accountType == AccountType.WALLET },
            creditCards = bankCards.filter { !it.isDebitRegistryCard() },
            unlinkedDebitCards = unlinkedDebit,
            loans = loans.filter { it.bank.id == bankId },
        )
    }.filter { tree ->
        tree.currentAccountNodes.isNotEmpty() ||
            tree.savingsAccounts.isNotEmpty() ||
            tree.walletAccounts.isNotEmpty() ||
            tree.creditCards.isNotEmpty() ||
            tree.unlinkedDebitCards.isNotEmpty() ||
            tree.loans.isNotEmpty()
    }
}

private fun ManagedCardUi.isDebitRegistryCard(): Boolean =
    when (cardType) {
        CardType.DEBIT -> true
        CardType.CREDIT -> false
        null -> cardNetwork == CardNetwork.MADA || linkedAccountMaskedNumber != null
    }
