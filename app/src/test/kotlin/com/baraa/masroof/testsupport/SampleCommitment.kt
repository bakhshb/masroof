package com.baraa.masroof.testsupport

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.ids.RegistryEntityIdFactory
import com.baraa.masroof.domain.model.Commitment
import com.baraa.masroof.domain.model.CommitmentRecurrence
import java.time.Instant
import java.time.LocalDate

/** A monthly 71 SAR "Netflix" commitment created on 2026-08-01. */
internal fun sampleCommitment(
    id: String = RegistryEntityIdFactory.newCommitmentId(),
    active: Boolean = true,
    transactionDate: LocalDate = LocalDate.parse("2026-08-01"),
    recurrence: CommitmentRecurrence? = CommitmentRecurrence.MONTHLY,
    dueDate: LocalDate? = null,
    createdAt: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    updatedAt: Instant = createdAt,
): Commitment = Commitment(
    id = id,
    name = "Netflix",
    amount = Money.of("71.00", Currency.SAR),
    transactionDate = transactionDate,
    recurrence = recurrence,
    dueDate = dueDate,
    active = active,
    sourceTransactionId = "tx-$id",
    createdAt = createdAt,
    updatedAt = updatedAt,
)
