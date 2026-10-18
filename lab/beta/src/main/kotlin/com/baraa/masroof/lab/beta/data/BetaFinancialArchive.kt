package com.baraa.masroof.lab.beta.data

import com.baraa.masroof.lab.bank.InterpretationVersion
import com.baraa.masroof.lab.bank.MessageFamily
import com.baraa.masroof.lab.beta.application.evidence.BetaLabelField
import com.baraa.masroof.lab.beta.application.evidence.InterpretationArchive
import com.baraa.masroof.lab.beta.application.interpretation.LegacyInterpretationAdapter
import com.baraa.masroof.lab.finance.value.Currency
import com.baraa.masroof.lab.finance.value.Money
import java.security.MessageDigest
import org.json.JSONObject

/** One financial reading of a saved outcome.
 *
 * A current bank-engine row stores that archive in the existing candidate column and leaves the
 * baseline column inert. Historical rows keep their explicit baseline. A model suggestion, an
 * inert baseline, or a span that does not match the original SMS cannot authorize money.
 */
internal object BetaFinancialArchive {
    private const val HUMAN_PROVENANCE = "USER_EXPLICIT_ORIGINAL_SPAN_V1"

    fun body(outcome: BetaEvidenceOutcomeEntity): String =
        if (outcome.baselineJson == "{}") outcome.candidateJson else outcome.baselineJson

    fun json(outcome: BetaEvidenceOutcomeEntity): JSONObject {
        val json = JSONObject(body(outcome))
        require(json.getInt("archiveVersion") == 1)
        require(json.getInt("contractVersion") == InterpretationVersion.CURRENT_CONTRACT)
        require(json.getString("evidenceId") == outcome.rawId)
        if (outcome.baselineJson == "{}") {
            require(InterpretationArchive.isCurrent(json))
            val evidence = json.getJSONArray("evidence")
            require(evidence.length() > 0)
            for (index in 0 until evidence.length()) {
                val span = evidence.getJSONObject(index)
                require(span.getString("ruleId").isNotBlank() && span.getString("field").isNotBlank())
                require(span.getInt("start") >= 0 && span.getInt("end") > span.getInt("start"))
            }
        } else {
            require(json.getString("engineVersion") == LegacyInterpretationAdapter.VERSION.engine &&
                json.getString("rulesVersion") == LegacyInterpretationAdapter.VERSION.rules)
        }
        return json
    }

    suspend fun facts(database: BetaDatabase, outcome: BetaEvidenceOutcomeEntity): JSONObject {
        val json = json(outcome)
        val facts = JSONObject(json.getJSONObject("facts").toString())
        if (outcome.baselineJson != "{}") return facts
        val labels = applicableLabels(database, outcome, json)
        if (labels.isEmpty()) return facts
        val body = requireNotNull(database.evidenceDao().raw(outcome.rawId)).body
        labels.forEach { label ->
            require(label.start >= 0 && label.end > label.start && label.end <= body.length)
            require(body.substring(label.start, label.end) == label.originalText)
        }
        labels.firstOrNull { it.field == BetaLabelField.FAMILY.name }?.let { label ->
            require(MessageFamily.entries.any { it.name == label.value })
            facts.put("family", label.value)
        }
        val amount = labels.firstOrNull { it.field == BetaLabelField.AMOUNT.name }
        val currency = labels.firstOrNull { it.field == BetaLabelField.CURRENCY.name }
        if (amount != null || currency != null) {
            val current = facts.getJSONObject("amount")
            val nextAmount = amount?.value ?: current.getString("amount")
            val nextCurrency = currency?.value ?: current.getString("currency")
            Money.of(nextAmount, Currency.valueOf(nextCurrency))
            facts.put("amount", JSONObject().put("amount", nextAmount).put("currency", nextCurrency))
        }
        return facts
    }

    suspend fun fingerprint(database: BetaDatabase, outcome: BetaEvidenceOutcomeEntity): String {
        val archive = digest(outcome.route + "\n" + body(outcome) + "\n" + outcome.attempts)
        if (outcome.baselineJson != "{}") return archive
        val json = runCatching { JSONObject(outcome.candidateJson) }.getOrNull() ?: return archive
        if (!InterpretationArchive.isCurrent(json)) return archive
        val stamp = applicableLabels(database, outcome, json)
            .joinToString("|") { "${it.field}:${it.revision}:${it.value}" }
        return if (stamp.isEmpty()) archive else digest(archive + "\n" + stamp)
    }

    private suspend fun applicableLabels(database: BetaDatabase, outcome: BetaEvidenceOutcomeEntity,
        json: JSONObject): List<BetaLearningLabelEntity> {
        val hash = digest(outcome.candidateJson)
        return database.learningDao().history(outcome.rawId)
            .filter { it.archiveHash == hash && it.interpretationAttempt == outcome.attempts &&
                it.provenance == HUMAN_PROVENANCE && it.engineVersion == json.getString("engineVersion") &&
                it.rulesVersion == json.getString("rulesVersion") }
            .groupBy { it.field }
            .map { (_, rows) -> rows.maxBy { it.revision } }
            .sortedBy { it.field }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
