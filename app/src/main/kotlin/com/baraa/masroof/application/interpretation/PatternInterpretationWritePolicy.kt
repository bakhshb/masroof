package com.baraa.masroof.application.interpretation

import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.engine.bank.patterns.PatternFactInterpreter
import com.baraa.masroof.parsing.model.PatternInterpretationStamp
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import kotlinx.coroutines.CancellationException

/** Called under the financial transaction's access lease, which excludes configuration edits / restores. */
class PatternInterpretationWritePolicy(private val store: MessagePatternStore,
    private val accounts: com.baraa.masroof.domain.repository.AccountRegistryRepository? = null,
    private val cards: com.baraa.masroof.domain.repository.CardRegistryRepository? = null) {
    /**
     * [posted] evidence is never re-read, so only its pattern's enabled state and owned accounts are checked:
     * a later save (new generation or revision) must not stop a posted leg from pairing.
     */
    suspend fun isCurrent(record: ParsedEventRecord, posted: Boolean = false): Boolean {
        if (!isPatternDerived(record)) return true
        val stamp = PatternInterpretationStamp.decode(record.event.confidence.reasons) ?: return false
        return try {
            val configuration = store.load()
            val pattern = configuration.banks.singleOrNull { it.bankId == record.event.bank.id }?.currentPatterns
                ?.singleOrNull { it.id == stamp.patternId } ?: return false
            val contract = pattern.factContract ?: return false
            if (stamp.roleProfile != (pattern.roleProfile != null)) return false
            if (!pattern.enabled) return false
            if (!posted && (pattern.revision != stamp.revision ||
                PatternFactInterpreter.of(configuration).fingerprint != stamp.fingerprint)) return false
            for (binding in contract.accountBindings) {
                if (binding.role == com.baraa.masroof.engine.bank.patterns.PatternAccountRole.CARD) {
                    val card = com.baraa.masroof.domain.model.CardReference(com.baraa.masroof.domain.model.Bank(binding.bank.value), binding.last4)
                    // A card link fills only a message without one; when it was used, the card must still be owned.
                    if (record.event.cardRef == card && cards?.get(card)?.ownership != com.baraa.masroof.domain.model.OwnershipStatus.OWNED) return false
                    continue
                }
                val reference = com.baraa.masroof.domain.model.AccountReference(com.baraa.masroof.domain.model.Bank(binding.bank.value), binding.last4)
                val observed = if (binding.role == com.baraa.masroof.engine.bank.patterns.PatternAccountRole.SOURCE) record.event.sourceAccountRef else record.event.destinationAccountRef
                // A built-in link, like a card link, fills only a missing account; a taught link always supplies it.
                if (pattern.roleProfile != null && observed != reference) continue
                val account = accounts?.get(reference) ?: return false
                if (account.bank != reference.bank || account.maskedNumber != binding.last4 || account.ownership != com.baraa.masroof.domain.model.OwnershipStatus.OWNED) return false
                if (observed != reference) return false
            }
            true
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { false } // Corrupt / unsupported definitions cannot grant a financial write.
    }

    companion object {
        fun isPatternDerived(record: ParsedEventRecord) = PatternInterpretationStamp.isPatternDerived(record.event.confidence.reasons)
    }
}
