package com.baraa.masroof.lab.beta

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.*
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BetaArchiveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val password get() = "Synthetic password 42".toCharArray()
    private val archive = BetaEncryptedArchive { SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256") }
    @Before fun clear() { BetaContainer.resetOffline(context) }

    @Test fun notificationReadStateUsesBackupCompatibleStringsAndSurvivesRestoreAndReopen() {
        val id="foreign_currency:Oct 2026"
        BetaContainer(context).use {beta ->
            beta.putMarker("notification-test","synthetic")
            beta.readNotification(id)
            beta.backup()
            assertTrue(beta.notifications(1,"Oct 2026").items.single {it.id==id}.isRead)
            beta.readNotification("synthetic-other")
            assertTrue(beta.restore())
        }
        BetaContainer(context).use {beta ->
            assertTrue(beta.notifications(1,"Oct 2026").items.single {it.id==id}.isRead)
        }
    }

    @Test fun roundTrip() {
        BetaContainer(context).use { it.putMarker("first", "synthetic"); it.putPreferenceProbe("a=b\nsecond"); it.setTheme("DARK"); it.backup() }
        val backup = BetaBackupRecovery(context).backupFile().readBytes()
        val bytes = export()
        BetaContainer(context).use { it.putMarker("second", "synthetic"); it.putPreferenceProbe("changed"); it.setTheme("LIGHT") }
        assertTrue(BetaBackupRecovery(context).importArchive(bytes.inputStream(), password, archive))
        BetaContainer(context).use { assertEquals(1, it.markerCount()); assertEquals("a=b\nsecond", it.preferenceProbe()); assertEquals("DARK", it.settings().theme) }
        assertArrayEquals(backup, BetaBackupRecovery(context).backupFile().readBytes())
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("MASROOF:BETA"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("synthetic"))
        assertFalse(export().contentEquals(bytes))
    }

    @Test fun rejection() {
        seed(); val bytes = export()
        val damaged = bytes.copyOf().also { it[it.lastIndex - 20] = (it[it.lastIndex - 20].toInt() xor 1).toByte() }
        val header = bytes.copyOf().also { it[0] = 0 }
        for (input in listOf(damaged, header, bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1))) {
            assertThrows(Exception::class.java) { BetaBackupRecovery(context).importArchive(input.inputStream(), password, archive) }; unchanged()
        }
        assertThrows(Exception::class.java) { BetaBackupRecovery(context).importArchive(bytes.inputStream(), "Wrong password 42".toCharArray(), archive) }; unchanged()
        val otherOrigin = BetaEncryptedArchive { SecretKeySpec(ByteArray(32) { 8 }, "HmacSHA256") }
        assertThrows(Exception::class.java) { BetaBackupRecovery(context).importArchive(bytes.inputStream(), password, otherOrigin) }; unchanged()
        val createFlags = mutableListOf<Boolean>()
        val lostKey = BetaEncryptedArchive { create -> createFlags += create; error("Synthetic lost origin key") }
        assertThrows(Exception::class.java) { BetaBackupRecovery(context).importArchive(bytes.inputStream(), password, lostKey) }; unchanged()
        assertEquals(listOf(false), createFlags)
        assertFalse(File(context.filesDir, "beta_recovery_v2").list()!!.any { it.startsWith("archive-") })
    }

    @Test fun storageFailure() {
        seed()
        val broken = object : OutputStream() { var count = 0; override fun write(value: Int) { if (++count > 200) throw IOException("Synthetic ENOSPC") } }
        assertThrows(IOException::class.java) { BetaBackupRecovery(context).exportArchive(broken, password, archive) }; unchanged()
        assertFalse(File(context.filesDir, "beta_recovery_v2").list()!!.any { it.startsWith("archive-") })
    }

    @Test fun deathRollback() {
        seed(); val bytes = export()
        BetaContainer(context).use { it.putMarker("newer", "synthetic"); it.putPreferenceProbe("CURRENT") }
        val recovery = BetaBackupRecovery(context) { if (it == BetaBackupRecovery.Stage.DATABASE_INSTALLED) throw Death() }
        assertThrows(Death::class.java) { recovery.importArchive(bytes.inputStream(), password, archive) }
        BetaContainer(context).use { assertEquals(2, it.markerCount()); assertEquals("CURRENT", it.preferenceProbe()) }
    }

    @Test fun longHistory() {
        BetaContainer(context).use { beta -> repeat(2_000) { beta.putMarker("history-$it", "Synthetic isolated M12 history $it") }; beta.putPreferenceProbe("LONG") }
        val bytes = export()
        assertTrue(bytes.size > 65_536)
        BetaContainer(context).use { it.putMarker("after", "synthetic") }
        assertTrue(BetaBackupRecovery(context).importArchive(bytes.inputStream(), password, archive))
        BetaContainer(context).use { assertEquals(2_000, it.markerCount()); assertEquals("LONG", it.preferenceProbe()) }
    }

    @Test fun limits() {
        seed()
        assertThrows(IllegalArgumentException::class.java) { BetaBackupRecovery(context).exportArchive(ByteArrayOutputStream(), "short".toCharArray(), archive) }
        val bytes = export()
        val magicSize = "MASROOF:BETA:ARCHIVE:1\u0000".toByteArray().size
        java.nio.ByteBuffer.wrap(bytes, magicSize + 24, 8).putLong(Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { BetaBackupRecovery(context).importArchive(bytes.inputStream(), password, archive) }; unchanged()
    }

    @Test fun chunkOrder() {
        val source = File(context.cacheDir, "beta-archive-source").also { it.mkdirs() }
        val target = File(context.cacheDir, "beta-archive-target").also { it.mkdirs() }
        try {
            File(source, "database.db").writeBytes(ByteArray(200_000) { (it % 251).toByte() })
            File(source, "preferences.json").writeText("{}")
            File(source, "manifest.json").writeText("{}")
            val bytes = ByteArrayOutputStream().also { archive.write(source, it, password) }.toByteArray()
            val start = "MASROOF:BETA:ARCHIVE:1\u0000".toByteArray().size + 32
            val chunk = 65_536 + 16
            val swapped = bytes.copyOf().also {
                bytes.copyInto(it, start, start + chunk, start + chunk * 2)
                bytes.copyInto(it, start + chunk, start, start + chunk)
            }
            val duplicate = bytes.copyOf().also { bytes.copyInto(it, start + chunk, start, start + chunk) }
            val dropped = bytes.copyOfRange(0, start) + bytes.copyOfRange(start + chunk, bytes.size)
            for (bad in listOf(swapped, duplicate, dropped)) assertThrows(Exception::class.java) { archive.read(bad.inputStream(), target, password) }
            archive.read(bytes.inputStream(), target, password)
            assertArrayEquals(File(source, "database.db").readBytes(), File(target, "database.db").readBytes())
        } finally { source.deleteRecursively(); target.deleteRecursively() }
    }

    @Test fun stagedValidation() {
        seed(); BetaContainer(context).use { it.backup() }
        val source = File(context.cacheDir, "beta-archive-invalid").also { it.mkdirs() }
        try {
            val snapshot = BetaBackupRecovery(context).backupFile().parentFile!!
            snapshot.listFiles()!!.forEach { it.copyTo(File(source, it.name), overwrite = true) }
            val manifest = File(source, "manifest.json")
            val metadata = org.json.JSONObject(manifest.readText())
            metadata.put("environment", "PRODUCTION"); manifest.writeText(metadata.toString())
            var bytes = ByteArrayOutputStream().also { archive.write(source, it, password) }.toByteArray()
            assertThrows(Exception::class.java) { BetaBackupRecovery(context).importArchive(bytes.inputStream(), password, archive) }; unchanged()
            metadata.put("environment", "BETA")
            val database = File(source, "database.db")
            android.database.sqlite.SQLiteDatabase.openDatabase(database.absolutePath, null, 0).use { it.execSQL("DROP TABLE beta_audit_log") }
            metadata.put("databaseSha256", java.security.MessageDigest.getInstance("SHA-256").digest(database.readBytes()).joinToString("") { "%02x".format(it) })
            manifest.writeText(metadata.toString())
            bytes = ByteArrayOutputStream().also { archive.write(source, it, password) }.toByteArray()
            assertThrows(Exception::class.java) { BetaBackupRecovery(context).importArchive(bytes.inputStream(), password, archive) }; unchanged()
        } finally { source.deleteRecursively() }
    }
    private fun seed() { BetaContainer(context).use { it.putMarker("keep", "synthetic"); it.putPreferenceProbe("KEEP") } }
    private fun unchanged() { BetaContainer(context).use { assertEquals(1, it.markerCount()); assertEquals("KEEP", it.preferenceProbe()) } }
    private fun export() = ByteArrayOutputStream().also { BetaBackupRecovery(context).exportArchive(it, password, archive) }.toByteArray()
    private class Death : Error()
}
