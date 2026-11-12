package com.baraa.masroof.data.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The whole message-pattern document (the `MessagePatternConfigurationCodec` text) in one row, id always
 * [ROW_ID]; [generation] repeats the document's own generation. Written only by `MessagePatternStore`.
 */
@Entity(tableName = "pattern_configuration")
data class PatternConfigurationEntity(
    @PrimaryKey val id: Long,
    val generation: Long,
    val document: String,
) {
    companion object {
        const val ROW_ID: Long = 1
    }
}
