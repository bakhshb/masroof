package com.baraa.masroof.ml.family

/** Model-owned labels. The composition root explicitly maps these to the bank's families. */
enum class SmsFamilyLabel {
    PURCHASE, TRANSFER_IN, TRANSFER_OUT, CARD_PAYMENT, BILL_PAYMENT, FINANCING_INSTALLMENT,
    WITHDRAWAL, REFUND, FEE, BALANCE_NOTICE, OTP, NON_FINANCIAL, UNKNOWN,
}

/** Always an exact human template; there is no statistical origin. */
data class FamilyPrediction(val family: SmsFamilyLabel, val modelVersion: String)

enum class HumanLabelSource { USER_MESSAGE_FAMILY, USER_EVENT_KIND, USER_NON_FINANCIAL }

/** Revokes assistance for an exact template without inventing a replacement label. */
data class HumanTemplateBlock(val bankId: String, val rawId: String, val decisionId: String,
    val template: String, val at: Long) {
    init {
        require(bankId.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")))
        require(rawId.isNotBlank() && decisionId.isNotBlank() && at >= 0)
        require(template.matches(Regex("[0-9a-f]{64}")))
    }
}

data class HumanFamilyLabel(
    val bankId: String, val rawId: String, val decisionId: String, val family: SmsFamilyLabel,
    val template: String, val terms: Map<String, Int>, val at: Long, val provenance: String,
) {
    init {
        require(bankId.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")))
        require(rawId.isNotBlank() && decisionId.isNotBlank() && at >= 0)
        require(template.matches(Regex("[0-9a-f]{64}")) && FamilyInputCleaner.validTerms(terms))
        require(provenance in HumanLabelSource.entries.map { it.name })
        require(family != SmsFamilyLabel.UNKNOWN)
        require((provenance == HumanLabelSource.USER_NON_FINANCIAL.name) == (family == SmsFamilyLabel.NON_FINANCIAL) || provenance == HumanLabelSource.USER_MESSAGE_FAMILY.name)
    }
}
