package com.baraa.masroof.application.transaction

import com.baraa.masroof.BuildConfig
import java.io.File

/**
 * Debug-only switch back to the pre-3.3a reconciliation path (plan 3.3a). A file named [FILE_NAME] in the
 * application files directory turns it on, for example
 * `adb shell run-as com.baraa.masroof touch files/debug-legacy-reconcile`; deleting it turns it off. Release builds
 * always use the ledger writer. Pieces 3.3b/3.3c delete the switch with the old path.
 */
object DebugLegacyReconciliation {
    const val FILE_NAME: String = "debug-legacy-reconcile"

    fun enabled(filesDir: File): Boolean = BuildConfig.DEBUG && File(filesDir, FILE_NAME).isFile
}
