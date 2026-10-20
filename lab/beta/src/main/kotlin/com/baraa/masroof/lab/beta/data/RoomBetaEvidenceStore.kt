package com.baraa.masroof.lab.beta.data

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.finance.value.BankId
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Created for the current database per operation; never retained across restore/reset. */
internal class RoomBetaEvidenceStore(private val database: BetaDatabase) : BetaEvidenceStore {
    private val dao get() = database.evidenceDao()
    /** Stable per-evidence role binding, independent of later archive revisions. */
    suspend fun proposalBinding(proposal: BetaAssetProposal): com.baraa.masroof.lab.finance.asset.AssetId? {
        val rows = database.assetDao().proposalBindings(proposal.id.environment.name, proposal.id.bank.value, "proposal-confirmed-v1|${proposal.id.localId}|")
        require(rows.size <= 1) { "Conflicting explicit proposal bindings" }
        return rows.singleOrNull()?.let { com.baraa.masroof.lab.finance.asset.AssetId(proposal.id.environment, proposal.id.bank, it.localId) }
    }

    /** Called only after explicit ownership confirmation in its surrounding Room transaction. */
    suspend fun recordProposalConfirmation(proposal: BetaAssetProposal, assetId: com.baraa.masroof.lab.finance.asset.AssetId = proposal.id) {
        require(assetId.environment == proposal.id.environment && assetId.bank == proposal.id.bank)
        require(proposalBinding(proposal).let { it == null || it == assetId }) { "Proposal already confirmed against another asset" }
        val encodedId = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(proposal.evidenceId.toByteArray(Charsets.UTF_8))
        val reference = "proposal-confirmed-v1|${proposal.id.localId}|$encodedId|${proposal.revision}"
        val assetDao = database.assetDao()
        if (!assetDao.hasProposalAlias(assetId.environment.name, assetId.bank.value, assetId.localId, proposal.last4, reference)) {
            assetDao.insertAlias(BetaAssetAliasEntity(assetId.environment.name, assetId.bank.value,
                assetId.localId, BetaAliasKind.LAST4.name, proposal.last4, reference))
        }
    }

    suspend fun discoveryProposals(id: String): List<BetaAssetProposal> {
        val outcome = dao.outcome(id) ?: return emptyList()
        if (dao.retry(id) != null) return emptyList()
        val route = try { decodeRoute(outcome.route) } catch (_: IllegalArgumentException) { return emptyList() } catch (_: org.json.JSONException) { return emptyList() }
        val body = dao.raw(id)?.body ?: return emptyList()
        if (outcome.baselineJson != "{}") return emptyList()
        val current = try { JSONObject(outcome.candidateJson) } catch (_: org.json.JSONException) { return emptyList() }
        if (!InterpretationArchive.isCurrent(current)) return emptyList()
        return BetaAssetDiscovery.proposals(id, route, outcome.candidateJson, body = body)
    }

    suspend fun ownershipObservations(id: String): List<BetaOwnershipObservation> {
        val outcome = dao.outcome(id) ?: return emptyList()
        if (dao.retry(id) != null || outcome.baselineJson != "{}") return emptyList()
        val raw = dao.raw(id) ?: return emptyList()
        val route = try { decodeRoute(outcome.route) } catch (_: IllegalArgumentException) { return emptyList() }
            catch (_: org.json.JSONException) { return emptyList() }
        return BetaAssetDiscovery.observations(id, route, outcome.candidateJson, raw.body)
    }

    suspend fun discoveryPage(afterId: String, limit: Int): BetaDiscoveryPage = database.withTransaction {
        val ids = dao.idsAfter(afterId, limit)
        var review = 0
        val proposals = ids.flatMap { id -> discoveryProposals(id).map { it.copy(confirmedAssetId = proposalBinding(it)) }.also { if (it.isEmpty()) review++ } }
        BetaDiscoveryPage(proposals, ids.lastOrNull(), review)
    }

    override suspend fun find(id: String) = (dao.raw(id) ?: dao.providerAlias(id)?.let { dao.raw(it) })?.toEvidence()
    override suspend fun resolveCaptureId(request: EvidenceRequest,message: EvidenceMessage,requestedId: String): String = database.withTransaction {
        dao.providerAlias(requestedId)?.let { return@withTransaction it }
        if(request.origin!=EvidenceOrigin.LIVE && (request.origin!=EvidenceOrigin.EXPLICIT_HISTORY || request.namespace!="beta-device-inbox-v1")) return@withTransaction requestedId
        dao.deviceReceipt(message.sender,message.body,message.receivedAt.toString()).singleOrNull()?.id ?: requestedId
    }
    override suspend fun recordProviderAlias(requestedId: String,canonicalId: String) = database.withTransaction {
        if(requestedId!=canonicalId) {
            val existing=dao.providerAlias(requestedId)
            require(existing==null || existing==canonicalId) { "Conflicting provider identity" }
            dao.insertProviderAlias(BetaProviderAliasRecord(requestedId,canonicalId))
            check(dao.providerAlias(requestedId)==canonicalId)
        }
    }

    override suspend fun capture(evidence: CapturedEvidence): CaptureState = database.withTransaction {
        val row = evidence.toEntity()
        val existing = dao.raw(row.id)
        if (existing != null) {
            if (existing.toEvidence().message == evidence.message && existing.origin == row.origin &&
                existing.namespace == row.namespace && existing.providerId == row.providerId) CaptureState.EXISTING
            else CaptureState.CONFLICT
        } else {
            check(dao.insertRaw(row) != -1L) { "Evidence identity collision" }
            dao.retry(BetaEvidenceRetryEntity(row.id, EvidenceStage.CAPTURED.name, 0, "CAPTURED_PENDING", row.capturedAt))
            CaptureState.INSERTED
        }
    }

    override suspend fun summary(id: String): EvidenceSummary = database.withTransaction {
        val raw = requireNotNull(dao.raw(id)) { "Unknown Beta evidence" }
        val outcome = dao.outcome(id)
        val retry = dao.retry(id)
        val decision = dao.decision(id)
        EvidenceSummary(id, outcome?.disposition?.let(EvidenceDisposition::valueOf),
            retry?.stage?.let(EvidenceStage::valueOf), retry?.attempts ?: outcome?.attempts ?: 0,
            decision?.revision ?: 0, decodeRoute(outcome?.route ?: raw.captureRoute),
            outcome?.reasons?.let(::strings)?.toSet().orEmpty() + listOfNotNull(retry?.code),
            decision?.decision?.let(EvidenceReviewDecision::valueOf),
            if (database.financialAdoptionDao().retry(id) != null) "FINANCIAL_APPROVAL_PENDING"
            else database.financialAdoptionDao().evidence(id)?.takeIf { it.status == "REVIEW" }?.reason
                ?: database.correctionDao().evidenceOwner("BETA",id)?.let {owner ->
                    "CORRECTION_REVIEW".takeIf {database.correctionDao().current("BETA",owner)?.activity=="REVIEW"}})
    }

    override suspend fun begin(id: String, at: Instant) = database.withTransaction {
        requireNotNull(dao.raw(id)) { "Unknown Beta evidence" }
        // Re-parsing does not clear a user's ownership/configuration hold. The durable
        // interpretation retry already prevents financial use until this attempt finishes.
        if(database.financialAdoptionDao().evidence(id)?.reason !in setOf("OWNERSHIP_CHANGED","REGISTRY_CHANGED"))
            database.financialAdoptionDao().hold(id, "INTERPRETATION_PENDING")
        val attempts = (dao.retry(id)?.attempts ?: dao.outcome(id)?.attempts ?: 0) + 1
        dao.retry(BetaEvidenceRetryEntity(id, EvidenceStage.INTERPRETATION.name, attempts, "INTERPRETATION_PENDING", at.toString()))
    }

    override suspend fun complete(id: String, route: EvidenceRoute, comparison: EvidenceComparison, at: Instant) = database.withTransaction {
        requireNotNull(dao.raw(id)) { "Unknown Beta evidence" }
        val pending = requireNotNull(dao.retry(id)) { "Missing durable attempt" }
        dao.outcome(BetaEvidenceOutcomeEntity(id, comparison.disposition.name, encodeRoute(route),
            JSONArray(comparison.reasons.sorted()).toString(), comparison.baselineJson, comparison.candidateJson,
            at.toString(), pending.attempts))
        dao.clearRetry(id)
        RoomBetaDashboardAuxFacts(database).refresh(id)
        Unit
    }

    override suspend fun failure(id: String, stage: EvidenceStage, at: Instant) = database.withTransaction {
        val pending = requireNotNull(dao.retry(id)) { "Missing durable attempt" }
        dao.retry(pending.copy(stage = stage.name, code = "${stage.name}_FAILED", updatedAt = at.toString()))
    }
    override suspend fun pending() = dao.pending()
    override suspend fun decide(id: String, expectedRevision: Long, decision: EvidenceReviewDecision, at: Instant) = database.withTransaction {
        requireNotNull(dao.raw(id)) { "Unknown Beta evidence" }
        if ((dao.decision(id)?.revision ?: 0L) != expectedRevision) false
        else {
            dao.decision(BetaEvidenceDecisionEntity(id, expectedRevision + 1, decision.name, at.toString()))
            true
        }
    }

    private fun CapturedEvidence.toEntity() = BetaRawEvidenceEntity(id, request.namespace, message.providerId,
        request.origin.name, message.sender, message.body, message.receivedAt.toString(), message.timeSource.name,
        capturedAt.toString(), encodeRoute(route), JSONArray(selections.map {
            JSONObject().put("bank", it.bankId.value).put("state", it.state.name)
        }).toString(), JSONArray(request.historyBanks.map { it.value }.sorted()).toString())

    private fun BetaRawEvidenceEntity.toEvidence() = CapturedEvidence(id,
        EvidenceRequest(namespace, EvidenceOrigin.valueOf(origin), strings(historyBanks).map(::BankId).toSet()),
        EvidenceMessage(providerId, sender, body, Instant.parse(receivedAt), ReceiptTimeSource.valueOf(timeSource)),
        Instant.parse(capturedAt), decodeRoute(captureRoute), JSONArray(captureSelections).let { array ->
            (0 until array.length()).map { index -> array.getJSONObject(index).let {
                UserBankSelection(BankId(it.getString("bank")), BankSelectionState.valueOf(it.getString("state")))
            } }
        })

    private fun encodeRoute(route: EvidenceRoute) = JSONObject().put("kind", route.kind.name)
        .put("routerVersion", route.routerVersion).put("candidates", JSONArray(route.candidates.map { it.value }.sorted())).toString()
    private fun decodeRoute(value: String) = JSONObject(value).let {
        EvidenceRoute(RouteKind.valueOf(it.getString("kind")), strings(it.getJSONArray("candidates").toString()).map(::BankId).toSet(),
            it.getString("routerVersion"))
    }
    private fun strings(value: String): List<String> = JSONArray(value).let { array ->
        (0 until array.length()).map(array::getString)
    }
}
