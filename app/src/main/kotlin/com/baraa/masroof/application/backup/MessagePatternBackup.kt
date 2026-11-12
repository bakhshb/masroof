package com.baraa.masroof.application.backup

import android.content.Context
import com.baraa.masroof.data.patterns.LegacyMessagePatternFile
import com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec

/**
 * From schema [DATABASE_VERSION] on the pattern document travels inside the database. An older archive carries it
 * in `preferences.json`, and import converts it into the staged database. Restore snapshots still carry the legacy
 * file ([LegacyMessagePatternFile]); restore and recovery already own exclusive access.
 */
object MessagePatternBackup {
    /** The first schema whose database holds the patterns; an archive from it on ignores the preferences field. */
    const val DATABASE_VERSION = 21

    fun captureOriginal(context: Context): String? = LegacyMessagePatternFile.read(LegacyMessagePatternFile.of(context))
    fun validate(raw: String?) { if (raw != null) MessagePatternConfigurationCodec.decode(raw) }
    fun restore(context: Context, raw: String?, validate: Boolean = true) {
        val file = LegacyMessagePatternFile.of(context)
        if (raw == null) LegacyMessagePatternFile.delete(file) else LegacyMessagePatternFile.write(file, raw, validate)
    }
}
