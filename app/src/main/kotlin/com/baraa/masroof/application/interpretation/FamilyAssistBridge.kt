package com.baraa.masroof.application.interpretation

import com.baraa.masroof.engine.bank.Confidence
import com.baraa.masroof.engine.bank.Facts
import com.baraa.masroof.engine.bank.Interpretation
import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.ParseStatus
import com.baraa.masroof.engine.bank.interpretation.FamilyOrigin
import com.baraa.masroof.engine.bank.interpretation.FamilySuggestion
import com.baraa.masroof.engine.bank.interpretation.FamilyTextPreparation
import com.baraa.masroof.engine.bank.interpretation.FamilyTextShape
import com.baraa.masroof.ml.family.*
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/** The single bridge used by live intake, feedback and offline fixture exports. */
object FamilyAssistBridge {
    fun clean(body: String): FamilyCleaningResult = clean(FamilyTextPreparation.prepare(body))
    fun clean(shape: FamilyTextShape): FamilyCleaningResult = FamilyInputCleaner.clean(shape.title, shape.fieldLabels)

    fun suggest(classifier: FamilyClassifier?, bankId: String, features: CleanFamilyFeatures?): FamilySuggestion? {
        val proposal = classifier?.suggest(bankId, features ?: return null) ?: return null
        return FamilySuggestion(toBank(proposal.family), 1.0, proposal.modelVersion, FamilyOrigin.HUMAN_TEMPLATE)
    }

    fun suggest(classifier: FamilyClassifier?, bankId: String, body: String): FamilySuggestion? =
        classifier?.let { suggest(it, bankId, clean(body).features) }

    /**
     * A remembered "not financial" decision settles only a message the rules could not identify and that carries no
     * transaction amount, in any bank. The existing informational policy then keeps it out of review; it never
     * touches money or posting. A message with an amount stays reviewable even with the same wording.
     */
    fun rememberNonFinancial(result: Interpretation, suggestion: FamilySuggestion?): Interpretation =
        if (suggestion?.family != MessageFamily.NON_FINANCIAL || result.facts.family != MessageFamily.UNKNOWN ||
            result.facts.amount != null || result.bank == null) result
        // The draft mapper stamps family_origin/model reasons from [Interpretation.familyInference].
        else result.copy(facts = Facts(MessageFamily.NON_FINANCIAL), status = ParseStatus.NON_FINANCIAL,
            confidence = Confidence(suggestion.score, emptyList()), evidence = emptyList(), blockers = emptySet(), familyInference = suggestion)

    /** Messages a remembered "not financial" once settled that today's decisions no longer settle; they go back to review. */
    fun revokedMemories(records: List<ParsedEventRecord>, bodies: Map<String, String>,
        models: Map<String, FamilyClassifier>): Set<String> = records.filter { record ->
            val body = bodies[record.event.rawSmsId] ?: return@filter false
            suggest(models[record.event.bank.id], record.event.bank.id, body)?.family !=
                MessageFamily.NON_FINANCIAL
        }.map { it.event.rawSmsId }.toSet()

    fun toModel(family: MessageFamily): SmsFamilyLabel = when (family) {
        MessageFamily.PURCHASE -> SmsFamilyLabel.PURCHASE
        MessageFamily.TRANSFER_IN -> SmsFamilyLabel.TRANSFER_IN
        MessageFamily.TRANSFER_OUT -> SmsFamilyLabel.TRANSFER_OUT
        MessageFamily.CARD_PAYMENT -> SmsFamilyLabel.CARD_PAYMENT
        MessageFamily.BILL_PAYMENT -> SmsFamilyLabel.BILL_PAYMENT
        MessageFamily.FINANCING_INSTALLMENT -> SmsFamilyLabel.FINANCING_INSTALLMENT
        MessageFamily.WITHDRAWAL -> SmsFamilyLabel.WITHDRAWAL
        MessageFamily.REFUND -> SmsFamilyLabel.REFUND
        MessageFamily.FEE -> SmsFamilyLabel.FEE
        MessageFamily.BALANCE_NOTICE -> SmsFamilyLabel.BALANCE_NOTICE
        MessageFamily.OTP -> SmsFamilyLabel.OTP
        MessageFamily.NON_FINANCIAL -> SmsFamilyLabel.NON_FINANCIAL
        MessageFamily.UNKNOWN -> SmsFamilyLabel.UNKNOWN
    }
    fun toBank(family: SmsFamilyLabel): MessageFamily = when (family) {
        SmsFamilyLabel.PURCHASE -> MessageFamily.PURCHASE
        SmsFamilyLabel.TRANSFER_IN -> MessageFamily.TRANSFER_IN
        SmsFamilyLabel.TRANSFER_OUT -> MessageFamily.TRANSFER_OUT
        SmsFamilyLabel.CARD_PAYMENT -> MessageFamily.CARD_PAYMENT
        SmsFamilyLabel.BILL_PAYMENT -> MessageFamily.BILL_PAYMENT
        SmsFamilyLabel.FINANCING_INSTALLMENT -> MessageFamily.FINANCING_INSTALLMENT
        SmsFamilyLabel.WITHDRAWAL -> MessageFamily.WITHDRAWAL
        SmsFamilyLabel.REFUND -> MessageFamily.REFUND
        SmsFamilyLabel.FEE -> MessageFamily.FEE
        SmsFamilyLabel.BALANCE_NOTICE -> MessageFamily.BALANCE_NOTICE
        SmsFamilyLabel.OTP -> MessageFamily.OTP
        SmsFamilyLabel.NON_FINANCIAL -> MessageFamily.NON_FINANCIAL
        SmsFamilyLabel.UNKNOWN -> MessageFamily.UNKNOWN
    }
}
