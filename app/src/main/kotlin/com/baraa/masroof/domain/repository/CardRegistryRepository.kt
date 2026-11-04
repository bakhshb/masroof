package com.baraa.masroof.domain.repository

import com.baraa.masroof.domain.model.CardNetwork
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.CardRegistryEntry
import com.baraa.masroof.domain.model.CardType
import com.baraa.masroof.domain.model.OwnershipStatus

/**
 * Durable card ownership registry. Discovery observes; confirmation sets status.
 */
interface CardRegistryRepository {
    /** Saves a confirmed setup atomically; never changes other cards or financial rows. */
    suspend fun configure(reference: CardReference, configuration: com.baraa.masroof.domain.model.CardConfiguration)

    suspend fun observe(reference: CardReference, rawSmsId: String)

    suspend fun setOwnership(reference: CardReference, status: OwnershipStatus)

    suspend fun resolve(reference: CardReference): OwnershipStatus

    suspend fun get(reference: CardReference): CardRegistryEntry?

    suspend fun listAll(): List<CardRegistryEntry>

    suspend fun updateDisplayName(reference: CardReference, displayName: String?)

    suspend fun updateCardNetwork(reference: CardReference, network: CardNetwork?)

    suspend fun updateCardType(reference: CardReference, cardType: CardType?)

    /** Removes the registry row only; callers verify nothing still references the card. */
    suspend fun delete(reference: CardReference): Unit =
        throw UnsupportedOperationException("Card registry delete is not supported")
}
