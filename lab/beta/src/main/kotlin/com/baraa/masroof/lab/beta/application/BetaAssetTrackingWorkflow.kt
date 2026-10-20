package com.baraa.masroof.lab.beta.application

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*

/** A persisted, bank-qualified registry identity; never a suffix-based tracking command. */
data class BetaTrackedAsset(val asset: OwnedAsset,val status: BetaOwnershipStatus,val revision: Long)

class BetaAssetTrackingWorkflow(private val database: BetaDatabase) {
    suspend fun snapshot(): List<BetaTrackedAsset> = database.withTransaction {
        val statuses=database.assetDao().tracking().associateBy {Triple(it.environment,it.bankId,it.localId)}
        val external=database.ownershipDao().all().filter {it.status=="EXTERNAL"}.mapTo(hashSetOf()) {
            BetaOwnershipReference(BankId(it.bankId),BetaOwnershipKind.valueOf(it.kind),it.suffix)
        }
        BetaAssetRegistry(database).all().map {asset ->
            val row=requireNotNull(statuses[Triple(asset.id.environment.name,asset.id.bank.value,asset.id.localId)])
            require(row.status in setOf("OWNED","UNKNOWN","EXTERNAL"))
            val kind=when(asset.kind) {AssetKind.ACCOUNT->BetaOwnershipKind.ACCOUNT;AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD->BetaOwnershipKind.CARD;else->null}
            val legacy=kind!=null && asset.last4?.let {BetaOwnershipReference(asset.id.bank,kind,it) in external}==true
            BetaTrackedAsset(asset,if(legacy) BetaOwnershipStatus.EXTERNAL else BetaOwnershipStatus.valueOf(row.status),row.revision)
        }
    }

    suspend fun setOwned(expected: BetaTrackedAsset,owned: Boolean) = database.withTransaction {
        require(expected.asset.id.environment==Environment.BETA)
        val current=snapshot().singleOrNull {it.asset.id==expected.asset.id} ?: error("Asset no longer exists")
        check(current==expected) {"Tracking changed; reopen"}
        val status=if(owned) "OWNED" else "EXTERNAL"
        val asset=current.asset
        require(asset.kind!=AssetKind.CREDIT_FACILITY) {"Track individual cards"}
        // An older reference-level dismissal can cover colliding identities. Preserve the other
        // identities as stopped before resuming this explicitly selected identity.
        val kind=when(asset.kind) {AssetKind.ACCOUNT->"ACCOUNT";AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD->"CARD";else->null}
        val legacy=database.ownershipDao().all().singleOrNull {it.bankId==asset.id.bank.value && it.kind==kind && it.suffix==asset.last4 && it.status=="EXTERNAL"}
        if(owned && legacy!=null) {
            snapshot().filter {it.asset.id!=asset.id && it.asset.id.bank==asset.id.bank && it.asset.last4==asset.last4 &&
                (if(kind=="ACCOUNT") it.asset.kind==AssetKind.ACCOUNT else it.asset.kind in setOf(AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD))}.forEach {write(it,"EXTERNAL")}
            database.ownershipDao().put(legacy.copy(status="OWNED",assetLocalId=asset.id.localId,revision=Math.addExact(legacy.revision,1)))
        }
        write(current,status)
        val registry=BetaAssetRegistry(database).all()
        val affected=hashSetOf(asset.id)
        do {val before=affected.size;registry.filter {it.parentId in affected}.forEach {affected+=it.id}} while(before!=affected.size)
        // Retain manual choices and audit. The existing durable automatic sweep reconsiders these
        // inputs; a failed sweep keeps the financial screen closed and is replayed on reopen.
        database.financialAdoptionDao().bank(asset.id.bank.value).forEach {row ->
            if(row.reason!="MATCHED_TRANSFER_NOTICE" && financialRegistryDependencies(BetaLedgerCodec.decodeInput(row.approvedInput)).any {it in affected})
                database.financialAdoptionDao().hold(row.evidenceId,"OWNERSHIP_CHANGED")
        }
    }
    private suspend fun write(current: BetaTrackedAsset,status: String) {
        val a=current.asset;val id=a.id;val dao=database.assetDao()
        val changed=when(a.kind) {
            AssetKind.ACCOUNT->dao.trackingAccount(id.environment.name,id.bank.value,id.localId,status,current.revision)
            AssetKind.CREDIT_CARD,AssetKind.DEBIT_CARD->dao.trackingCard(id.environment.name,id.bank.value,id.localId,status,current.revision)
            AssetKind.LOAN->dao.trackingLoan(id.environment.name,id.bank.value,id.localId,status,current.revision)
            AssetKind.CREDIT_FACILITY->error("Track individual cards")
        }
        check(changed==1) {"Registry changed; reopen"}
        dao.appendAudit(BetaAuditRecord(java.util.UUID.randomUUID().toString(),"ASSET_TRACKING",id.environment.name,id.localId,
            bankId=id.bank.value,operation=status,transition=Math.addExact(current.revision,1)))
    }
}
