package com.baraa.masroof.lab.beta.data

import com.baraa.masroof.lab.beta.application.*
import com.baraa.masroof.lab.finance.value.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.util.Collections

/** Normalized commitment facts, independent of bank parsing and financial posting authority. */
internal object BetaCommitmentCodec {
    fun json(c: BetaRecurringCommitment): JSONObject = JSONObject().put("id",c.id).put("label",c.label)
        .put("amount",c.amount.amount.toPlainString()).put("currency",c.amount.currency.name)
        .put("day",c.dueDay).put("starts",c.starts.toString()).put("ends",c.ends?.toString() ?: JSONObject.NULL)
        .put("paid",JSONArray(c.paidMonths.sorted().map(YearMonth::toString)))
        .put("recurrence",c.recurrence?.name ?: JSONObject.NULL).put("transactionDate",c.transactionDate?.toString() ?: JSONObject.NULL)
        .put("dueDate",c.dueDate?.toString() ?: JSONObject.NULL).put("active",c.active)
        .put("pauses",JSONArray().also {a -> c.pauseIntervals.forEach {a.put(JSONObject().put("pausedAt",it.pausedAt.toString()).put("resumedAt",it.resumedAt?.toString() ?: JSONObject.NULL))}})
        .put("source",c.sourceTransactionId ?: JSONObject.NULL)
    fun decode(j: JSONObject): BetaRecurringCommitment {
        val paid=j.getJSONArray("paid");require(paid.length()<=1200)
        val pauses=j.optJSONArray("pauses") ?: JSONArray();require(pauses.length()<=2400)
        return BetaRecurringCommitment(j.getString("id"),j.getString("label"),Money.of(j.getString("amount"),Currency.valueOf(j.optString("currency","SAR"))),
            j.getInt("day"),YearMonth.parse(j.getString("starts")),text(j,"ends")?.let(YearMonth::parse),
            Collections.unmodifiableSet((0 until paid.length()).map {YearMonth.parse(paid.getString(it))}.toSet()),
            if(!j.has("recurrence")) BetaCommitmentRecurrence.MONTHLY else text(j,"recurrence")?.let(BetaCommitmentRecurrence::valueOf),
            text(j,"transactionDate")?.let(LocalDate::parse),text(j,"dueDate")?.let(LocalDate::parse),j.optBoolean("active",true),
            Collections.unmodifiableList((0 until pauses.length()).map {i -> pauses.getJSONObject(i).let {BetaCommitmentPause(Instant.parse(it.getString("pausedAt")),text(it,"resumedAt")?.let(Instant::parse))}}),text(j,"source"))
    }
    fun json(c: BetaCommitmentRecord): JSONObject = JSONObject().put("id",c.id).put("label",c.label).put("amount",c.amountDecimal)
        .put("currency",c.amountCurrency).put("day",c.dueDay).put("starts",c.starts).put("ends",c.ends ?: JSONObject.NULL).put("paid",JSONArray(c.paidMonths))
        .put("active",c.active).put("pauses",JSONArray(c.pauseIntervals)).put("source",c.sourceTransactionId ?: JSONObject.NULL)
        .put("recurrence",c.recurrence ?: JSONObject.NULL).put("transactionDate",c.transactionDate ?: JSONObject.NULL).put("dueDate",c.dueDate ?: JSONObject.NULL)
    fun record(j: JSONObject,previous: BetaCommitmentRecord?): BetaCommitmentRecord = BetaCommitmentRecord(
        j.getString("id"),j.getString("label"),j.getString("amount"),j.optString("currency","SAR"),j.getInt("day"),j.getString("starts"),text(j,"ends"),
        if(j.has("active")) j.getBoolean("active") else previous?.active ?: true,j.getJSONArray("paid").toString(),
        if(j.has("pauses")) j.getJSONArray("pauses").toString() else previous?.pauseIntervals ?: "[]",
        if(j.has("source")) text(j,"source") else previous?.sourceTransactionId,
        if(j.has("recurrence")) text(j,"recurrence") else if(previous!=null) previous.recurrence else "MONTHLY",
        if(j.has("transactionDate")) text(j,"transactionDate") else previous?.transactionDate,
        if(j.has("dueDate")) text(j,"dueDate") else previous?.dueDate)
    private fun text(j: JSONObject,key: String): String?=if(!j.has(key)||j.isNull(key)) null else j.getString(key)
}
