package com.baraa.masroof.engine.bank.interpretation

import com.baraa.masroof.engine.bank.MessageFamily
import com.baraa.masroof.engine.bank.MessageInput

/** Supplies a family only. Monetary fields and financial approval cannot cross this interface. */
fun interface FamilyAssist { fun suggest(input: MessageInput, normalizedTitle: String): FamilySuggestion? }
/** Only remembered human decisions supply a family; there is no statistical model. */
enum class FamilyOrigin { HUMAN_TEMPLATE }
data class FamilySuggestion(val family: MessageFamily, val score: Double, val modelVersion: String, val origin: FamilyOrigin) {
    init { require(score.isFinite() && score in 0.0..1.0 && modelVersion.isNotBlank()) }
}

/** The engine owns which families may enter its existing validation pipeline. */
internal val assistFinancialFamilies = setOf(
    MessageFamily.PURCHASE, MessageFamily.TRANSFER_IN, MessageFamily.TRANSFER_OUT,
    MessageFamily.CARD_PAYMENT, MessageFamily.BILL_PAYMENT, MessageFamily.FINANCING_INSTALLMENT,
    MessageFamily.WITHDRAWAL, MessageFamily.REFUND, MessageFamily.FEE,
)
