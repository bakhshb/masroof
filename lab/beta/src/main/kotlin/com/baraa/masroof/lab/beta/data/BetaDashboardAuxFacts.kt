package com.baraa.masroof.lab.beta.data

import androidx.room.*
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.security.MessageDigest
import java.time.*
import org.json.JSONObject

/** Non-financial disposition is separate from approval. Only typed observation fields are read. */
@Entity(tableName = "beta_dashboard_aux_facts", foreignKeys = [ForeignKey(entity = BetaRawEvidenceEntity::class,
    parentColumns = ["id"], childColumns = ["evidenceId"], onDelete = ForeignKey.RESTRICT)])
internal data class BetaDashboardAuxFactsEntity(
    @PrimaryKey val evidenceId: String, val bankId: String, val archiveFingerprint: String,
    val outcomeAttempt: Int, val outcomeAt: String, val nonFinancial: Boolean,
    val registryBinding: String, val boundInput: String?, val observedAt: String?,
    val balanceAsset: String?, val balanceAmount: String?, val balanceCurrency: String?,
    val facilityAsset: String?, val dueAmount: String?, val dueCurrency: String?, val dueDate: String?,
    val loanAsset: String?, val loanType: String?, val loanRemaining: String?, val loanRemainingCurrency: String?,
    val loanInstallment: String?, val loanInstallmentCurrency: String?,
    val statementProved: Boolean = false,
) { override fun toString() = "BetaDashboardAuxFactsEntity(redacted)" }

@Dao internal interface BetaDashboardAuxFactsDao {
    @Upsert suspend fun put(row: BetaDashboardAuxFactsEntity)
    @Query("SELECT * FROM beta_dashboard_aux_facts WHERE evidenceId=:id") suspend fun evidence(id: String): BetaDashboardAuxFactsEntity?
    @Query("SELECT f.* FROM beta_dashboard_aux_facts f ORDER BY f.evidenceId LIMIT :limit")
    suspend fun all(limit: Int): List<BetaDashboardAuxFactsEntity>
}

/** Same immutable registry fields as dashboard financial validation, with length-framed hashing. */
internal fun dashboardRegistryBinding(assets: Collection<OwnedAsset>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    assets.sortedWith(compareBy({ it.id.bank.value }, { it.id.localId })).forEach { asset ->
        listOf(asset.id.environment.name, asset.id.bank.value, asset.id.localId, asset.kind.name, asset.last4,
            asset.parentId?.localId, asset.creditCardRole?.name).forEach { field ->
            val bytes = field?.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
            if (bytes != null) digest.update(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Write side only: the same explicit archive reading as financial adoption. Acknowledgement is not authority. */
internal class RoomBetaDashboardAuxFacts(private val database: BetaDatabase) {
    suspend fun refresh(id: String): Boolean {
        check(database.inTransaction())
        val outcome = database.evidenceDao().outcome(id) ?: return false
        if (database.evidenceDao().retry(id) != null) return false
        val json = try { BetaFinancialArchive.json(outcome) } catch (_: Exception) { return false }
        val route = try { JSONObject(outcome.route) } catch (_: org.json.JSONException) { return false }
        if (json.optString("evidenceId") != id || json.optString("bank") != "BANK_ALJAZIRA" ||
            route.optString("kind") != "MATCHED") return false
        val candidates = route.optJSONArray("candidates") ?: return false
        if (candidates.length() != 1 || candidates.optString(0) != json.getString("bank")) return false
        val blockers = json.optJSONArray("blockers") ?: return false
        if (blockers.length() != 0) return false
        val diagnostics = json.optJSONArray("diagnostics") ?: return false
        if ((0 until diagnostics.length()).any { diagnostics.getJSONObject(it).let { d ->
            d.getString("severity") == "ERROR" || d.getString("code").contains("CONFLICT") } }) return false
        val f = try { BetaFinancialArchive.facts(database, outcome) } catch (_: Exception) { return false }
        fun text(name: String): String? = if (f.isNull(name)) null else f.getString(name)
        fun money(name: String): Money? = if (f.isNull(name)) null else f.getJSONObject(name).let {
            Money.of(it.getString("amount"), Currency.valueOf(it.getString("currency"))) }
        val family = text("family")
        val informational = json.optString("status") == "NON_FINANCIAL" && family in setOf("OTP", "NON_FINANCIAL", "BALANCE_NOTICE")
        val registry = BetaAssetRegistry(database).all().associateBy { it.id }
        var boundInput: String? = null
        var loan: OwnedAsset? = null
        var remaining: Money? = null
        var installment: Money? = null
        if (!informational) {
            if (json.optString("status") != "SUCCESS" || family != "FINANCING_INSTALLMENT") return false
            val adopted = database.financialAdoptionDao().evidence(id) ?: return false
            if (adopted.status != "ACTIVE" || adopted.archiveFingerprint != BetaFinancialArchive.fingerprint(database, outcome)) return false
            val key = RoomBetaFinancialAdoption.key(id)
            val correction = readBetaCorrectionState(database, key)
            if (correction != null && correction.activity != com.baraa.masroof.lab.beta.application.corrections.BetaLedgerActivity.ACTIVE) return false
            val posting = RoomBetaFinancialLedger(database).readAudit(key) ?: return false
            val input = posting.classification.input
            val baseline = RoomBetaFinancialAdoption(database).observation(input.key)?.input ?: return false
            if (input.amount != baseline.amount || input.time != baseline.time || posting.matchIdentity != null || input.evidenceIds != setOf(id) ||
                financialRegistryDependencies(input).any { input.assets[it] != registry[it] }) return false
            if (posting.classification.effectiveType != FinancialType.LOAN_REPAYMENT) return false
            val loanId = input.financingLoan ?: (input.destination as? FinancialEndpoint.Owned)?.id
            loan = registry[loanId]?.takeIf { it.kind == AssetKind.LOAN && it.id.bank.value == json.getString("bank") } ?: return false
            if (text("loanType") !in setOf("PERSONAL", "AUTO", "MORTGAGE")) return false
            boundInput = BetaLedgerCodec.encodeInput(input)
            remaining = money("outstandingBalance")
            installment = money("amount")
        }
        val bank = json.getString("bank")
        fun reference(name: String): String? {
            if (f.isNull(name)) return null
            val ref = f.getJSONObject(name)
            if (!ref.isNull("bank") && ref.getString("bank") != bank) return ""
            val number = ref.optString("number").trim()
            return if (number.matches(Regex("[0-9*•xX\\s-]*[0-9]{4}"))) number.takeLast(4) else ""
        }
        val cardSuffix = reference("card")
        val accountSuffix = reference("sourceAccount")
        val extraAccount = text("debitSourceAccountLast4")
        val channel = text("cardChannel")
        val card = if (channel in setOf("CREDIT", "STATEMENT") && !cardSuffix.isNullOrEmpty() && accountSuffix != "" &&
            (extraAccount == null || extraAccount.matches(Regex("[0-9]{4}"))) &&
            (extraAccount == null || accountSuffix == null || extraAccount == accountSuffix)) registry.values.singleOrNull {
            it.id.bank.value == bank && it.kind == AssetKind.CREDIT_CARD && it.last4 == cardSuffix
        } else null
        val facility = if (informational && family != "OTP" && channel == "STATEMENT") card?.parentId?.let(registry::get) else null
        val candidateAccount = if (channel !in setOf("CREDIT", "STATEMENT") && !accountSuffix.isNullOrEmpty() &&
            (extraAccount == null || extraAccount == accountSuffix) && (cardSuffix == null || cardSuffix.isNotEmpty())) registry.values.singleOrNull {
            it.id.bank.value == bank && it.kind == AssetKind.ACCOUNT && it.last4 == accountSuffix
        } else null
        val account = candidateAccount?.takeIf { candidate -> cardSuffix == null || registry.values.count {
            it.kind == AssetKind.DEBIT_CARD && it.id.bank == candidate.id.bank && it.last4 == cardSuffix && it.parentId == candidate.id
        } == 1 }
        // Keep the proved account target as a tombstone on a contradictory card-parent reference.
        val balanceAsset = if (!informational || family == "OTP") null else card ?: candidateAccount
        val timestamp = if (family == "OTP") null else text("occurredAtLocal")?.let {
            LocalDateTime.parse(it).atZone(ZoneId.of("Asia/Riyadh")).toInstant()
        } ?: text("occurredAt")?.let(Instant::parse) ?: database.evidenceDao().raw(id)?.receivedAt?.let(Instant::parse)
        val balance = if (card != null || account != null) balanceAsset?.let { money("availableBalance") } else null
        val due = if (family == "OTP") null else facility?.let { money("outstandingBalance") }
        val dueDate = text("paymentDueDate")?.let { raw ->
            try { LocalDate.parse(raw).toString() } catch (_: java.time.format.DateTimeParseException) { return false }
        }
        database.dashboardAuxFactsDao().put(BetaDashboardAuxFactsEntity(id, bank, BetaFinancialArchive.fingerprint(database, outcome),
            outcome.attempts, outcome.updatedAt, informational, dashboardRegistryBinding(registry.values), boundInput, timestamp?.toString(),
            balanceAsset?.id?.localId, balance?.amount?.toPlainString(), balance?.currency?.name,
            facility?.id?.localId, due?.amount?.toPlainString(), due?.currency?.name, dueDate,
            loan?.id?.localId, if (loan != null) text("loanType") else null, remaining?.amount?.toPlainString(), remaining?.currency?.name,
            installment?.amount?.toPlainString(), installment?.currency?.name, facility != null && channel == "STATEMENT"))
        return true
    }
}
