package com.baraa.masroof.application.logging

import android.content.Context
import android.net.Uri
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class AppLogService(
    private val context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val retentionDays: Long = DEFAULT_RETENTION_DAYS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val logFile: File = File(context.filesDir, LOG_FILE_NAME)
    private var nextId = 1L
    private val entries = ArrayDeque<AppLogEntry>()

    init {
        loadFromDisk()
        pruneExpiredLocked()
    }

    fun log(level: AppLogLevel, category: String, message: String) {
        val entry = AppLogEntry(
            id = synchronized(lock) { nextId++ },
            timestampEpochMs = clock(),
            category = category.trim().ifEmpty { "general" },
            level = level,
            message = AppLogRedactor.redact(message.trim()),
        )
        synchronized(lock) {
            entries.addLast(entry)
            pruneLocked()
            // A full or broken disk must not crash callers; the entry stays in memory.
            runCatching { rewriteDiskLocked() }
        }
    }

    fun info(category: String, message: String) = log(AppLogLevel.INFO, category, message)

    fun warn(category: String, message: String) = log(AppLogLevel.WARN, category, message)

    fun error(category: String, message: String) = log(AppLogLevel.ERROR, category, message)

    /** A caught failure: the step, exception type and a masked message, so a report can be traced without SMS data. */
    fun failure(category: String, action: String, error: Throwable) = error(category, "$action failed (${describe(error)})")

    fun readAll(): List<AppLogEntry> =
        synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            if (logFile.exists()) {
                logFile.delete()
            }
            nextId = 1L
        }
    }

    /** The same text the export writes, for sharing from the log screen. */
    fun exportText(): String = buildExportText(readAll())

    fun exportTo(uri: Uri): Result<Unit> =
        runCatching {
            val text = exportText()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open export destination")
        }

    fun exportFileName(timestampEpochMs: Long = clock()): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        val stamp = formatter.format(Instant.ofEpochMilli(timestampEpochMs).atZone(zoneId))
        return "masroof-log-$stamp.txt"
    }

    private fun pruneLocked(nowEpochMs: Long = clock()) {
        pruneExpiredLocked(nowEpochMs)
        // Routine info/warn lines must not push failures out of a full log.
        while (entries.size > maxEntries) {
            val oldestRoutine = entries.indexOfFirst { it.level != AppLogLevel.ERROR }
            if (oldestRoutine >= 0) entries.removeAt(oldestRoutine) else entries.removeFirst()
        }
    }

    private fun pruneExpiredLocked(nowEpochMs: Long = clock()) {
        val cutoff = nowEpochMs - TimeUnit.DAYS.toMillis(retentionDays)
        while (entries.isNotEmpty() && entries.first().timestampEpochMs < cutoff) {
            entries.removeFirst()
        }
    }

    private fun rewriteDiskLocked() {
        logFile.parentFile?.mkdirs()
        logFile.writeText(
            entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n") { serialize(it) },
            Charsets.UTF_8,
        )
    }

    private fun loadFromDisk() {
        if (!logFile.exists()) return
        val loaded = logFile.readLines()
            .mapNotNull(::parseLine)
        synchronized(lock) {
            entries.clear()
            entries.addAll(loaded)
            nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1L
        }
    }

    private fun serialize(entry: AppLogEntry): String =
        "${entry.id}\t${entry.timestampEpochMs}\t${entry.level.name}\t${escapeField(entry.category)}\t${escapeField(entry.message)}"

    private fun parseLine(line: String): AppLogEntry? {
        if (line.isBlank()) return null
        val parts = line.split('\t', limit = 5)
        if (parts.size < 5) return null
        val level = runCatching { AppLogLevel.valueOf(parts[2]) }.getOrNull() ?: return null
        val id = parts[0].toLongOrNull() ?: return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        return AppLogEntry(
            id = id,
            timestampEpochMs = timestamp,
            category = unescapeField(parts[3]),
            level = level,
            message = unescapeField(parts[4]),
        )
    }

    private fun escapeField(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(char)
                }
            }
        }

    private fun unescapeField(value: String): String =
        buildString(value.length) {
            var index = 0
            while (index < value.length) {
                if (value[index] == '\\' && index + 1 < value.length) {
                    when (value[index + 1]) {
                        '\\' -> append('\\')
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> {
                            append('\\')
                            append(value[index + 1])
                        }
                    }
                    index += 2
                } else {
                    append(value[index])
                    index++
                }
            }
        }

    private fun buildExportText(entries: List<AppLogEntry>): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        val header = buildString {
            appendLine("Masroof diagnostic log")
            appendLine("Exported: ${formatter.format(Instant.now().atZone(zoneId))}")
            appendLine("App: ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()}")
            appendLine("Android: ${android.os.Build.VERSION.SDK_INT} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Entries: ${entries.size}")
            appendLine("Retention: $maxEntries entries / $retentionDays days")
            appendLine()
        }
        return header + entries.joinToString(separator = "\n") { formatEntry(it, formatter) }
    }

    private fun formatEntry(
        entry: AppLogEntry,
        formatter: DateTimeFormatter,
    ): String {
        val timestamp = formatter.format(Instant.ofEpochMilli(entry.timestampEpochMs).atZone(zoneId))
        return "$timestamp ${entry.level.name} [${entry.category}] ${entry.message}"
    }

    companion object {
        const val LOG_FILE_NAME: String = "masroof-app.log"
        const val DEFAULT_MAX_ENTRIES: Int = 500
        const val DEFAULT_RETENTION_DAYS: Long = 14L

        /** `IllegalStateException: Snapshot failed integrity_check ← SQLiteException`; digit runs are masked. */
        fun describe(error: Throwable): String = buildString {
            append(error.javaClass.simpleName)
            error.message?.trim()?.takeIf { it.isNotEmpty() }?.let { append(": ").append(it.replace(LONG_DIGIT_RUN, "####").take(160)) }
            error.cause?.takeIf { it !== error }?.let { append(" ← ").append(it.javaClass.simpleName) }
        }
    }

}

private val LONG_DIGIT_RUN = Regex("[0-9]{4,}")
