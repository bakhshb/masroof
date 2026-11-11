package com.baraa.masroof.application.settings

import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.AccountRegistryEntry
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanRegistryEntry
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.ownership.OwnershipConfirmationService
import com.baraa.masroof.domain.repository.AccountRegistryRepository
import com.baraa.masroof.domain.repository.CardRegistryRepository
import com.baraa.masroof.domain.repository.LoanRegistryRepository
import kotlinx.coroutines.withContext

/**
 * Settings-screen registry reads, metadata edits, and ownership confirmation.
 */
class SettingsRegistryWorkflow(
    private val cardRegistryRepository: CardRegistryRepository,
    private val accountRegistryRepository: AccountRegistryRepository,
    private val loanRegistryRepository: LoanRegistryRepository,
    private val ownershipConfirmationService: OwnershipConfirmationService,
    private val parsedEvents: com.baraa.masroof.parsing.repository.ParsedEventRepository,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
    private val transactions: com.baraa.masroof.domain.repository.FinancialTransactionRepository,
    /** Accounts a taught message pattern posts to; deleting one would keep receiving transactions. */
    private val patternBoundAccounts: suspend () -> Set<AccountReference> = { emptySet() },
    /** Cards a message pattern links to; deleting one would keep receiving transactions. */
    private val patternBoundCards: suspend () -> Set<CardReference> = { emptySet() },
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    suspend fun addAccount(bankId: String, last4: String, name: String?): AccountReference {
        val ref = AccountReference(Bank(bankId), canonicalLast4(last4))
        require(ref.bank != Bank.UNKNOWN)
        inTransaction {
            require(accountRegistryRepository.get(ref) == null) { "Account already exists" }
            ownershipConfirmationService.confirmAccountOwned(ref)
            accountRegistryRepository.updateDisplayName(ref, name?.trim()?.ifEmpty { null })
        }
        return ref
    }

    suspend fun addCard(bankId: String, last4: String, name: String?, configuration: com.baraa.masroof.domain.model.CardConfiguration): CardReference {
        val ref = CardReference(Bank(bankId), canonicalLast4(last4))
        require(ref.bank != Bank.UNKNOWN)
        configuration.validate(ref.bank, requireNotNull(ref.last4))
        inTransaction {
            require(cardRegistryRepository.get(ref) == null) { "Card already exists" }
            ownershipConfirmationService.confirmCardOwned(ref)
            cardRegistryRepository.configure(ref, configuration)
            cardRegistryRepository.updateDisplayName(ref, name?.trim()?.ifEmpty { null })
        }
        return ref
    }

    suspend fun configureCard(card: CardReference, configuration: com.baraa.masroof.domain.model.CardConfiguration) {
        cardRegistryRepository.configure(card, configuration)
    }

    /** A suggestion only. Conflicting evidence stays unknown; existing user choices are never overwritten. */
    suspend fun suggestedCardType(card: CardReference): com.baraa.masroof.domain.model.CardType? {
        val signals = parsedEvents.listByRawSmsIds(parsedEvents.listRawSmsIdsReferencingCard(card))
            .filter { it.event.cardRef == card && it.event.bank == card.bank && it.event.parseStatus == com.baraa.masroof.domain.model.ParseStatus.SUCCESS }
            .mapNotNull { record -> when (record.details.cardSmsChannel) {
                com.baraa.masroof.parsing.model.CardSmsChannel.CREDIT,
                com.baraa.masroof.parsing.model.CardSmsChannel.STATEMENT -> com.baraa.masroof.domain.model.CardType.CREDIT
                com.baraa.masroof.parsing.model.CardSmsChannel.DEBIT -> com.baraa.masroof.domain.model.CardType.DEBIT
                null -> null
            } }.toSet()
        return signals.singleOrNull()
    }

    data class Snapshot(
        val cards: List<CardRegistryEntry>,
        val accounts: List<AccountRegistryEntry>,
        val loans: List<LoanRegistryEntry>,
    )

    suspend fun loadSnapshot(): Snapshot =
        Snapshot(
            cards = cardRegistryRepository.listAll(),
            accounts = accountRegistryRepository.listAll(),
            loans = loanRegistryRepository.listAll(),
        )

    suspend fun updateCardDisplayName(card: CardReference, displayName: String?) {
        cardRegistryRepository.updateDisplayName(card, displayName)
    }

    suspend fun updateAccountDisplayName(account: AccountReference, displayName: String?) {
        accountRegistryRepository.updateDisplayName(account, displayName)
    }

    suspend fun updateAccountType(account: AccountReference, accountType: AccountType) {
        accountRegistryRepository.updateAccountType(account, accountType)
    }

    suspend fun confirmCardOwned(card: CardReference) {
        ownershipConfirmationService.confirmCardOwned(card)
    }

    suspend fun markCardExternal(card: CardReference) {
        ownershipConfirmationService.markCardExternal(card)
    }

    suspend fun confirmAccountOwned(account: AccountReference) {
        ownershipConfirmationService.confirmAccountOwned(account)
    }

    suspend fun markAccountExternal(account: AccountReference) {
        ownershipConfirmationService.markAccountExternal(account)
    }

    suspend fun confirmLoanOwned(loan: LoanReference) {
        ownershipConfirmationService.confirmLoanOwned(loan)
    }

    suspend fun markLoanExternal(loan: LoanReference) {
        ownershipConfirmationService.markLoanExternal(loan)
    }

    // Scans the ledger and reads the pattern file, so it stays off the caller's (main) thread.
    suspend fun deletionPreview(target: RegistryDeleteTarget): DeletionPreview = withContext(ioDispatcher) {
        val linked = linkedTransactions(target)
        DeletionPreview(linked.take(DeletionPreview.SHOWN), linked.size, blocker(target, linked))
    }

    /** Re-checks inside the write so a transaction posted after the preview still blocks deletion. */
    suspend fun delete(target: RegistryDeleteTarget): Unit = withContext(ioDispatcher) {
        inTransaction {
            val blocker = blocker(target, linkedTransactions(target))
            check(blocker == null) { "Delete blocked: $blocker" }
            when (target) {
                is RegistryDeleteTarget.Account -> accountRegistryRepository.delete(target.account)
                is RegistryDeleteTarget.Card -> cardRegistryRepository.delete(target.card)
                is RegistryDeleteTarget.Loan -> loanRegistryRepository.delete(target.loan)
            }
        }
    }

    private suspend fun linkedTransactions(target: RegistryDeleteTarget): List<com.baraa.masroof.domain.model.FinancialTransaction> {
        val containerIds = when (target) {
            // Legacy rows may still hold the bank-less account id; treat them as linked rather than guess.
            is RegistryDeleteTarget.Account -> setOfNotNull(FinancialContainerIdFactory.accountId(target.account),
                target.account.maskedNumber?.let { "account:$it" })
            is RegistryDeleteTarget.Card -> setOfNotNull(FinancialContainerIdFactory.cardId(target.card))
            is RegistryDeleteTarget.Loan -> setOfNotNull(FinancialContainerIdFactory.loanId(target.loan))
        }
        val all = transactions.listAll()
        val rawSmsIds = when (target) {
            is RegistryDeleteTarget.Account -> parsedEvents.listRawSmsIdsReferencingAccount(target.account)
            is RegistryDeleteTarget.Card -> parsedEvents.listRawSmsIdsReferencingCard(target.card)
            is RegistryDeleteTarget.Loan -> parsedEvents.listRawSmsIdsReferencingLoan(target.loan)
        }
        val evidenceLinked = transactions.listTransactionIdsForRawSmsIds(rawSmsIds)
        return all.filter { it.sourceContainerId in containerIds || it.destinationContainerId in containerIds || it.id in evidenceLinked }
            .sortedByDescending { it.occurredAt }
    }

    private suspend fun blocker(target: RegistryDeleteTarget, linked: List<com.baraa.masroof.domain.model.FinancialTransaction>): DeleteBlocker? {
        if (linked.isNotEmpty()) return DeleteBlocker.LINKED_TRANSACTIONS
        return when (target) {
            is RegistryDeleteTarget.Card -> when {
                cardRegistryRepository.listAll().any { it.bank == target.card.bank && it.parentCardLast4 == target.card.last4 } ->
                    DeleteBlocker.SUPPLEMENTARY_CARDS
                target.card in patternBoundCards() -> DeleteBlocker.MESSAGE_PATTERN
                else -> null
            }
            is RegistryDeleteTarget.Account -> when {
                cardRegistryRepository.listAll().any {
                    it.linkedAccountBankId == target.account.bank.id && it.linkedAccountMaskedNumber == target.account.maskedNumber
                } -> DeleteBlocker.LINKED_CARD
                target.account in patternBoundAccounts() -> DeleteBlocker.MESSAGE_PATTERN
                else -> null
            }
            is RegistryDeleteTarget.Loan -> null
        }
    }
}

sealed interface RegistryDeleteTarget {
    data class Account(val account: AccountReference) : RegistryDeleteTarget
    data class Card(val card: CardReference) : RegistryDeleteTarget
    data class Loan(val loan: LoanReference) : RegistryDeleteTarget
}

enum class DeleteBlocker { LINKED_TRANSACTIONS, SUPPLEMENTARY_CARDS, LINKED_CARD, MESSAGE_PATTERN }

/** [linked] holds the newest [SHOWN] linked transactions; [linkedCount] counts them all. */
data class DeletionPreview(
    val linked: List<com.baraa.masroof.domain.model.FinancialTransaction>,
    val linkedCount: Int,
    val blocker: DeleteBlocker?,
) {
    companion object { const val SHOWN = 5 }
}

/** Normalized last four digits typed by the user; throws unless exactly four digits. */
internal fun canonicalLast4(value: String): String {
    val suffix = com.baraa.masroof.parsing.normalizer.MessageNormalizer().normalize(value).comparisonBody.trim()
    require(suffix.matches(Regex("[0-9]{4}")))
    return suffix
}
