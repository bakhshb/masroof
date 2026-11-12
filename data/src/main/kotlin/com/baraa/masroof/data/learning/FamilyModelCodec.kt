package com.baraa.masroof.data.learning

import com.baraa.masroof.ml.family.FamilyInputCleaner
import kotlinx.serialization.json.*

/** Data-only codec for stored cleaned terms. No stored data can supply code, monetary facts or ownership. */
object FamilyModelCodec {
    fun terms(element: JsonElement): Map<String,Int> = element.jsonObject.mapValues {it.value.jsonPrimitive.int}.also { map ->
        require(FamilyInputCleaner.validTerms(map))
    }
    fun termsJson(terms: Map<String,Int>) = buildJsonObject {terms.toSortedMap().forEach {(key,value)->put(key,value)}}
}
