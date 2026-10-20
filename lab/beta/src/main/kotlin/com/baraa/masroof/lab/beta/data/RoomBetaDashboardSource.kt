package com.baraa.masroof.lab.beta.data

import com.baraa.masroof.lab.beta.application.BetaConfiguredPeriods
import com.baraa.masroof.lab.beta.application.BetaDashboardConfigurationDraft
import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.baraa.masroof.lab.beta.application.corrections.BetaLedgerActivity
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.FinancialClassificationInput
import com.baraa.masroof.lab.finance.classification.FinancialEndpoint
import com.baraa.masroof.lab.finance.dashboard.*
import com.baraa.masroof.lab.finance.dashboard.producer.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import com.baraa.masroof.lab.finance.projection.LinkedInternationalFee
import java.util.Collections

/** The v6 ledger stores time inside checksummed financial payloads, not an SQL time index.
 * Consequently this adapter reads a bounded CURRENT set, never the append-only audit history.
 * Overflow fails explicitly: a truncated set is never presented as complete or as zero.
 */
data class BetaDashboardReadLimits(val currentRows: Int = 10_000, val assets: Int = 2_000, val evidenceIds: Int = 10_000) {
    init { require(currentRows in 1..100_000 && assets in 1..10_000 && evidenceIds in 1..100_000) }
}

class BetaDashboardReadLimitExceeded(val collection: String, val limit: Int) : IllegalStateException(
    "Beta dashboard $collection exceeds the explicit read limit $limit; no partial snapshot was returned",
)

/** No parser, market service, raw SMS, per-row DAO call, migration or write entry point.
 * Each invocation owns one Room transaction; the data revision covers every loaded collection.
 * The optional v7 adoption gate is read within that same snapshot so held postings never leak.
 */
class RoomBetaDashboardSource(
    private val database: BetaDatabase,
    private val periods: DashboardPeriods = DashboardPeriods(),
    private val limits: BetaDashboardReadLimits = BetaDashboardReadLimits(),
    private val clock: Clock = Clock.systemUTC(),
) : DashboardReadSource {
    init { require(database.openHelper.databaseName == BetaDatabase.NAME) { "Dashboard requires the isolated Beta database" } }

    override suspend fun load(query: DashboardQuery): DashboardReadBatch {
        require(query.environment == Environment.BETA && query.currency == Currency.SAR)
        return database.withTransaction {
            val readAt = clock.instant()
            val digest = MessageDigest.getInstance("SHA-256")
            fun fingerprint(value: String?) {
                // Length framing distinguishes null, empty and concatenation collisions.
                val bytes = value?.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
                if (bytes != null) digest.update(bytes)
            }
            fingerprint("BETA_DASHBOARD_CURRENT_1")
            fingerprint(query.toString())
            val schema = database.openHelper.readableDatabase.version
            check(schema == BetaDatabase.VERSION) { "Unsupported Beta dashboard schema" }
            fingerprint(schema.toString())
            val configuration = if (schema >= 10) RoomBetaDashboardConfiguration(database).current() else null
            val configured = configuration?.takeIf { it.revision > 0 }?.let { BetaConfiguredPeriods.periods(it.draft, query, readAt) } ?: DashboardPeriods()
            val frozenPeriods = DashboardPeriods(periods.salary ?: configured.salary, periods.calendar ?: configured.calendar,
                Collections.unmodifiableMap(LinkedHashMap(configured.statements + periods.statements)))
            val windows = listOf(DisplayPeriod(query.from, query.until, query.zone, PeriodKind.SELECTED, null)) +
                listOfNotNull(frozenPeriods.salary, frozenPeriods.calendar) + frozenPeriods.statements.values
            require(windows.all { it.zone == query.zone })
            fingerprint(configuration.toString()); fingerprint(frozenPeriods.toString())
            val paymentCutoff = readAt.plusNanos(1)
            val from=windows.minOf { it.from }.toEpochMilli()
            val until=windows.maxOf { it.until }.toEpochMilli()
            val cutoff=minOf(readAt.toEpochMilli(),query.until.toEpochMilli()-1)
            fun moneyTime(column: String)="($column >= $from AND $column < $until)"
            val rawMoney="SELECT r.id FROM beta_raw_evidence r LEFT JOIN beta_parsed_event p ON p.rawId=r.id WHERE "+moneyTime("COALESCE(p.occurredAtEpochMillis,r.receivedAtEpochMillis)")
            fun latest(prefix: String,asset: String): String = "SELECT p.rawId FROM beta_parsed_event p JOIN (SELECT ${prefix}_bankId AS bank,${prefix}_${asset} AS asset,MAX(${prefix}_epochMillis) AS at FROM beta_parsed_event WHERE ${prefix}_present=1 AND ${prefix}_${asset} IS NOT NULL AND ${prefix}_epochMillis <= $cutoff GROUP BY ${prefix}_bankId,${prefix}_${asset}) last ON p.${prefix}_bankId=last.bank AND p.${prefix}_${asset}=last.asset AND p.${prefix}_epochMillis=last.at WHERE p.${prefix}_present=1"
            // Explicit bounded foreign-definition sources may predate the display windows.
            // They supply valuation facts only and are removed from the financial rows below.
            val commitmentSources="SELECT sourceTransactionId FROM beta_commitment WHERE amountCurrency!='SAR' AND sourceTransactionId IS NOT NULL"
            val commitmentEvidence="SELECT evidenceId FROM beta_financial_transaction_raw_sms_link WHERE environment='BETA' AND transactionId IN ($commitmentSources)"
            val monetaryFactScope="SELECT rawId FROM beta_parsed_event WHERE f_present=1 AND "+moneyTime("f_epochMillis")+" UNION "+latest("f","balanceAsset")
            val factScope="$monetaryFactScope UNION SELECT rawId FROM beta_parsed_event WHERE f_present=1 AND f_transactionId IN ($commitmentSources)"
            val auxScope="SELECT rawId FROM beta_parsed_event WHERE a_present=1 AND (rawId IN ($rawMoney) OR "+moneyTime("a_epochMillis")+") UNION "+latest("a","balanceAsset")+" UNION "+latest("a","facilityAsset")+" UNION "+latest("a","loanAsset")
            val relevantEvidence="$rawMoney UNION $factScope UNION $auxScope UNION $commitmentEvidence"
            val moneyLedger="SELECT transactionId FROM beta_financial_transaction WHERE environment='BETA' AND ("+moneyTime("occurredAtEpochMillis")+" OR (financialType='CREDIT_CARD_PAYMENT' AND occurredAtEpochMillis < ${paymentCutoff.toEpochMilli()}))"
            val monetaryLedger="$moneyLedger UNION SELECT f_transactionId FROM beta_parsed_event WHERE rawId IN ($monetaryFactScope) AND f_transactionId IS NOT NULL"
            val relevantLedger="$monetaryLedger UNION $commitmentSources"
            val heldInMoney=linkedSetOf<String>()

            val capabilities = database.query(SimpleSQLiteQuery(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name IN ('beta_financial_adoption','beta_financial_approval_retry','beta_dashboard_evidence_facts','beta_dashboard_aux_facts')",
            )).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val hasAdoption = "beta_financial_adoption" in capabilities
            val hasApprovalRetry = "beta_financial_approval_retry" in capabilities
            check(schema < 7 || (hasAdoption && hasApprovalRetry)) { "Missing Beta financial adoption gates" }
            val hasFacts = "beta_dashboard_evidence_facts" in capabilities
            check(schema < 8 || hasFacts) { "Missing Beta persisted dashboard facts" }
            val hasAux = "beta_dashboard_aux_facts" in capabilities
            check(schema < 9 || hasAux) { "Missing Beta auxiliary dashboard facts" }
            val auxiliary = if (hasAux) database.dashboardAuxFactsDao().scoped(SimpleSQLiteQuery("SELECT * FROM beta_dashboard_aux_facts WHERE evidenceId IN ($auxScope) ORDER BY evidenceId LIMIT ?",arrayOf(limits.evidenceIds+1))) else emptyList()
            if (auxiliary.size > limits.evidenceIds) throw BetaDashboardReadLimitExceeded("auxiliary facts", limits.evidenceIds)
            val auxByEvidence = auxiliary.associateBy { it.evidenceId }
            val currentAux = hashSetOf<String>()
            val informationalAux = hashSetOf<String>()
            val factRows = if (hasFacts) database.dashboardFactsDao().scoped(SimpleSQLiteQuery("SELECT * FROM beta_dashboard_evidence_facts WHERE evidenceId IN ($factScope) ORDER BY evidenceId LIMIT ?",arrayOf(limits.currentRows+1))) else emptyList()
            if (factRows.size > limits.currentRows) throw BetaDashboardReadLimitExceeded("persisted facts", limits.currentRows)
            val persistedFacts = factRows.associateBy { it.transactionId }
            val projectedEvidence = factRows.mapTo(hashSetOf()) { it.evidenceId }
            val adoptionBindings = mutableMapOf<String, Triple<String, String, Long>>()
            val held = linkedSetOf<String>()
            val excluded = linkedSetOf<String>()
            val externalEvidence = if (schema >= 13) database.query(SimpleSQLiteQuery("SELECT rawId FROM beta_ownership_exclusion WHERE rawId IN ($relevantEvidence) UNION SELECT d.targetId AS rawId FROM beta_audit_log d WHERE d.eventKind='EVIDENCE_DECISION' AND d.operation='USER_NON_FINANCIAL' AND d.targetId IN ($relevantEvidence) AND NOT EXISTS(SELECT 1 FROM beta_audit_log n WHERE n.eventKind=d.eventKind AND n.targetId=d.targetId AND n.transition>d.transition) ORDER BY rawId LIMIT ?", arrayOf(limits.evidenceIds + 1))).use { cursor ->
                val result = hashSetOf<String>()
                while (cursor.moveToNext()) { check(result.size < limits.evidenceIds); result += cursor.getString(0); fingerprint(cursor.getString(0)) }
                result
            } else emptySet()
            if (hasAdoption) {
                database.query(SimpleSQLiteQuery(
                    "SELECT a.transactionId,a.status,a.archiveFingerprint,a.observationRevision,a.bankId," +
                        (if (hasApprovalRetry) "EXISTS(SELECT 1 FROM beta_financial_approval_retry r WHERE r.evidenceId=a.evidenceId)" else "0") +
                        ",a.evidenceId,CASE WHEN a.evidenceId IN ($rawMoney) OR a.transactionId IN ($moneyLedger) THEN 1 ELSE 0 END FROM beta_financial_adoption a WHERE a.evidenceId IN ($relevantEvidence) OR a.transactionId IN ($relevantLedger) ORDER BY a.evidenceId LIMIT ?",
                    arrayOf(limits.currentRows + 1),
                )).use { cursor ->
                    var count = 0
                    while (cursor.moveToNext()) {
                        if (++count > limits.currentRows) throw BetaDashboardReadLimitExceeded("adoption rows", limits.currentRows)
                        val id = cursor.getString(0); val status = cursor.getString(1)
                        check(id.isNotBlank() && status in setOf("ACTIVE", "REVIEW", "EXCLUDED")) { "Invalid Beta adoption state" }
                        fingerprint(id); fingerprint(status); fingerprint(cursor.getString(2)); fingerprint(cursor.getLong(3).toString())
                        adoptionBindings[id] = Triple(cursor.getString(4), cursor.getString(2), cursor.getLong(3)); fingerprint(cursor.getString(4))
                        fingerprint(cursor.getInt(5).toString())
                        if (status == "EXCLUDED" && cursor.getString(6) in externalEvidence && cursor.getInt(5) == 0) excluded += id
                        if (status != "ACTIVE" || cursor.getInt(5) != 0) { held += id; if(cursor.getInt(7)==1) heldInMoney += id }
                    }
                }
            }
            // A safety hold is not an intentional non-financial dismissal. Neither a durable
            // retry nor a review-only correction may turn an incomplete period into known zero.
            var completeLedger = database.query(SimpleSQLiteQuery(
                "SELECT EXISTS(SELECT 1 FROM beta_correction_current WHERE environment=? AND activity='REVIEW' AND (occurredAtEpochMillis IS NULL OR ${moneyTime("occurredAtEpochMillis")})) " +
                    "OR EXISTS(SELECT 1 FROM beta_evidence_retry WHERE rawId IN ($rawMoney))" +
                    (if (hasApprovalRetry) " OR EXISTS(SELECT 1 FROM beta_financial_approval_retry WHERE evidenceId IN ($rawMoney))" else ""), arrayOf(Environment.BETA.name),
            )).use { it.moveToFirst(); it.getInt(0) == 0 }
            if ((heldInMoney - excluded).isNotEmpty()) completeLedger = false
            fingerprint(completeLedger.toString())
            val unhandledEvidence = linkedSetOf<String>()
            val evidenceOwners = mutableMapOf<String, String>()
            val reviewKeys = linkedSetOf<String>()
            if (hasFacts) database.query(SimpleSQLiteQuery("SELECT transactionId FROM beta_correction_current WHERE environment=? AND activity='REVIEW' AND (occurredAtEpochMillis IS NULL OR ${moneyTime("occurredAtEpochMillis")}) ORDER BY transactionId LIMIT ?",
                arrayOf(Environment.BETA.name, limits.currentRows + 1))).use { cursor ->
                while (cursor.moveToNext()) {
                    if (reviewKeys.size == limits.currentRows) throw BetaDashboardReadLimitExceeded("review identities", limits.currentRows)
                    reviewKeys += cursor.getString(0); fingerprint(cursor.getString(0))
                }
            }
            database.query(SimpleSQLiteQuery(
                "SELECT r.id,c.transactionId,c.correction,c.interpretation,c.transition,c.activity,c.payload,o.attempts,o.updatedAt," +
                    "EXISTS(SELECT 1 FROM beta_evidence_retry retry WHERE retry.rawId=r.id),CASE WHEN r.id IN ($rawMoney) THEN 1 ELSE 0 END " +
                    "FROM beta_raw_evidence r LEFT JOIN beta_correction_evidence_claim x ON x.environment=? AND x.evidenceId=r.id " +
                    "LEFT JOIN beta_correction_current c ON c.environment=x.environment AND c.transactionId=x.transactionId " +
                    "LEFT JOIN beta_evidence_outcome o ON o.rawId=r.id " +
                    "WHERE r.id IN ($relevantEvidence) ORDER BY r.id LIMIT ?",
                arrayOf(Environment.BETA.name, limits.evidenceIds + 1),
            )).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    if (++count > limits.evidenceIds) throw BetaDashboardReadLimitExceeded("evidence identities", limits.evidenceIds)
                    repeat(10) { fingerprint(cursor.optional(it)) }
                    val id = cursor.getString(0)
                    evidenceOwners[id] = cursor.optional(1) ?: "evidence:$id"
                    val correction = cursor.optional(6)?.let(BetaCorrectionCodec::decode)
                    if (correction != null) check(correction.overlay.key == PostingKey(Environment.BETA, cursor.getString(1)) &&
                        correction.overlay.correctionRevision == cursor.getLong(2) && correction.overlay.interpretationRevision == cursor.getLong(3) &&
                        correction.transition == cursor.getLong(4) && correction.activity.name == cursor.getString(5)) { "Inconsistent evidence correction state" }
                    else check(cursor.isNull(5)) { "Missing evidence correction payload" }
                    if (cursor.getInt(10)==1 && correction?.activity != BetaLedgerActivity.SUPPRESSED && "evidence:$id" !in excluded && id !in externalEvidence) unhandledEvidence += id
                    val aux = auxByEvidence[id]
                    if (aux != null && id !in externalEvidence) {
                        // No archive decoding: the write-side binds a completed outcome generation.
                        listOf(aux.evidenceId, aux.bankId, aux.archiveFingerprint, aux.outcomeAttempt.toString(), aux.outcomeAt,
                            aux.nonFinancial.toString(), aux.registryBinding, aux.boundInput, aux.observedAt, aux.balanceAsset,
                            aux.balanceAmount, aux.balanceCurrency, aux.facilityAsset, aux.dueAmount, aux.dueCurrency, aux.dueDate,
                            aux.loanAsset, aux.loanType, aux.loanRemaining, aux.loanRemainingCurrency,
                            aux.loanInstallment, aux.loanInstallmentCurrency, aux.statementProved.toString()).forEach(::fingerprint)
                        if (!cursor.isNull(7) && aux.outcomeAttempt == cursor.getInt(7) && aux.outcomeAt == cursor.getString(8) &&
                            cursor.getInt(9) == 0 && correction?.activity !in setOf(BetaLedgerActivity.REVIEW, BetaLedgerActivity.SUPPRESSED)) {
                            currentAux += id
                            if (aux.nonFinancial && cursor.isNull(1) && "evidence:$id" !in adoptionBindings) {
                                informationalAux += id
                                unhandledEvidence -= id
                            }
                        }
                    }
                }
            }
            val externalReferences = if (schema >= 13) database.query(SimpleSQLiteQuery(
                "SELECT bankId,kind,suffix,status,assetLocalId,revision FROM beta_ownership_decision ORDER BY bankId,kind,suffix LIMIT ?", arrayOf(limits.evidenceIds * 2 + 1))).use { cursor ->
                val result = hashSetOf<com.baraa.masroof.lab.beta.application.BetaOwnershipReference>()
                var count = 0
                while (cursor.moveToNext()) {
                    if (++count > limits.evidenceIds * 2) throw BetaDashboardReadLimitExceeded("ownership decisions", limits.evidenceIds * 2)
                    repeat(6) { fingerprint(cursor.optional(it)) }
                    if (cursor.getString(3) == "EXTERNAL") result += com.baraa.masroof.lab.beta.application.BetaOwnershipReference(BankId(cursor.getString(0)),
                        com.baraa.masroof.lab.beta.application.BetaOwnershipKind.valueOf(cursor.getString(1)), cursor.getString(2))
                }
                result
            } else emptySet()
            // Ownership columns already exist in each normalized registry. Read within this same snapshot.
            val nonOwnedIds=hashSetOf<AssetId>()
            if(schema>=14) database.query(SimpleSQLiteQuery("SELECT environment,bankId,localId,ownershipStatus,metadataRevision FROM beta_account_registry UNION ALL SELECT environment,bankId,localId,ownershipStatus,metadataRevision FROM beta_card_registry UNION ALL SELECT environment,bankId,localId,ownershipStatus,metadataRevision FROM beta_credit_facility UNION ALL SELECT environment,bankId,localId,ownershipStatus,metadataRevision FROM beta_loan_registry ORDER BY bankId,localId LIMIT ?",arrayOf(limits.assets+1))).use {cursor ->
                var count=0
                while(cursor.moveToNext()) {
                    if(++count>limits.assets) throw BetaDashboardReadLimitExceeded("confirmed assets",limits.assets)
                    repeat(5) {fingerprint(cursor.optional(it))}
                    check(cursor.getString(3) in setOf("OWNED","UNKNOWN","EXTERNAL")) {"Invalid asset ownership"}
                    if(cursor.getString(3)!="OWNED") nonOwnedIds+=AssetId(Environment.valueOf(cursor.getString(0)),BankId(cursor.getString(1)),cursor.getString(2))
                }
            }
            val assetMetadata=mutableMapOf<AssetId,com.baraa.masroof.lab.beta.application.BetaAssetMetadata>()
            val assets = com.baraa.masroof.lab.beta.application.ownedBetaAssets(database.query(SimpleSQLiteQuery(
                "SELECT environment,bankId,localId,kind,last4,parentLocalId,creditCardRole,displayName,network,accountType,loanType FROM beta_asset " +
                    "WHERE environment=? ORDER BY bankId,localId LIMIT ?",
                arrayOf(Environment.BETA.name, limits.assets + 1),
            )).use { cursor ->
                val result = mutableListOf<OwnedAsset>()
                while (cursor.moveToNext()) {
                    if (result.size == limits.assets) throw BetaDashboardReadLimitExceeded("confirmed assets", limits.assets)
                    repeat(11) { fingerprint(cursor.optional(it)) }
                    val id = AssetId(Environment.valueOf(cursor.getString(0)), BankId(cursor.getString(1)), cursor.getString(2))
                    assetMetadata[id]=com.baraa.masroof.lab.beta.application.BetaAssetMetadata(cursor.optional(7),cursor.optional(8),cursor.optional(9),cursor.optional(10))
                    result += OwnedAsset(id, AssetKind.valueOf(cursor.getString(3)), cursor.optional(4),
                        cursor.optional(5)?.let { AssetId(id.environment, id.bank, it) }, cursor.optional(6)?.let(CreditCardRole::valueOf))
                }
                result
            }, externalReferences, nonOwnedIds)
            val registry = assets.associateBy { it.id }
            val registryBinding = dashboardRegistryBinding(assets)
            val unavailableObligations = linkedSetOf<AssetId>()
            frozenPeriods.salary?.let { salary ->
                val tailUntil = minOf(salary.until, readAt.plusNanos(1))
                if (tailUntil > query.until) {
                    val facilitiesByBank = assets.filter { it.kind == AssetKind.CREDIT_FACILITY }.groupBy { it.id.bank.value }
                    // All auxiliary rows are already loaded. Include held/tied/tombstone observations;
                    // purchases and inert financial outstanding columns never block statement authority.
                    for (row in auxiliary) {
                        val at = row.observedAt?.let(Instant::parse) ?: continue
                        if (at < query.until || at >= tailUntil) continue
                        if (row.statementProved) {
                            if (row.facilityAsset == null) facilitiesByBank[row.bankId].orEmpty().forEach { unavailableObligations += it.id }
                            else AssetId(Environment.BETA, BankId(row.bankId), row.facilityAsset).let {
                                if (registry[it]?.kind == AssetKind.CREDIT_FACILITY) unavailableObligations += it
                            }
                        }
                        row.loanAsset?.let { AssetId(Environment.BETA, BankId(row.bankId), it) }?.let {
                            if (registry[it]?.kind == AssetKind.LOAN) unavailableObligations += it
                        }
                    }
                }
            }
            val rows = mutableListOf<DashboardLedgerRow>()
            val valuationSources=mutableListOf<DashboardLedgerRow>()
            val foreignSourceIds=configuration?.draft?.commitments.orEmpty().filter {it.amount.currency!=Currency.SAR}.mapNotNull {it.sourceTransactionId}.toSet()
            val postingFacts = mutableListOf<DashboardPostingFacts>()
            val fees = mutableListOf<LinkedInternationalFee>()
            val knownFees = mutableSetOf<PostingKey>()
            data class Observation(val row: BetaDashboardFactsEntity, val eligible: Boolean)
            val observations = mutableListOf<Observation>()
            val activeInputs = mutableMapOf<String, String>()
            var completeMerchantFacts = hasFacts
            database.query(SimpleSQLiteQuery(
                "SELECT l.transactionId,l.correction,l.interpretation,l.payload," +
                    "c.correction,c.interpretation,c.transition,c.activity,c.payload,l.occurredAtEpochMillis," +
                    "CASE WHEN l.transactionId IN ($monetaryLedger) THEN 1 ELSE 0 END AS moneyRelevant " +
                    "FROM beta_financial_transaction l LEFT JOIN beta_correction_current c " +
                    "ON c.environment=l.environment AND c.transactionId=l.transactionId " +
                    "WHERE l.environment=? AND l.transactionId IN ($relevantLedger) ORDER BY l.transactionId LIMIT ?",
                arrayOf(Environment.BETA.name, limits.currentRows + 1),
            )).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    if (++count > limits.currentRows) throw BetaDashboardReadLimitExceeded("current ledger rows", limits.currentRows)
                    repeat(9) { fingerprint(cursor.optional(it)) }
                    val id = cursor.getString(0)
                    val moneyRelevant=cursor.getInt(10)==1
                    val key = PostingKey(Environment.BETA, id)
                    val revision = PostingRevision(cursor.getLong(1), cursor.getLong(2))
                    val correction = cursor.optional(8)?.let(BetaCorrectionCodec::decode)
                    if (correction != null) {
                        check(correction.overlay.key == key && correction.overlay.correctionRevision == cursor.getLong(4) &&
                            correction.overlay.interpretationRevision == cursor.getLong(5) && correction.transition == cursor.getLong(6) &&
                            correction.activity.name == cursor.getString(7)) { "Inconsistent correction current row" }
                    } else check(cursor.isNull(7)) { "Missing correction payload" }
                    val persisted = persistedFacts[id]
                    if (persisted != null) {
                        listOf(persisted.evidenceId, persisted.transactionId, persisted.bankId, persisted.archiveFingerprint, persisted.observationRevision.toString(),
                            persisted.correction.toString(), persisted.interpretation.toString(), persisted.boundInput, persisted.observedAt,
                            persisted.feeKnown.toString(), persisted.feeAmount, persisted.feeCurrency, persisted.balanceAsset, persisted.balanceAmount,
                            persisted.balanceCurrency, persisted.facilityAsset, persisted.dueAmount, persisted.dueCurrency, persisted.dueDate,
                            persisted.merchant, persisted.counterparty, persisted.debitCardAsset, persisted.debitCardLast4, persisted.debitCardParent,
                            persisted.messageFamily).forEach(::fingerprint)
                    }
                    if (id in excluded) continue // Explicitly external evidence has no current financial effect.
                    val active = id !in held && (correction == null || correction.activity == BetaLedgerActivity.ACTIVE)
                    if ((id in heldInMoney || correction?.activity == BetaLedgerActivity.REVIEW) && (cursor.isNull(9) || (cursor.getLong(9)>=from && cursor.getLong(9)<until))) { completeLedger = false; reviewKeys += id }
                    if (!active && persisted != null) observations += Observation(persisted, false)
                    if (!active) continue // Audit snapshots are never decoded/projected as current financial rows.
                    if (correction != null) check(revision == PostingRevision(correction.overlay.correctionRevision,
                        correction.overlay.interpretationRevision)) { "Active correction and ledger revisions differ" }
                    val posting = BetaLedgerCodec.decode(cursor.getString(3))
                    check(posting.command.key == key && posting.command.revision == revision) { "Inconsistent financial current row" }
                    // Registry edits require explicit financial reconsideration. A stale frozen
                    // ownership snapshot is unavailable, not a crash or a silent zero balance.
                    if ((listOf(posting.classification.input) + posting.observations.map { it.input }).any { input ->
                            !matchesCurrentRegistry(input, registry)
                        }) { if(moneyRelevant) {completeLedger = false; reviewKeys += id}; if (persisted != null) observations += Observation(persisted, false); continue }
                    unhandledEvidence.removeAll(posting.command.evidenceIds)
                    posting.command.evidenceIds.forEach { activeInputs[it] = BetaLedgerCodec.encodeInput(posting.classification.input) }
                    val fact = persisted?.takeIf { row ->
                        row.correction == revision.correction && row.interpretation == revision.interpretation &&
                            BetaLedgerCodec.decodeInput(row.boundInput) == posting.classification.input &&
                            adoptionBindings[id] == Triple(row.bankId, row.archiveFingerprint, row.observationRevision) && row.evidenceId in posting.command.evidenceIds
                    }
                    if (persisted != null) observations += Observation(persisted, fact != null)
                    val provedDebit = fact?.debitCardAsset?.let { local -> registry[AssetId(Environment.BETA,
                        BankId(fact.bankId), local)] }?.takeIf { card ->
                        card.kind == AssetKind.DEBIT_CARD && card.last4 == fact.debitCardLast4 && card.parentId?.localId == fact.debitCardParent
                    }?.id
                    if(id in foreignSourceIds) valuationSources+=DashboardLedgerRow(posting,true)
                    if (fact?.feeKnown == true && ((fact.feeAmount == null && fact.feeCurrency == null) ||
                        (fact.feeAmount != null && fact.feeCurrency == Currency.SAR.name))) {
                        knownFees += key
                        if (fact.feeAmount != null && fact.feeCurrency != null) fees += LinkedInternationalFee(key, revision,
                            fact.evidenceId, Money.of(fact.feeAmount, Currency.valueOf(fact.feeCurrency)))
                    }
                    val scoped = windows.any { posting.command.time.instant >= it.from && posting.command.time.instant < it.until }
                    val possibleSettlement = posting.classification.effectiveType == FinancialType.CREDIT_CARD_PAYMENT && posting.command.time.instant < paymentCutoff
                    if (!scoped && !possibleSettlement) continue
                    rows += DashboardLedgerRow(posting, true)
                    val metadata = correction?.overlay?.metadata.orEmpty()
                    if (moneyRelevant && posting.classification.effectiveType in setOf(FinancialType.EXPENSE, FinancialType.REFUND) &&
                        fact == null && "merchant" !in metadata) completeMerchantFacts = false
                    if (fact != null || metadata.keys.any { it == "merchant" || it == "counterparty" }) postingFacts += DashboardPostingFacts(
                        key, revision, posting.command.evidenceIds.sorted().first(), metadata["merchant"] ?: fact?.merchant,
                        metadata["counterparty"] ?: fact?.counterparty, debitCard = provedDebit,
                        messageFamily = fact?.messageFamily?.let(DashboardEvidenceFamily::valueOf),
                    )

                }
            }
            if (unhandledEvidence.isNotEmpty()) completeLedger = false
            // One unresolved identity per stable financial transaction; overlapping retries,
            // adoption holds and correction holds do not multiply the global review count.
            reviewKeys += unhandledEvidence.map { evidenceOwners.getValue(it) }
            reviewKeys += (heldInMoney - excluded)
            val reviewCount = if (hasFacts && unhandledEvidence.all { it in projectedEvidence }) reviewKeys.size else null
            val assetFacts = mutableListOf<DashboardAssetFacts>()
            val observed = observations.filter { Instant.parse(it.row.observedAt).let { at -> at <= readAt && at < query.until } }
            data class AuxObservation(val row: BetaDashboardAuxFactsEntity, val eligible: Boolean)
            val auxObserved = auxiliary.filter { row -> row.observedAt?.let(Instant::parse)?.let { it <= readAt && it < query.until } == true }.map { row ->
                AuxObservation(row, row.evidenceId in currentAux && row.registryBinding == registryBinding &&
                    (if (row.nonFinancial) row.evidenceId in informationalAux else row.boundInput != null && activeInputs[row.evidenceId] == row.boundInput))
            }
            // A newer held/suppressed observation blocks fallback to an older apparently valid
            // value. Same-time conflicting observations remain unknown rather than arbitrary.
            for (asset in assets) {
                fun latest(selector: (BetaDashboardFactsEntity) -> String?): Observation? {
                    val candidates = observed.filter { selector(it.row) == asset.id.localId &&
                        it.row.bankId == asset.id.bank.value }
                    val at = candidates.maxOfOrNull { Instant.parse(it.row.observedAt) } ?: return null
                    return candidates.filter { Instant.parse(it.row.observedAt) == at }.let { same ->
                        same.singleOrNull()?.takeIf { it.eligible }
                    }
                }
                val balance = latest { it.balanceAsset }?.row
                fun auxLatest(selector: (BetaDashboardAuxFactsEntity) -> String?): AuxObservation? {
                    val candidates = auxObserved.filter { it.row.bankId == asset.id.bank.value && selector(it.row) == asset.id.localId }
                    val at = candidates.maxOfOrNull { Instant.parse(it.row.observedAt) } ?: return null
                    return candidates.filter { Instant.parse(it.row.observedAt) == at }.singleOrNull()?.takeIf { it.eligible }
                }
                // Compare all candidates, including invalidated tombstones, before choosing a source.
                fun auxWins(selector: (BetaDashboardAuxFactsEntity) -> String?, old: (BetaDashboardFactsEntity) -> String?): Boolean? {
                    val a = auxObserved.filter { it.row.bankId == asset.id.bank.value && selector(it.row) == asset.id.localId }
                        .maxOfOrNull { Instant.parse(it.row.observedAt) }
                    val b = observed.filter { it.row.bankId == asset.id.bank.value && old(it.row) == asset.id.localId }
                        .maxOfOrNull { Instant.parse(it.row.observedAt) }
                    return when { a == null -> false; b == null -> true; a == b -> null; else -> a > b }
                }
                val auxBalance = auxLatest { it.balanceAsset }?.row
                val auxDue = auxLatest { if (it.statementProved) it.facilityAsset else null }?.row
                val loan = auxLatest { it.loanAsset }?.row
                val balanceFact = when (auxWins({ it.balanceAsset }, { it.balanceAsset })) {
                    true -> auxBalance?.takeIf { it.balanceAmount != null && it.balanceCurrency == Currency.SAR.name }?.let {
                        ObservedSignedBalance(SignedAmount(it.balanceAmount!!.toBigDecimal(), Currency.SAR), Instant.parse(it.observedAt)) }
                    false -> balance?.takeIf { it.balanceAmount != null && it.balanceCurrency == Currency.SAR.name }?.let {
                        ObservedSignedBalance(SignedAmount(it.balanceAmount!!.toBigDecimal(), Currency.valueOf(it.balanceCurrency!!)), Instant.parse(it.observedAt)) }
                    null -> null
                }
                // Only a proved informational STATEMENT supplies dues. Inert v8 financial
                // outstanding columns (even newer or held purchases) cannot supply or block one.
                val statementFact = auxDue?.takeIf { it.dueAmount != null && it.dueCurrency == Currency.SAR.name }?.let {
                        ObservedStatement(Money.of(it.dueAmount!!, Currency.SAR), Instant.parse(it.observedAt), it.dueDate?.let(LocalDate::parse)) }
                val meta=assetMetadata[asset.id]
                if (balanceFact != null || statementFact != null || loan != null || meta?.displayName!=null || meta?.network!=null || meta?.accountType!=null || meta?.loanType!=null) assetFacts += DashboardAssetFacts(asset.id,
                    label=meta?.displayName,network=meta?.network,
                    accountCategory=if(asset.kind==AssetKind.ACCOUNT) AccountCategory.entries.firstOrNull {it.name==meta?.accountType} ?: if(meta?.accountType==null) AccountCategory.CURRENT else AccountCategory.OTHER else null,
                    balance = balanceFact, statement = statementFact, loanType = loan?.loanType ?: meta?.loanType.takeIf {asset.kind==AssetKind.LOAN},
                    loanRemaining = loan?.takeIf { it.loanRemaining != null && it.loanRemainingCurrency == Currency.SAR.name }?.let {
                        ObservedMoney(Money.of(it.loanRemaining!!, Currency.SAR), Instant.parse(it.observedAt)) },
                    loanInstallment = loan?.takeIf { it.loanInstallment != null && it.loanInstallmentCurrency == Currency.SAR.name }?.let {
                        ObservedMoney(Money.of(it.loanInstallment!!, Currency.SAR), Instant.parse(it.observedAt)) })
            }
            val settlementPeriods = assetFacts.mapNotNull { fact -> fact.statement?.takeIf { it.issuedAt < paymentCutoff }?.let {
                fact.id to DisplayPeriod(it.issuedAt, paymentCutoff, query.zone, PeriodKind.SELECTED, null)
            } }.toMap()
            val valuedCommitments=com.baraa.masroof.lab.beta.application.dashboard.BetaCommitmentValuationCalculator.resolve(
                configuration?.draft ?: BetaDashboardConfigurationDraft(),valuationSources,fees,knownFees,frozenPeriods.salary)
            rows.removeAll { row ->
                val at = row.posting.command.time.instant
                windows.none { at >= it.from && at < it.until } && settlementPeriods.none { (facility, window) ->
                    val destination = (row.posting.classification.input.destination as? FinancialEndpoint.Owned)?.id
                    row.posting.classification.effectiveType == FinancialType.CREDIT_CARD_PAYMENT && at >= window.from && at < window.until &&
                        (destination == facility || registry[destination]?.parentId == facility)
                }
            }
            val includedKeys = rows.mapTo(hashSetOf()) { it.posting.command.key }
            postingFacts.removeAll { it.key !in includedKeys }; fees.removeAll { it.key !in includedKeys }
            val occurrences = com.baraa.masroof.lab.beta.application.dashboard.BetaCommitmentOccurrenceCalculator.rows(
                valuedCommitments.draft, frozenPeriods.salary,
                com.baraa.masroof.lab.beta.application.dashboard.BetaCommitmentPaymentCalculator.payments(rows,postingFacts,fees,knownFees))
            val token = digest.digest().joinToString("") { "%02x".format(it) }
            DashboardReadBatch(query, ReadRevision("beta-current-v2:$token", readAt),
                Collections.unmodifiableList(rows), Collections.unmodifiableList(assets),
                DashboardCoverage(completeLedger = completeLedger, completeRegistry = true, completeFeeFacts = hasFacts && rows.all { it.posting.command.key in knownFees },
                    reviewRequiredCount = reviewCount, atomicRuntimeSource = true, completeMerchantFacts = completeMerchantFacts, completeUserCommitments = configuration?.revision?.let { it > 0 } == true && valuedCommitments.complete),
                frozenPeriods, assetFacts = Collections.unmodifiableList(assetFacts), postingFacts = Collections.unmodifiableList(postingFacts),
                fees = Collections.unmodifiableList(fees), commitmentOccurrences = Collections.unmodifiableList(occurrences),
                settlementPeriods = Collections.unmodifiableMap(settlementPeriods),
                feeProofKeys = Collections.unmodifiableSet(knownFees.filterTo(linkedSetOf()) { it in includedKeys }),
                unavailableObligationAssets = Collections.unmodifiableSet(unavailableObligations))
        }
    }
}

private fun Cursor.optional(index: Int): String? = if (isNull(index)) null else getString(index)

private fun matchesCurrentRegistry(input: FinancialClassificationInput, registry: Map<AssetId, OwnedAsset>): Boolean {
    val endpoints = listOfNotNull((input.source as? FinancialEndpoint.Owned)?.id,
        (input.destination as? FinancialEndpoint.Owned)?.id, input.financingLoan).toSet()
    val dependencies = endpoints + endpoints.mapNotNull { input.assets[it]?.parentId }
    return dependencies.all { id -> input.assets[id]?.let { it.id == id && registry[id] == it } == true }
}
