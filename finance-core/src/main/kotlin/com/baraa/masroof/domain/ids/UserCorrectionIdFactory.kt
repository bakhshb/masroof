package com.baraa.masroof.domain.ids

import java.util.UUID

/**
 * Opaque unique ids for append-only [com.baraa.masroof.domain.model.UserCorrection] rows.
 *
 * Not derived from rawSmsId / epochMillis — those are not unique under same-ms writes.
 * Chronological ordering uses [com.baraa.masroof.domain.model.UserCorrection.createdAt]
 * (then id) separately.
 */
object UserCorrectionIdFactory {
    fun create(uuid: String = UUID.randomUUID().toString()): String {
        val trimmed = uuid.trim()
        require(trimmed.isNotEmpty()) { "uuid must not be blank" }
        return "correction:$trimmed"
    }
}
