package com.baraa.masroof.domain.model

/**
 * How a [ReviewItem] left [ReviewStatus.REQUIRED].
 */
enum class ReviewResolutionKind {
    AUTO_NO_LONGER_REQUIRED,
    USER_CORRECTION,
    USER_EXTERNAL_TRANSFER,
    USER_SELF_TRANSFER_PAIR,
    USER_FINANCIAL_TYPE,
    USER_NON_FINANCIAL,
}
