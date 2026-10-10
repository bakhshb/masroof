package com.baraa.masroof.instrumentation

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.baraa.masroof.MainActivity
import com.baraa.masroof.MasroofApplication
import com.baraa.masroof.R
import com.baraa.masroof.core.money.Currency
import com.baraa.masroof.core.money.Money
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.FinancialContainerIdFactory
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.OwnershipStatus
import com.baraa.masroof.parsing.model.ParsedEventDetails
import com.baraa.masroof.domain.model.Confidence
import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.MessageFamily
import com.baraa.masroof.domain.model.MoneyDirection
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ParsedEvent
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.domain.statement.StatementComparisonStatus
import com.baraa.masroof.presentation.statement.STATEMENT_RECONCILIATION_PICK_TAG
import com.baraa.masroof.presentation.statement.statementOutcomeTag
import com.baraa.masroof.presentation.statement.statementSectionTag
import com.baraa.masroof.sms.hash.SmsBodyHasher
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Device evidence for read-only statement reconciliation on an emulator.
 *
 * The journey uses the screen's [androidx.activity.result.contract.ActivityResultContracts.OpenDocument]
 * launcher. UiAutomator drives DocumentsUI: the anonymized CSV is inserted through MediaStore
 * Downloads and copied to `/sdcard/Download`, Settings opens the statement screen, the pick
 * button launches the system picker, and the test taps that file. There is no test-only import
 * bypass.
 *
 * [androidx.test.core.app.ActivityScenario.recreate] is a configuration change. The comparison
 * stays in the activity ViewModel. This is not process death.
 *
 * Orchestrator `clearPackageData` starts each test clean, so the ledger and the CSV are created
 * inside [statementImport_showsFourOutcomesAndSurvivesRecreate].
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class StatementReconciliationSmokeTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rule: TestRule = RuleChain
        .outerRule(StatementOnboardingRule())
        .around(composeRule)

    @Test
    fun statementImport_showsFourOutcomesAndSurvivesRecreate() {
        awaitFinancialShell()
        seedLedgerAndEvidence()
        publishStatementCsv()
        val before = mutationCounts()
        assertSeededCounts(before)

        openStatementScreen()
        val beforePicker = mutationCounts()
        assertSameCounts(before, beforePicker, "after opening the statement screen")

        launchSystemPicker()
        selectPublishedCsv()
        assertFourOutcomes()
        scrollOutcome(StatementComparisonStatus.AMBIGUOUS)
        capture("m19-outcomes-summary")
        scrollSection(StatementComparisonStatus.MATCHED)
        capture("m19-matched-and-statement-lines")
        scrollSection(StatementComparisonStatus.AMBIGUOUS)
        capture("m19-ledger-and-ambiguous-lines")
        composeRule.waitForIdle()
        val afterShown = mutationCounts()
        assertSameCounts(before, afterShown, "after import and showing the result")

        composeRule.activityRule.scenario.recreate()
        assertFourOutcomes()
        scrollOutcome(StatementComparisonStatus.AMBIGUOUS)
        capture("m19-after-recreate")
        val afterRecreate = mutationCounts()
        assertSameCounts(before, afterRecreate, "after configuration-change recreate")
    }

    private fun seedLedgerAndEvidence() {
        runBlocking(Dispatchers.IO) {
            val container = application().container
            container.accountRegistryRepository.setOwnership(
                AccountReference(Bank.BANK_ALJAZIRA, ACCOUNT),
                OwnershipStatus.OWNED,
            )
            container.withDatabaseTransaction {
                val account = FinancialContainerIdFactory.accountId(Bank.BANK_ALJAZIRA, ACCOUNT)
                SEEDED.forEach { row ->
                    val at = at(row.localDateTime)
                    container.rawSmsRepository.insertIfAbsent(
                        RawSms(
                            id = row.rawSmsId,
                            sender = "AlJazira",
                            body = row.body,
                            receivedAt = at,
                            deviceMessageId = row.rawSmsId,
                            bodyHash = SmsBodyHasher.sha256Hex(row.body),
                        ),
                    )
                    container.parsedEventRepository.save(
                        ParsedEvent(
                            id = row.eventId,
                            rawSmsId = row.rawSmsId,
                            bank = Bank.BANK_ALJAZIRA,
                            messageFamily = MessageFamily.PURCHASE,
                            direction = MoneyDirection.OUTGOING,
                            amount = Money.of(row.amount, Currency.SAR),
                            purchaseChannel = null,
                            sourceAccountRef = null,
                            destinationAccountRef = null,
                            cardRef = null,
                            merchant = "Anon merchant",
                            counterparty = null,
                            occurredAt = at,
                            bankNetworkType = null,
                            confidence = Confidence(1.0),
                            parseStatus = ParseStatus.SUCCESS,
                        ),
                        ParsedEventDetails(
                            transactionReference = if (row.transactionId == "m19-matched") "REF-M" else null,
                        ),
                    )
                    val saved = container.financialTransactionRepository.save(
                        transaction = FinancialTransaction(
                            id = row.transactionId,
                            type = FinancialTransactionType.EXPENSE,
                            amount = Money.of(row.amount, Currency.SAR),
                            occurredAt = at,
                            sourceContainerId = account,
                            destinationContainerId = null,
                            merchant = "Anon merchant",
                            counterparty = null,
                            categoryId = null,
                            linkedParsedEventIds = listOf(row.eventId),
                            occurredAtZone = ZONE.id,
                        ),
                        rawSmsIds = listOf(row.rawSmsId),
                    )
                    check(saved is FinancialTransactionSaveResult.Saved) {
                        emulatorMessage("ledger seed was not saved for ${row.transactionId}: $saved")
                    }
                }
                container.userCorrectionRepository.save(
                    UserCorrection(
                        id = "uc-m19-matched",
                        targetRawSmsId = "m19-sms-matched",
                        correctedType = null,
                        correctedAmount = null,
                        correctedMerchant = "Anon merchant",
                        correctedCounterparty = null,
                        createdAt = at("2026-04-02T12:05:00"),
                    ),
                )
                container.reviewRepository.upsertRequired(
                    rawSmsId = "m19-sms-ledger-only",
                    kind = ReviewKind.NEEDS_REVIEW,
                    reasons = listOf("m19_statement_fixture"),
                    now = at("2026-04-11T12:05:00"),
                )
            }
        }
    }

    private fun publishStatementCsv() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(CSV_NAME),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, CSV_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw AssertionError(emulatorMessage("MediaStore Downloads insert failed for $CSV_NAME"))
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(ANON_CSV.toByteArray(Charsets.UTF_8))
        } ?: throw AssertionError(emulatorMessage("could not write $CSV_NAME"))
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        val indexed = indexedPath(uri) ?: "/sdcard/Download/$CSV_NAME"
        val device = device()
        device.executeShellCommand("mkdir /sdcard/Download")
        device.executeShellCommand("cp $indexed /sdcard/Download/$CSV_NAME")
        awaitPublished("/sdcard/Download/$CSV_NAME", CSV_NAME)
    }

    private fun openStatementScreen() {
        val settings = text(R.string.dashboard_open_settings)
        waitUntil("settings action") { contentDescriptionExists(settings) }
        composeRule.onNodeWithContentDescription(settings).performClick()
        clickText(text(R.string.settings_statement_reconciliation_title))
        awaitTag(STATEMENT_RECONCILIATION_PICK_TAG)
    }

    private fun launchSystemPicker() {
        awaitTag(STATEMENT_RECONCILIATION_PICK_TAG)
        scrollTo(hasTestTag(STATEMENT_RECONCILIATION_PICK_TAG))
        composeRule.onNodeWithTag(STATEMENT_RECONCILIATION_PICK_TAG).assertIsDisplayed()
        composeRule.waitForIdle()
        val label = text(R.string.settings_statement_pick_file)
        val button = device().wait(Until.findObject(By.text(label)), PICKER_TIMEOUT_MS)
            ?: throw AssertionError(emulatorMessage("pick button '$label' was not in the window"))
        button.click()
    }

    /**
     * DocumentsUI is the activity started by the screen's OpenDocument contract.
     * The test taps the published CSV there. It does not synthesize an activity result.
     */
    private fun selectPublishedCsv() {
        val device = device()
        val pkg = awaitDocumentsUi(device)
        if (!clickDocument(device, CSV_STEM)) {
            openDownloadsRoot(device, pkg)
            if (!clickDocument(device, CSV_STEM)) {
                throw AssertionError(
                    emulatorMessage("could not select $CSV_NAME in $pkg. ${hierarchySnippet(device)}"),
                )
            }
        }
        awaitReturnToApp(device)
    }

    private fun awaitDocumentsUi(device: UiDevice): String {
        val deadline = SystemClock.uptimeMillis() + PICKER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val pkg = device.currentPackageName.orEmpty()
            if (pkg.contains("documentsui")) return pkg
            if (pkg.contains("resolver") || pkg == "android") {
                clickFirstLabel(device, listOf("Files", "Documents", "الملفات"))
            }
            SystemClock.sleep(250)
        }
        throw AssertionError(
            emulatorMessage(
                "DocumentsUI did not open. package=${device.currentPackageName}. ${hierarchySnippet(device)}",
            ),
        )
    }

    private fun openDownloadsRoot(device: UiDevice, pkg: String) {
        val showRoots = device.findObject(By.desc("Show roots"))
            ?: device.findObject(By.descContains("roots"))
            ?: device.findObject(By.descContains("الجذور"))
            ?: device.findObject(By.res(pkg, "toolbar"))
                ?.findObject(By.clazz("android.widget.ImageButton"))
        showRoots?.click()
        device.waitForIdle(2_000)
        val downloads = device.wait(Until.findObject(By.text("Downloads")), 4_000)
            ?: device.findObject(By.text("Download"))
            ?: device.findObject(By.textContains("التنزيل"))
        if (downloads == null) {
            throw AssertionError(
                emulatorMessage("Downloads root was not listed in $pkg. ${hierarchySnippet(device)}"),
            )
        }
        downloads.click()
        device.wait(Until.findObject(By.textContains(CSV_STEM)), 8_000)
    }

    private fun clickDocument(device: UiDevice, stem: String): Boolean {
        val visible = device.findObject(By.textContains(stem))
        if (visible != null) {
            visible.click()
            return true
        }
        val scrolled = runCatching {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().textContains(stem))
        }.getOrDefault(false)
        if (!scrolled) return false
        val node = device.findObject(By.textContains(stem)) ?: return false
        node.click()
        return true
    }

    private fun awaitReturnToApp(device: UiDevice) {
        val deadline = SystemClock.uptimeMillis() + PICKER_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val pkg = device.currentPackageName.orEmpty()
            if (pkg == APP_PACKAGE) return
            if (pkg.contains("documentsui")) {
                clickFirstLabel(device, listOf("Open", "OPEN", "Select", "SELECT", "فتح"))
            }
            SystemClock.sleep(250)
        }
        throw AssertionError(
            emulatorMessage(
                "picker did not return to $APP_PACKAGE. package=${device.currentPackageName}. " +
                    hierarchySnippet(device),
            ),
        )
    }

    private fun clickFirstLabel(device: UiDevice, labels: List<String>) {
        labels.firstNotNullOfOrNull { label -> device.findObject(By.text(label)) }?.click()
    }

    private fun assertFourOutcomes() {
        awaitTag(statementOutcomeTag(StatementComparisonStatus.MATCHED))
        assertOutcome(StatementComparisonStatus.MATCHED, 1)
        assertOutcome(StatementComparisonStatus.STATEMENT_ONLY, 1)
        assertOutcome(StatementComparisonStatus.LEDGER_ONLY, 1)
        assertOutcome(StatementComparisonStatus.AMBIGUOUS, 4)
        assertLine(StatementComparisonStatus.MATCHED, "MATCHED ANON")
        assertLine(StatementComparisonStatus.STATEMENT_ONLY, "STATEMENT ONLY ANON")
        assertLine(StatementComparisonStatus.LEDGER_ONLY, "m19-ledger-only")
        assertLine(StatementComparisonStatus.AMBIGUOUS, "AMBIGUOUS ANON A")
        assertLine(StatementComparisonStatus.AMBIGUOUS, "AMBIGUOUS ANON B")
    }

    private fun assertOutcome(status: StatementComparisonStatus, count: Int) {
        val tag = statementOutcomeTag(status)
        awaitTag(tag)
        scrollTo(hasTestTag(tag))
        try {
            composeRule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertTextContains(status.name, substring = true)
                .assertTextContains(count.toString(), substring = true)
        } catch (error: AssertionError) {
            throw AssertionError(
                emulatorMessage("${status.name} count $count was not shown"),
                error,
            )
        }
    }

    private fun assertLine(status: StatementComparisonStatus, title: String) {
        val section = statementSectionTag(status)
        awaitTag(section)
        scrollTo(hasTestTag(section))
        try {
            composeRule.onNodeWithTag(section)
                .assertIsDisplayed()
                .assertTextContains(status.name, substring = true)
        } catch (error: AssertionError) {
            throw AssertionError(emulatorMessage("${status.name} section was not shown"), error)
        }
        val matcher = hasText(title, substring = true)
        waitUntil("line '$title'") { nodeExists(matcher) }
        scrollTo(matcher)
        assertTrue(
            emulatorMessage("'$title' for ${status.name} was not displayed"),
            nodeExists(matcher),
        )
        composeRule.onAllNodes(matcher)[0].assertIsDisplayed()
    }

    private fun scrollOutcome(status: StatementComparisonStatus) {
        awaitTag(statementOutcomeTag(status))
        scrollTo(hasTestTag(statementOutcomeTag(status)))
        composeRule.waitForIdle()
    }

    private fun scrollSection(status: StatementComparisonStatus) {
        awaitTag(statementSectionTag(status))
        scrollTo(hasTestTag(statementSectionTag(status)))
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: throw AssertionError(emulatorMessage("UiAutomation.takeScreenshot returned null for $name"))
        val context = instrumentation.targetContext
        val displayName = "$name.png"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "Download/m19-statement-screenshots/"
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(displayName),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw AssertionError(emulatorMessage("could not store screenshot $displayName"))
        resolver.openOutputStream(uri)?.use { stream ->
            val encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            if (!encoded) {
                throw AssertionError(emulatorMessage("PNG encode failed for $displayName"))
            }
        } ?: throw AssertionError(emulatorMessage("screenshot stream was null for $displayName"))
        bitmap.recycle()
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        val indexed = indexedPath(uri)
            ?: "/sdcard/Download/m19-statement-screenshots/$displayName"
        val device = device()
        device.executeShellCommand("mkdir /sdcard/m19-statement-screenshots")
        val target = "/sdcard/m19-statement-screenshots/$displayName"
        device.executeShellCommand("cp $indexed $target")
        awaitPublished(target, displayName)
    }

    private fun awaitPublished(path: String, label: String) {
        val device = device()
        val deadline = SystemClock.uptimeMillis() + 5_000
        var listing = ""
        while (SystemClock.uptimeMillis() < deadline) {
            listing = device.executeShellCommand("ls -l $path")
            if (listing.contains(label) && !listing.contains("No such")) return
            SystemClock.sleep(200)
        }
        throw AssertionError(emulatorMessage("$label was not published at $path ($listing)"))
    }

    private fun indexedPath(uri: android.net.Uri): String? {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (index < 0) return null
            return cursor.getString(index)
        }
        return null
    }

    private fun mutationCounts(): MutationCounts = runBlocking(Dispatchers.IO) {
        val field = application().container.javaClass.getDeclaredField("database")
        field.isAccessible = true
        val database = field.get(application().container) as MasroofDatabase
        val sqlite = database.openHelper.readableDatabase
        fun count(table: String): Int {
            check(table in COUNTED_TABLES) { emulatorMessage("unexpected table $table") }
            sqlite.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
                check(cursor.moveToFirst()) { emulatorMessage("count failed for $table") }
                return cursor.getInt(0)
            }
        }
        MutationCounts(
            financialTransaction = count("financial_transaction"),
            rawSms = count("raw_sms"),
            parsedEvent = count("parsed_event"),
            userCorrection = count("user_correction"),
            review = count("review_item"),
        )
    }

    private fun assertSeededCounts(counts: MutationCounts) {
        assertTrue(emulatorMessage("financial_transaction seed $counts"), counts.financialTransaction >= 4)
        assertTrue(emulatorMessage("raw_sms seed $counts"), counts.rawSms >= 4)
        assertTrue(emulatorMessage("parsed_event seed $counts"), counts.parsedEvent >= 4)
        assertTrue(emulatorMessage("user_correction seed $counts"), counts.userCorrection >= 1)
        assertTrue(emulatorMessage("review_item seed $counts"), counts.review >= 1)
    }

    private fun assertSameCounts(before: MutationCounts, after: MutationCounts, stage: String) {
        assertEquals(
            emulatorMessage(
                "$stage changed rows: " +
                    "financial_transaction ${before.financialTransaction}->${after.financialTransaction}, " +
                    "raw_sms ${before.rawSms}->${after.rawSms}, " +
                    "parsed_event ${before.parsedEvent}->${after.parsedEvent}, " +
                    "user_correction ${before.userCorrection}->${after.userCorrection}, " +
                    "review_item ${before.review}->${after.review}",
            ),
            before,
            after,
        )
    }

    private fun awaitFinancialShell() {
        awaitText(text(R.string.dashboard_empty_period), scroll = true)
        val blocked = text(R.string.startup_maintenance_blocked)
        assertEquals(emulatorMessage("startup maintenance blocked the shell"), 0, nodeCount(textMatcher(blocked)))
        composeRule.onNodeWithContentDescription(text(R.string.dashboard_open_settings)).assertIsDisplayed()
    }

    private fun awaitText(value: String, scroll: Boolean) {
        waitUntil("text '$value'") { textExists(value) }
        if (scroll) scrollTo(textMatcher(value))
        composeRule.onAllNodes(textMatcher(value))[0].assertIsDisplayed()
    }

    private fun awaitTag(tag: String) {
        waitUntil("tag '$tag'") { nodeExists(hasTestTag(tag)) }
    }

    private fun clickText(value: String) {
        val matcher = textMatcher(value) and hasClickAction()
        waitUntil("clickable text '$value'") { nodeExists(matcher) }
        scrollTo(matcher)
        composeRule.onNode(matcher).performClick()
        composeRule.waitForIdle()
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        val scrollables = composeRule.onAllNodes(hasScrollAction())
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        for (index in scrollables.indices) {
            val scrolled = runCatching {
                composeRule.onAllNodes(hasScrollAction())[index].performScrollToNode(matcher)
            }.isSuccess
            if (scrolled) return
        }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(TIMEOUT_MS, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(emulatorMessage("timed out after ${TIMEOUT_MS}ms waiting for $description"), e)
        }
    }

    private fun textMatcher(value: String) = hasText(value, substring = true)

    private fun nodeExists(matcher: SemanticsMatcher): Boolean =
        nodeCount(matcher) > 0

    private fun nodeCount(matcher: SemanticsMatcher): Int =
        composeRule.onAllNodes(matcher)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size

    private fun textExists(value: String): Boolean = nodeExists(textMatcher(value))

    private fun contentDescriptionExists(value: String): Boolean =
        composeRule.onAllNodes(hasContentDescription(value))
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .isNotEmpty()

    private fun text(id: Int): String = composeRule.activity.getString(id)

    private fun device(): UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun hierarchySnippet(device: UiDevice): String {
        val bytes = ByteArrayOutputStream()
        return runCatching {
            device.dumpWindowHierarchy(bytes)
            bytes.toString(Charsets.UTF_8.name()).replace(Regex("\\s+"), " ").take(1_200)
        }.getOrElse { "hierarchy unavailable (${it.javaClass.simpleName})" }
    }

    private fun application(): MasroofApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MasroofApplication

    private fun at(localDateTime: String): Instant =
        LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant()

    private fun emulatorMessage(detail: String): String =
        "emulator API ${Build.VERSION.SDK_INT}: $detail"

    private data class MutationCounts(
        val financialTransaction: Int,
        val rawSms: Int,
        val parsedEvent: Int,
        val userCorrection: Int,
        val review: Int,
    )

    private data class SeededMovement(
        val transactionId: String,
        val rawSmsId: String,
        val eventId: String,
        val amount: String,
        val localDateTime: String,
        val body: String,
    )

    private companion object {
        const val APP_PACKAGE: String = "com.baraa.masroof"
        const val ACCOUNT: String = "3001"
        const val CSV_NAME: String = "m19-anon-statement.csv"
        const val CSV_STEM: String = "m19-anon-statement"
        const val TIMEOUT_MS: Long = 20_000L
        const val PICKER_TIMEOUT_MS: Long = 15_000L
        val ZONE: ZoneId = ZoneId.of("Asia/Riyadh")
        val COUNTED_TABLES: Set<String> = setOf(
            "financial_transaction",
            "raw_sms",
            "parsed_event",
            "user_correction",
            "review_item",
        )
        val SEEDED: List<SeededMovement> = listOf(
            SeededMovement(
                transactionId = "m19-matched",
                rawSmsId = "m19-sms-matched",
                eventId = "pe-m19-matched",
                amount = "12.00",
                localDateTime = "2026-04-02T12:00:00",
                body = "m19 anonymized ledger evidence matched",
            ),
            SeededMovement(
                transactionId = "m19-ambiguous-a",
                rawSmsId = "m19-sms-ambiguous-a",
                eventId = "pe-m19-ambiguous-a",
                amount = "5.00",
                localDateTime = "2026-04-10T09:00:00",
                body = "m19 anonymized ledger evidence ambiguous a",
            ),
            SeededMovement(
                transactionId = "m19-ambiguous-b",
                rawSmsId = "m19-sms-ambiguous-b",
                eventId = "pe-m19-ambiguous-b",
                amount = "5.00",
                localDateTime = "2026-04-10T18:00:00",
                body = "m19 anonymized ledger evidence ambiguous b",
            ),
            SeededMovement(
                transactionId = "m19-ledger-only",
                rawSmsId = "m19-sms-ledger-only",
                eventId = "pe-m19-ledger-only",
                amount = "7.00",
                localDateTime = "2026-04-11T12:00:00",
                body = "m19 anonymized ledger evidence ledger only",
            ),
        )
        val ANON_CSV: String = """
            # masroof-statement-v1
            # periodStart=2026-04-01
            # periodEnd=2026-04-30
            bankId,accountMasked,bookedAt,direction,amount,currency,description,reference
            BANK_ALJAZIRA,3001,2026-04-02,DEBIT,12.00,SAR,MATCHED ANON,REF-M
            BANK_ALJAZIRA,3001,2026-04-10,DEBIT,5.00,SAR,AMBIGUOUS ANON A,
            BANK_ALJAZIRA,3001,2026-04-10,DEBIT,5.00,SAR,AMBIGUOUS ANON B,
            BANK_ALJAZIRA,3001,2026-04-11,DEBIT,4.00,SAR,STATEMENT ONLY ANON,REF-S
        """.trimIndent() + "\n"
    }
}

/**
 * Marks onboarding complete before [MainActivity] reads it. The ledger and the CSV are not
 * seeded here; the test method creates both after startup maintenance is ready.
 */
private class StatementOnboardingRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val app = InstrumentationRegistry.getInstrumentation()
                    .targetContext.applicationContext as MasroofApplication
                app.container.onboardingPreferencesRepository.setOnboardingStarted(true)
                app.container.onboardingPreferencesRepository.setOnboardingCompleted(true)
                base.evaluate()
            }
        }
}
