package com.baraa.masroof.presentation.settings

import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardRole
import com.baraa.masroof.domain.model.OwnershipStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CardManageOrderTest {
    private fun card(last4: String, role: CardRole?, parent: String? = null) =
        ManagedCardUi(last4, Bank.BANK_ALJAZIRA, last4, OwnershipStatus.OWNED, cardRole = role, parentCardLast4 = parent)

    @Test fun primaryComesFirstWithItsSupplementariesNestedAndOrphansLast() {
        val ordered = primaryFirst(listOf(
            card("6995", CardRole.SUPPLEMENTARY, "7271"),
            card("3478", CardRole.SUPPLEMENTARY, "7271"),
            card("9000", CardRole.SUPPLEMENTARY, "0000"),
            card("7271", CardRole.PRIMARY),
            card("1111", CardRole.STANDALONE),
        ))
        assertEquals(listOf("1111" to false, "7271" to false, "3478" to true, "6995" to true, "9000" to false),
            ordered.map { (card, nested) -> card.last4 to nested })
    }
}
