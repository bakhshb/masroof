package com.baraa.masroof.data.room.dao

/**
 * Chunks `IN (:ids)` lookups so one statement never exceeds SQLite's bind-argument
 * limit (999 before SQLite 3.32, which older Android releases ship).
 */
object RoomBatch {
    const val MAX_BIND_ARGS = 500

    suspend fun <T> query(
        ids: Collection<String>,
        chunkSize: Int = MAX_BIND_ARGS,
        block: suspend (List<String>) -> List<T>,
    ): List<T> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyList()
        return distinct.chunked(chunkSize).flatMap { block(it) }
    }
}
