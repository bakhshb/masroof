package com.baraa.masroof.data.patterns

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.entity.PatternConfigurationEntity
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration
import com.baraa.masroof.engine.bank.patterns.SavedMessagePattern
import com.baraa.masroof.engine.bank.patterns.SenderIdentity
import java.io.File

/**
 * Durable user definitions, never a rebuildable learning cache: the whole document is the one
 * `pattern_configuration` row of `masroof.db` (schema v21). Each write reads, checks and replaces the row in one
 * database transaction, so the generation check holds across threads; no lock is held around database access, so
 * a read from inside a financial transaction joins it and never waits on a writer. Each touch holds a blocking
 * admission of the access gate (immediate inside a lease), so a read during a restore can never reopen the file. The decoded document is
 * reused only while the stored row is unchanged, so any writer, restore or other database is seen.
 */
class MessagePatternStore(
    private val database: () -> SupportSQLiteDatabase,
    private val accessGate: DatabaseAccessGate = DatabaseAccessGate(),
    private val beforeCommit: () -> Unit = {},
) {
    /** Every touch of [database] holds an [accessGate] admission: after the gate retires nothing reopens Room. */
    constructor(database: RoomDatabase, accessGate: DatabaseAccessGate = DatabaseAccessGate(), beforeCommit: () -> Unit = {}) :
        this({ database.openHelper.writableDatabase }, accessGate, beforeCommit)

    fun load(): MessagePatternConfiguration {
        val (generation, raw) = readRow() ?: return MessagePatternConfiguration()
        decoded?.takeIf { it.generation == generation && it.raw == raw }?.let { return it.configuration }
        val configuration = MessagePatternConfigurationCodec.decode(raw)
        check(configuration.generation == generation) { "Pattern configuration generation mismatch" }
        decoded = Decoded(generation, raw, configuration)
        return configuration
    }

    /** Runs when the live database opens (bundled seeding) and under the exclusive access gate (interactive installation). */
    fun installMissingBanks(bundled: List<BankMessagePatterns>, mergeBankIds: Set<String> = emptySet()): MessagePatternConfiguration = transaction {
        var current = load()
        for (bank in bundled) installation(current, bank, mergeBankIds)?.let { current = write(current, it) }
        current
    }

    fun saveBank(expectedGeneration: Long, bank: BankMessagePatterns): MessagePatternConfiguration = transaction {
        val current = load()
        check(current.generation == expectedGeneration) { "Pattern configuration changed" }
        val prior = current.banks.singleOrNull { it.bankId == bank.bankId }
        val deletedIds = prior?.deletedPatternIds.orEmpty() + bank.deletedPatternIds
        require(bank.patterns.none { it.id in deletedIds }) { "Pattern was permanently deleted" }
        val versions = prior?.patterns.orEmpty().associateBy { it.id to it.revision }.toMutableMap()
        bank.patterns.sortedBy { it.revision }.forEach { pattern ->
            val key = pattern.id to pattern.revision
            val existing = versions[key]
            if (existing != null) require(existing == pattern) { "Pattern revision is immutable" }
            else {
                require(pattern.revision > (versions.values.filter { it.id == pattern.id }.maxOfOrNull { it.revision } ?: 0)) {
                    "Pattern revision must advance"
                }
                versions[key] = pattern
            }
        }
        write(current, bank.copy(patterns = keepRecent(versions.values), deletedPatternIds = deletedIds))
    }

    fun deletePattern(expectedGeneration: Long, bankId: String, patternId: String): MessagePatternConfiguration = transaction {
        val current = load()
        check(current.generation == expectedGeneration) { "Pattern configuration changed" }
        val bank = current.banks.single { it.bankId == bankId }
        val pattern = bank.currentPatterns.single { it.id == patternId }
        require(!pattern.enabled) { "Disable the pattern before deleting it" }
        write(current, bank.copy(patterns = bank.patterns.filterNot { it.id == patternId },
            deletedPatternIds = bank.deletedPatternIds + patternId))
    }

    /** Replaces one bank's stored history as given; callers own its revision rules. */
    private fun write(current: MessagePatternConfiguration, updated: BankMessagePatterns): MessagePatternConfiguration {
        val next = current.copy(generation = Math.addExact(current.generation, 1),
            banks = current.banks.filterNot { it.bankId == updated.bankId } + updated)
        val raw = MessagePatternConfigurationCodec.encode(next)
        put(next.generation, raw)
        return MessagePatternConfigurationCodec.decode(raw)
    }

    /** The stored document exactly as written, or null when there is none. */
    fun captureRaw(): String? = readRow()?.second

    /** Replaces the whole document with [raw] (validated first), or removes it when null. */
    fun restoreRaw(raw: String?): Unit = transaction {
        if (raw == null) database().execSQL("DELETE FROM pattern_configuration")
        else put(MessagePatternConfigurationCodec.decode(raw).generation, raw)
    }

    /**
     * One-time copy of the pre-v21 [file] into the row, byte for byte; runs when the live database opens.
     * The file is deleted only after the stored row was read back: equal to the file, or, when a row was already
     * there and differs, once that row decodes. Once a row exists every write goes to it and no app version writes
     * the file again (v21 never downgrades), so a differing file is the older one. Any throw keeps the file and the
     * next open repeats the copy; after success there is no file and a rerun does nothing.
     */
    fun importLegacyFile(file: File) {
        val legacy = LegacyMessagePatternFile.read(file) ?: return
        transaction { if (readRow() == null) restoreRaw(legacy) }
        if (captureRaw() != legacy) load()
        LegacyMessagePatternFile.delete(file)
    }

    private fun readRow(): Pair<Long, String>? = accessGate.withBlockingAccess {
        database().query("SELECT generation, document FROM pattern_configuration WHERE id = ${PatternConfigurationEntity.ROW_ID}")
            .use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null }
    }

    private fun put(generation: Long, raw: String) = database().execSQL(
        "INSERT OR REPLACE INTO pattern_configuration (id, generation, document) VALUES (?, ?, ?)",
        arrayOf<Any>(PatternConfigurationEntity.ROW_ID, generation, raw),
    )

    /** Nests inside a caller's transaction on the same connection; a throw, also from [beforeCommit], rolls back. */
    private fun <T> transaction(block: () -> T): T = accessGate.withBlockingAccess {
        val db = database()
        db.beginTransaction()
        try {
            block().also { beforeCommit(); db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        /**
         * The bank to store for one bundled definition, or null when nothing changes. A missing bank installs unless
         * another bank owns its sender; an installed bank gains missing patterns and newer bundled role rules the user
         * left untouched (deleted, disabled and edited patterns stay the user's decision). [mergeBankIds] also merge senders.
         */
        fun installation(configuration: MessagePatternConfiguration, seed: BankMessagePatterns,
            mergeBankIds: Set<String>): BankMessagePatterns? {
            val prior = configuration.banks.singleOrNull { it.bankId == seed.bankId }
                ?: return seed.takeIf { seed.bankId in mergeBankIds || configuration.banks.none { bank -> seed.senders.any { mine -> bank.senders.any { SenderIdentity.overlap(it, mine) } } } }
            val installed = prior.currentPatterns.associateBy { it.id }
            val updates = seed.patterns.mapNotNull { bundled ->
                val mine = installed[bundled.id]
                when {
                    bundled.id in prior.deletedPatternIds -> null
                    mine == null -> bundled
                    mine.roleProfile != null && bundled.roleProfile != null && mine.enabled &&
                        mine.copy(revision = bundled.revision, factContract = mine.factContract?.unlinked()) != bundled -> {
                        // Approved account/card links travel with a newer rule of the same family; a changed family needs a fresh approval.
                        val links = mine.factContract?.accountBindings.orEmpty()
                        if (links.isNotEmpty() && mine.factContract?.family != bundled.factContract?.family) null
                        else bundled.copy(revision = Math.addExact(mine.revision, 1), factContract = bundled.factContract?.let {
                            if (links.isEmpty()) it else it.copy(version = 2, accountBindings = links) })
                    }
                    else -> null
                }
            }
            val senders = if (seed.bankId in mergeBankIds) (prior.senders + seed.senders).distinct() else prior.senders
            if (updates.isEmpty() && senders == prior.senders) return null
            // An upgraded role keeps only its new revision, so repeated updates never grow the bounded history.
            val upgraded = updates.map { it.id }.toSet()
            return prior.copy(senders = senders,
                patterns = keepRecent(prior.patterns.filterNot { it.id in upgraded && it.roleProfile != null } + updates))
        }

        private fun com.baraa.masroof.engine.bank.patterns.PatternFactContract.unlinked() = copy(version = 1, accountBindings = emptyList())

        /** Every save adds a revision; keep the newest few per pattern so a bank never reaches the codec's 128 cap. */
        internal fun keepRecent(patterns: Collection<SavedMessagePattern>): List<SavedMessagePattern> =
            patterns.groupBy { it.id }.values.flatMap { history -> history.sortedByDescending { it.revision }.take(KEPT_REVISIONS) }

        internal const val KEPT_REVISIONS = 4

        /** One slot: the app has one document; tests that make many must not keep every copy. */
        private class Decoded(val generation: Long, val raw: String, val configuration: MessagePatternConfiguration)
        @Volatile private var decoded: Decoded? = null
    }
}
