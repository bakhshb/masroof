package com.baraa.masroof.integration.bank

import com.baraa.masroof.testsupport.saveCorrection
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.patterns.PatternApprovalException
import com.baraa.masroof.application.patterns.PatternApprovalProblem
import com.baraa.masroof.application.patterns.PatternApprovalStatus
import com.baraa.masroof.application.patterns.PatternOperation
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.FinancialTransactionType
import com.baraa.masroof.domain.model.ParseStatus
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import com.baraa.masroof.domain.model.UserCorrection
import com.baraa.masroof.domain.period.FinancialPeriod
import com.baraa.masroof.sms.datasource.InboxRow
import com.baraa.masroof.sms.datasource.SmsDataSource
import com.baraa.masroof.sms.mapper.AndroidSmsMapper
import com.baraa.masroof.sms.model.ProviderSmsRecord
import com.baraa.masroof.testsupport.TestStorageContext
import com.baraa.masroof.testsupport.ingest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StcSingleAccountJourneyTest {
    private val bankId = "BANK_STC"
    private val incoming = "Internal incoming transfer\nAmount:100SAR\nFrom:OTHER PERSON\nAcc:321\nAt:15/10/26 12:30"
    private val outgoing = "Internal outward transfer\nAmount:25SAR\nTo:SECOND PERSON\nAcc:876\nAt:16/10/26 12:30"
    private val period = FinancialPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))

    @Test fun importedMessagesUseTheExplicitOwnAccountWhileCounterpartyNumbersVaryAndDashboardShowsThePostedFlows() = runBlocking {
        val rows = listOf(
            row("stc-in-one", incoming, "2026-10-15T10:00:00Z"),
            row("stc-in-two", incoming.replace("100SAR", "50SAR").replace("Acc:321", "Acc:654"), "2026-10-15T11:00:00Z"),
            row("stc-out", outgoing, "2026-10-16T10:00:00Z"),
        )
        val source = object : SmsDataSource {
            override suspend fun <T> withInbox(receivedAfter: Instant?, block: suspend (Sequence<InboxRow>) -> T): T = block(queryInbox(receivedAfter))
            fun queryInbox(receivedAfter: Instant?): Sequence<InboxRow> =
                rows.asSequence().map(InboxRow::Valid)
        }
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "stc-single-account-${UUID.randomUUID()}")
        val container = AppContainer(context, smsDataSourceOverride = source)
        try {
            val workflow = container.patterns.messagePatternsWorkflow
            val imported = container.sms.bankSmsImportWorkflow.import(setOf(bankId), period.startDate, LocalDate.of(2026, 10, 26))
            assertEquals(3, imported.inserted)
            assertEquals(3, imported.reviewRequired)
            assertEquals(3, container.storage.reviewRepository.listRequired().size)
            assertTrue(container.storage.financialTransactionRepository.listAll().isEmpty())

            // Registering an account cannot itself turn preview matches into financial authority.
            val ownAccount = container.dashboard.settingsRegistryWorkflow.addAccount(bankId, "4321", "My STC account")
            val account = workflow.patternAccounts(bankId).single()
            assertEquals("4321", account.last4)
            assertTrue(container.dashboard.dashboardService.loadOverview(period).transactions.isEmpty())
            assertEquals(PatternApprovalStatus.PREVIEW_ONLY,
                workflow.catalog().banks.single { it.bankId == bankId }.currentPatterns.single { it.id == "stc-internal-in" }.status)

            val generation = workflow.catalog().generation
            val examples = workflow.approvalExamples(bankId, generation, "stc-internal-in")
            assertEquals(setOf(incoming, rows[1].body), examples.map { it.body }.toSet())
            assertEquals(setOf("321", "654"), examples.map { it.fields.getValue("account_reference").text }.toSet())
            assertEquals(rows.take(2).map { row ->
                container.storage.reviewRepository.findByRawSmsId(AndroidSmsMapper.toRawSms(row).id)!!.id
            }.toSet(), examples.map { it.reviewId }.toSet())
            assertEquals(3, workflow.previewPending(bankId, generation).requiredReviewCount)

            val mismatch = runCatching {
                workflow.previewFinancial(generation, bankId, "stc-internal-in", PatternOperation.TRANSFER_IN,
                    account.id, outgoing, null, setOf("account_reference"))
            }.exceptionOrNull()
            assertTrue(mismatch is PatternApprovalException)
            assertEquals(PatternApprovalProblem.MESSAGE_MISMATCH, (mismatch as PatternApprovalException).problem)
            val unresolvedReference = runCatching {
                workflow.previewFinancial(generation, bankId, "stc-internal-in", PatternOperation.TRANSFER_IN,
                    account.id, incoming, null, emptySet())
            }.exceptionOrNull()
            assertTrue(unresolvedReference is PatternApprovalException)
            assertEquals(PatternApprovalProblem.REFERENCE_ROLE, (unresolvedReference as PatternApprovalException).problem)

            val beforeApproval = rows.associate { row ->
                val id = AndroidSmsMapper.toRawSms(row).id
                id to container.storage.parsedEventRepository.findByRawSmsId(id)
            }
            for ((patternId, operation, example) in listOf(
                Triple("stc-internal-in", PatternOperation.TRANSFER_IN, incoming),
                Triple("stc-internal-out", PatternOperation.TRANSFER_OUT, outgoing),
            )) {
                val preview = workflow.previewFinancial(workflow.catalog().generation, bankId, patternId, operation,
                    account.id, example, null, setOf("account_reference"))
                workflow.saveFinancial(preview)
            }
            assertTrue(container.storage.financialTransactionRepository.listAll().isEmpty())
            // An approved pattern posts to this account, so it cannot be deleted even before anything is posted.
            val deleteOwn = com.baraa.masroof.application.settings.RegistryDeleteTarget.Account(ownAccount)
            assertEquals(com.baraa.masroof.application.settings.DeleteBlocker.MESSAGE_PATTERN,
                container.dashboard.settingsRegistryWorkflow.deletionPreview(deleteOwn).blocker)
            beforeApproval.forEach { (id, evidence) -> assertEquals(evidence, container.storage.parsedEventRepository.findByRawSmsId(id)) }

            val approved = workflow.catalog()
            val applied = workflow.applyPending(bankId, approved.generation, patternId = "stc-internal-in")
            assertEquals(2, applied.processed)
            assertEquals(2, applied.posted)
            assertFalse(applied.incomplete)
            assertEquals(3, applied.reviewsBefore)
            assertEquals(1, applied.reviewsAfter)
            assertEquals(1, workflow.previewPending(bankId, approved.generation).requiredReviewCount)
            assertTrue(workflow.approvalExamples(bankId, approved.generation, "stc-internal-in").isEmpty())
            val outgoingId = AndroidSmsMapper.toRawSms(rows.last()).id
            assertEquals(beforeApproval.getValue(outgoingId), container.storage.parsedEventRepository.findByRawSmsId(outgoingId))
            assertEquals(ReviewStatus.REQUIRED, container.storage.reviewRepository.findByRawSmsId(outgoingId)!!.status)
            val inboundOnly = container.dashboard.dashboardService.loadOverview(period).accountsFleet!!.accounts.single { it.bank == Bank(bankId) }
            assertEquals("150.00", inboundOnly.totalIn.amount.toPlainString())
            assertEquals("0.00", inboundOnly.totalOut.amount.toPlainString())

            val appliedOutgoing = workflow.applyPending(bankId, approved.generation, patternId = "stc-internal-out")
            assertEquals(1, appliedOutgoing.processed)
            assertEquals(1, appliedOutgoing.posted)
            assertEquals(1, appliedOutgoing.reviewsBefore)
            assertEquals(0, appliedOutgoing.reviewsAfter)
            assertFalse(appliedOutgoing.incomplete)
            assertTrue(container.storage.reviewRepository.listRequired().isEmpty())
            assertEquals(listOf(ownAccount), container.storage.accountRegistryRepository.listAll().map { AccountReference(it.bank, it.maskedNumber) })

            rows.forEach { row ->
                val id = AndroidSmsMapper.toRawSms(row).id
                assertEquals(ParseStatus.SUCCESS, container.storage.parsedEventRepository.findByRawSmsId(id)!!.event.parseStatus)
                assertEquals(ReviewStatus.RESOLVED, container.storage.reviewRepository.findByRawSmsId(id)!!.status)
                assertTrue(container.storage.financialTransactionRepository.isRawSmsLinked(id))
            }
            val posted = container.storage.financialTransactionRepository.listAll()
            assertEquals(2, posted.count { it.type == FinancialTransactionType.EXTERNAL_TRANSFER_IN })
            assertEquals(1, posted.count { it.type == FinancialTransactionType.EXTERNAL_TRANSFER_OUT })
            assertTrue(posted.all { it.sourceContainerId == "account:$bankId:4321" || it.destinationContainerId == "account:$bankId:4321" })

            val dashboard = container.dashboard.dashboardService.loadOverview(period)
            val stc = requireNotNull(dashboard.accountsFleet).accounts.single { it.bank == Bank(bankId) }
            assertEquals("150.00", stc.totalIn.amount.toPlainString())
            assertEquals("25.00", stc.totalOut.amount.toPlainString())
            assertEquals("125.00", stc.remaining.amount.toPlainString())
            assertEquals(3, dashboard.transactions.size)
            assertEquals(0, workflow.applyPending(bankId, workflow.catalog().generation).processed)
            assertEquals(posted, container.storage.financialTransactionRepository.listAll())
            val linked = container.dashboard.settingsRegistryWorkflow.deletionPreview(deleteOwn)
            assertEquals(com.baraa.masroof.application.settings.DeleteBlocker.LINKED_TRANSACTIONS, linked.blocker)
            assertEquals(3, linked.linkedCount)
            assertTrue(runCatching { container.dashboard.settingsRegistryWorkflow.delete(deleteOwn) }.isFailure)
            assertEquals(listOf(ownAccount), container.storage.accountRegistryRepository.listAll().map { AccountReference(it.bank, it.maskedNumber) })

            // A later counterparty suffix must use the same approved own-account constant.
            val future = row("stc-future", incoming.replace("100SAR", "20SAR").replace("Acc:321", "Acc:999"), "2026-10-17T10:00:00Z")
            container.sms.processRawSmsUseCase.ingest(AndroidSmsMapper.toRawSms(future))
            assertEquals(4, container.storage.financialTransactionRepository.listAll().size)
            val refreshed = container.dashboard.dashboardService.loadOverview(period).accountsFleet!!.accounts.single { it.bank == Bank(bankId) }
            assertEquals("170.00", refreshed.totalIn.amount.toPlainString())
            assertEquals("145.00", refreshed.remaining.amount.toPlainString())
        } finally {
            container.close()
            context.getDatabasePath("masroof.db").parentFile?.deleteRecursively()
        }
    }

    @Test fun approvalExamplesExcludeHumanDecisionsAndConflictingPatterns() = runBlocking {
        val context = TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "stc-examples-${UUID.randomUUID()}")
        val container = AppContainer(context)
        try {
            val rows = (1..3).map { index ->
                AndroidSmsMapper.toRawSms(row("stc-example-$index", incoming.replace("100SAR", "${index}0SAR"), "2026-10-15T10:00:00Z"))
            }
            rows.forEach { container.sms.processRawSmsUseCase.ingest(it) }
            val dismissed = container.storage.reviewRepository.findByRawSmsId(rows[1].id)!!
            container.storage.reviewRepository.markResolved(dismissed.id, ReviewResolutionKind.USER_NON_FINANCIAL, Instant.now(), null)
            container.storage.database.saveCorrection(UserCorrection("counterparty-edit", rows[2].id,
                null, null, null, "Manually corrected person", Instant.now()))

            val workflow = container.patterns.messagePatternsWorkflow
            val snapshot = workflow.load()
            val examples = workflow.approvalExamples(bankId, snapshot.generation, "stc-internal-in")
            assertEquals(listOf(rows[0].body), examples.map { it.body })
            assertEquals(container.storage.reviewRepository.findByRawSmsId(rows[0].id)!!.id, examples.single().reviewId)

            val bank = snapshot.banks.single { it.bankId == bankId }
            val conflicting = bank.currentPatterns.single { it.id == "stc-internal-in" }.copy(id = "competing-incoming")
            workflow.saveBank(snapshot.generation, bank.copy(patterns = listOf(conflicting)))
            assertTrue(workflow.approvalExamples(bankId, workflow.catalog().generation, "stc-internal-in").isEmpty())
            assertTrue(container.storage.financialTransactionRepository.listAll().isEmpty())
        } finally {
            container.close()
            context.getDatabasePath("masroof.db").parentFile?.deleteRecursively()
        }
    }

    private fun row(id: String, body: String, receivedAt: String) = ProviderSmsRecord(id, "STC Bank", body, Instant.parse(receivedAt))
}
