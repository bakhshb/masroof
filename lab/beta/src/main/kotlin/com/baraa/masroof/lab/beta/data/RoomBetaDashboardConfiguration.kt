package com.baraa.masroof.lab.beta.data

import androidx.room.*
import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.value.*
import org.json.JSONArray
import org.json.JSONObject
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
            array.put(JSONObject().put("id",c.id).put("label",c.label).put("amount",c.amountDecimal).put("currency",c.amountCurrency)
                .put("day",c.dueDay).put("starts",c.starts).put("ends",c.ends ?: JSONObject.NULL).put("paid",JSONArray(c.paidMonths)))
        } })
        row.copy(payload=j.toString())
    }
    suspend fun put(row: BetaDashboardConfigurationEntity) {
        val j=JSONObject(row.payload); val array=j.getJSONArray("commitments")
        // This facade owns cycle/amount/date settings, not registry tracking or provenance.
        // Preserve those normalized facts when replacing its editable projection.
        val existing=commitments().associateBy {it.id}
        val commitments=(0 until array.length()).map { index -> val c=array.getJSONObject(index)
            BetaCommitmentRecord(c.getString("id"),c.getString("label"),c.getString("amount"),c.optString("currency","SAR"),
                c.getInt("day"),c.getString("starts"),if(c.isNull("ends")) null else c.getString("ends"),paidMonths=c.getJSONArray("paid").toString()).let {changed ->
                    existing[changed.id]?.let {previous -> changed.copy(active=previous.active,
                        pauseIntervals=previous.pauseIntervals,sourceTransactionId=previous.sourceTransactionId)} ?: changed
                } }
        clearCommitments(); saveCommitments(commitments)
        j.remove("commitments")
        appendAudit(BetaAuditRecord(auditId("FINANCIAL_CONFIGURATION",row.revision),"FINANCIAL_CONFIGURATION",targetId="periods",transition=row.revision,payload=j.toString()))
    }
}

internal class RoomBetaDashboardConfiguration(private val database: BetaDatabase) : BetaDashboardControls {
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
        database.dashboardConfigurationDao().put(next)
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
            for (item in draft.commitments) {
                require(item.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && item.label.isNotBlank() && item.label.length <= 120) { "Invalid commitment identity or label" }
                require(item.amount.currency == Currency.SAR && item.amount.amount.signum() > 0 && item.amount.amount.precision() <= 20 && item.amount.amount.scale() <= 2) { "Commitment must be a positive SAR amount with at most two decimals" }
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
                array.put(JSONObject().put("id", it.id).put("label", it.label).put("amount", it.amount.amount.toPlainString())
                    .put("day", it.dueDay).put("starts", it.starts.toString()).put("ends", it.ends?.toString() ?: JSONObject.NULL)
                    .put("paid", JSONArray(it.paidMonths.sorted().map(YearMonth::toString))))
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
                val item = array.getJSONObject(index)
                val paid = item.getJSONArray("paid").let { values -> require(values.length() <= 1200); (0 until values.length()).map { YearMonth.parse(values.getString(it)) }.toSet() }
                BetaRecurringCommitment(item.getString("id"), item.getString("label"), Money.of(item.getString("amount"), Currency.SAR), item.getInt("day"),
                    YearMonth.parse(item.getString("starts")), if (item.isNull("ends")) null else YearMonth.parse(item.getString("ends")), Collections.unmodifiableSet(paid))
            } }
            return BetaDashboardConfigurationDraft(salary, Collections.unmodifiableList(statements), Collections.unmodifiableList(commitments)).also(::validate)
        }
    }
}
