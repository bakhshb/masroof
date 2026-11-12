package com.baraa.masroof.presentation.dashboard

import com.baraa.masroof.application.dashboard.DashboardRegistryWorkflow
import com.baraa.masroof.application.dashboard.DashboardTransactionFacts
import com.baraa.masroof.domain.model.FinancialTransaction
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun transactionPreview(
    tx: FinancialTransaction,
    facts: DashboardTransactionFacts?,
    zoneId: ZoneId,
    tag: String,
    formatter: DateTimeFormatter,
): TransactionPreviewUi {
    val title = tx.merchant?.takeIf { it.isNotBlank() }
        ?: tx.counterparty?.takeIf { it.isNotBlank() }
    // Rows share the device-zone period window; dating them by another zone would list a row outside its period.
    val localDate = tx.occurredAt.atZone(zoneId).toLocalDate()
    val searchText = listOfNotNull(
        tx.merchant?.trim()?.takeIf { it.isNotEmpty() },
        tx.counterparty?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" ").lowercase(Locale.ROOT)
    val effectiveType = facts?.effectiveType ?: tx.type
    return TransactionPreviewUi(
        id = tx.id,
        title = title,
        amount = tx.amount,
        localDate = localDate,
        amountLabel = MoneyUiFormatter.format(tx.amount, tag),
        dateLabel = formatter.format(localDate),
        type = tx.type,
        typeLabelResHint = effectiveType,
        direction = TransactionTypePresentation.direction(effectiveType),
        cardLast4 = facts?.primaryCardLast4,
        sourceContainerId = tx.sourceContainerId,
        destinationContainerId = tx.destinationContainerId,
        searchText = searchText,
        sarEquivalent = facts?.sarEquivalent,
        appliedExchangeRate = tx.appliedExchangeRate,
        exchangeRateSource = tx.exchangeRateSource,
    )
}

internal suspend fun DashboardRegistryWorkflow.unknownCardsUi(): List<UnknownCardCandidateUi> =
    listUnknownCards()
        .map { UnknownCardCandidateUi(bank = it.bank, last4 = it.last4) }

internal suspend fun DashboardRegistryWorkflow.ownedAccountsUi(): List<OwnedAccountUi> =
    listOwnedAccounts()
        .map {
            OwnedAccountUi(
                bank = it.bank,
                maskedNumber = it.maskedNumber,
                displayName = it.displayName,
            )
        }

internal fun mergeOwnedAccounts(
    registryAccounts: List<OwnedAccountUi>,
    periodSummaries: List<com.baraa.masroof.application.dashboard.OwnedAccountPeriodSummary>,
): List<OwnedAccountUi> {
    val netsByKey = periodSummaries.associateBy { "${it.bank.id}:${it.maskedNumber}" }
    return registryAccounts.map { account ->
        val summary = netsByKey["${account.bank.id}:${account.maskedNumber}"]
        account.copy(periodSummary = summary?.summary)
    }
}

internal suspend fun DashboardRegistryWorkflow.ownedCardsUi(): List<OwnedCardUi> =
    listOwnedCards()
        .map {
            OwnedCardUi(
                bank = it.bank,
                last4 = it.last4,
                displayName = it.displayName,
                cardNetwork = it.cardNetwork,
                cardRole = it.cardRole,
                parentCardLast4 = it.parentCardLast4,
            )
        }
