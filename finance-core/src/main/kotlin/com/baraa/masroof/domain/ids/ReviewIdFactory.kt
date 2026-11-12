package com.baraa.masroof.domain.ids

/**
 * Deterministic ReviewItem ids derived from stable RawSms evidence ids.
 */
object ReviewIdFactory {
    fun fromRawSmsId(rawSmsId: String): String {
        val id = rawSmsId.trim()
        require(id.isNotEmpty()) { "rawSmsId must not be blank" }
        return "review:$id"
    }
}
