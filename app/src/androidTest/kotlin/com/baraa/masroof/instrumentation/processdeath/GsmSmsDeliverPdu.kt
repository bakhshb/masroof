package com.baraa.masroof.instrumentation.processdeath

import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import java.io.ByteArrayOutputStream

/**
 * GSM SMS-DELIVER PDUs that [Telephony.Sms.Intents.getMessagesFromIntent] accepts.
 *
 * Address length follows TS 23.040 and Android's `GsmSmsAddress`: semi-octets, then
 * `countSeptets = addressLength * 4 / 7`. UCS-2 user data longer than 140 octets is
 * split with an 8-bit concatenation header. No SMS body is logged by callers.
 */
internal object GsmSmsDeliverPdu {
    private const val MAX_UCS2_OCTETS: Int = 140
    private const val CONCAT_HEADER_OCTETS: Int = 6
    private const val CONCAT_TEXT_CHARS: Int = (MAX_UCS2_OCTETS - CONCAT_HEADER_OCTETS) / 2

    fun encode(sender: String, body: String): List<ByteArray> {
        val textOctets = body.length * 2
        if (textOctets <= MAX_UCS2_OCTETS) {
            return listOf(segment(sender, body, concatenated = false, reference = 0, total = 1, sequence = 1))
        }
        val parts = body.chunked(CONCAT_TEXT_CHARS)
        return parts.mapIndexed { index, part ->
            segment(
                sender = sender,
                body = part,
                concatenated = true,
                reference = 0x3D,
                total = parts.size,
                sequence = index + 1,
            )
        }
    }

    fun receivedIntent(sender: String, body: String): Intent {
        val pdus = encode(sender, body).toTypedArray()
        return Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", pdus as java.io.Serializable)
            putExtra("format", SmsMessage.FORMAT_3GPP)
        }
    }

    fun hexList(sender: String, body: String): String =
        encode(sender, body).joinToString(separator = ",") { pdu ->
            pdu.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }

    private fun segment(
        sender: String,
        body: String,
        concatenated: Boolean,
        reference: Int,
        total: Int,
        sequence: Int,
    ): ByteArray {
        val text = body.toByteArray(Charsets.UTF_16BE)
        val userData = if (concatenated) {
            byteArrayOf(
                0x05,
                0x00,
                0x03,
                reference.toByte(),
                total.toByte(),
                sequence.toByte(),
            ) + text
        } else {
            text
        }
        check(userData.size <= MAX_UCS2_OCTETS) { "SMS user data is longer than one segment" }
        val out = ByteArrayOutputStream()
        out.write(0x00)
        out.write(if (concatenated) 0x44 else 0x04)
        out.write(alphanumericAddress(sender))
        out.write(0x00)
        out.write(0x08)
        // 2026-08-03 14:32:00 UTC, nibble-swapped BCD, timezone 0.
        out.write(byteArrayOf(0x62, 0x80.toByte(), 0x30, 0x41, 0x23, 0x00, 0x00))
        out.write(userData.size)
        out.write(userData)
        return out.toByteArray()
    }

    private fun alphanumericAddress(sender: String): ByteArray {
        val packed = packGsm7(sender)
        var semiOctets = 0
        while (semiOctets * 4 / 7 < sender.length) semiOctets++
        check(semiOctets * 4 / 7 == sender.length) { "sender length is not an exact GSM address" }
        val dataBytes = (semiOctets + 1) / 2
        check(packed.size <= dataBytes) { "packed sender is longer than the address field" }
        return byteArrayOf(semiOctets.toByte(), 0xD0.toByte()) + packed.copyOf(dataBytes)
    }

    private fun packGsm7(text: String): ByteArray {
        val septets = text.map { char ->
            check(char.code in 0..127) { "sender is not GSM 7-bit" }
            char.code and 0x7F
        }
        val packed = ByteArray((septets.size * 7 + 7) / 8)
        var bitPosition = 0
        for (septet in septets) {
            val byteIndex = bitPosition / 8
            val shift = bitPosition % 8
            packed[byteIndex] = (packed[byteIndex].toInt() or ((septet shl shift) and 0xFF)).toByte()
            if (shift > 1 && byteIndex + 1 < packed.size) {
                packed[byteIndex + 1] =
                    (packed[byteIndex + 1].toInt() or ((septet shr (8 - shift)) and 0xFF)).toByte()
            }
            bitPosition += 7
        }
        return packed
    }
}
