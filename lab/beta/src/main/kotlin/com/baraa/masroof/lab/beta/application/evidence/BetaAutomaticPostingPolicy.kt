package com.baraa.masroof.lab.beta.application.evidence

import com.baraa.masroof.lab.bank.MessageFamily
import com.baraa.masroof.lab.bank.MoneyDirection
import com.baraa.masroof.lab.beta.application.BetaAssetProposal
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.IncomeAttribution
import com.baraa.masroof.lab.finance.posting.FinancialType

internal data class BetaAutomaticDecision(val type: FinancialType, val source: AssetId? = null,
    val destination: AssetId? = null, val income: IncomeAttribution? = null, val hold: String? = null)

/** Consumes proven persisted identities only. Never guesses ownership, loan attribution or transfer pairing. */
internal object BetaAutomaticPostingPolicy {
    fun decide(family: MessageFamily, direction: MoneyDirection?, salary: Boolean, financing: Boolean,
        cardObserved: Boolean, proposals: List<BetaAssetProposal>, registry: Map<AssetId, OwnedAsset>, loanId: AssetId? = null,
        transferSource: AssetId? = null, transferDestination: AssetId? = null,
        transferSourceExternal: Boolean = false,transferDestinationExternal: Boolean = false): BetaAutomaticDecision {
        fun hold(reason: String) = BetaAutomaticDecision(FinancialType.UNKNOWN, hold = reason)
        val resolved = mutableListOf<OwnedAsset>()
        for (proposal in proposals) {
            val matches = registry.values.filter { it.id.bank == proposal.id.bank && it.id.environment == proposal.id.environment &&
                it.kind == proposal.kind && it.last4 == proposal.last4 }
            val chosen = proposal.confirmedAssetId?.let { id -> matches.singleOrNull { it.id == id } }
                ?: matches.singleOrNull().takeIf { proposal.confirmedAssetId == null }
                ?: return hold(if (matches.size > 1) "AMBIGUOUS_OWNER" else "CONFIRM_OWNERSHIP")
            resolved += chosen
        }
        val accounts = resolved.filter { it.kind == AssetKind.ACCOUNT }.distinctBy { it.id }
        val cards = resolved.filter { it.kind in setOf(AssetKind.DEBIT_CARD, AssetKind.CREDIT_CARD) }.distinctBy { it.id }
        if (family in setOf(MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT)) {
            val source = transferSource ?: accounts.singleOrNull()?.id.takeIf { family == MessageFamily.TRANSFER_OUT && !transferSourceExternal }
            val destination = transferDestination ?: accounts.singleOrNull()?.id.takeIf { family == MessageFamily.TRANSFER_IN && !transferDestinationExternal }
            if (direction != if (family == MessageFamily.TRANSFER_IN) MoneyDirection.INCOMING else MoneyDirection.OUTGOING)
                return hold("UNCLEAR_DIRECTION")
            if (source != null && destination != null)
                return BetaAutomaticDecision(FinancialType.SELF_TRANSFER, source, destination)
            return if (destination != null && (family==MessageFamily.TRANSFER_IN || transferSourceExternal))
                BetaAutomaticDecision(if (salary) FinancialType.INCOME else FinancialType.EXTERNAL_TRANSFER_IN,
                    destination = destination, income = if (salary) IncomeAttribution.SALARY else IncomeAttribution.OTHER)
            else if (source != null && (family==MessageFamily.TRANSFER_OUT || transferDestinationExternal))
                BetaAutomaticDecision(FinancialType.EXTERNAL_TRANSFER_OUT, source = source)
            else hold("CONFIRM_OWNERSHIP")
        }
        if (accounts.size > 1 || cards.size > 1) return hold("AMBIGUOUS_OWNER")
        val account = accounts.singleOrNull()
        val card = cards.singleOrNull()
        if (card?.kind == AssetKind.DEBIT_CARD && (card.parentId == null || registry[card.parentId]?.kind != AssetKind.ACCOUNT))
            return hold("LINK_DEBIT_ACCOUNT")
        if (card != null && account != null && card.parentId != account.id && card.kind == AssetKind.DEBIT_CARD)
            return hold("CONFLICTING_ACCOUNT_LINK")
        if (financing || family == MessageFamily.FINANCING_INSTALLMENT) {
            val loan=loanId?.let(registry::get)?.takeIf {it.kind==AssetKind.LOAN && it.id.bank==account?.id?.bank}
                ?: return hold("CONFIRM_LOAN")
            if(direction!=MoneyDirection.OUTGOING) return hold("UNCLEAR_DIRECTION")
            return BetaAutomaticDecision(FinancialType.LOAN_REPAYMENT,requireNotNull(account).id,loan.id)
        }
        val type = when (family) {
            MessageFamily.PURCHASE -> FinancialType.EXPENSE
            MessageFamily.REFUND -> FinancialType.REFUND
            MessageFamily.WITHDRAWAL -> FinancialType.CASH_WITHDRAWAL
            MessageFamily.BILL_PAYMENT -> FinancialType.BILL_PAYMENT
            MessageFamily.FEE -> FinancialType.FEE
            MessageFamily.TRANSFER_IN -> if (salary) FinancialType.INCOME else return hold("CONFIRM_TRANSFER")
            MessageFamily.CARD_PAYMENT -> FinancialType.CREDIT_CARD_PAYMENT
            else -> return hold("CONFIRM_TRANSFER_OR_TYPE")
        }
        val incoming = type in setOf(FinancialType.REFUND, FinancialType.INCOME)
        if (direction != if (incoming) MoneyDirection.INCOMING else MoneyDirection.OUTGOING) return hold("UNCLEAR_DIRECTION")
        if (type == FinancialType.CREDIT_CARD_PAYMENT) {
            if (account == null || card?.kind != AssetKind.CREDIT_CARD) return hold("CONFIRM_PAYMENT_ENDPOINTS")
            return BetaAutomaticDecision(type, account.id, card.id)
        }
        if (cardObserved && card == null && type != FinancialType.CASH_WITHDRAWAL) return hold("CONFIRM_CARD")
        val owner = if (type in setOf(FinancialType.INCOME, FinancialType.CASH_WITHDRAWAL)) account
            else if(card?.kind==AssetKind.DEBIT_CARD) card.parentId?.let(registry::get) else card ?: account
        if (owner == null) return hold("CONFIRM_OWNERSHIP")
        return if (incoming) BetaAutomaticDecision(type, destination = owner.id,
            income = IncomeAttribution.SALARY.takeIf { type == FinancialType.INCOME }) else BetaAutomaticDecision(type, source = owner.id)
    }
}
