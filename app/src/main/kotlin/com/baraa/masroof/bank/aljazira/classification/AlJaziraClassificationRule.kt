package com.baraa.masroof.bank.aljazira.classification

/**
 * One deterministic AlJazira family rule over folded comparison text.
 *
 * A rule only reports evidence; it never decides against other rules. Its
 * [AlJaziraClassificationCandidate.specificity] — not its position in the rule list —
 * is what [AlJaziraClassificationResolver] uses to choose between candidates.
 */
internal fun interface AlJaziraClassificationRule {
    fun evaluate(text: String): AlJaziraClassificationCandidate?

    companion object {
        fun of(
            id: String,
            specificity: Int,
            rank: Int = 0,
            matches: (String) -> Boolean,
            classify: (String) -> AlJaziraClassification,
        ): AlJaziraClassificationRule =
            AlJaziraClassificationRule { text ->
                if (matches(text)) {
                    AlJaziraClassificationCandidate(id, specificity, rank, classify(text))
                } else {
                    null
                }
            }
    }
}

/**
 * Evidence for one family. [rank] only breaks ties between candidates of the
 * *same* family (e.g. POS vs online purchase wording); it never decides between families.
 */
internal data class AlJaziraClassificationCandidate(
    val ruleId: String,
    val specificity: Int,
    val rank: Int,
    val classification: AlJaziraClassification,
)

/**
 * Explicit specificity tiers. A higher tier outranks a lower one; two different
 * families in the same top tier are ambiguous and fail safe to review.
 */
internal object AlJaziraClassificationSpecificity {
    /** OTP / verification: never financial, whatever amounts it quotes. */
    const val SECURITY = 100

    /** Login, loyalty and beneficiary notices: account events that move no money. */
    const val ACCOUNT_NOTICE = 95

    /** «إشعار رصيد» titled balance notices. */
    const val BALANCE_NOTICE = 90

    /** Card/account statement notices; they quote due amounts but move no money. */
    const val STATEMENT = 85

    /** Named financial products: installment, card payment, bill payment, refund. */
    const val PRODUCT = 80

    /** Money movement titles: purchase, withdrawal, transfer, titled fee. */
    const val MOVEMENT = 70

    /** Generic wording that only hints at a family (e.g. a fee line in the body). */
    const val GENERIC = 50
}
