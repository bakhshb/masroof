package com.baraa.masroof.lab.beta.data

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.corrections.*
import com.baraa.masroof.lab.finance.fx.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.matching.*
import com.baraa.masroof.lab.finance.value.*
import org.json.JSONObject
import java.security.MessageDigest
import java.time.*

/** Beta-only financial adoption. Automatic writes require a current validated archive and uniquely
 * confirmed ownership; manual decisions are preserved. Learning labels never authorize money. */
class RoomBetaFinancialAdoption(private val database: BetaDatabase) {
    init { require(database.openHelper.databaseName == BetaDatabase.NAME) { "Financial adoption requires the isolated Beta database" } }
    private val dao get() = database.financialAdoptionDao()
    private val ledger get() = RoomBetaFinancialLedger(database)

    suspend fun preview(id: String): BetaFinancialApprovalPreview? = database.withTransaction {
        if (database.evidenceDao().raw(id) == null) return@withTransaction null
        val outcome = database.evidenceDao().outcome(id)
        val adopted = dao.evidence(id)
        val pending = dao.retry(id)?.let { decodeRequest(it.request) }
        val registry = BetaAssetRegistry(database).owned().associateBy { it.id }
        val bank = outcome?.let { runCatching { rawBank(it) }.getOrNull() }
        val choices = registry.values.filter { it.id.bank.value == bank }.sortedBy { it.id.localId }
        val facts = outcome?.let { runCatching { currentInput(it, null, null, FinancialType.EXPENSE, null, registry) }.getOrNull() }
        val correctionOwned = database.correctionDao().current(Environment.BETA.name, key(id).transactionId) != null
        val blocked = when {
            database.evidenceDao().retry(id) != null -> "INTERPRETATION_PENDING"
            outcome == null -> "NO_COMPLETED_ARCHIVE"
            facts == null -> "UNSAFE_INTERPRETATION"
            database.bankSelectionDao().all().none { it.bankId == bank && it.state == "ENABLED" } -> "BANK_DISABLED"
            correctionOwned -> "CORRECTION_OWNS_DECISION"
            else -> null
        }
        val item = BetaFinancialReviewItem(id, key(id), outcome?.let(::fingerprint).orEmpty(),
            if (pending != null) "RETRY" else adopted?.status ?: "UNAPPROVED",
            adopted?.reason ?: "EXPLICIT_APPROVAL_REQUIRED", ledger.readAudit(key(id))?.command?.revision)
        val display = if (facts != null) currentArchive(requireNotNull(outcome)).getJSONObject("facts") else null
        val fee = display?.takeUnless { it.isNull("internationalFee") }?.getJSONObject("internationalFee")?.let {
            runCatching { Money.of(it.getString("amount"), Currency.valueOf(it.getString("currency"))) }.getOrNull() }
        BetaFinancialApprovalPreview(item, facts?.amount, facts?.time,
            java.util.Collections.unmodifiableList(choices.toList()), registryFingerprint(choices), pending, blocked, correctionOwned,
            display?.text("merchant"), display?.text("exchangeRate"), fee)
    }

    /** Bounded write-side sweep. Atomic batches can be replayed after process death. */
    suspend fun automaticPage(afterId: String = "", limit: Int = 100): String? = database.withTransaction {
        require(limit in 1..100)
        val ids = database.evidenceDao().idsAfter(afterId, limit)
        automaticIds(ids)
        ids.lastOrNull()
    }

    suspend fun automaticEvidence(id: String) = database.withTransaction { automaticIds(listOf(id)) }

    private suspend fun automaticIds(ids: List<String>) {
        com.baraa.masroof.lab.beta.application.BetaLoanDiscoveryWorkflow(database).synchronize(ids)
        val registry = BetaAssetRegistry(database).owned().associateBy { it.id }
        val enabled = database.bankSelectionDao().all().filter { it.state == "ENABLED" }.map { it.bankId }.toSet()
        val ownership = database.ownershipDao().all().associateBy { Triple(it.bankId, it.kind, it.suffix) }
        val store = RoomBetaEvidenceStore(database)
        val loanTypes=database.assetDao().all().filter {it.kind=="LOAN" && it.loanType!=null}.associate {
            AssetId(Environment.valueOf(it.environment),BankId(it.bankId),it.localId) to requireNotNull(it.loanType) }
        for (id in ids) {
            val observations = store.ownershipObservations(id)
            val external = observations.isNotEmpty() && observations.all { observation -> observation.reference.let {
                ownership[Triple(it.bank.value, it.kind.name, it.suffix)]?.status == "EXTERNAL" } }
            val previous = dao.evidence(id)
            val manual = database.evidenceDao().decision(id)?.decision.let {it!=null && it!="RESTORE_AUTOMATIC"} || dao.retry(id) != null ||
                database.correctionDao().current(Environment.BETA.name, key(id).transactionId) != null ||
                (previous != null && previous.approvalRequest != AUTOMATIC_AUTHORITY)
            val matchedNotice = previous?.reason=="MATCHED_TRANSFER_NOTICE" &&
                database.ledgerDao().evidenceOwner(Environment.BETA.name,id)?.let { owner -> dao.transaction(owner)?.status=="ACTIVE" }==true
            if ((external && !manual) || matchedNotice) database.ownershipDao().exclude(BetaOwnershipExclusionEntity(id))
            else database.ownershipDao().clearExclusion(id)
            adoptAutomatic(id, registry, enabled, ownership, external && !manual,loanTypes)
            // Parser upgrades must carry existing explicit financial decisions, not ask the
            // user to classify the same evidence again. Pending approvals retain their retry.
            if(previous!=null && previous.reason !in setOf("OWNERSHIP_CHANGED","REGISTRY_CHANGED") &&
                (previous.approvalRequest!=AUTOMATIC_AUTHORITY || database.correctionDao().current(Environment.BETA.name,key(id).transactionId)!=null))
                reconcileApprovedEvidence(id)
            RoomBetaDashboardFacts(database).refresh(id)
        }
    }

    private suspend fun adoptAutomatic(id: String, registry: Map<AssetId, OwnedAsset>, enabled: Set<String>,
        ownership: Map<Triple<String, String, String>, BetaOwnershipDecisionEntity>, externallyExcluded: Boolean,loanTypes: Map<AssetId,String>) {
        val evidence = database.evidenceDao()
        if (evidence.retry(id) != null || evidence.decision(id)?.decision.let {it!=null && it!="RESTORE_AUTOMATIC"} || dao.retry(id) != null) return
        if (database.correctionDao().current(Environment.BETA.name, key(id).transactionId) != null) return
        val previous = dao.evidence(id)
        if (previous != null && previous.approvalRequest != AUTOMATIC_AUTHORITY) return // Every manual choice wins.
        val outcome = evidence.outcome(id) ?: return
        if(previous?.reason=="AMBIGUOUS_TRANSFER_NOTICES" && previous.archiveFingerprint==fingerprint(outcome)) return
        val claimedOwner=database.ledgerDao().evidenceOwner(Environment.BETA.name,id)
        if(claimedOwner!=null && claimedOwner!=key(id).transactionId && dao.transaction(claimedOwner)?.status!="ACTIVE") {
            if(previous!=null) dao.hold(id,"AMBIGUOUS_TRANSFER_NOTICES")
            return
        }
        if(previous?.reason=="MATCHED_TRANSFER_NOTICE" && previous.archiveFingerprint==fingerprint(outcome) &&
            database.ledgerDao().evidenceOwner(Environment.BETA.name,id)?.let {owner -> dao.transaction(owner)?.status=="ACTIVE"}==true) return
        if (outcome.disposition != EvidenceDisposition.INTERPRETED.name) return
        val raw = evidence.raw(id) ?: return
        val json = try { BetaCurrentFinancialArchive.read(outcome, raw.body,durableReceipt=raw.durableReceiptTime()) }
            catch (_: IllegalArgumentException) { return }
            catch (_: org.json.JSONException) { return }
        if (json.getString("bank") !in enabled) return
        val f = json.getJSONObject("facts")
        if (f.getString("family") in setOf("OTP", "NON_FINANCIAL", "BALANCE_NOTICE", "STATEMENT")) return
        // Reuse the exact financial firewall. Partial parses, unsafe execution and diagnostics cannot pass.
        val base = try { currentInput(outcome, null, null, FinancialType.UNKNOWN, null, registry) }
            catch (_: IllegalArgumentException) { return }
            catch (_: org.json.JSONException) { return }
            catch (_: java.time.DateTimeException) { return }
        val existing = ledger.readAudit(key(id))
        if (existing != null && previous == null) return
        val store = RoomBetaEvidenceStore(database)
        val identityObservations = store.ownershipObservations(id)
        val proposals = store.discoveryProposals(id).map { proposal ->
            val binding = store.proposalBinding(proposal)
            val referenceKind = if (proposal.kind == AssetKind.ACCOUNT) "ACCOUNT" else "CARD"
            val decision = ownership[Triple(proposal.id.bank.value, referenceKind, proposal.last4)]
            val selected = decision?.takeIf { it.status == "OWNED" }?.assetLocalId?.let {
                AssetId(Environment.BETA, proposal.id.bank, it)
            }
            proposal.copy(confirmedAssetId = binding ?: selected)
        }.toMutableList()
        // An explicitly configured owned card can resolve an unknown observed card kind.
        for (observation in identityObservations.filter { it.reference.kind == com.baraa.masroof.lab.beta.application.BetaOwnershipKind.CARD && it.proposal == null }) {
            val ref = observation.reference
            val decision = ownership[Triple(ref.bank.value, ref.kind.name, ref.suffix)]
            val matches = registry.values.filter { it.id.bank == ref.bank && it.last4 == ref.suffix && it.kind in setOf(AssetKind.DEBIT_CARD, AssetKind.CREDIT_CARD) }
            val card = if (decision?.assetLocalId != null) matches.singleOrNull { it.id.localId == decision.assetLocalId } else matches.singleOrNull()
            if (decision?.status == "OWNED" && card != null) proposals += com.baraa.masroof.lab.beta.application.BetaAssetProposal(
                card.id, card.kind, ref.suffix, id, fingerprint(outcome), card.id)
        }
        val ownershipRows = identityObservations.mapNotNull { observation -> observation.reference.let {
            ownership[Triple(it.bank.value, it.kind.name, it.suffix)] } }
        val excluded = ownershipRows.any { it.status == "EXTERNAL" }
        val conflictingOwnership = excluded && ownershipRows.any { it.status == "OWNED" }
        var ambiguousTransferOwner=false
        fun transferOwner(field: String): AssetId? {
            if (f.isNull(field)) return null
            val reference = f.getJSONObject(field)
            val bank = reference.text("bank") ?: return null
            val suffix = reference.text("number") ?: return null
            val candidates = registry.values.filter { it.kind == AssetKind.ACCOUNT && it.id.bank.value == bank && it.last4 == suffix }
            val selected = ownership[Triple(bank, "ACCOUNT", suffix)]?.takeIf {it.status=="OWNED"}?.assetLocalId
            if(selected==null && candidates.size>1) ambiguousTransferOwner=true
            return if(selected!=null) candidates.singleOrNull {it.id.localId==selected}?.id else candidates.singleOrNull()?.id
        }
        val decision = BetaAutomaticPostingPolicy.decide(
            com.baraa.masroof.lab.bank.MessageFamily.valueOf(f.getString("family")),
            f.text("direction")?.let(com.baraa.masroof.lab.bank.MoneyDirection::valueOf),
            f.optBoolean("salaryIncomeWording", false), f.text("loanType") != null, !f.isNull("card"), proposals, registry,
            loanTypes.filter { (id,type) -> id in registry && id.bank.value==rawBank(outcome) && type==f.text("loanType") }.keys.singleOrNull(), transferOwner("sourceAccount"), transferOwner("destinationAccount"))
        fun record(input: FinancialClassificationInput, status: String, reason: String) = BetaFinancialAdoptionEntity(
            id, key(id).transactionId, rawBank(outcome), fingerprint(outcome), status, reason,
            BetaLedgerCodec.encodeInput(input), input.revision.correction, input.revision.interpretation,
            AUTOMATIC_AUTHORITY, input.revision.interpretation)
        if (externallyExcluded) {
            dao.put(record(base, "EXCLUDED", "EXTERNAL_OWNERSHIP")); return
        }
        val hold = if(ambiguousTransferOwner) "AMBIGUOUS_OWNER" else if (ownershipRows.any { it.status == "REVIEW" }) "OWNERSHIP_CONFLICT" else if (conflictingOwnership) "CONFLICTING_OWNERSHIP" else if (base.amount.amount.signum() <= 0) "INVALID_AMOUNT" else (decision.hold?.let { reason ->
            if (reason in setOf("CONFIRM_CARD", "LINK_DEBIT_ACCOUNT") && ownershipRows.any { it.kind == "CARD" && it.status == "OWNED" }) "CONFIGURE_CARD" else reason
        }) ?: if (evidence.hasDuplicate(id, raw.sender, raw.body, raw.receivedAt)) "POSSIBLE_DUPLICATE" else null
        if (hold != null) {
            dao.put(record(base, "REVIEW", hold)); return
        }
        var input = currentInput(outcome, decision.source, decision.destination, decision.type, decision.income, registry)
            .copy(revision = PostingRevision(0, maxOf(outcome.attempts.toLong(), previous?.observationRevision ?: 0)),
                appliedFx = existing?.command?.appliedFx)
        if(input.storedType!=FinancialType.SELF_TRANSFER && existing!=null &&
            input.copy(revision=existing.command.revision)!=existing.classification.input)
            input=input.copy(revision=PostingRevision(existing.command.revision.correction,maxOf(input.revision.interpretation,existing.command.revision.interpretation+1)))
        if (previous?.status == "ACTIVE" && previous.archiveFingerprint == fingerprint(outcome) &&
            existing?.classification?.input == input) return
        val enriched = resolveFx(input, outcome)
        val finalInput = if (enriched is FxEnrichmentResult.Ready) input.copy(appliedFx = enriched.proposal?.appliedFx ?: input.appliedFx) else input
        if(finalInput.storedType==FinancialType.SELF_TRANSFER) {
            postAutomaticSelfTransfer(finalInput,id,outcome)
            return
        }
        val result = ledger.post(finalInput)
        when (result) {
            is FinancialPostingResult.Applied, is FinancialPostingResult.AlreadyApplied -> {
                dao.put(record(finalInput, "ACTIVE", "AUTOMATIC"))
            }
            else -> dao.put(record(finalInput, "REVIEW", "AUTOMATIC_POSTING_REVIEW"))
        }
    }

    /** Complete current candidate pool; neither amount nor a suffix alone chooses a pair. */
    private suspend fun postAutomaticSelfTransfer(input: FinancialClassificationInput, id: String, outcome: BetaEvidenceOutcomeEntity) {
        fun scoped(value: FinancialClassificationInput)=value.copy(assets=value.assets.filterKeys {it in financialRegistryDependencies(value)})
        fun observation(value: FinancialClassificationInput, family: String)=TransferObservation(scoped(value),
            if(family=="TRANSFER_OUT") TransferDirection.OUTBOUND else TransferDirection.INBOUND)
        val family=currentArchive(outcome).getJSONObject("facts").getString("family")
        val nearby=dao.bank(rawBank(outcome)).filter { it.approvalRequest==AUTOMATIC_AUTHORITY && it.status=="ACTIVE" && it.transactionId!=input.key.transactionId }
            .mapNotNull {row -> ledger.readAudit(PostingKey(Environment.BETA,row.transactionId))?.takeIf {posting ->
                posting.matchIdentity?.kind=="BANK_NOTICES" && posting.command.source==(input.source as? FinancialEndpoint.Owned)?.id &&
                posting.command.destination==(input.destination as? FinancialEndpoint.Owned)?.id && posting.command.amount==input.amount &&
                Duration.between(posting.command.time.instant,input.time.instant).abs()<=Duration.ofMinutes(10)
            } }
        val own=ledger.readAudit(input.key)?.takeIf {it.matchIdentity?.kind=="BANK_NOTICES"}
        val existing=own ?: nearby.singleOrNull()
        // Replace in place: observation order is part of the exact persisted payload.
        // Removing the canonical notice and appending it reverses a valid pair on replay,
        // causing REVISION_PAYLOAD at an otherwise unchanged revision.
        val updated=observation(input,family)
        val originals=existing?.observations.orEmpty().map {it.copy(input=scoped(it.input))}
        val pool=if(originals.any {it.input.key==input.key}) originals.map {if(it.input.key==input.key) updated else it} else originals+updated
        val canonical=existing?.command?.key ?: input.key
        val result=if(nearby.size>1) TransferMatchResult.Review(setOf(ReviewReason.AMBIGUOUS_MATCH))
            else SelfTransferMatcher.match(pool,TransferMatchProof.BankNotices(canonical))
        if(result !is TransferMatchResult.Matched) {
            dao.put(BetaFinancialAdoptionEntity(id,input.key.transactionId,rawBank(outcome),fingerprint(outcome),"REVIEW","AMBIGUOUS_TRANSFER_NOTICES",
                BetaLedgerCodec.encodeInput(input),input.revision.correction,input.revision.interpretation,AUTOMATIC_AUTHORITY,input.revision.interpretation))
            (nearby+listOfNotNull(own)).forEach {p -> dao.transaction(p.command.key.transactionId)?.let {dao.hold(it.evidenceId,"AMBIGUOUS_TRANSFER_NOTICES")} }
            return
        }
        // A new notice extends the existing stable transaction; repeating a sweep is idempotent.
        val changed=existing!=null && (existing.observations.toSet()!=pool.toSet())
        val revision=if(changed) PostingRevision(existing!!.command.revision.correction,existing.command.revision.interpretation+1) else existing?.command?.revision ?: input.revision
        val revised=pool.map {o -> if(o.input.key==canonical) o.copy(input=o.input.copy(revision=revision)) else o}
        val matched=SelfTransferMatcher.match(revised,TransferMatchProof.BankNotices(canonical)) as? TransferMatchResult.Matched ?: return
        val posted=ledger.post(matched.transfer)
        if(posted !is FinancialPostingResult.Applied && posted !is FinancialPostingResult.AlreadyApplied) {
            // A conservative domain rejection needs a scoped human decision, like ordinary
            // automatic postings. Actual database exceptions still escape and keep startup blocked.
            dao.put(BetaFinancialAdoptionEntity(id,input.key.transactionId,rawBank(outcome),fingerprint(outcome),"REVIEW","AUTOMATIC_POSTING_REVIEW",
                BetaLedgerCodec.encodeInput(input),input.revision.correction,input.revision.interpretation,AUTOMATIC_AUTHORITY,input.revision.interpretation))
            (nearby+listOfNotNull(own)).forEach {p -> dao.transaction(p.command.key.transactionId)?.let {dao.hold(it.evidenceId,"AUTOMATIC_POSTING_REVIEW")} }
            return
        }
        val canonicalId=canonical.transactionId.removePrefix("evidence:")
        val canonicalOutcome=requireNotNull(database.evidenceDao().outcome(canonicalId))
        val merged=matched.transfer.input
        dao.put(BetaFinancialAdoptionEntity(canonicalId,canonical.transactionId,rawBank(canonicalOutcome),fingerprint(canonicalOutcome),"ACTIVE","AUTOMATIC",
            BetaLedgerCodec.encodeInput(merged),merged.revision.correction,merged.revision.interpretation,AUTOMATIC_AUTHORITY,merged.revision.interpretation))
        if(id!=canonicalId) {
            dao.put(BetaFinancialAdoptionEntity(id,input.key.transactionId,rawBank(outcome),fingerprint(outcome),"EXCLUDED","MATCHED_TRANSFER_NOTICE",
                BetaLedgerCodec.encodeInput(input),input.revision.correction,input.revision.interpretation,AUTOMATIC_AUTHORITY,input.revision.interpretation))
            database.ownershipDao().exclude(BetaOwnershipExclusionEntity(id))
        }
        RoomBetaDashboardFacts(database).refresh(canonicalId)
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
            BetaFinancialReviewItem(id, key(id), outcome?.let(::fingerprint).orEmpty(),
                if (pending) "RETRY" else adopted?.status ?: "UNAPPROVED",
                if (pending) "FINANCIAL_APPROVAL_PENDING" else adopted?.reason ?: "EXPLICIT_APPROVAL_REQUIRED",
                adopted?.let { PostingRevision(it.correction, it.interpretation) })
        }
    }

    suspend fun approve(request: BetaFinancialApproval, restoreIgnored: Boolean = false): BetaFinancialAdoptionResult {
        // Commit explicit intent before financial derivation. Process death/SQL failure can retry
        // the same bound request, never infer approval from comparison or a background parser.
        database.withTransaction {
            requireNotNull(database.evidenceDao().raw(request.evidenceId))
            if(restoreIgnored) {
                check(database.correctionDao().evidenceOwner("BETA",request.evidenceId)==null)
                val previous=requireNotNull(database.evidenceDao().decision(request.evidenceId))
                check(previous.decision in setOf(EvidenceReviewDecision.USER_NON_FINANCIAL.name,EvidenceReviewDecision.RESTORE_AUTOMATIC.name))
                database.evidenceDao().decision(previous.copy(revision=previous.revision+1,
                    decision=EvidenceReviewDecision.RESTORE_AUTOMATIC.name,updatedAt=Instant.now().toString()))
            }
            database.ownershipDao().clearExclusion(request.evidenceId)
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
        val result = approveAtomic(decoded)
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

    private suspend fun approveAtomic(request: BetaFinancialApproval): BetaFinancialAdoptionResult = database.withTransaction {
        if (dao.retry(request.evidenceId)?.request != encodeRequest(request)) return@withTransaction review("SUPERSEDED_APPROVAL")
        val outcome = database.evidenceDao().outcome(request.evidenceId)
            ?: return@withTransaction review("NO_COMPLETED_ARCHIVE")
        if (database.evidenceDao().retry(request.evidenceId) != null) return@withTransaction review("INTERPRETATION_PENDING")
        if (fingerprint(outcome) != request.archiveRevision) return@withTransaction review("STALE_ARCHIVE")
        try { currentArchive(outcome) } catch (_: Exception) { return@withTransaction review("UNSAFE_INTERPRETATION") }
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
        val allAssets = BetaAssetRegistry(database).owned().associateBy { it.id }
        if (request.expectedRegistryRevision != null && request.expectedRegistryRevision !=
            registryFingerprint(allAssets.values.filter { it.id.bank.value == rawBank(outcome) }))
            return@withTransaction review("STALE_REGISTRY")
        if (database.bankSelectionDao().all().none {
                it.bankId == rawBank(outcome) && it.state == "ENABLED" }) return@withTransaction review("BANK_DISABLED")
        val next = try { currentInput(outcome, request.source, request.destination, request.type,
            request.incomeAttribution, allAssets) } catch (_: Exception) { return@withTransaction review("UNSAFE_INTERPRETATION") }
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
        try { CorrectionObservation(currentInput(outcome, (previous.source as? FinancialEndpoint.Owned)?.id,
            (previous.destination as? FinancialEndpoint.Owned)?.id, previous.storedType, previous.incomeAttribution,
            BetaAssetRegistry(database).owned().associateBy { it.id }).let { it.copy(revision = PostingRevision(0,
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
        val correction = readBetaCorrectionState(database, key)
        val stored=ledger.readAudit(key)
        if(correction==null) {
            if(adopted.approvalRequest==AUTOMATIC_AUTHORITY || stored==null || stored.matchIdentity!=null ||
                stored.classification.input.authority !in setOf(CorrectionAuthority.USER_FINANCIAL_TYPE,CorrectionAuthority.CONFIRMED_FAMILY_OR_AMOUNT))
                return@withTransaction review("EXPLICIT_REAPPROVAL_REQUIRED")
            if(adopted.status=="ACTIVE" && adopted.archiveFingerprint==fingerprint(outcome))
                ledger.read(key)?.let {return@withTransaction BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(it))}
        }
        // An immediate retry after a manual type/endpoint edit has no new raw observation. Rebuilding
        // an observation from the new selected endpoint at the old interpretation would falsely conflict
        // with the immutable observation reviewed by that edit. Preserve that exact transition.
        if (correction!=null && adopted.status == "ACTIVE" && adopted.archiveFingerprint == fingerprint(outcome) &&
            adopted.observationRevision <= correction.overlay.interpretationRevision) {
            val current = ledger.read(key)
            return@withTransaction if (correction.activity == BetaLedgerActivity.ACTIVE && current != null)
                BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(current))
            else review(if (correction.activity == BetaLedgerActivity.SUPPRESSED) "MANUAL_SUPPRESSION_PRESERVED" else "CORRECTION_REVIEW")
        }
        if(stored!=null) {
            val owned=BetaAssetRegistry(database).owned().associateBy {it.id}
            val changed=financialRegistryDependencies(stored.classification.input).filter {stored.classification.input.assets[it]!=owned[it]}
            if(changed.isNotEmpty()) {
                val reason=if(changed.any {owned[it]==null}) "OWNERSHIP_CHANGED" else "REGISTRY_CHANGED"
                if(adopted.reason!=reason) dao.hold(id,reason)
                return@withTransaction review(reason)
            }
        }
        val latest = observation(key) ?: run {
            val previous = correction?.overlay?.latestObservation?.input ?: requireNotNull(stored).classification.input
            CorrectionObservation(previous.copy(revision = PostingRevision(0, maxOf(outcome.attempts.toLong(), adopted.observationRevision)),
                authority = CorrectionAuthority.NONE), ObservationSafety.SECURITY)
        }
        val lifecycle=RoomBetaCorrectionLifecycle(database)
        val result=if(correction!=null) lifecycle.rebase(latest,correction.overlay.correctionRevision) else {
            val original=requireNotNull(stored)
            // Keep carries the verified stored selection; it grants no new financial authority.
            // The seeded overlay also makes a new security hold durable and sticky across replay.
            lifecycle.submit(latest,CorrectionRequest("preserve-approved:$id:${original.command.revision.correction}:${latest.input.revision.interpretation}",
                key,original.command.revision.correction+1,latest.input.revision.interpretation,original.command.evidenceIds,FinancialDecision.Keep))
        }
        val current = when (result) {
            is BetaCorrectionResult.Applied -> result.current
            is BetaCorrectionResult.AlreadyApplied -> result.current
            else -> return@withTransaction review("CORRECTION_REBASE_REJECTED")
        }
        val posting = ledger.readAudit(key)
        dao.put(adopted.copy(status = "ACTIVE", reason = if (current.activity == BetaLedgerActivity.REVIEW) "CORRECTION_REVIEW" else "CORRECTION_REBASED",
            archiveFingerprint = fingerprint(outcome), observationRevision = latest.input.revision.interpretation,
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
            try { currentArchive(outcome) } catch (_: Exception) { return@withTransaction null }
            if (adopted.status != "ACTIVE" || posting.command.revision != expected ||
                adopted.archiveFingerprint != fingerprint(outcome) || database.evidenceDao().retry(evidenceId) != null) null
            else Triple(adopted, posting.classification.input, outcome)
        } ?: return review("STALE_OR_HELD")
        val (adopted, input, outcome) = snapshot
        if (input.appliedFx != null) return BetaFinancialAdoptionResult.Posted(FinancialPostingResult.AlreadyApplied(
            requireNotNull(ledger.readAudit(input.key))))
        val resolution = resolveFx(input, outcome, market)
        val proposal = (resolution as? FxEnrichmentResult.Ready)?.proposal ?: return review("FX_UNAVAILABLE")
        return database.withTransaction {
            if (dao.evidence(evidenceId) != adopted || ledger.read(input.key)?.classification?.input != input ||
                database.evidenceDao().retry(evidenceId) != null || database.evidenceDao().outcome(evidenceId)?.let(::fingerprint) != adopted.archiveFingerprint)
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
        val f = currentArchive(outcome).getJSONObject("facts")
        fun money(name: String): Money? = if (f.isNull(name)) null else f.getJSONObject(name).let {
            Money.of(it.getString("amount"), Currency.valueOf(it.getString("currency"))) }
        val fact = FxEvidenceFact(Environment.BETA, outcome.rawId, money("amount")?.currency,
            money("labeledForeignAmount")?.currency, f.text("merchant"), f.text("exchangeRate")?.toBigDecimal(),
            money("internationalFee"), f.text("occurredAtLocal")?.let(LocalDateTime::parse),
            f.text("occurredAt")?.let(Instant::parse), persistedTransactionZone = input.time.zone,
            fixedBankZone = input.time.zone)
        return FxEnrichmentPolicy(market).resolve(FxEnrichmentRequest(input, listOf(outcome.rawId), listOf(fact)))
    }

    private suspend fun currentInput(outcome: BetaEvidenceOutcomeEntity, source: AssetId?, destination: AssetId?, type: FinancialType,
        income: IncomeAttribution?, registry: Map<AssetId, OwnedAsset>): FinancialClassificationInput {
        val json = currentArchive(outcome)
        val route = JSONObject(outcome.route)
        require(route.getString("kind") == "MATCHED" && route.getJSONArray("candidates").let {
            it.length() == 1 && it.getString(0) == rawBank(outcome) })
        require(json.getString("evidenceId") == outcome.rawId && json.getString("status") == "SUCCESS")
        require(json.getJSONArray("blockers").length() == 0)
        val f = json.getJSONObject("facts")
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

    private fun rawBank(outcome: BetaEvidenceOutcomeEntity) = JSONObject(outcome.candidateJson).getString("bank")

    private suspend fun currentArchive(outcome: BetaEvidenceOutcomeEntity): JSONObject {
        val raw=requireNotNull(database.evidenceDao().raw(outcome.rawId))
        return BetaCurrentFinancialArchive.read(outcome,raw.body,durableReceipt=raw.durableReceiptTime())
    }
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
        private const val AUTOMATIC_AUTHORITY = "BANK_ENGINE_AUTOMATIC_V1"
        fun key(evidenceId: String) = PostingKey(Environment.BETA, "evidence:$evidenceId")
        internal fun fingerprint(outcome: BetaEvidenceOutcomeEntity): String = MessageDigest.getInstance("SHA-256")
            .digest(("BANK_ENGINE_FINANCE_V1\n" + outcome.route + "\n" + outcome.candidateJson + "\n" + outcome.baselineJson + "\n" + outcome.attempts).toByteArray())
            .joinToString("") { "%02x".format(it) }
        private fun review(reason: String) = BetaFinancialAdoptionResult.Review(reason)
    }
}
