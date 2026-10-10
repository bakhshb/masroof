package com.baraa.masroof.instrumentation.processdeath

import android.Manifest
import android.os.Build
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.application.sms.DebugProcessHalt
import com.baraa.masroof.application.sms.WorkManagerLiveSmsWorkScheduler
import com.baraa.masroof.application.transaction.IgnoreResult
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.CardReference
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ProcessingRetryMode
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.sms.receiver.DebugSmsPduExtra
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only process death. There is no physical device.
 *
 * Each arm method plants `filesDir/m13-halt-after`, delivers a real
 * `SMS_RECEIVED` broadcast to the manifest receiver, and returns
 * while the receiver or [com.baraa.masroof.application.sms.LiveSmsProcessingWorker]
 * is parked after the durable write. The CI runner then runs
 * `adb shell am force-stop com.baraa.masroof` from outside this process.
 *
 * The matching resume method is a new `am instrument` process. [com.baraa.masroof.instrumentation.MasroofAndroidTestRunner]
 * deletes the halt file before [com.baraa.masroof.MasroofApplication.onCreate], and startup's
 * `schedulePendingProcessing` plus WorkManager are the resume path.
 *
 * These methods stay out of the orchestrated `clearPackageData=true` suite. A data wipe
 * between arm and resume would destroy the journey. `scripts/m13-process-death.sh` runs
 * each pair with the package data kept.
 */
@RunWith(AndroidJUnit4::class)
class M13ProcessDeathJourneyTest {
    private val broadcastOutput = AtomicReference("")

    @Before
    fun requireEmulator() {
        assertFalse(
            evidence("Robolectric is not process-death evidence"),
            Build.FINGERPRINT.equals("robolectric", ignoreCase = true),
        )
        val emulator = Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true)
        assertTrue(evidence("emulator-only; no physical device"), emulator)
    }

    @Test(timeout = 120_000)
    fun armAfterCapture() = runBlocking {
        armFinancial(DebugProcessHalt.CAPTURE)
        val rawSmsId = singleRawSmsId()
        assertNull(evidence("parse has not finished"), app().container.parsedEventRepository.findByRawSmsId(rawSmsId))
        assertNull(evidence("nothing posted at capture"), app().container.financialTransactionRepository.findByRawSmsId(rawSmsId))
        assertEquals(
            evidence("captured RawSms is awaiting processing"),
            listOf(rawSmsId),
            app().container.rawSmsRepository.listIdsAwaitingProcessing(),
        )
        JourneyNote.write(filesDir(), rawSmsId)
    }

    @Test(timeout = 120_000)
    fun resumeAfterCapture() = runBlocking {
        val rawSmsId = JourneyNote.read(filesDir()).rawSmsId
        awaitStartup()
        assertPostedOnce(rawSmsId)
    }

    @Test(timeout = 120_000)
    fun armAfterParsed() = runBlocking {
        armFinancial(DebugProcessHalt.PARSED)
        val rawSmsId = singleRawSmsId()
        val parsed = app().container.parsedEventRepository.findByRawSmsId(rawSmsId)
        assertNotNull(evidence("ParsedEvent is durable"), parsed)
        assertEquals(evidence("purchase parsed"), MessageFamily.PURCHASE, parsed!!.event.messageFamily)
        assertEquals(evidence("parse succeeded"), ParseStatus.SUCCESS, parsed.event.parseStatus)
        assertNull(evidence("reconcile has not posted"), app().container.financialTransactionRepository.findByRawSmsId(rawSmsId))
        JourneyNote.write(filesDir(), rawSmsId)
    }

    @Test(timeout = 120_000)
    fun resumeAfterParsed() = runBlocking {
        val rawSmsId = JourneyNote.read(filesDir()).rawSmsId
        awaitStartup()
        assertPostedOnce(rawSmsId)
    }

    @Test(timeout = 120_000)
    fun armAfterReconciled() = runBlocking {
        armFinancial(DebugProcessHalt.RECONCILED)
        val rawSmsId = singleRawSmsId()
        assertPurchase(rawSmsId)
        JourneyNote.write(filesDir(), rawSmsId, transactionId = postedId(rawSmsId))
    }

    @Test(timeout = 120_000)
    fun resumeAfterReconciled() = runBlocking {
        val note = JourneyNote.read(filesDir())
        awaitStartup()
        assertPostedOnce(note.rawSmsId, expectedTransactionId = note.transactionId)
    }

    @Test(timeout = 120_000)
    fun armAfterReview() = runBlocking {
        armFinancial(DebugProcessHalt.REVIEW)
        val rawSmsId = singleRawSmsId()
        assertPurchase(rawSmsId)
        JourneyNote.write(filesDir(), rawSmsId, transactionId = postedId(rawSmsId))
    }

    @Test(timeout = 120_000)
    fun resumeAfterReview() = runBlocking {
        val note = JourneyNote.read(filesDir())
        awaitStartup()
        assertPostedOnce(note.rawSmsId, expectedTransactionId = note.transactionId)
    }

    @Test(timeout = 120_000)
    fun armRequiredReview() = runBlocking {
        arm(DebugProcessHalt.REVIEW)
        sendBankSms(NOTICE_BODY)
        awaitHalt(DebugProcessHalt.REVIEW)
        val rawSmsId = singleRawSmsId()
        val reviewId = assertRequiredReview(rawSmsId)
        JourneyNote.write(filesDir(), rawSmsId, reviewId = reviewId)
    }

    @Test(timeout = 120_000)
    fun resumeRequiredReview() = runBlocking {
        val note = JourneyNote.read(filesDir())
        awaitStartup()
        waitUntilLiveWorkSettled(note.rawSmsId)
        assertEquals(evidence("same required review row"), note.reviewId, assertRequiredReview(note.rawSmsId))
        assertEquals(evidence("unknown notice posts nothing"), 0, app().container.financialTransactionRepository.listAll().size)
    }

    @Test(timeout = 120_000)
    fun armUserNonFinancial() = runBlocking {
        grantSmsPermissions()
        ownAlJaziraCard()
        sendBankSms(PURCHASE_BODY)
        val rawSmsId = waitForSingleRawSms()
        waitForPurchase(rawSmsId)
        waitUntilLiveWorkSettled(rawSmsId)
        val ignored = app().container.transactionIgnoreService.ignore(postedId(rawSmsId))
        assertTrue(evidence("user ignore succeeded"), ignored is IgnoreResult.Success)
        val review = app().container.reviewRepository.findByRawSmsId(rawSmsId)
        assertNotNull(evidence("USER_NON_FINANCIAL review"), review)
        assertEquals(evidence("resolved non-financial"), ReviewStatus.RESOLVED, review!!.status)
        assertEquals(evidence("USER_NON_FINANCIAL"), ReviewResolutionKind.USER_NON_FINANCIAL, review.resolutionKind)
        assertNull(evidence("ignore removes the transaction"), app().container.financialTransactionRepository.findByRawSmsId(rawSmsId))
        JourneyNote.write(filesDir(), rawSmsId, reviewId = review.id)
    }

    @Test(timeout = 120_000)
    fun resumeUserNonFinancial() = runBlocking {
        val note = JourneyNote.read(filesDir())
        awaitStartup()
        waitUntilLiveWorkSettled(note.rawSmsId)
        val review = app().container.reviewRepository.findByRawSmsId(note.rawSmsId)
        assertNotNull(evidence("USER_NON_FINANCIAL survived process death"), review)
        assertEquals(evidence("same review row"), note.reviewId, review!!.id)
        assertEquals(evidence("still resolved"), ReviewStatus.RESOLVED, review.status)
        assertEquals(evidence("still USER_NON_FINANCIAL"), ReviewResolutionKind.USER_NON_FINANCIAL, review.resolutionKind)
        assertNull(evidence("startup did not repost"), app().container.financialTransactionRepository.findByRawSmsId(note.rawSmsId))
        assertEquals(evidence("no posted transaction"), 0, app().container.financialTransactionRepository.listAll().size)
        assertTrue(
            evidence("USER_NON_FINANCIAL is not a live retry"),
            app().container.processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.LIVE).isEmpty(),
        )
    }

    @Test(timeout = 120_000)
    fun armLiveRetry() = runBlocking {
        arm(DebugProcessHalt.CAPTURE)
        File(filesDir(), DebugProcessHalt.HOLD_RECONCILE_FILE_NAME).writeText("hold")
        ownAlJaziraCard()
        sendBankSms(PURCHASE_BODY)
        awaitHalt(DebugProcessHalt.CAPTURE)
        val rawSmsId = singleRawSmsId()
        app().container.processingRecovery.markExhausted(rawSmsId)
        assertEquals(
            evidence("M17 LIVE processing_retry row"),
            listOf(rawSmsId),
            app().container.processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.LIVE),
        )
        assertNull(evidence("exhausted retry has not posted"), app().container.financialTransactionRepository.findByRawSmsId(rawSmsId))
        JourneyNote.write(filesDir(), rawSmsId)
    }

    @Test(timeout = 120_000)
    fun resumeLiveRetry() = runBlocking {
        val rawSmsId = JourneyNote.read(filesDir()).rawSmsId
        awaitStartup()
        awaitFile(File(filesDir(), DebugProcessHalt.RECONCILE_HELD_MARKER_FILE_NAME))
        assertNotNull(
            evidence("resume reached reconcile"),
            app().container.parsedEventRepository.findByRawSmsId(rawSmsId),
        )
        assertEquals(
            evidence("LIVE processing_retry survives incomplete reconcile"),
            listOf(rawSmsId),
            app().container.processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.LIVE),
        )
        assertNull(
            evidence("incomplete reconcile does not post"),
            app().container.financialTransactionRepository.findByRawSmsId(rawSmsId),
        )
        assertEquals(evidence("no posted transaction"), 0, app().container.financialTransactionRepository.listAll().size)
    }

    private suspend fun armFinancial(stage: String) {
        arm(stage)
        ownAlJaziraCard()
        sendBankSms(PURCHASE_BODY)
        awaitHalt(stage)
    }

    private fun arm(stage: String) {
        grantSmsPermissions()
        File(filesDir(), DebugProcessHalt.REQUEST_FILE_NAME).writeText(stage)
    }

    private suspend fun ownAlJaziraCard() {
        app().container.cardRegistryRepository.setOwnership(
            CardReference(Bank.BANK_ALJAZIRA, "7271"),
            OwnershipStatus.OWNED,
        )
    }

    private suspend fun awaitStartup() {
        app().container.awaitStartupMaintenance()
    }

    private suspend fun assertPostedOnce(rawSmsId: String, expectedTransactionId: String? = null) {
        waitForPurchase(rawSmsId)
        waitUntilLiveWorkSettled(rawSmsId)
        assertPurchase(rawSmsId)
        val transactionId = postedId(rawSmsId)
        if (expectedTransactionId != null) {
            assertEquals(evidence("same posted transaction"), expectedTransactionId, transactionId)
        }
        assertEquals(evidence("exactly one posted transaction"), 1, app().container.financialTransactionRepository.listAll().size)
        assertEquals(
            evidence("one RawSms link"),
            listOf(rawSmsId),
            app().container.financialTransactionRepository.listRawSmsIds(transactionId),
        )
    }

    private suspend fun assertPurchase(rawSmsId: String) {
        val transaction = app().container.financialTransactionRepository.findByRawSmsId(rawSmsId)
        assertNotNull(evidence("financial SMS posted"), transaction)
        assertEquals(evidence("purchase expense"), FinancialTransactionType.EXPENSE, transaction!!.type)
        assertEquals(evidence("merchant"), "Keeta", transaction.merchant)
        assertEquals(evidence("amount"), Money.of("51.99", Currency.SAR), transaction.amount)
    }

    private suspend fun postedId(rawSmsId: String): String =
        app().container.financialTransactionRepository.findByRawSmsId(rawSmsId)!!.id

    private suspend fun assertRequiredReview(rawSmsId: String): String {
        val parsed = app().container.parsedEventRepository.findByRawSmsId(rawSmsId)
        assertNotNull(evidence("notice was parsed"), parsed)
        assertEquals(evidence("unknown family"), MessageFamily.UNKNOWN, parsed!!.event.messageFamily)
        assertEquals(evidence("review required"), ParseStatus.REVIEW_REQUIRED, parsed.event.parseStatus)
        val review = app().container.reviewRepository.findByRawSmsId(rawSmsId)
        assertNotNull(evidence("REQUIRED review"), review)
        assertEquals(evidence("review still required"), ReviewStatus.REQUIRED, review!!.status)
        assertNull(evidence("not resolved"), review.resolutionKind)
        assertTrue(evidence("reason unknown_message_family"), review.reasons.contains("unknown_message_family"))
        assertFalse(evidence("reason is not parse_review_required"), review.reasons.contains("parse_review_required"))
        assertNull(evidence("notice is not posted"), app().container.financialTransactionRepository.findByRawSmsId(rawSmsId))
        return review.id
    }

    private suspend fun singleRawSmsId(): String {
        val ids = app().container.rawSmsRepository.listIdsByReceivedAt()
        assertEquals(evidence("one RawSms row"), 1, ids.size)
        return ids.single()
    }

    private suspend fun waitForSingleRawSms(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val ids = app().container.rawSmsRepository.listIdsByReceivedAt()
            if (ids.size == 1) return ids.single()
            delay(50)
        }
        error(evidence("RawSms was not captured from the SMS_RECEIVED broadcast; broadcastCompleted=${broadcastCompleted()}"))
    }

    private suspend fun waitForPurchase(rawSmsId: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (app().container.financialTransactionRepository.findByRawSmsId(rawSmsId) != null) return
            delay(50)
        }
        error(evidence("posted transaction did not appear"))
    }

    private suspend fun waitUntilLiveWorkSettled(rawSmsId: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        var idlePolls = 0
        while (System.nanoTime() < deadline) {
            when (liveWorkInFlight(rawSmsId)) {
                true -> idlePolls = 0
                false -> {
                    idlePolls += 1
                    if (idlePolls >= 10) return
                }
                null -> idlePolls = 0
            }
            delay(50)
        }
        error(evidence("live WorkManager work did not finish"))
    }

    private suspend fun liveWorkInFlight(rawSmsId: String): Boolean? = withContext(Dispatchers.IO) {
        val infos = runCatching {
            WorkManager.getInstance(targetContext())
                .getWorkInfosForUniqueWork(WorkManagerLiveSmsWorkScheduler.uniqueWorkName(rawSmsId))
                .get(2, TimeUnit.SECONDS)
        }.getOrNull() ?: return@withContext null
        infos.any { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        }
    }

    private fun awaitHalt(stage: String) {
        val marker = File(filesDir(), DebugProcessHalt.MARKER_FILE_NAME)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (marker.isFile && marker.readText().trim() == stage) return
            Thread.sleep(50)
        }
        error(evidence("halt marker $stage did not appear; broadcastCompleted=${broadcastCompleted()}"))
    }

    private fun awaitFile(marker: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (marker.isFile) return
            Thread.sleep(50)
        }
        error(evidence("marker ${marker.name} did not appear"))
    }

    private fun sendBankSms(body: String) {
        val intent = GsmSmsDeliverPdu.receivedIntent(SENDER, body)
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        check(messages != null && messages.isNotEmpty()) {
            evidence("SMS_RECEIVED PDU was rejected by getMessagesFromIntent")
        }
        val joined = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        check(joined == body) {
            evidence("getMessagesFromIntent did not return the SMS body")
        }
        check(messages.all { it.displayOriginatingAddress == SENDER }) {
            evidence("getMessagesFromIntent originating address was not the AlJazira sender")
        }
        val hex = GsmSmsDeliverPdu.hexList(SENDER, body)
        val command = "am broadcast -a android.provider.Telephony.SMS_RECEIVED " +
            "-n $PACKAGE/.sms.receiver.IncomingSmsReceiver " +
            "--es format 3gpp --es ${DebugSmsPduExtra.EXTRA_PDU_HEX} $hex"
        thread(name = "m13-sms-broadcast", isDaemon = true) {
            broadcastOutput.set(shell(command))
        }
    }

    private fun grantSmsPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).forEach { permission ->
            runCatching { automation.grantRuntimePermission(PACKAGE, permission) }
        }
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand(command).use { parcel ->
            FileInputStream(parcel.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }

    private fun broadcastCompleted(): Boolean = broadcastOutput.get().contains("Broadcast completed")

    private fun app(): MasroofApplication =
        targetContext().applicationContext as MasroofApplication

    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun filesDir(): File = targetContext().filesDir

    private fun evidence(detail: String): String =
        "emulator-only instrumentation API ${Build.VERSION.SDK_INT}: $detail"

    private data class JourneyNote(
        val rawSmsId: String,
        val reviewId: String? = null,
        val transactionId: String? = null,
    ) {
        companion object {
            private const val FILE_NAME: String = "m13-journey-note"

            fun write(
                filesDir: File,
                rawSmsId: String,
                reviewId: String? = null,
                transactionId: String? = null,
            ) {
                File(filesDir, FILE_NAME).writeText(
                    listOf(
                        "rawSmsId=$rawSmsId",
                        "reviewId=${reviewId.orEmpty()}",
                        "transactionId=${transactionId.orEmpty()}",
                    ).joinToString(separator = "\n"),
                )
            }

            fun read(filesDir: File): JourneyNote {
                val lines = File(filesDir, FILE_NAME).readLines().associate { line ->
                    val key = line.substringBefore('=')
                    val value = line.substringAfter('=')
                    key to value
                }
                return JourneyNote(
                    rawSmsId = lines.getValue("rawSmsId"),
                    reviewId = lines["reviewId"]?.takeIf { it.isNotEmpty() },
                    transactionId = lines["transactionId"]?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    private companion object {
        const val PACKAGE: String = "com.baraa.masroof"
        const val SENDER: String = "AlJazira"
        val PURCHASE_BODY: String = """
            شراء عبر الانترنت
            بطاقة: 7271
            لدى: Keeta
            بمبلغ: 51.99 SAR
            في: 14:32 03-08-2026
        """.trimIndent()
        const val NOTICE_BODY: String =
            "تنبيه بنك الجزيرة: حدث تحديث في خدماتك. راجع التطبيق للتفاصيل."
    }
}
