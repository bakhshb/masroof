package com.baraa.masroof.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.domain.model.AccountType
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.CardType

private fun maskedName(custom: String?, fallback: String, number: String): String {
    val last4 = com.baraa.masroof.application.locale.AppDigits.display(number.takeLast(4))
    val name = custom?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    return if (com.baraa.masroof.application.locale.AppDigits.display(name).contains(last4)) name else "$name •• $last4"
}

@Composable
internal fun registryLabel(account: ManagedAccountUi): String =
    maskedName(account.displayName, accountTypeLabel(account.accountType), account.maskedNumber)

@Composable
internal fun registryLabel(card: ManagedCardUi): String = maskedName(card.displayName,
    stringResource(when {
        card.cardNetwork == com.baraa.masroof.domain.model.CardNetwork.MADA -> R.string.card_network_mada
        card.cardType == CardType.DEBIT -> R.string.settings_card_type_debit
        card.cardType == null -> R.string.settings_card_type_unknown
        card.cardRole == CardRole.SUPPLEMENTARY -> R.string.dashboard_credit_facility_additional
        card.cardRole == CardRole.PRIMARY -> R.string.dashboard_credit_facility_primary
        else -> R.string.dashboard_credit_card_credit_badge
    }), card.last4)

@Composable
internal fun accountTypeLabel(accountType: AccountType): String =
    when (accountType) {
        AccountType.CURRENT -> stringResource(R.string.settings_account_type_current)
        AccountType.SAVINGS -> stringResource(R.string.settings_account_type_savings)
        AccountType.WALLET -> stringResource(R.string.settings_account_type_wallet)
    }
