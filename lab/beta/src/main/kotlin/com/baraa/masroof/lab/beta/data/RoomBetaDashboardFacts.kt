package com.baraa.masroof.lab.beta.data

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.application.corrections.BetaLedgerActivity
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import org.json.JSONObject

/** Write-side maintenance only. Does not authorize, post, reparse or repair financial rows.
 * A migrated database deliberately starts with unknown facts, never guessed zero observations.
 * The caller controls a bounded cursor; failure rolls back the whole page and remains retryable. */
class RoomBetaDashboardFacts(private val database: BetaDatabase) {
    init { require(database.openHelper.databaseName == BetaDatabase.NAME) }

    suspend fun refreshPage(afterId: String = "", limit: Int = 100): BetaDashboardFactsRefresh = database.withTransaction {
        require(limit in 1..500)
        val ids = database.evidenceDao().idsAfter(afterId, limit)
        var refreshed = 0
        for (id in ids) if (refresh(id)) refreshed++
        BetaDashboardFactsRefresh(ids.lastOrNull() ?: afterId, ids.size, refreshed)
    }

    internal suspend fun refreshTransaction(key: PostingKey) {
        database.financialAdoptionDao().transaction(key.transactionId)?.let { refresh(it.evidenceId) }
    }

    /** Must join the financial/correction caller's transaction. Pending approval cannot become
     * visible in a read until its separate exact durable acknowledgement succeeds. */
    internal suspend fun refresh(id: String): Boolean {
        check(database.inTransaction())
        val auxiliary = RoomBetaDashboardAuxFacts(database).refresh(id)
        val adopted = database.financialAdoptionDao().evidence(id) ?: return auxiliary
        if (adopted.status != "ACTIVE" || database.evidenceDao().retry(id) != null) return false
        val outcome = database.evidenceDao().outcome(id) ?: return false
        if (adopted.archiveFingerprint != BetaFinancialArchive.fingerprint(database, outcome)) return false
        val key = RoomBetaFinancialAdoption.key(id)
        val correction = readBetaCorrectionState(database, key)
        if (correction != null && correction.activity != BetaLedgerActivity.ACTIVE) return false
        val posting = RoomBetaFinancialLedger(database).readAudit(key) ?: return false
        if (posting.matchIdentity != null || posting.command.evidenceIds != setOf(id)) return false
        val input = posting.classification.input
        val baseline = RoomBetaFinancialAdoption(database).observation(key)?.input ?: return false
        val registry = BetaAssetRegistry(database).all().associateBy { it.id }
        if (financialRegistryDependencies(input).any { input.assets[it] != registry[it] }) return false
        val facts = BetaFinancialArchive.facts(database, outcome)
        fun text(name: String): String? = if (facts.isNull(name)) null else facts.getString(name)
        fun money(name: String): Money? = if (facts.isNull(name)) null else facts.getJSONObject(name).let {
            Money.of(it.getString("amount"), Currency.valueOf(it.getString("currency"))) }
        // A manually changed amount/time or endpoint no longer proves this observation's role.
        val sameMoneyTime = input.amount == baseline.amount && input.time == baseline.time
        val original = BetaLedgerCodec.decodeInput(adopted.approvedInput)
        val sameEndpoints = input.source == original.source && input.destination == original.destination
        val endpoint = (if (input.storedType == FinancialType.REFUND) input.destination else input.source)
            .let { (it as? FinancialEndpoint.Owned)?.id }?.let(registry::get)
        val cardReference = if (facts.isNull("card")) null else facts.getJSONObject("card").getString("number")
        fun maskedSuffix(value: String): String = value.trim().let {
            if (it.matches(Regex("[0-9*•xX\\s-]*[0-9]{4}"))) it.takeLast(4) else ""
        }
        val accountSuffixes = listOfNotNull(text("debitSourceAccountLast4")?.let {
            if (it.matches(Regex("[0-9]{4}"))) it else ""
        }, if (facts.isNull("sourceAccount")) null else maskedSuffix(facts.getJSONObject("sourceAccount").optString("number")))
        fun referenceBankMatches(name: String): Boolean = facts.isNull(name) || facts.getJSONObject(name).let {
            it.isNull("bank") || it.getString("bank") == adopted.bankId
        }
        val debitCard = if (sameEndpoints && text("cardChannel") == "DEBIT" && cardReference != null) {
            registry.values.filter { it.kind == AssetKind.DEBIT_CARD && it.id.bank == endpoint?.id?.bank &&
                it.last4 == cardReference && (endpoint.kind == AssetKind.ACCOUNT && it.parentId == endpoint.id) &&
                referenceBankMatches("sourceAccount") && referenceBankMatches("card") &&
                accountSuffixes.all { suffix -> suffix == endpoint.last4 } }
                .singleOrNull()
        } else null
        val balanceAsset = if (!sameEndpoints || !sameMoneyTime) null else when (endpoint?.kind) {
            AssetKind.ACCOUNT -> endpoint.takeIf {
                text("cardChannel") != "CREDIT" && accountSuffixes.isNotEmpty() &&
                    referenceBankMatches("sourceAccount") && referenceBankMatches("card") &&
                    accountSuffixes.all { suffix -> suffix == it.last4 }
            }
            AssetKind.DEBIT_CARD -> endpoint.parentId?.let(registry::get)
            AssetKind.CREDIT_CARD -> endpoint.takeIf { it.last4 == cardReference && text("cardChannel") == "CREDIT" }
            else -> null
        }
        val facility = if (!sameEndpoints || !sameMoneyTime) null else when (endpoint?.kind) {
            AssetKind.CREDIT_CARD -> endpoint.takeIf { it.last4 == cardReference && text("cardChannel") == "CREDIT" }?.parentId?.let(registry::get)
            AssetKind.CREDIT_FACILITY -> endpoint.takeIf { facility ->
                registry.values.count { it.kind == AssetKind.CREDIT_CARD && it.parentId == facility.id && it.last4 == cardReference } == 1
            }
            else -> null
        }
        val previousFacts = database.dashboardFactsDao().evidence(id)
        val fee = if (sameMoneyTime) money("internationalFee") else null
        val balance = balanceAsset?.let { money("availableBalance") }
        // A financial purchase/refund outstanding balance is not a statement. Historical v8
        // columns remain intact, but new financial writes cannot manufacture statement authority.
        val due: Money? = null
        database.dashboardFactsDao().put(BetaDashboardFactsEntity(id, key.transactionId, adopted.bankId,
            adopted.archiveFingerprint, adopted.observationRevision, input.revision.correction, input.revision.interpretation,
            BetaLedgerCodec.encodeInput(input), baseline.time.instant.toString(),
            sameMoneyTime && facts.has("internationalFee"), fee?.amount?.toPlainString(), fee?.currency?.name,
            (balanceAsset?.id?.localId ?: previousFacts?.balanceAsset), balance?.amount?.toPlainString(), balance?.currency?.name,
            (facility?.id?.localId ?: previousFacts?.facilityAsset), due?.amount?.toPlainString(), due?.currency?.name, text("paymentDueDate"),
            text("merchant"), text("counterparty"), debitCard?.id?.localId, debitCard?.last4, debitCard?.parentId?.localId, text("family")))
        return true
    }
}

data class BetaDashboardFactsRefresh(val afterId: String, val visited: Int, val refreshed: Int)
