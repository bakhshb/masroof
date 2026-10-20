package com.baraa.masroof.lab.beta.application

import androidx.room.withTransaction
import com.baraa.masroof.lab.beta.application.corrections.*
import com.baraa.masroof.lab.beta.data.*
import com.baraa.masroof.lab.finance.dashboard.DashboardQuery
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.time.*
import java.util.UUID

sealed interface BetaCommitmentCreationResult {
    data class Created(val id: String): BetaCommitmentCreationResult
    data class AlreadyExists(val id: String): BetaCommitmentCreationResult
    data class Rejected(val reason: String): BetaCommitmentCreationResult
}
data class BetaCommitmentCreationPreview(val token: BetaReviewToken,val eligible: Boolean,val existingId: String?,val confirmationKey: String)

/** Explicit metadata creation from one current approved transaction, never a new financial posting. */
class BetaCommitmentCreationWorkflow(private val database: BetaDatabase,private val clock: Clock=Clock.systemUTC()) {
    private data class Source(val detail: BetaReviewDetail,val posting: StoredFinancialPosting,val name: String?)
    private suspend fun source(key: PostingKey): Source? {
        val detail=BetaReviewWorkflow(database).detail(key) ?: return null
        if(detail.current!=null && detail.current.activity!=BetaLedgerActivity.ACTIVE) return null
        val stored=detail.stored ?: return null
        val controls=RoomBetaDashboardConfiguration(database)
        val zone=controls.read().draft.salary?.zone ?: stored.command.time.zone
        val date=stored.command.time.instant.atZone(zone).toLocalDate()
        val query=DashboardQuery(Environment.BETA,date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant(),zone,Currency.SAR)
        // Reuse the same current/ownership/held-evidence firewall as the financial screen.
        val read=RoomBetaDashboardSource(database,clock=clock).load(query)
        val current=read.rows.singleOrNull {it.active && it.posting.command.key==key}?.posting ?: return null
        if(current.command.revision!=detail.token.ledgerRevision) return null
        val label=read.postingFacts.singleOrNull {it.key==key && it.revision==current.command.revision}
        val name=label?.merchant?.trim()?.takeIf {it.isNotEmpty()} ?: label?.counterparty?.trim()?.takeIf {it.isNotEmpty()}
        return Source(detail,current,name)
    }
    suspend fun preview(key: PostingKey): BetaCommitmentCreationPreview? = database.withTransaction {
        val source=source(key) ?: return@withTransaction null
        val existing=RoomBetaDashboardConfiguration(database).read().draft.commitments.singleOrNull {it.sourceTransactionId==key.transactionId}
        BetaCommitmentCreationPreview(source.detail.token,source.posting.classification.effectiveType in eligibleTypes,existing?.id,confirmationKey(source))
    }
    private val eligibleTypes=setOf(FinancialType.EXPENSE,FinancialType.FEE,FinancialType.BILL_PAYMENT)
    private fun confirmationKey(source: Source): String {
        val token=source.detail.token;val command=source.posting.command
        return auditId("COMMITMENT_SOURCE",token.key.environment,token.key.transactionId,token.interpretation,token.correction,
            token.transition,token.ledgerRevision,command.amount.amount.toPlainString(),command.amount.currency,
            command.time.instant,command.time.zone,source.posting.classification.effectiveType,source.name,*token.evidence.sorted().toTypedArray())
    }
    private fun canCreate(source: Source)=source.posting.classification.effectiveType in eligibleTypes &&
        source.posting.command.amount.amount.signum()>0 && source.name!=null
    suspend fun create(preview: BetaCommitmentCreationPreview): BetaCommitmentCreationResult = database.withTransaction {
        val token=preview.token
        val current=source(token.key) ?: return@withTransaction BetaCommitmentCreationResult.Rejected("unavailable_source")
        if(current.detail.token!=token || confirmationKey(current)!=preview.confirmationKey) return@withTransaction BetaCommitmentCreationResult.Rejected("stale_source")
        val controls=RoomBetaDashboardConfiguration(database,clock);val configuration=controls.read()
        configuration.draft.commitments.singleOrNull {it.sourceTransactionId==token.key.transactionId}?.let {
            return@withTransaction BetaCommitmentCreationResult.AlreadyExists(it.id)
        }
        if(!canCreate(current)) return@withTransaction BetaCommitmentCreationResult.Rejected("invalid_transaction")
        val command=current.posting.command;val date=command.time.instant.atZone(command.time.zone).toLocalDate()
        val definition=BetaRecurringCommitment(UUID.randomUUID().toString(),checkNotNull(current.name),command.amount,date.dayOfMonth,
            YearMonth.from(date),transactionDate=date,sourceTransactionId=command.key.transactionId)
        when(val result=controls.save(configuration.revision,configuration.draft.copy(commitments=configuration.draft.commitments+definition))) {
            is BetaDashboardConfigurationWrite.Saved->BetaCommitmentCreationResult.Created(definition.id)
            else->BetaCommitmentCreationResult.Rejected("configuration_write_incomplete")
        }
    }
}
