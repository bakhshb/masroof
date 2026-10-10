package com.baraa.masroof.sms.receiver

import android.content.Intent
import com.baraa.masroof.BuildConfig

/**
 * Debug-only bridge so an emulator shell can deliver [android.provider.Telephony.SMS_RECEIVED]
 * without putting binary PDUs on the `am` command line.
 *
 * Real platform broadcasts already carry a `pdus` extra. This reads
 * [EXTRA_PDU_HEX] only when that extra is absent, then
 * [android.provider.Telephony.Sms.Intents.getMessagesFromIntent] sees the same bytes.
 * Release builds ignore the extra. No SMS body is logged.
 */
internal object DebugSmsPduExtra {
    const val EXTRA_PDU_HEX: String = "m13_pdu_hex"

    fun materialize(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.hasExtra("pdus")) return
        val encoded = intent.getStringExtra(EXTRA_PDU_HEX) ?: return
        val pdus = encoded.split(',')
            .mapNotNull(::decodeHex)
            .toTypedArray()
        if (pdus.isEmpty()) return
        intent.putExtra("pdus", pdus as java.io.Serializable)
        if (intent.getStringExtra("format").isNullOrEmpty()) {
            intent.putExtra("format", "3gpp")
        }
    }

    private fun decodeHex(part: String): ByteArray? {
        if (part.isEmpty() || part.length % 2 != 0) return null
        return runCatching {
            ByteArray(part.length / 2) { index ->
                part.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}
