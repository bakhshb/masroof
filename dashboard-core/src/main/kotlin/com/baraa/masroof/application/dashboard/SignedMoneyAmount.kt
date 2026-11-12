package com.baraa.masroof.application.dashboard

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Projection-only signed amount.
 *
 * Domain [Money] remains non-negative. Dashboard net spending may be negative when
 * refunds exceed expenses in the selected period.
 */
data class SignedMoneyAmount(
    val amount: BigDecimal,
    val currency: Currency,
) {
    init {
        require(amount.scale() <= Money.SCALE) {
            "SignedMoneyAmount scale must be <= ${Money.SCALE}, was ${amount.scale()}"
        }
    }

    val isNegative: Boolean
        get() = amount.signum() < 0

    companion object {
        fun of(money: Money): SignedMoneyAmount =
            SignedMoneyAmount(money.amount, money.currency)

        fun zero(currency: Currency): SignedMoneyAmount =
            SignedMoneyAmount(BigDecimal.ZERO.setScale(Money.SCALE), currency)

        fun difference(left: Money, right: Money): SignedMoneyAmount {
            require(left.currency == right.currency) {
                "Currency mismatch: ${left.currency} vs ${right.currency}"
            }
            return SignedMoneyAmount(
                left.amount.subtract(right.amount).setScale(Money.SCALE, RoundingMode.HALF_EVEN),
                left.currency,
            )
        }
    }
}
