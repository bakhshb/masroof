package com.baraa.masroof.lab.beta.data

import androidx.room.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.Clock
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections

/** One bounded configuration snapshot participates in the same database transaction as the ledger. */
internal data class BetaDashboardConfigurationEntity(val id: Int,val revision: Long,val payload: String)
@Dao internal interface BetaDashboardConfigurationDao : BetaAuditWriter {
    @Query("SELECT 1 AS id,transition AS revision,payload FROM beta_audit_log WHERE eventKind='FINANCIAL_CONFIGURATION' ORDER BY transition DESC LIMIT 1")
    suspend fun latest(): List<BetaDashboardConfigurationEntity>
    @Query("SELECT * FROM beta_commitment ORDER BY id") suspend fun commitments(): List<BetaCommitmentRecord>
    @Query("DELETE FROM beta_commitment") suspend fun clearCommitments()
    @Insert suspend fun saveCommitments(rows: List<BetaCommitmentRecord>)
    suspend fun all(): List<BetaDashboardConfigurationEntity> = latest().map { row ->
        val j=JSONObject(row.payload)
        j.put("commitments",JSONArray().also { array -> commitments().forEach { c ->
            array.put(BetaCommitmentCodec.json(c))
        } })
        row.copy(payload=j.toString())
    }
    @Query("SELECT a.targetId AS id,c.label AS name,a.operation AS event,a.createdAt AS at,a.transition AS revision FROM beta_audit_log a JOIN beta_commitment c ON c.id=a.targetId WHERE a.eventKind='COMMITMENT_LIFECYCLE' ORDER BY a.createdAt DESC,a.id DESC LIMIT 10001")
    suspend fun history(): List<BetaCommitmentHistoryRow>
    suspend fun put(row: BetaDashboardConfigurationEntity,at: String=Instant.now().toString()) {
        val j=JSONObject(row.payload); val array=j.getJSONArray("commitments")
        // Legacy payloads preserve registry metadata; current payloads explicitly own all user fields.
        val existing=commitments().associateBy {it.id}
        val commitments=(0 until array.length()).map { index -> val c=array.getJSONObject(index)
            BetaCommitmentCodec.record(c,existing[c.getString("id")]) }
        clearCommitments(); saveCommitments(commitments)
        commitments.forEach {next ->
            val previous=existing[next.id]
            val event=when {previous==null->"CREATED";previous.active && !next.active->"PAUSED";!previous.active && next.active->"RESUMED";else->null}
            val eventAt=when(event) {
                "PAUSED"->next.pauseIntervals.let {org.json.JSONArray(it)}.let {pauses ->
                    if(pauses.length()>0) pauses.getJSONObject(pauses.length()-1).getString("pausedAt") else at}
                "RESUMED"->next.pauseIntervals.let {org.json.JSONArray(it)}.let {pauses ->
                    if(pauses.length()>0) pauses.getJSONObject(pauses.length()-1).optString("resumedAt").takeUnless {it.isBlank() || it=="null"} ?: at else at}
                else->at
            }
            if(event!=null) appendAudit(BetaAuditRecord(auditId("COMMITMENT_LIFECYCLE",row.revision)+":"+next.id,
                "COMMITMENT_LIFECYCLE",targetId=next.id,transition=row.revision,operation=event,createdAt=eventAt))
        }
        j.remove("commitments")
        appendAudit(BetaAuditRecord(auditId("FINANCIAL_CONFIGURATION",row.revision),"FINANCIAL_CONFIGURATION",targetId="periods",transition=row.revision,payload=j.toString()))
    }
}

internal data class BetaCommitmentHistoryRow(val id: String,val name: String,val event: String,val at: String,val revision: Long)
internal class RoomBetaDashboardConfiguration(private val database: BetaDatabase,private val clock: Clock=Clock.systemUTC()) : BetaDashboardControls,BetaCommitmentHistorySource {
    override suspend fun commitmentHistory(): List<BetaCommitmentHistoryEntry> = database.withTransaction {
        val rows=database.dashboardConfigurationDao().history()
        require(rows.size<=10000) {"Commitment history exceeds the bounded read"}
        rows.sortedWith(compareByDescending<BetaCommitmentHistoryRow> {Instant.parse(it.at)}.thenByDescending {it.revision}.thenBy {it.id}).map {BetaCommitmentHistoryEntry(it.id,it.name,BetaCommitmentRecordEvent.valueOf(it.event),Instant.parse(it.at))}
    }
    override suspend fun read(): BetaDashboardConfiguration = database.withTransaction { current() }

    internal suspend fun current(): BetaDashboardConfiguration {
        val rows = database.dashboardConfigurationDao().all()
        require(rows.size <= 1 && rows.all { it.id == 1 && it.revision > 0 && it.payload.length <= MAX_PAYLOAD && it.payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD }) { "Invalid Beta dashboard configuration" }
        return rows.singleOrNull()?.let { BetaDashboardConfiguration(it.revision, decode(it.payload)) }
            ?: BetaDashboardConfiguration(0, BetaDashboardConfigurationDraft())
    }

    override suspend fun save(expectedRevision: Long, draft: BetaDashboardConfigurationDraft): BetaDashboardConfigurationWrite = database.withTransaction {
        val current = current()
        if (current.revision != expectedRevision) return@withTransaction BetaDashboardConfigurationWrite.Conflict(current)
        val payload = try {
            validate(draft)
            val ids = draft.statements.map { it.facilityId }.toSet()
            // One registry read, no per-facility lookup. Registry edits cannot manufacture authority.
            if (ids.isNotEmpty()) require(BetaAssetRegistry(database).all().filter { it.kind == AssetKind.CREDIT_FACILITY }.map { it.id }.containsAll(ids)) {
                "A configured statement facility is no longer confirmed"
            }
            encode(draft).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD) { "Configuration exceeds its bounded payload" } }
        } catch (invalid: IllegalArgumentException) {
            return@withTransaction BetaDashboardConfigurationWrite.Invalid(invalid.message ?: "Invalid configuration")
        }
        if (current.revision > 0 && current.draft == decode(payload)) return@withTransaction BetaDashboardConfigurationWrite.Saved(current)
        check(current.revision < Long.MAX_VALUE) { "Configuration revision exhausted" }
        val next = BetaDashboardConfigurationEntity(1, current.revision + 1, payload)
        database.dashboardConfigurationDao().put(next,clock.instant().toString())
        BetaDashboardConfigurationWrite.Saved(BetaDashboardConfiguration(next.revision, decode(payload)))
    }

    companion object {
        const val MAX_PAYLOAD = 262_144
        private fun validate(draft: BetaDashboardConfigurationDraft) {
            require(draft.salary == null || (draft.salary.day in 1..31 && draft.salary.zone == ZoneId.of("Asia/Riyadh"))) {
                "Salary day must be 1–31 and zone Asia/Riyadh"
            }
            require(draft.statements.size <= 100 && draft.statements.map { it.facilityId }.distinct().size == draft.statements.size) { "Duplicate or excessive facilities" }
            require(draft.statements.all { it.facilityId.environment == Environment.BETA && it.closingDay in 1..31 }) { "Invalid statement configuration" }
            require(draft.commitments.size <= 100 && draft.commitments.map { it.id }.distinct().size == draft.commitments.size) { "Duplicate or excessive commitments" }
            require(draft.commitments.mapNotNull {it.sourceTransactionId}.distinct().size==draft.commitments.mapNotNull {it.sourceTransactionId}.size) {"Duplicate commitment source"}
            for (item in draft.commitments) {
                require(item.transactionDate==null || item.transactionDate.year in 1900..2200)
                require(item.dueDate==null || item.dueDate.year in 1900..2200)
                require(item.sourceTransactionId==null || (item.sourceTransactionId.isNotBlank() && item.sourceTransactionId.length<=1000))
                require(item.pauseIntervals.size<=2400)
                item.pauseIntervals.forEachIndexed {index,pause ->
                    require(pause.resumedAt==null || pause.resumedAt>=pause.pausedAt)
                    if(index>0) require(item.pauseIntervals[index-1].resumedAt?.let {it<=pause.pausedAt}==true)
                }
                require(!item.active || item.pauseIntervals.none {it.resumedAt==null})
                require(item.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && item.label.isNotBlank() && item.label.length <= 120) { "Invalid commitment identity or label" }
                require((item.amount.currency == Currency.SAR || item.sourceTransactionId!=null) && item.amount.amount.signum() > 0 && item.amount.amount.precision() <= 20 && item.amount.amount.scale() <= 2) { "Commitment must have a positive amount with at most two decimals and a source for foreign currency" }
                require(item.dueDay in 1..31 && item.starts.year in 1900..2200 && (item.ends == null || (item.ends >= item.starts && item.ends.year in 1900..2200))) { "Invalid commitment date range" }
                require(item.paidMonths.size <= 1200 && item.paidMonths.all { it >= item.starts && it.year in 1900..2200 && (item.ends == null || it <= item.ends) }) { "Invalid paid occurrences" }
            }
        }
        private fun encode(draft: BetaDashboardConfigurationDraft): String = JSONObject().apply {
            put("version", 1)
            put("salary", draft.salary?.let { JSONObject().put("day", it.day).put("zone", it.zone.id) } ?: JSONObject.NULL)
            put("statements", JSONArray().also { array -> draft.statements.sortedWith(compareBy({ it.facilityId.bank.value }, { it.facilityId.localId })).forEach {
                array.put(JSONObject().put("bank", it.facilityId.bank.value).put("id", it.facilityId.localId).put("day", it.closingDay))
            } })
            put("commitments", JSONArray().also { array -> draft.commitments.sortedBy { it.id }.forEach {
                array.put(BetaCommitmentCodec.json(it))
            } })
        }.toString()

        private fun decode(payload: String): BetaDashboardConfigurationDraft {
            require(payload.length <= MAX_PAYLOAD && payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD)
            val json = JSONObject(payload)
            require(json.getInt("version") == 1) { "Unknown configuration version" }
            val salary = if (json.isNull("salary")) null else json.getJSONObject("salary").let { BetaSalaryCycle(it.getInt("day"), ZoneId.of(it.getString("zone"))) }
            val statements = json.getJSONArray("statements").let { array -> require(array.length() <= 100); (0 until array.length()).map { index ->
                array.getJSONObject(index).let { BetaFacilityCycle(AssetId(Environment.BETA, BankId(it.getString("bank")), it.getString("id")), it.getInt("day")) }
            } }
            val commitments = json.getJSONArray("commitments").let { array -> require(array.length() <= 100); (0 until array.length()).map { index ->
                BetaCommitmentCodec.decode(array.getJSONObject(index))
            } }
            return BetaDashboardConfigurationDraft(salary, Collections.unmodifiableList(statements), Collections.unmodifiableList(commitments)).also(::validate)
        }
    }
}
