package com.baraa.masroof.lab.beta.application

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import java.security.MessageDigest
import java.util.UUID

/** One reference list across the entire import. Grouping observations does not merge registry entities. */
class BetaOwnershipWorkflow(private val database: BetaDatabase) {
    private data class State(val observations: List<BetaOwnershipObservation>, val token: String,
        val candidate: BetaOwnershipCandidate)

    suspend fun snapshot(): BetaOwnershipSnapshot = database.withTransaction {
        BetaOwnershipSnapshot(states().map { it.candidate })
    }

    private suspend fun states(): List<State> {
        val enabled = database.bankSelectionDao().all().filter { it.state == "ENABLED" }.map { it.bankId }.toSet()
        val registry = BetaAssetRegistry(database).all()
        val decisions = database.ownershipDao().all().associateBy { Triple(it.bankId, it.kind, it.suffix) }
        val stopped=database.assetDao().tracking().filter {it.status=="EXTERNAL"}.mapTo(hashSetOf()) {Triple(it.environment,it.bankId,it.localId)}
        val store = RoomBetaEvidenceStore(database)
        val observations = mutableListOf<BetaOwnershipObservation>()
        var after = ""
        do {
            val ids = database.evidenceDao().idsAfter(after, 100)
            for (id in ids) observations += store.ownershipObservations(id).filter { it.reference.bank.value in enabled }
            check(observations.size <= 200_000) { "Ownership discovery limit exceeded" }
            after = ids.lastOrNull().orEmpty()
        } while (after.isNotEmpty())
        return observations.groupBy { it.reference }.entries.sortedWith(compareBy({ it.key.kind }, { it.key.bank.value }, { it.key.suffix })).map { (ref, rows) ->
            val choices = registry.filter { it.id.bank == ref.bank && it.last4 == ref.suffix &&
                if (ref.kind == BetaOwnershipKind.ACCOUNT) it.kind == AssetKind.ACCOUNT
                else it.kind in setOf(AssetKind.DEBIT_CARD, AssetKind.CREDIT_CARD) }.sortedBy { it.id.localId }
            val decision = decisions[Triple(ref.bank.value, ref.kind.name, ref.suffix)]
            val kinds = rows.mapNotNull { it.proposal?.kind }.toSet()
            val conflict = kinds.size > 1 || (kinds.isNotEmpty() && choices.size == 1 && choices.single().kind !in kinds)
            val chosen = if (decision?.assetLocalId != null) choices.singleOrNull { it.id.localId == decision.assetLocalId }
                else choices.singleOrNull().takeIf { !conflict }
            val status = when {
                decision?.status == "EXTERNAL" -> BetaOwnershipStatus.EXTERNAL
                chosen?.id?.let {Triple(it.environment.name,it.bank.value,it.localId) in stopped}==true -> BetaOwnershipStatus.EXTERNAL
                conflict && decision?.status == "REVIEW" -> BetaOwnershipStatus.REVIEW
                conflict -> BetaOwnershipStatus.UNKNOWN
                decision?.status == "OWNED" && (chosen != null || (choices.isEmpty() && kinds.isEmpty())) -> BetaOwnershipStatus.OWNED
                chosen != null && choices.size == 1 -> BetaOwnershipStatus.OWNED
                else -> BetaOwnershipStatus.UNKNOWN
            }
            // Bind the decision to the complete current evidence set and every possible registry match.
            val basis = listOf(ref.toString(), rows.map { it.proposal?.let { p -> "${p.id.localId}:${p.revision}" } ?: it.evidenceRevision }.sorted().toString(),
                choices.toString(), decision.toString()).joinToString("|")
            val token = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray()).joinToString("") { "%02x".format(it) }
            State(rows, token, BetaOwnershipCandidate(ref, status, token, chosen, choices, conflict))
        }
    }

    suspend fun defer(expected: BetaOwnershipCandidate) = database.withTransaction {
        val state = states().singleOrNull { it.candidate.reference == expected.reference } ?: error("Candidate no longer exists")
        check(state.token == expected.token && state.candidate.conflictingKinds) { "Reopen the conflicting reference" }
        val ref = expected.reference
        val previous = database.ownershipDao().all().singleOrNull { it.bankId == ref.bank.value && it.kind == ref.kind.name && it.suffix == ref.suffix }
        database.ownershipDao().put(BetaOwnershipDecisionEntity(ref.bank.value, ref.kind.name, ref.suffix,
            "REVIEW", null, Math.addExact(previous?.revision ?: 0, 1)))
    }

    /** A single explicit decision covers this observed reference; colliding registry identities need a selection. */
    suspend fun decide(expected: BetaOwnershipCandidate, owned: Boolean, selected: AssetId? = null) = database.withTransaction {
        val state = states().singleOrNull { it.candidate.reference == expected.reference } ?: error("Candidate no longer exists")
        check(state.token == expected.token) { "Ownership changed; reopen" }
        val ref = expected.reference
        val dao = database.ownershipDao()
        val previous = dao.all().singleOrNull { it.bankId == ref.bank.value && it.kind == ref.kind.name && it.suffix == ref.suffix }
        var asset = selected?.let { id -> state.candidate.choices.single { it.id == id } } ?: state.candidate.asset
        if (owned) {
            check(!state.candidate.conflictingKinds) { "Conflicting card kinds need review" }
            check(state.candidate.choices.size <= 1 || selected != null) { "Select the actual asset; matching suffixes are not identities" }
            if (asset == null) {
                val kind = state.observations.mapNotNull { it.proposal?.kind }.distinct().singleOrNull()
                if (kind != null) {
                    asset = OwnedAsset(AssetId(Environment.BETA, ref.bank, UUID.randomUUID().toString()), kind, ref.suffix)
                    BetaAssetRegistry(database).register(asset)
                }
            }
            if (asset != null) {
                val store = RoomBetaEvidenceStore(database)
                state.observations.mapNotNull { it.proposal }.forEach { proposal ->
                    val binding = store.proposalBinding(proposal)
                    check(binding == null || binding == asset.id) { "Conflicting evidence binding" }
                    store.recordProposalConfirmation(proposal, asset.id)
                }
            }
        }
        dao.put(BetaOwnershipDecisionEntity(ref.bank.value, ref.kind.name, ref.suffix,
            if (owned) "OWNED" else "EXTERNAL", if (owned) asset?.id?.localId else null, Math.addExact(previous?.revision ?: 0, 1)))
        if (!owned) {
            val affected = BetaAssetRegistry(database).all().filter { it.id.bank == ref.bank && it.last4 == ref.suffix &&
                if (ref.kind == BetaOwnershipKind.ACCOUNT) it.kind == AssetKind.ACCOUNT
                else it.kind in setOf(AssetKind.DEBIT_CARD, AssetKind.CREDIT_CARD) }.mapTo(hashSetOf()) { it.id }
            database.financialAdoptionDao().bank(ref.bank.value).filter { it.approvalRequest != "BANK_ENGINE_AUTOMATIC_V1" }.forEach { adoption ->
                if (financialRegistryDependencies(BetaLedgerCodec.decodeInput(adoption.approvedInput)).any { it in affected })
                    database.financialAdoptionDao().hold(adoption.evidenceId, "OWNERSHIP_CHANGED")
            }
        }
        linkProvenDebitAccounts()
    }

    /** A saved "mine" decision remains authoritative when a later message proves the kind. */
    suspend fun materializeConfirmedReferences() = database.withTransaction {
        val decisions=database.ownershipDao().all().filter {it.status=="OWNED" && it.assetLocalId==null}
        for(decision in decisions) {
            val state=states().singleOrNull {it.candidate.reference.let {ref -> ref.bank.value==decision.bankId && ref.kind.name==decision.kind && ref.suffix==decision.suffix}} ?: continue
            if(state.candidate.conflictingKinds || state.candidate.choices.isNotEmpty()) continue
            if(state.observations.mapNotNull {it.proposal?.kind}.distinct().size==1) decide(state.candidate,true)
        }
    }

    /** Only an explicit, consistent account role in the same evidence can link a confirmed debit card. */
    suspend fun linkProvenDebitAccounts() = database.withTransaction {
        val current = states()
        val observations = current.flatMap { it.observations }.mapNotNull { it.proposal }.groupBy { it.evidenceId }
        val registry = BetaAssetRegistry(database)
        for (card in current.filter { it.candidate.status == BetaOwnershipStatus.OWNED }.mapNotNull { it.candidate.asset }.filter { it.kind == AssetKind.DEBIT_CARD && it.parentId == null }) {
            val references = observations.values.filter { rows -> rows.any { it.kind == AssetKind.DEBIT_CARD && it.last4 == card.last4 && it.id.bank == card.id.bank } }
                .flatMap { rows -> rows.filter { it.kind == AssetKind.ACCOUNT && it.id.bank == card.id.bank }.map { BetaOwnershipReference(it.id.bank, BetaOwnershipKind.ACCOUNT, it.last4) } }.toSet()
            if (references.size != 1) continue
            val account = current.singleOrNull { it.candidate.reference == references.single() && it.candidate.status == BetaOwnershipStatus.OWNED }?.candidate?.asset ?: continue
            registry.save(card.copy(parentId = account.id), editing = true, expectedOriginal = card)
        }
    }
}
