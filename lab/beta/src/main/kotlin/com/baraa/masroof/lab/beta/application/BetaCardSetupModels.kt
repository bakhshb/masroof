package com.baraa.masroof.lab.beta.application

import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.BankId

/** UI vocabulary from the approved card setup. These values do not grant parsing/posting authority. */
enum class BetaCardNetwork { MADA, VISA, MASTERCARD, AMEX, UNKNOWN }
data class BetaManagedCardUi(val asset: OwnedAsset,val displayName: String? = null,
    val cardNetwork: BetaCardNetwork? = null,val parentCardId: AssetId? = null,
    val cardType: AssetKind? = asset.kind) {
    val id: AssetId get()=asset.id
    val bank: BankId get()=id.bank
    val last4: String get()=asset.last4.orEmpty()
    val cardRole: CreditCardRole? get()=asset.creditCardRole
    val linkedAccountId: AssetId? get()=asset.parentId.takeIf {asset.kind==AssetKind.DEBIT_CARD}
}
data class BetaManagedAccountUi(val asset: OwnedAsset,val displayName: String? = null) {
    val id: AssetId get()=asset.id
    val bank: BankId get()=id.bank
    val last4: String get()=asset.last4.orEmpty()
}
data class BetaCardConfiguration(val network: BetaCardNetwork?,val type: AssetKind,val role: CreditCardRole?,
    val primaryCardId: AssetId?,val accountId: AssetId?)

fun betaManagedCards(assets: List<OwnedAsset>,metadata: Map<AssetId,BetaAssetMetadata>): List<BetaManagedCardUi> =
    assets.filter {it.kind in setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD)}.map {asset ->
        val primary=if(asset.creditCardRole==CreditCardRole.SUPPLEMENTARY) assets.singleOrNull {
            it.kind==AssetKind.CREDIT_CARD && it.creditCardRole==CreditCardRole.PRIMARY && it.parentId==asset.parentId && it.id.bank==asset.id.bank
        }?.id else null
        BetaManagedCardUi(asset,metadata[asset.id]?.displayName,BetaCardNetwork.entries.singleOrNull {it.name==metadata[asset.id]?.network},primary)
    }

/** The production registry always retains the identifying suffix beside a custom name. */
fun approvedRegistryDisplayLabel(custom: String?, fallback: String, number: String): String {
    val suffix=BetaDisplayDigits.display(number.takeLast(4))
    val name=custom?.trim()?.takeIf {it.isNotEmpty()} ?: fallback
    return if(BetaDisplayDigits.display(name).contains(suffix)) name else "$name •• $suffix"
}
