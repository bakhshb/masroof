package com.baraa.masroof.sms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.baraa.masroof.BuildConfig
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import kotlinx.coroutines.launch

/**
 * Receives [Telephony.Sms.Intents.SMS_RECEIVED_ACTION] and hands off to the
 * application [com.baraa.masroof.application.sms.LiveSmsIntake] boundary.
 *
 * Multipart PDUs are combined into one RawSms body via [ReceivedSmsAssembler].
 * [com.baraa.masroof.domain.model.RawSms.receivedAt] uses the PDU service-center
 * timestamp when Android supplies a valid one ([LiveReceiptTimestamp]); otherwise
 * the application [com.baraa.masroof.sms.time.InstantClock].
 *
 * Android I/O only: assembles the message, then [goAsync] covers just the short
 * capture of durable RawSms evidence and scheduling of its processing by rawSmsId.
 * Parse and reconciliation run in WorkManager, not within the broadcast lifetime.
 * Does not log SMS bodies, OTPs, or financial fields.
 *
 * No-arg constructor required for manifest instantiation.
 */
class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        if (BuildConfig.DEBUG) {
            DebugSmsPduExtra.materialize(intent)
        }

        val app = context.applicationContext as? MasroofApplication
        if (app == null) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            return
        }

        val assembled = ReceivedSmsAssembler.assemble(
            messages.map { msg ->
                ReceivedSmsAssembler.Part(
                    sender = msg.displayOriginatingAddress,
                    body = msg.displayMessageBody,
                    providerTimestampMillis = msg.timestampMillis,
                )
            },
        ) ?: return

        val receivedAt = LiveReceiptTimestamp.resolve(
            providerTimestampsMillis = assembled.providerTimestampsMillis,
            deviceNow = app.container.clock.now(),
        )
        val rawSms = try {
            AndroidSmsMapper.toRawSms(
                ProviderSmsRecord(
                    providerMessageId = null,
                    sender = assembled.sender,
                    body = assembled.body,
                    receivedAt = receivedAt,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return
        }

        // A system delivery always has a pending result. A debug instrumentation
        // call of onReceive does not, because SMS_RECEIVED cannot be sent by the app.
        val pendingResult = pendingResultOrNull()
        app.container.applicationScope.launch {
            try {
                app.container.liveSmsIntake.ingest(rawSms)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun pendingResultOrNull(): PendingResult? =
        try {
            goAsync()
        } catch (error: IllegalStateException) {
            if (!BuildConfig.DEBUG) throw error
            null
        }
}
