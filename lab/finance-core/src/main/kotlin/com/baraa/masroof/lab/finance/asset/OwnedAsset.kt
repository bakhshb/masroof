package com.baraa.masroof.lab.finance.asset

import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.lab.finance.value.Environment

enum class AssetKind { ACCOUNT, DEBIT_CARD, CREDIT_CARD, CREDIT_FACILITY, LOAN }
enum class CreditCardRole { PRIMARY, SUPPLEMENTARY, STANDALONE }

/** localId is a registry ID, not last4. Two cards at one bank may share a suffix. */
data class AssetId(val environment: Environment, val bank: BankId, val localId: String) {
    init { require(localId.isNotBlank()) }
}

/** An explicitly confirmed asset; candidate discovery does not create OwnedAsset. */
data class OwnedAsset(
    val id: AssetId,
    val kind: AssetKind,
    val last4: String? = null,
    val parentId: AssetId? = null,
    val creditCardRole: CreditCardRole? = null,
) {
    init {
        require(last4 == null || last4.matches(Regex("[0-9]{4}")))
        require(parentId == null || (
            parentId.bank == id.bank && parentId.environment == id.environment && parentId != id
        ))
        // Unknown links stay null. Data adapters validate the referenced parent kind.
        require(parentId == null || kind in setOf(AssetKind.DEBIT_CARD, AssetKind.CREDIT_CARD))
        require(creditCardRole == null || kind == AssetKind.CREDIT_CARD)
    }
}

/** A suffix is discovery evidence only. Multiple matches stay ambiguous. */
sealed interface AssetResolution {
    data class Confirmed(val asset: OwnedAsset) : AssetResolution
    data class Ambiguous(val candidates: Set<AssetId>) : AssetResolution {
        init { require(candidates.size > 1) }
    }
    data object Unconfirmed : AssetResolution
}
