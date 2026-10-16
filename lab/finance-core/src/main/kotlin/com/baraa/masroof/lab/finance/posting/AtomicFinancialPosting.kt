package com.baraa.masroof.lab.finance.posting

import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.matching.*
import com.baraa.masroof.lab.finance.value.Environment
import java.util.Collections

/** Financial input still carries the gate. A shadow comparison/acknowledgement is not this API. */
interface FinancialPostingLedger {
    suspend fun post(input: FinancialClassificationInput): FinancialPostingResult
    suspend fun post(transfer: MatchedSelfTransfer): FinancialPostingResult
}

enum class PostingConflict { REVISION_PAYLOAD, EVIDENCE_ALREADY_CLAIMED, MATCH_ALREADY_CLAIMED, PAIR_PROVENANCE, MANUAL_DECISION, IMMUTABLE_FX }

sealed interface FinancialPostingResult {
    data class Applied(val posting: StoredFinancialPosting) : FinancialPostingResult
    data class AlreadyApplied(val posting: StoredFinancialPosting) : FinancialPostingResult
    data class Stale(val current: StoredFinancialPosting) : FinancialPostingResult
    data class Review(val reasons: Set<ReviewReason>) : FinancialPostingResult
    data class Conflict(val reason: PostingConflict) : FinancialPostingResult
}

/** Current financial result plus the original facts of each paired observation. No raw SMS text.
 * Earlier revisions are retained separately, not overwritten or silently reinterpreted.
 */
data class StoredFinancialPosting(
    val classification: FinancialClassification.Classified,
    val command: PostingCommand,
    val observations: List<TransferObservation>,
    val matchIdentity: TransferMatchIdentity?,
)

/** Storage support only: shared preparation and replacement guards keep durable adapters on the
 * same financial policy as the reference. These helpers do not write or grant authority. */
sealed interface PostingPreparation {
    data class Ready(val posting: StoredFinancialPosting) : PostingPreparation
    data class Rejected(val result: FinancialPostingResult) : PostingPreparation
}

object FinancialPostingRules {
    fun prepare(input: FinancialClassificationInput): PostingPreparation {
        val frozen = freezeFinancialInput(input)
        // Independent transfer observations cannot each become a new self-transfer row. A future
        // single-message transfer proof is a separate contract; only checked pairs are supported now.
        if (frozen.storedType == FinancialType.SELF_TRANSFER) {
            return PostingPreparation.Rejected(FinancialPostingResult.Review(setOf(ReviewReason.AMBIGUOUS_MATCH)))
        }
        return candidate(frozen, emptyList(), null)
    }

    fun prepare(transfer: MatchedSelfTransfer): PostingPreparation {
        // Re-run matching at the boundary rather than trust an object after external mutation.
        val proof = transfer.identity.let { identity ->
            if (identity.kind == "BANK_REFERENCE") {
                TransferMatchProof.BankReference(ConfirmedBankTransferReference(requireNotNull(identity.issuer), identity.reference))
            } else {
                val outgoing = transfer.observations.single { it.direction == TransferDirection.OUTBOUND }.input
                val incoming = transfer.observations.single { it.direction == TransferDirection.INBOUND }.input
                TransferMatchProof.UserPair(identity.reference, outgoing.key, outgoing.revision, outgoing.evidenceIds,
                    incoming.key, incoming.revision, incoming.evidenceIds)
            }
        }
        val checked = SelfTransferMatcher.match(transfer.observations, proof)
        if (checked is TransferMatchResult.Review) return PostingPreparation.Rejected(FinancialPostingResult.Review(checked.reasons))
        val valid = (checked as TransferMatchResult.Matched).transfer
        if (valid.input != transfer.input || valid.identity != transfer.identity) {
            return PostingPreparation.Rejected(FinancialPostingResult.Conflict(PostingConflict.PAIR_PROVENANCE))
        }
        return candidate(valid.input, valid.observations, valid.identity)
    }

    private fun candidate(input: FinancialClassificationInput, observations: List<TransferObservation>,
        identity: TransferMatchIdentity?): PostingPreparation {
        val classified = FinancialImpactClassifier.classify(input)
        if (classified is FinancialClassification.Review) return PostingPreparation.Rejected(FinancialPostingResult.Review(classified.reasons))
        val impact = classified as FinancialClassification.Classified
        val frozenImpact = impact.copy(input = input,
            metrics = Collections.unmodifiableMap(LinkedHashMap(impact.metrics)),
            accountMovements = Collections.unmodifiableList(ArrayList(impact.accountMovements)))
        val command = PostingCommand(input.key, input.revision, impact.effectiveType, input.amount, input.time,
            (input.source as? FinancialEndpoint.Owned)?.id, (input.destination as? FinancialEndpoint.Owned)?.id,
            input.evidenceIds, input.appliedFx)
        val candidate = StoredFinancialPosting(frozenImpact, command,
            Collections.unmodifiableList(ArrayList(observations)), identity)
        return PostingPreparation.Ready(candidate)
    }

    fun replacementConflict(existing: StoredFinancialPosting?, candidate: StoredFinancialPosting): PostingConflict? {
        val input = candidate.classification.input
        val impact = candidate.classification
        val identity = candidate.matchIdentity
        // A stored FX pair is a rate FROM its original native currency. Even a newer explicit
        // decision cannot reinterpret that same pair as a different currency's rate.
        if (existing?.command?.appliedFx != null && existing.command.amount.currency != input.amount.currency &&
            existing.command.appliedFx == input.appliedFx) return PostingConflict.IMMUTABLE_FX
        // Interpretation retries do not remove or rewrite a previously confirmed manual decision.
        // Full correction overlays live in M8.3; a changed decision needs a newer correction revision.
        if (existing != null && existing.classification.input.authority in setOf(
                CorrectionAuthority.CONFIRMED_FAMILY_OR_AMOUNT, CorrectionAuthority.USER_FINANCIAL_TYPE) &&
            (input.revision.correction == existing.command.revision.correction || input.authority !in setOf(
                CorrectionAuthority.CONFIRMED_FAMILY_OR_AMOUNT, CorrectionAuthority.USER_FINANCIAL_TYPE)) &&
            (input.authority != existing.classification.input.authority ||
                input.storedType != existing.classification.input.storedType || input.amount != existing.command.amount ||
                input.source != existing.classification.input.source || input.destination != existing.classification.input.destination ||
                input.time != existing.command.time || input.financingLoan != existing.classification.input.financingLoan ||
                input.incomeAttribution != existing.classification.input.incomeAttribution ||
                impact.effectiveType != existing.classification.effectiveType || input.appliedFx != existing.command.appliedFx)
        ) return PostingConflict.MANUAL_DECISION
        val explicitNewDecision = existing != null && input.revision.correction > existing.command.revision.correction &&
            input.authority in setOf(CorrectionAuthority.CONFIRMED_FAMILY_OR_AMOUNT, CorrectionAuthority.USER_FINANCIAL_TYPE)
        if (existing?.matchIdentity != null && identity != existing.matchIdentity && !explicitNewDecision) {
            return PostingConflict.PAIR_PROVENANCE
        }
        if (existing?.command?.appliedFx != null && input.appliedFx != existing.command.appliedFx && !explicitNewDecision) {
            return PostingConflict.IMMUTABLE_FX
        }
        return null
    }
}

private data class EvidenceClaim(val environment: Environment, val evidenceId: String)
private data class LedgerState(
    val current: Map<PostingKey, StoredFinancialPosting> = emptyMap(),
    val history: Map<PostingKey, List<StoredFinancialPosting>> = emptyMap(),
    val evidence: Map<EvidenceClaim, PostingKey> = emptyMap(),
    val matches: Map<TransferMatchIdentity, PostingKey> = emptyMap(),
)

/** Thread-safe in-memory reference implementation, NOT durable Beta storage or runtime adoption.
 * A Room adapter must implement this same atomic boundary in one database transaction: validate,
 * compare revision and complete payload, claim evidence/proof, replace result and append history.
 * No store-level entry accepts a prebuilt PostingCommand or caller-built Classified as authority.
 */
class InMemoryFinancialPostingLedger internal constructor(private val beforePublish: () -> Unit) : FinancialPostingLedger {
    constructor() : this({})

    private val lock = Any()
    private var state = LedgerState()

    override suspend fun post(input: FinancialClassificationInput): FinancialPostingResult = apply(FinancialPostingRules.prepare(input))
    override suspend fun post(transfer: MatchedSelfTransfer): FinancialPostingResult = apply(FinancialPostingRules.prepare(transfer))

    private fun apply(prepared: PostingPreparation): FinancialPostingResult = synchronized(lock) {
        if (prepared is PostingPreparation.Rejected) return@synchronized prepared.result
        val candidate = (prepared as PostingPreparation.Ready).posting
        val input = candidate.classification.input
        val identity = candidate.matchIdentity
        val existing = state.current[input.key]
        when (PostingRevisionPolicy.action(existing?.command?.revision, input.revision)) {
            RevisionAction.STALE -> return@synchronized FinancialPostingResult.Stale(requireNotNull(existing))
            RevisionAction.ALREADY_APPLIED -> return@synchronized if (existing == candidate) {
                FinancialPostingResult.AlreadyApplied(requireNotNull(existing))
            } else FinancialPostingResult.Conflict(PostingConflict.REVISION_PAYLOAD)
            RevisionAction.APPLY -> Unit
        }
        FinancialPostingRules.replacementConflict(existing, candidate)?.let {
            return@synchronized FinancialPostingResult.Conflict(it)
        }
        // Prior evidence claims remain reserved even when a later revision has a smaller link set.
        // A correction can never make an already consumed message available as a second transaction.
        val claims = input.evidenceIds.map { EvidenceClaim(input.key.environment, it) }
        if (claims.any { state.evidence[it]?.let { owner -> owner != input.key } == true }) {
            return@synchronized FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED)
        }
        if (identity != null && state.matches[identity]?.let { it != input.key } == true) {
            return@synchronized FinancialPostingResult.Conflict(PostingConflict.MATCH_ALREADY_CLAIMED)
        }
        // Stage the entire transaction before publication. An exception leaves no result, claim,
        // revision or history partial write; replay of the same request can safely retry.
        val next = LedgerState(
            current = state.current + (input.key to candidate),
            history = state.history + (input.key to Collections.unmodifiableList(
                state.history[input.key].orEmpty() + candidate)),
            evidence = state.evidence + claims.associateWith { input.key },
            matches = if (identity == null) state.matches else state.matches + (identity to input.key),
        )
        beforePublish()
        state = next
        FinancialPostingResult.Applied(candidate)
    }

    fun read(key: PostingKey): StoredFinancialPosting? = synchronized(lock) { state.current[key] }
    fun history(key: PostingKey): List<StoredFinancialPosting> = synchronized(lock) { state.history[key].orEmpty() }
    fun size(): Int = synchronized(lock) { state.current.size }
}
