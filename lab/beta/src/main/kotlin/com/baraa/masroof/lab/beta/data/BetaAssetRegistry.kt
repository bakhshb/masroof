package com.baraa.masroof.lab.beta.data

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.application.ManualAssetStore
import com.baraa.masroof.lab.beta.application.BankSelectionState
import com.baraa.masroof.lab.finance.asset.AssetId
import com.baraa.masroof.lab.finance.asset.AssetKind
import com.baraa.masroof.lab.finance.asset.CreditCardRole
import com.baraa.masroof.lab.finance.asset.OwnedAsset
import com.baraa.masroof.lab.finance.value.BankId
import com.baraa.masroof.lab.finance.value.Environment

enum class BetaAliasKind { LAST4, ACCOUNT_REFERENCE, CARD_REFERENCE }

data class BetaAssetAlias(val kind: BetaAliasKind, val value: String, val evidenceId: String) {
    init {
        require(value.isNotBlank() && evidenceId.isNotBlank())
        require(kind != BetaAliasKind.LAST4 || value.matches(Regex("[0-9]{4}")))
    }
}

/** Explicit registration only. Discovery never merges assets or chooses among colliding aliases. */
class BetaAssetRegistry(private val database: BetaDatabase) : ManualAssetStore {
    suspend fun register(asset: OwnedAsset, aliases: List<BetaAssetAlias> = emptyList()) {
        require(asset.id.environment == Environment.BETA) { "Beta registry cannot store rehearsal data" }
        database.withTransaction {
            val parent = asset.parentId?.let {
                database.assetDao().get(it.environment.name, it.bank.value, it.localId)
                    ?: error("Parent asset must be registered first")
            }
            require(parent == null || when (asset.kind) {
                AssetKind.DEBIT_CARD -> parent.kind == AssetKind.ACCOUNT.name
                AssetKind.CREDIT_CARD -> parent.kind == AssetKind.CREDIT_FACILITY.name
                else -> false
            }) { "Card parent kind does not match" }
            database.assetDao().insert(asset.toEntity())
            aliases.forEach { alias ->
                database.assetDao().insertAlias(BetaAssetAliasEntity(
                    asset.id.environment.name, asset.id.bank.value, asset.id.localId,
                    alias.kind.name, alias.value, alias.evidenceId,
                ))
            }
        }
    }

    override suspend fun save(asset: OwnedAsset, editing: Boolean, expectedOriginal: OwnedAsset?): OwnedAsset = database.withTransaction {
        require(asset.id.environment == Environment.BETA)
        val dao = database.assetDao()
        val all = all()
        val existing = all.singleOrNull { it.id == asset.id }
        if (editing) {
            require(existing != null) { "The asset no longer exists" }
            require(expectedOriginal?.id == asset.id) { "Editing requires the original stable identity" }
            if (existing == asset) return@withTransaction existing
            require(existing == expectedOriginal) { "Asset changed; reopen its configuration" }
        }
        val selected = database.bankSelectionDao().all().singleOrNull { it.bankId == asset.id.bank.value }
        require(selected != null && (editing || selected.state == BankSelectionState.ENABLED.name)) {
            "Select and enable the bank before adding an asset"
        }
        // A retry of the same draft is idempotent, while a new draft always has a new stable ID.
        if (!editing && existing != null) {
            require(existing == asset) { "Stable identity already exists with a different configuration" }
            return@withTransaction existing
        }
        if (existing != null) {
            val sameFamily = existing.kind == asset.kind ||
                (existing.kind in setOf(AssetKind.DEBIT_CARD, AssetKind.CREDIT_CARD) && asset.kind in setOf(AssetKind.DEBIT_CARD, AssetKind.CREDIT_CARD))
            require(sameFamily) { "An account or facility cannot change its identity kind" }
        }
        val parent = asset.parentId?.let { parentId -> all.singleOrNull { it.id == parentId } ?: error("Parent does not exist") }
        require(parent == null || when (asset.kind) {
            AssetKind.DEBIT_CARD -> parent.kind == AssetKind.ACCOUNT
            AssetKind.CREDIT_CARD -> parent.kind == AssetKind.CREDIT_FACILITY
            else -> false
        }) { "Parent kind does not match" }
        val after = all.filterNot { it.id == asset.id } + asset
        // Both credit roles belong to one facility; a supplementary does not become a separate debt.
        val affectedFacilities = listOfNotNull(
            existing?.takeIf { it.kind == AssetKind.CREDIT_CARD }?.parentId,
            asset.takeIf { it.kind == AssetKind.CREDIT_CARD }?.parentId).toSet()
        after.filter { it.kind == AssetKind.CREDIT_CARD && it.parentId in affectedFacilities }.groupBy { it.parentId }.values.forEach { cards ->
            require(cards.none {it.creditCardRole==CreditCardRole.STANDALONE} || cards.size==1) { "A standalone card cannot share its credit facility" }
            require(cards.count { it.creditCardRole == CreditCardRole.PRIMARY } <= 1) { "A facility already has a primary card" }
            require(cards.none { it.creditCardRole == CreditCardRole.SUPPLEMENTARY } || cards.any { it.creditCardRole == CreditCardRole.PRIMARY }) {
                "A supplementary card needs a primary card in the same facility"
            }
        }
        after.filter { it.id == asset.id || it.parentId == asset.id }.forEach { child ->
            child.parentId?.let { parentId ->
                val linked = after.single { it.id == parentId }
                require((child.kind == AssetKind.DEBIT_CARD && linked.kind == AssetKind.ACCOUNT) ||
                    (child.kind == AssetKind.CREDIT_CARD && linked.kind == AssetKind.CREDIT_FACILITY))
            }
        }
        if (existing == null) dao.insert(asset.toEntity()) else check(dao.update(asset.toEntity()) == 1)
        if (existing != asset) {
            // Includes cards whose parent changed, and parent membership referenced by a posting.
            // Preserve approved/manual facts; require scoped explicit review before financial reuse.
            database.financialAdoptionDao().bank(asset.id.bank.value).forEach { adoption ->
                if (asset.id in financialRegistryDependencies(BetaLedgerCodec.decodeInput(adoption.approvedInput)))
                    database.financialAdoptionDao().hold(adoption.evidenceId, "REGISTRY_CHANGED")
            }
        }
        dao.clearManualSuffix(asset.id.environment.name, asset.id.bank.value, asset.id.localId)
        asset.last4?.let { suffix -> dao.insertAlias(BetaAssetAliasEntity(
            asset.id.environment.name, asset.id.bank.value, asset.id.localId,
            BetaAliasKind.LAST4.name, suffix, "manual-owned-confirmation")) }
        asset
    }

    /** Even a single suffix match remains discovery evidence for a later explicit decision. */
    suspend fun candidates(bank: BankId, kind: BetaAliasKind, value: String): List<OwnedAsset> =
        database.assetDao().candidates(Environment.BETA.name, bank.value, kind.name, value).map { it.toAsset() }

    suspend fun all(): List<OwnedAsset> = database.assetDao().all().map { it.toAsset() }

    suspend fun owned(): List<OwnedAsset> = com.baraa.masroof.lab.beta.application.ownedBetaAssets(all(),
        database.ownershipDao().all().filter { it.status == "EXTERNAL" }.mapTo(hashSetOf()) {
            com.baraa.masroof.lab.beta.application.BetaOwnershipReference(BankId(it.bankId),
                com.baraa.masroof.lab.beta.application.BetaOwnershipKind.valueOf(it.kind), it.suffix)
        })

    private fun OwnedAsset.toEntity() = BetaAssetEntity(
        id.environment.name, id.bank.value, id.localId, kind.name, last4,
        parentId?.localId, creditCardRole?.name,
    )

    private fun BetaAssetEntity.toAsset(): OwnedAsset {
        val id = AssetId(Environment.valueOf(environment), BankId(bankId), localId)
        return OwnedAsset(id, AssetKind.valueOf(kind), last4,
            parentLocalId?.let { AssetId(id.environment, id.bank, it) },
            creditCardRole?.let(CreditCardRole::valueOf))
    }
}
