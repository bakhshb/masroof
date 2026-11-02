package com.baraa.masroof.presentation.dashboard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baraa.masroof.R
import com.baraa.masroof.application.dashboard.CreditCardDashboardRow
import com.baraa.masroof.application.dashboard.EntityTransactionScope
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.presentation.common.formatCardLast4

object DashboardRegistryLabels {
    fun customName(displayName: String?): String? =
        displayName?.trim()?.takeIf { it.isNotEmpty() }

    fun accountLast4(maskedNumber: String): String {
        val trimmed = maskedNumber.trim()
        val last4 = if (trimmed.length <= 4) trimmed else trimmed.takeLast(4)
        return formatCardLast4(last4)
    }
}

@Composable
fun OwnedAccountUi.displayLabel(): String {
    val custom = DashboardRegistryLabels.customName(displayName)
    if (custom != null) return custom
    return stringResource(
        R.string.dashboard_account_item,
        DashboardRegistryLabels.accountLast4(maskedNumber),
    )
}

@Composable
fun OwnedCardUi.displayLabel(): String {
    val custom = DashboardRegistryLabels.customName(displayName)
    if (custom != null) return custom
    return stringResource(R.string.dashboard_credit_card_last4, formatCardLast4(last4))
}

@Composable
fun CreditCardDashboardRow.displayLabel(ownedCards: List<OwnedCardUi>): String {
    val match = ownedCards.find { it.bank == bank && it.last4 == last4 }
    val custom = DashboardRegistryLabels.customName(match?.displayName)
    if (custom != null) return custom
    return stringResource(R.string.dashboard_credit_card_last4, formatCardLast4(last4))
}

/** Localized like credit cards: the user's name, else "بطاقة ···1234" (never the English registry default). */
@Composable
fun com.baraa.masroof.application.dashboard.DebitCardOverview.displayLabel(ownedCards: List<OwnedCardUi>): String =
    cardDisplayLabel(ownedCards, bank, last4)

/** Mada cards read "مدى"; other networks read "خصم مباشر · فيزا"; unknown network reads "خصم مباشر". */
@Composable
fun debitCardTypeLabel(network: com.baraa.masroof.domain.model.CardNetwork?): String = when (network) {
    com.baraa.masroof.domain.model.CardNetwork.MADA -> stringResource(R.string.card_network_mada)
    null, com.baraa.masroof.domain.model.CardNetwork.UNKNOWN -> stringResource(R.string.settings_card_type_debit)
    else -> stringResource(R.string.settings_card_type_debit) + " · " + com.baraa.masroof.presentation.settings.cardNetworkLabel(network)
}

@Composable
fun accountDisplayLabel(
    accounts: List<OwnedAccountUi>,
    bank: Bank,
    maskedNumber: String,
): String = accountDisplayLabel(LocalContext.current, accounts, bank, maskedNumber)

fun accountDisplayLabel(
    context: Context,
    accounts: List<OwnedAccountUi>,
    bank: Bank,
    maskedNumber: String,
): String {
    val match = accounts.find { it.bank == bank && it.maskedNumber == maskedNumber }
    val custom = DashboardRegistryLabels.customName(match?.displayName)
    if (custom != null) return custom
    return context.getString(
        R.string.dashboard_account_item,
        DashboardRegistryLabels.accountLast4(maskedNumber),
    )
}

@Composable
fun accountDisplayLabel(
    accounts: List<OwnedAccountUi>,
    containerId: String,
): String? = accountDisplayLabel(LocalContext.current, accounts, containerId)

fun accountDisplayLabel(
    context: Context,
    accounts: List<OwnedAccountUi>,
    containerId: String,
): String? {
    val (bank, masked) = EntityTransactionScope.accountOf(containerId) ?: return null
    return accountDisplayLabel(context, accounts, bank, masked)
}

@Composable
fun cardDisplayLabel(
    cards: List<OwnedCardUi>,
    bank: Bank,
    last4: String,
): String {
    val match = cards.find { it.bank == bank && it.last4 == last4 }
    val custom = DashboardRegistryLabels.customName(match?.displayName)
    if (custom != null) return custom
    return stringResource(R.string.dashboard_credit_card_last4, formatCardLast4(last4))
}

@Composable
fun cardDisplayLabel(
    cards: List<OwnedCardUi>,
    last4: String,
): String = cardDisplayLabel(LocalContext.current, cards, last4)

fun cardDisplayLabel(
    context: Context,
    cards: List<OwnedCardUi>,
    last4: String,
): String {
    val match = cards.find { it.last4 == last4 }
    val custom = DashboardRegistryLabels.customName(match?.displayName)
    if (custom != null) return custom
    return context.getString(R.string.dashboard_credit_card_last4, formatCardLast4(last4))
}

@Composable
fun cardDisplayLabelFromTransaction(
    row: TransactionPreviewUi,
    cards: List<OwnedCardUi>,
): String? {
    val last4 = row.cardLast4 ?: return null
    val bank = EntityTransactionScope.cardBankOf(listOf(row.sourceContainerId, row.destinationContainerId))
    return if (bank != null) {
        cardDisplayLabel(cards, bank, last4)
    } else {
        cardDisplayLabel(cards, last4)
    }
}
