package com.baraa.masroof.application.backup

import android.net.Uri
import java.io.File

private const val ZIP_TEST_PASSPHRASE = "zip-test-passphrase"

/** Test support: the plain package [zip] sealed in an envelope, the only form the app imports. */
fun sealZip(zip: File): File {
    val sealed = File.createTempFile("sealed-", ".masroof")
    sealed.outputStream().use { output ->
        BackupEnvelope.encrypt(output, ZIP_TEST_PASSPHRASE.toCharArray(), 1) { plain -> zip.inputStream().use { it.copyTo(plain) } }
    }
    return sealed
}

suspend fun DatabaseBackupGateway.importZip(zip: File): BackupImportOutcome =
    importFrom(Uri.fromFile(sealZip(zip)), ZIP_TEST_PASSPHRASE.toCharArray())
