package com.baraa.masroof.lab.beta.data

import androidx.room.withTransaction
import com.baraa.masroof.lab.finance.classification.FinancialClassificationInput
import com.baraa.masroof.lab.finance.matching.MatchedSelfTransfer
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.Environment
import java.util.Collections

/** Beta-only durable financial boundary. No intake, parser, dashboard or live posting adoption.
 * Targeted current/claim lookups only: financial history is never replayed on a write/read.
 */
class RoomBetaFinancialLedger internal constructor(
    private val database: BetaDatabase,
    private val afterWritesBeforeCommit: suspend () -> Unit,
) : FinancialPostingLedger {
    constructor(database: BetaDatabase) : this(database, {})

    init { require(database.openHelper.databaseName == BetaDatabase.NAME) { "Financial ledger requires the isolated Beta database" } }

    override suspend fun post(input: FinancialClassificationInput): FinancialPostingResult {
        require(input.key.environment == Environment.BETA)
        return commit(FinancialPostingRules.prepare(input))
    }

    override suspend fun post(transfer: MatchedSelfTransfer): FinancialPostingResult {
        require(transfer.input.key.environment == Environment.BETA)
        return commit(FinancialPostingRules.prepare(transfer))
    }

    private suspend fun commit(prepared: PostingPreparation): FinancialPostingResult {
        if (prepared is PostingPreparation.Rejected) return prepared.result
        val candidate = (prepared as PostingPreparation.Ready).posting
        val input = candidate.classification.input
        return database.withTransaction {
            val dao = database.ledgerDao()
            val existing = readCurrent(input.key)
            when (PostingRevisionPolicy.action(existing?.command?.revision, input.revision)) {
                RevisionAction.STALE -> return@withTransaction FinancialPostingResult.Stale(requireNotNull(existing))
                RevisionAction.ALREADY_APPLIED -> return@withTransaction if (existing == candidate) {
                    FinancialPostingResult.AlreadyApplied(requireNotNull(existing))
                } else FinancialPostingResult.Conflict(PostingConflict.REVISION_PAYLOAD)
                RevisionAction.APPLY -> Unit
            }
            FinancialPostingRules.replacementConflict(existing, candidate)?.let {
                return@withTransaction FinancialPostingResult.Conflict(it)
            }
            // The pure API assumes a confirmed registry snapshot. This adapter verifies that
            // authority against the actual Beta registry inside the same transaction. Historical
            // snapshots remain historical; a changed registry does not rewrite posted history.
            val snapshots = listOf(input) + candidate.observations.map { it.input }
            for (snapshot in snapshots) for ((id, asset) in snapshot.assets) {
                require(id.environment == Environment.BETA && asset.id.environment == Environment.BETA)
                val row = database.assetDao().get(id.environment.name, id.bank.value, id.localId)
                if (asset.id != id || row == null || row.kind != asset.kind.name || row.last4 != asset.last4 ||
                    row.parentLocalId != asset.parentId?.localId || row.creditCardRole != asset.creditCardRole?.name) {
                    return@withTransaction FinancialPostingResult.Review(setOf(ReviewReason.OWNERSHIP))
                }
            }
            val environment = input.key.environment.name
            val transactionId = input.key.transactionId
            val owners = input.evidenceIds.associateWith { dao.evidenceOwner(environment, it) }
            if (owners.values.any { it != null && it != transactionId }) {
                return@withTransaction FinancialPostingResult.Conflict(PostingConflict.EVIDENCE_ALREADY_CLAIMED)
            }
            val identity = candidate.matchIdentity
            val proofOwner = identity?.let { dao.proofOwner(environment, it.kind, it.issuer?.value.orEmpty(), it.reference) }
            if (proofOwner != null && proofOwner != transactionId) {
                return@withTransaction FinancialPostingResult.Conflict(PostingConflict.MATCH_ALREADY_CLAIMED)
            }
            val payload = BetaLedgerCodec.encode(candidate)
            // ABORT inserts and composite primary keys enforce uniqueness independently of this
            // adapter instance. Room's database transaction serializes all reads and writes.
            dao.writeCurrent(BetaLedgerCurrentEntity(environment, transactionId, input.revision.correction, input.revision.interpretation, payload))
            dao.appendHistory(BetaLedgerHistoryEntity(environment, transactionId, input.revision.correction, input.revision.interpretation, payload))
            owners.filterValues { it == null }.keys.forEach { dao.claimEvidence(BetaLedgerEvidenceClaimEntity(environment, it, transactionId)) }
            if (identity != null && proofOwner == null) {
                dao.claimProof(BetaLedgerProofClaimEntity(environment, identity.kind, identity.issuer?.value.orEmpty(), identity.reference, transactionId))
            }
            afterWritesBeforeCommit()
            FinancialPostingResult.Applied(candidate)
        }
    }

    suspend fun read(key: PostingKey): StoredFinancialPosting? {
        require(key.environment == Environment.BETA)
        return readCurrent(key)
    }

    private suspend fun readCurrent(key: PostingKey): StoredFinancialPosting? = database.ledgerDao()
        .current(key.environment.name, key.transactionId)?.let { row ->
            BetaLedgerCodec.decode(row.payload).also {
                check(it.command.key == key && it.command.revision == PostingRevision(row.correction, row.interpretation)) { "Inconsistent Beta current row" }
            }
        }

    suspend fun history(key: PostingKey): List<StoredFinancialPosting> {
        require(key.environment == Environment.BETA)
        return Collections.unmodifiableList(database.ledgerDao().history(key.environment.name, key.transactionId).map { row ->
            BetaLedgerCodec.decode(row.payload).also {
                check(it.command.key == key && it.command.revision == PostingRevision(row.correction, row.interpretation)) { "Inconsistent Beta history row" }
            }
        })
    }
}
