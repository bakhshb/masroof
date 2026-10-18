package com.baraa.masroof.lab.beta.data

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.corrections.*
import com.baraa.masroof.lab.finance.fx.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import org.json.JSONObject
import java.security.MessageDigest
import java.time.*

/** Explicit Beta-only archive adoption. Neither comparison nor acknowledgement authorizes money.
 * A current bank-engine archive, including its original spans and matching human labels, is the
 * financial reading. A historical baseline stays explicit and is never replaced automatically. */
class RoomBetaFinancialAdoption(private val database: BetaDatabase) {
    init { require(database.openHelper.databaseName == BetaDatabase.NAME) { "Financial adoption requires the isolated Beta database" } }
    private val dao get() = database.financialAdoptionDao()
    private val ledger get() = RoomBetaFinancialLedger(database)

    suspend fun preview(id: String): BetaFinancialApprovalPreview? = database.withTransaction {
        if (database.evidenceDao().raw(id) == null) return@withTransaction null
        val outcome = database.evidenceDao().outcome(id)
        val adopted = dao.evidence(id)
        val pending = dao.retry(id)?.let { decodeRequest(it.request) }
        val registry = BetaAssetRegistry(database).all().associateBy { it.id }
        val bank = outcome?.let { runCatching { rawBank(it) }.getOrNull() }
        val choices = registry.values.filter { it.id.bank.value == bank }.sortedBy { it.id.localId }
        val facts = outcome?.let { runCatching { baseline(it, null, null, FinancialType.EXPENSE, null, registry) }.getOrNull() }
        val correctionOwned = database.correctionDao().current(Environment.BETA.name, key(id).transactionId) != null
        val blocked = when {
            database.evidenceDao().retry(id) != null -> "INTERPRETATION_PENDING"
            outcome == null -> "NO_COMPLETED_ARCHIVE"
            facts == null -> "UNSAFE_BASELINE"
            database.bankSelectionDao().all().none { it.bankId == bank && it.state == "ENABLED" } -> "BANK_DISABLED"
            correctionOwned -> "CORRECTION_OWNS_DECISION"
            else -> null
        }
        val item = BetaFinancialReviewItem(id, key(id), outcome?.let { BetaFinancialArchive.fingerprint(database, it) }.orEmpty(),
            if (pending != null) "RETRY" else adopted?.status ?: "UNAPPROVED",
            adopted?.reason ?: "EXPLICIT_APPROVAL_REQUIRED", ledger.readAudit(key(id))?.command?.revision)
        val display = if (facts != null) BetaFinancialArchive.facts(database, requireNotNull(outcome)) else null
        val fee = display?.takeUnless { it.isNull("internationalFee") }?.getJSONObject("internationalFee")?.let {
            runCatching { Money.of(it.getString("amount"), Currency.valueOf(it.getString("currency"))) }.getOrNull() }
        BetaFinancialApprovalPreview(item, facts?.amount, facts?.time,
            java.util.Collections.unmodifiableList(choices.toList()), registryFingerprint(choices), pending, blocked, correctionOwned,
            display?.text("merchant"), display?.text("exchangeRate"), fee)
    }

    private fun registryFingerprint(assets: Collection<OwnedAsset>): String {
        val encoded = org.json.JSONArray()
        assets.sortedWith(compareBy({ it.id.bank.value }, { it.id.localId })).forEach { asset ->
            encoded.put(org.json.JSONArray(listOf(asset.id.bank.value, asset.id.localId, asset.kind.name,
                asset.last4, asset.parentId?.localId, asset.creditCardRole?.name)))
        }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    suspend fun reviewPage(afterId: String = "", limit: Int = 25): List<BetaFinancialReviewItem> = database.withTransaction {
        require(limit in 1..100)
        database.evidenceDao().idsAfter(afterId, limit).map { id ->
            val outcome = database.evidenceDao().outcome(id)
            val adopted = dao.evidence(id)
            val pending = dao.retry(id) != null
            BetaFinancialReviewItem(id, key(id), outcome?.let { BetaFinancialArchive.fingerprint(database, it) }.orEmpty(),
                if (pending) "RETRY" else adopted?.status ?: "UNAPPROVED",
                if (pending) "FINANCIAL_APPROVAL_PENDING" else adopted?.reason ?: "EXPLICIT_APPROVAL_REQUIRED",
                adopted?.let { PostingRevision(it.correction, it.interpretation) })
        }
    }

    suspend fun approve(request: BetaFinancialApproval): BetaFinancialAdoptionResult {
        // Commit explicit intent before financial derivation. Process death/SQL failure can retry
        // the same bound request, never infer approval from comparison or a background parser.
        database.withTransaction {
            requireNotNull(database.evidenceDao().raw(request.evidenceId))
            dao.retry(BetaFinancialApprovalRetryEntity(request.evidenceId, encodeRequest(request)))
        }
        val result = approveAtomic(request)
        if (result is BetaFinancialAdoptionResult.Posted && result.result.let {
                it is FinancialPostingResult.Applied || it is FinancialPostingResult.AlreadyApplied })
            database.withTransaction { if (dao.retry(request.evidenceId)?.request == encodeRequest(request)) dao.clearRetry(request.evidenceId) }
        return result
    }

    suspend fun retryApproval(id: String, expected: BetaFinancialApproval? = null): BetaFinancialAdoptionResult {
        val request = dao.retry(id) ?: return review("NO_APPROVAL_RETRY")
        if (expected != null && request.request != encodeRequest(expected)) return review("SUPERSEDED_APPROVAL")
        // Never rewrite a newer request while retrying the exact intent the user reviewed.
        val decoded = decodeRequest(request.request)
        val result = approveAtomic(decoded, requireEnabledBank = expected != null)
        if (result is BetaFinancialAdoptionResult.Posted && result.result.let {
                it is FinancialPostingResult.Applied || it is FinancialPostingResult.AlreadyApplied })
            database.withTransaction { if (dao.retry(id)?.request == request.request) dao.clearRetry(id) }
        return result
    }

    /** Explicit cancellation affects only this exact pending request. It never clears a parser or
     * registry hold, changes a manual decision, or resurrects a suppressed financial result. */
    suspend fun cancelApproval(request: BetaFinancialApproval): Boolean = database.withTransaction {
        if (dao.retry(request.evidenceId)?.request != encodeRequest(request)) false
        else { dao.clearRetry(request.evidenceId); true }
    }

    private suspend fun approveAtomic(request: BetaFinancialApproval, requireEnabledBank: Boolean = false): BetaFinancialAdoptionResult = database.withTransaction {
        if (dao.retry(request.evidenceId)?.request != encodeRequest(request)) return@withTransaction review("SUPERSEDED_APPROVAL")
        val outcome = database.evidenceDao().outcome(request.evidenceId)
            ?: return@withTransaction review("NO_COMPLETED_ARCHIVE")
        if (database.evidenceDao().retry(request.evidenceId) != null) return@withTransaction review("INTERPRETATION_PENDING")
        if (BetaFinancialArchive.fingerprint(database, outcome) != request.archiveRevision) return@withTransaction review("STALE_ARCHIVE")
        val existing = ledger.readAudit(key(request.evidenceId))
        if (database.correctionDao().current(Environment.BETA.name, key(request.evidenceId).transactionId) != null)
            return@withTransaction review("CORRECTION_OWNS_DECISION")
        // Existing ledger rows created outside this workflow are never adopted by guessing authority.
        val adopted = dao.evidence(request.evidenceId)
        if (adopted?.status == "ACTIVE" && adopted.approvalRequest == encodeRequest(request) &&
            adopted.archiveFingerprint == request.archiveRevision && existing?.classification?.input == BetaLedgerCodec.decodeInput(adopted.approvedInput))
            return@withTransaction BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(requireNotNull(existing)))
        if (existing?.command?.revision != request.expectedPostingRevision) return@withTransaction review("STALE_POSTING")
        if (existing != null && adopted?.status == "ACTIVE" && adopted.archiveFingerprint == request.archiveRevision &&
            existing.command.revision == request.expectedPostingRevision &&
            existing.classification.input.let { it.storedType == request.type && (it.source as? FinancialEndpoint.Owned)?.id == request.source &&
                (it.destination as? FinancialEndpoint.Owned)?.id == request.destination && it.incomeAttribution == request.incomeAttribution })
            return@withTransaction BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(existing))
        if (existing != null && adopted == null) return@withTransaction review("UNOWNED_EXISTING_POSTING")
        val raw = requireNotNull(database.evidenceDao().raw(request.evidenceId))
        val allAssets = BetaAssetRegistry(database).all().associateBy { it.id }
        if (request.expectedRegistryRevision != null && request.expectedRegistryRevision !=
            registryFingerprint(allAssets.values.filter { it.id.bank.value == rawBank(outcome) }))
            return@withTransaction review("STALE_REGISTRY")
        if ((request.expectedRegistryRevision != null || requireEnabledBank) && database.bankSelectionDao().all().none {
                it.bankId == rawBank(outcome) && it.state == "ENABLED" }) return@withTransaction review("BANK_DISABLED")
        val next = try { baseline(outcome, request.source, request.destination, request.type,
            request.incomeAttribution, allAssets) } catch (_: Exception) { return@withTransaction review("UNSAFE_BASELINE") }
        if (next.assets.values.any { it.id.bank.value != rawBank(outcome) }) return@withTransaction review("CROSS_BANK_ENDPOINT")
        // Repeating an exact approved choice is idempotent, not a new manual revision.
        val previousInput = adopted?.let { BetaLedgerCodec.decodeInput(it.approvedInput) }
        val same = previousInput != null && adopted.archiveFingerprint == request.archiveRevision &&
            previousInput.copy(revision = next.revision, appliedFx = null) == next.copy(authority = CorrectionAuthority.USER_FINANCIAL_TYPE)
        if (same && adopted.status == "ACTIVE" && existing != null)
            return@withTransaction BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(existing))
        // New decisions are explicit. A complete pair remains currency-bound and immutable here.
        if (existing?.command?.appliedFx != null && existing.command.amount.currency != next.amount.currency)
            return@withTransaction review("FX_CURRENCY_CHANGE_REQUIRES_CORRECTION")
        val selected = next.copy(authority = CorrectionAuthority.USER_FINANCIAL_TYPE,
            revision = PostingRevision((existing?.command?.revision?.correction ?: 0) + 1,
                maxOf(next.revision.interpretation, adopted?.observationRevision ?: 0)),
            appliedFx = existing?.command?.appliedFx)
        val enriched = resolveFx(selected, outcome)
        val finalInput = if (enriched is FxEnrichmentResult.Ready) selected.copy(appliedFx = enriched.proposal?.appliedFx ?: selected.appliedFx)
            else selected // Native financial result can exist with explicitly unavailable SAR valuation.
        val result = ledger.post(finalInput)
        if (result is FinancialPostingResult.Applied || result is FinancialPostingResult.AlreadyApplied) {
            dao.put(BetaFinancialAdoptionEntity(request.evidenceId, selected.key.transactionId, rawBank(outcome),
                request.archiveRevision, "ACTIVE", if (enriched is FxEnrichmentResult.Unavailable) "FX_UNAVAILABLE" else "APPROVED",
                BetaLedgerCodec.encodeInput(finalInput), finalInput.revision.correction, finalInput.revision.interpretation,
                encodeRequest(request), finalInput.revision.interpretation))
            RoomBetaDashboardFacts(database).refresh(request.evidenceId)
        }
        BetaFinancialAdoptionResult.Posted(result)
    }

    /** Trusted current archive observation, never display DTO reconstruction. Caller still has to
     * atomically re-read this value when submitting a correction. */
    suspend fun observation(key: PostingKey): CorrectionObservation? = database.withTransaction {
        require(key.environment == Environment.BETA)
        val adopted = dao.transaction(key.transactionId) ?: return@withTransaction null
        val outcome = database.evidenceDao().outcome(adopted.evidenceId) ?: return@withTransaction null
        if (database.evidenceDao().retry(adopted.evidenceId) != null) return@withTransaction null
        val previous = ledger.readAudit(key)?.classification?.input ?: BetaLedgerCodec.decodeInput(adopted.approvedInput)
        try { CorrectionObservation(baseline(outcome, (previous.source as? FinancialEndpoint.Owned)?.id,
            (previous.destination as? FinancialEndpoint.Owned)?.id, previous.storedType, previous.incomeAttribution,
            BetaAssetRegistry(database).all().associateBy { it.id }).let { it.copy(revision = PostingRevision(0,
                maxOf(it.revision.interpretation, adopted.observationRevision))) }, ObservationSafety.ORDINARY) }
        catch (_: Exception) { null } // Security, informational and malformed archives remain held.
    }

    /** Rebase an existing explicit manual decision after archive/configuration changes. This does
     * not authorize a new row or replace user-selected amount/time/endpoints/FX with parser facts.
     * Unsafe archives produce a sticky safety hold instead of falling back to an old ordinary base. */
    suspend fun reconcileApprovedEvidence(id: String): BetaFinancialAdoptionResult = database.withTransaction {
        val adopted = dao.evidence(id) ?: return@withTransaction review("EXPLICIT_APPROVAL_REQUIRED")
        if (database.evidenceDao().retry(id) != null) return@withTransaction review("INTERPRETATION_PENDING")
        if (dao.retry(id) != null) return@withTransaction review("APPROVAL_RETRY_PENDING")
        val outcome = database.evidenceDao().outcome(id) ?: return@withTransaction review("NO_COMPLETED_ARCHIVE")
        val key = key(id)
        val correction = readBetaCorrectionState(database, key) ?: return@withTransaction review("EXPLICIT_REAPPROVAL_REQUIRED")
        // An immediate retry after a manual type/endpoint edit has no new raw observation. Rebuilding
        // a baseline from the new selected endpoint at the old interpretation would falsely conflict
        // with the immutable observation reviewed by that edit. Preserve that exact transition.
        if (adopted.status == "ACTIVE" && adopted.archiveFingerprint == BetaFinancialArchive.fingerprint(database, outcome) &&
            adopted.observationRevision <= correction.overlay.interpretationRevision) {
            val current = ledger.read(key)
            return@withTransaction if (correction.activity == BetaLedgerActivity.ACTIVE && current != null)
                BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(current))
            else review(if (correction.activity == BetaLedgerActivity.SUPPRESSED) "MANUAL_SUPPRESSION_PRESERVED" else "CORRECTION_REVIEW")
        }
        val latest = observation(key) ?: run {
            val previous = correction.overlay.latestObservation.input
            CorrectionObservation(previous.copy(revision = PostingRevision(0, maxOf(outcome.attempts.toLong(), adopted.observationRevision)),
                authority = CorrectionAuthority.NONE), ObservationSafety.SECURITY)
        }
        val result = RoomBetaCorrectionLifecycle(database).rebase(latest, correction.overlay.correctionRevision)
        val current = when (result) {
            is BetaCorrectionResult.Applied -> result.current
            is BetaCorrectionResult.AlreadyApplied -> result.current
            else -> return@withTransaction review("CORRECTION_REBASE_REJECTED")
        }
        val posting = ledger.readAudit(key)
        dao.put(adopted.copy(status = "ACTIVE", reason = if (current.activity == BetaLedgerActivity.REVIEW) "CORRECTION_REVIEW" else "CORRECTION_REBASED",
            archiveFingerprint = BetaFinancialArchive.fingerprint(database, outcome), observationRevision = latest.input.revision.interpretation,
            approvedInput = posting?.classification?.input?.let(BetaLedgerCodec::encodeInput) ?: adopted.approvedInput,
            correction = posting?.command?.revision?.correction ?: adopted.correction,
            interpretation = posting?.command?.revision?.interpretation ?: adopted.interpretation))
        RoomBetaDashboardFacts(database).refresh(id)
        if (current.activity == BetaLedgerActivity.ACTIVE && posting != null)
            BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(posting))
        else review(if (current.activity == BetaLedgerActivity.SUPPRESSED) "MANUAL_SUPPRESSION_PRESERVED" else "CORRECTION_REVIEW")
    }

    /** Market lookup is injected and runs outside the write transaction. No network client here.
     * Commit rechecks archive, input and registry; failure leaves the previous whole pair intact. */
    suspend fun enrich(evidenceId: String, expected: PostingRevision, market: FxMarketRateProvider): BetaFinancialAdoptionResult {
        val snapshot = database.withTransaction {
            val adopted = dao.evidence(evidenceId) ?: return@withTransaction null
            val posting = ledger.read(key(evidenceId)) ?: return@withTransaction null
            val outcome = database.evidenceDao().outcome(evidenceId) ?: return@withTransaction null
            if (adopted.status != "ACTIVE" || posting.command.revision != expected ||
                adopted.archiveFingerprint != BetaFinancialArchive.fingerprint(database, outcome) || database.evidenceDao().retry(evidenceId) != null) null
            else Triple(adopted, posting.classification.input, outcome)
        } ?: return review("STALE_OR_HELD")
        val (adopted, input, outcome) = snapshot
        if (input.appliedFx != null) return BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(
            requireNotNull(ledger.readAudit(input.key))))
        val resolution = resolveFx(input, outcome, market)
        val proposal = (resolution as? FxEnrichmentResult.Ready)?.proposal ?: return review("FX_UNAVAILABLE")
        return database.withTransaction {
            if (dao.evidence(evidenceId) != adopted || ledger.read(input.key)?.classification?.input != input ||
                database.evidenceDao().retry(evidenceId) != null || database.evidenceDao().outcome(evidenceId)?.let { BetaFinancialArchive.fingerprint(database, it) } != adopted.archiveFingerprint)
                return@withTransaction review("STALE_OR_HELD")
            // A new explicit financial revision is needed because the original selection was manual.
            val updated = input.copy(appliedFx = proposal.appliedFx,
                revision = PostingRevision(expected.correction + 1, expected.interpretation))
            val result = ledger.post(updated)
            if (result is FinancialPostingResult.Applied || result is FinancialPostingResult.AlreadyApplied)
                dao.put(adopted.copy(approvedInput = BetaLedgerCodec.encodeInput(updated), correction = updated.revision.correction, reason = "FX_ENRICHED"))
            RoomBetaDashboardFacts(database).refresh(evidenceId)
            BetaFinancialAdoptionResult.Posted(result)
        }
    }

    private suspend fun resolveFx(input: FinancialClassificationInput, outcome: BetaEvidenceOutcomeEntity,
        market: FxMarketRateProvider = FxMarketRateProvider { _, _ -> null }): FxEnrichmentResult {
        val f = BetaFinancialArchive.facts(database, outcome)
        fun money(name: String): Money? = if (f.isNull(name)) null else f.getJSONObject(name).let {
            Money.of(it.getString("amount"), Currency.valueOf(it.getString("currency"))) }
        val fact = FxEvidenceFact(Environment.BETA, outcome.rawId, money("amount")?.currency,
            money("labeledForeignAmount")?.currency, f.text("merchant"), f.text("exchangeRate")?.toBigDecimal(),
            money("internationalFee"), f.text("occurredAtLocal")?.let(LocalDateTime::parse),
            f.text("occurredAt")?.let(Instant::parse), persistedTransactionZone = input.time.zone,
            fixedBankZone = input.time.zone)
        return FxEnrichmentPolicy(market).resolve(FxEnrichmentRequest(input, listOf(outcome.rawId), listOf(fact)))
    }

    private suspend fun baseline(outcome: BetaEvidenceOutcomeEntity, source: AssetId?, destination: AssetId?, type: FinancialType,
        income: IncomeAttribution?, registry: Map<AssetId, OwnedAsset>): FinancialClassificationInput {
        val json = BetaFinancialArchive.json(outcome)
        val route = JSONObject(outcome.route)
        require(route.getString("kind") == "MATCHED" && route.getJSONArray("candidates").let {
            it.length() == 1 && it.getString(0) == rawBank(outcome) })
        require(json.getString("evidenceId") == outcome.rawId && json.getString("status") == "SUCCESS")
        require(json.getJSONArray("blockers").length() == 0)
        val f = BetaFinancialArchive.facts(database, outcome)
        require(f.getString("family") !in setOf("UNKNOWN", "OTP", "NON_FINANCIAL", "BALANCE_NOTICE", "STATEMENT"))
        require(f.text("executionState").let { it == null || it == "EXECUTED" })
        val diagnostics = json.getJSONArray("diagnostics")
        require((0 until diagnostics.length()).none { diagnostics.getJSONObject(it).let { diagnostic ->
            diagnostic.getString("severity") == "ERROR" || diagnostic.getString("code").let { code ->
                listOf("SECURITY", "OTP", "UNEXECUTED", "CONFLICT").any(code::contains) } } })
        // Only AlJazira's reviewed fixed bank-clock policy is supported by this first adapter.
        require(rawBank(outcome) == "BANK_ALJAZIRA")
        val zone = ZoneId.of("Asia/Riyadh")
        val at = f.text("occurredAtLocal")?.let { LocalDateTime.parse(it).atZone(zone).toInstant() }
            ?: f.text("occurredAt")?.let(Instant::parse) ?: error("Missing bank time")
        val amount = f.getJSONObject("amount").let { Money.of(it.getString("amount"), Currency.valueOf(it.getString("currency"))) }
        val selected = listOfNotNull(source, destination).toSet().flatMap { id ->
            val asset = requireNotNull(registry[id]); listOfNotNull(asset, asset.parentId?.let { requireNotNull(registry[it]) })
        }.associateBy { it.id }
        return FinancialClassificationInput(key(outcome.rawId), PostingRevision(0, outcome.attempts.toLong()), type, amount,
            TransactionTime(at, zone), source?.let(FinancialEndpoint::Owned) ?: FinancialEndpoint.External,
            destination?.let(FinancialEndpoint::Owned) ?: FinancialEndpoint.External, selected, setOf(outcome.rawId),
            validatedParse = true, incomeAttribution = income)
    }

    private fun rawBank(outcome: BetaEvidenceOutcomeEntity) = BetaFinancialArchive.json(outcome).getString("bank")
    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else get(name) as String
    private fun encodeRequest(value: BetaFinancialApproval): String {
        fun asset(id: AssetId?) = id?.let { JSONObject().put("bank", it.bank.value).put("id", it.localId) }
        return JSONObject().put("id", value.evidenceId).put("archive", value.archiveRevision)
            .put("expected", value.expectedPostingRevision?.let { "${it.correction}:${it.interpretation}" } ?: JSONObject.NULL)
            .put("type", value.type.name).put("source", asset(value.source) ?: JSONObject.NULL)
            .put("destination", asset(value.destination) ?: JSONObject.NULL)
            .put("income", value.incomeAttribution?.name ?: JSONObject.NULL).apply {
                value.expectedRegistryRevision?.let { put("registry", it) }
            }.toString()
    }
    private fun decodeRequest(value: String): BetaFinancialApproval = JSONObject(value).let { json ->
        fun asset(name: String) = if (json.isNull(name)) null else json.getJSONObject(name).let {
            AssetId(Environment.BETA, BankId(it.getString("bank")), it.getString("id")) }
        BetaFinancialApproval(json.getString("id"), json.getString("archive"), json.text("expected")?.split(':')?.let {
            PostingRevision(it[0].toLong(), it[1].toLong()) }, FinancialType.valueOf(json.getString("type")),
            asset("source"), asset("destination"), json.text("income")?.let(IncomeAttribution::valueOf),
            if (json.has("registry")) json.getString("registry") else null)
    }
    companion object {
        fun key(evidenceId: String) = PostingKey(Environment.BETA, "evidence:$evidenceId")
        private fun review(reason: String) = BetaFinancialAdoptionResult.Review(reason)
    }
}
