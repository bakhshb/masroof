package com.baraa.masroof.domain.matching

import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.BankNetworkType
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionMatcherTest {

    @Test
    fun matchesUniqueCandidatesWithSameReferenceAtWindowBoundary() {
        val outgoing = outgoing(reference = "ref-1", localTime = defaultLocalTime)
        val incoming = incoming(reference = "ref-1", localTime = defaultLocalTime.plusMinutes(10))

        assertEquals(
            listOf(TransferMatchPair(outgoing, incoming)),
            TransactionMatcher.findMutuallyUniquePairs(listOf(outgoing, incoming)),
        )
    }

    @Test
    fun pairsEachExactLocalTimeWhenAnotherMovementIsInsideTheWindow() {
        val firstOut = outgoing(
            id = "out-a",
            rawSmsId = "raw-out-a",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )
        val firstIn = incoming(
            id = "in-a",
            rawSmsId = "raw-in-a",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )
        val secondOut = outgoing(
            id = "out-b",
            rawSmsId = "raw-out-b",
            reference = null,
            localTime = defaultLocalTime.plusMinutes(8),
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )
        val secondIn = incoming(
            id = "in-b",
            rawSmsId = "raw-in-b",
            reference = null,
            localTime = defaultLocalTime.plusMinutes(8),
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )

        val pairs = TransactionMatcher.findMutuallyUniquePairs(
            listOf(firstOut, secondOut, firstIn, secondIn),
        )

        assertEquals(setOf("out-a" to "in-a", "out-b" to "in-b"), pairs.map {
            it.outgoing.event.id to it.incoming.event.id
        }.toSet())
    }

    @Test
    fun doesNotPairTwoMovementsThatShareOneLocalTime() {
        val firstOut = outgoing(
            id = "out-a",
            rawSmsId = "raw-out-a",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )
        val secondOut = outgoing(
            id = "out-b",
            rawSmsId = "raw-out-b",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )
        val firstIn = incoming(
            id = "in-a",
            rawSmsId = "raw-in-a",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )
        val secondIn = incoming(
            id = "in-b",
            rawSmsId = "raw-in-b",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )

        assertTrue(
            TransactionMatcher.findMutuallyUniquePairs(
                listOf(firstOut, secondOut, firstIn, secondIn),
            ).isEmpty(),
        )
    }

    @Test
    fun doesNotPairWhenIntraBankLegsAreUnequalAtOneLocalTime() {
        val firstOut = outgoing(
            id = "out-a",
            rawSmsId = "raw-out-a",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )
        val secondOut = outgoing(
            id = "out-b",
            rawSmsId = "raw-out-b",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )
        val onlyIn = incoming(
            id = "in-a",
            rawSmsId = "raw-in-a",
            reference = null,
            localTime = defaultLocalTime,
            source = AccountReference(Bank.BANK_ALJAZIRA, "3001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "3003"),
            network = BankNetworkType.INTRA_BANK,
        )

        assertTrue(
            TransactionMatcher.findMutuallyUniquePairs(listOf(firstOut, secondOut, onlyIn)).isEmpty(),
        )
    }

    @Test
    fun doesNotMatchAmbiguousCandidates() {
        val outgoing = outgoing(reference = "ref-1")
        val firstIncoming = incoming(id = "in-1", rawSmsId = "raw-in-1", reference = "ref-1")
        val secondIncoming = incoming(id = "in-2", rawSmsId = "raw-in-2", reference = "ref-1")

        assertTrue(
            TransactionMatcher.findMutuallyUniquePairs(
                listOf(outgoing, firstIncoming, secondIncoming),
            ).isEmpty(),
        )
    }

    @Test
    fun doesNotMatchMixedTimestampsWithoutEffectiveInstant() {
        val outgoing = outgoing(reference = "ref-1", localTime = defaultLocalTime)
        val incoming = incoming(reference = "ref-1", localTime = null)

        assertFalse(TransactionMatcher.compatiblePair(outgoing, incoming))
    }

    @Test
    fun matchesMixedTimestampsWhenEffectiveInstantsAreInsideWindow() {
        val local = defaultLocalTime
        val zone = java.time.ZoneId.of("Asia/Riyadh")
        val outgoing = outgoing(reference = "ref-1", localTime = local).copy(
            effectiveOccurredAt = local.atZone(zone).toInstant(),
        )
        val incoming = incoming(reference = "ref-1", localTime = null).copy(
            receivedAt = local.atZone(zone).toInstant().plusSeconds(120),
            effectiveOccurredAt = local.atZone(zone).toInstant().plusSeconds(120),
        )

        assertTrue(TransactionMatcher.compatiblePair(outgoing, incoming))
    }

    @Test
    fun rejectsMixedTimestampsWhenEffectiveInstantsExceedWindow() {
        val local = defaultLocalTime
        val zone = java.time.ZoneId.of("Asia/Riyadh")
        val outgoing = outgoing(reference = "ref-1", localTime = local).copy(
            effectiveOccurredAt = local.atZone(zone).toInstant(),
        )
        val incoming = incoming(reference = "ref-1", localTime = null).copy(
            effectiveOccurredAt = local.atZone(zone).toInstant().plus(java.time.Duration.ofMinutes(11)),
        )

        assertFalse(TransactionMatcher.compatiblePair(outgoing, incoming))
    }

    @Test
    fun matchesIntraBankMovementWithoutSharedReference() {
        val outgoing = outgoing(
            reference = null,
            source = AccountReference(Bank.BANK_ALJAZIRA, "1001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "2002"),
            network = BankNetworkType.INTRA_BANK,
        )
        val incoming = incoming(
            reference = null,
            source = AccountReference(Bank.BANK_ALJAZIRA, "1001"),
            destination = AccountReference(Bank.BANK_ALJAZIRA, "2002"),
            network = BankNetworkType.INTRA_BANK,
        )

        assertTrue(TransactionMatcher.compatiblePair(outgoing, incoming))
    }

    @Test
    fun rejectsDifferentCurrenciesEvenWhenNumericAmountsMatch() {
        val outgoing = outgoing(reference = "ref-1")
        val incoming = incoming(reference = "ref-1", amount = Money.of(BigDecimal.TEN, Currency.USD))

        assertFalse(TransactionMatcher.compatiblePair(outgoing, incoming))
    }

    private fun outgoing(
        id: String = "out",
        rawSmsId: String = "raw-out",
        reference: String?,
        localTime: LocalDateTime? = defaultLocalTime,
        source: AccountReference = AccountReference(Bank.BANK_ALJAZIRA, "1001"),
        destination: AccountReference = AccountReference(Bank.UNKNOWN, "2002"),
        network: BankNetworkType? = BankNetworkType.INTER_BANK,
    ) = candidate(
        id = id,
        rawSmsId = rawSmsId,
        family = MessageFamily.TRANSFER_OUT,
        reference = reference,
        localTime = localTime,
        source = source,
        destination = destination,
        network = network,
        sourceOwnership = OwnershipStatus.OWNED,
        destinationOwnership = OwnershipStatus.UNKNOWN,
    )

    private fun incoming(
        id: String = "in",
        rawSmsId: String = "raw-in",
        reference: String?,
        localTime: LocalDateTime? = defaultLocalTime,
        amount: Money = sarTen,
        source: AccountReference? = null,
        destination: AccountReference = AccountReference(Bank("D360"), "2002"),
        network: BankNetworkType? = BankNetworkType.INTER_BANK,
    ) = candidate(
        id = id,
        rawSmsId = rawSmsId,
        family = MessageFamily.TRANSFER_IN,
        reference = reference,
        localTime = localTime,
        amount = amount,
        source = source,
        destination = destination,
        network = network,
        sourceOwnership = OwnershipStatus.UNKNOWN,
        destinationOwnership = OwnershipStatus.OWNED,
    )

    private fun candidate(
        id: String,
        rawSmsId: String,
        family: MessageFamily,
        reference: String?,
        localTime: LocalDateTime?,
        amount: Money = sarTen,
        source: AccountReference?,
        destination: AccountReference?,
        network: BankNetworkType?,
        sourceOwnership: OwnershipStatus,
        destinationOwnership: OwnershipStatus,
    ) = TransferMatchCandidate(
        event = ParsedEvent(
            id = id,
            rawSmsId = rawSmsId,
            bank = Bank.BANK_ALJAZIRA,
            messageFamily = family,
            direction = MoneyDirection.OUTGOING,
            amount = amount,
            purchaseChannel = null,
            sourceAccountRef = source,
            destinationAccountRef = destination,
            cardRef = null,
            merchant = null,
            counterparty = null,
            occurredAt = null,
            bankNetworkType = network,
            confidence = Confidence(1.0),
            parseStatus = ParseStatus.SUCCESS,
        ),
        transactionReference = reference,
        occurredAtLocal = localTime,
        receivedAt = Instant.parse("2026-08-10T09:00:00Z"),
        sourceOwnership = sourceOwnership,
        destinationOwnership = destinationOwnership,
    )

    private companion object {
        val defaultLocalTime: LocalDateTime = LocalDateTime.parse("2026-08-10T12:00:00")
        val sarTen: Money = Money.of(BigDecimal.TEN, Currency.SAR)
    }
}
