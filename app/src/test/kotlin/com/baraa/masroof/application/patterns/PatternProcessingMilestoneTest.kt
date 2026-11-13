package com.baraa.masroof.application.patterns

import com.baraa.masroof.testsupport.correction
import com.baraa.masroof.testsupport.saveCorrection
import com.baraa.masroof.testsupport.ingest
import com.baraa.masroof.testsupport.uniqueStorageContext
import com.baraa.masroof.testsupport.withAppContainer
import com.baraa.masroof.application.interpretation.ConfiguredPatternAdapters

import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.ingestion.SmsIngestionResult
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.engine.bank.patterns.*
import com.baraa.masroof.sms.hash.SmsBodyHasher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PatternProcessingMilestoneTest {
    @Test fun deletingAnApprovedPatternPreservesPostedEvidenceAndRejectsAStaleApproval() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "account_reference", emptySet()))
            c.sms.processRawSmsUseCase.ingest(sms("delete-posted", incoming))
            val evidence = c.storage.parsedEventRepository.findByRawSmsId("delete-posted")
            val transactions = c.storage.financialTransactionRepository.listAll()
            assertEquals(1, transactions.size)
            val stale = w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "account_reference", emptySet())
            w.setEnabled(w.load().generation, "BANK_STC", "stc-internal-in", false)
            w.deletePattern(w.load().generation, "BANK_STC", "stc-internal-in")
            assertTrue(runCatching { w.saveFinancial(stale) }.isFailure)
            c.sms.processStoredSmsUseCase.process("delete-posted")
            assertEquals(evidence, c.storage.parsedEventRepository.findByRawSmsId("delete-posted"))
            assertEquals(transactions, c.storage.financialTransactionRepository.listAll())
            assertTrue(w.load().banks.single { it.bankId == "BANK_STC" }.patterns.none { it.id == "stc-internal-in" })
        }
    }

    @Test fun sarieTransferWithEmptyAccountCanBeApprovedAndAppliedOnlyToTheSelectedAccount() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val body = "Inward transfer (SARIE)\n100.00 SAR\nFrom TEST PERSON\nFrom BJAZ\nAccount\n02-09-2026 08:01\nRef. No. *8PJ7"
            c.sms.processRawSmsUseCase.ingest(sms("empty-sarie-account", body))
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            val review = requireNotNull(c.storage.reviewRepository.findByRawSmsId("empty-sarie-account"))
            val account = w.confirmPatternAccount("BANK_STC", "1234")
            val missingReference = runCatching {
                w.previewFinancial(w.load().generation, "BANK_STC", "stc-sarie-in", PatternOperation.TRANSFER_IN,
                    account.id, body, "account_reference", setOf("counterparty_bank"))
            }.exceptionOrNull()
            assertEquals(PatternApprovalProblem.REFERENCE_MISMATCH, (missingReference as? PatternApprovalException)?.problem)
            val approved = w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-sarie-in",
                PatternOperation.TRANSFER_IN, account.id, body, null, setOf("account_reference", "counterparty_bank")))
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            assertEquals(1, w.applyPending("BANK_STC", approved.generation, review.id).posted)
            val event = requireNotNull(c.storage.parsedEventRepository.findByRawSmsId("empty-sarie-account")).event
            assertEquals(ParseStatus.SUCCESS, event.parseStatus)
            assertEquals(MessageFamily.TRANSFER_IN, event.messageFamily)
            assertEquals("100.00", event.amount!!.amount.toPlainString())
            assertEquals(AccountReference(Bank("BANK_STC"), "1234"), event.destinationAccountRef)
            assertNull(event.sourceAccountRef)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
        }
    }

    @Test fun manualCardSetupRollsBackInvalidLinksAndNeverCreatesBalancesOrTransactions() = runBlocking {
        fixture { c ->
            val bank = Bank("BANK_STC")
            val invalid = CardConfiguration(CardNetwork.MADA, CardType.DEBIT,
                linkedAccount = AccountReference(bank, "9999"))
            assertTrue(runCatching { c.dashboard.settingsRegistryWorkflow.addCard(bank.id, "8219", null, invalid) }.isFailure)
            assertNull(c.storage.cardRegistryRepository.get(CardReference(bank, "8219")))
            val account = c.dashboard.settingsRegistryWorkflow.addAccount(bank.id, "٣٠٠١", "My account")
            c.dashboard.settingsRegistryWorkflow.addCard(bank.id, "8219", "My card", invalid.copy(linkedAccount = account))
            assertEquals("3001", account.maskedNumber)
            assertEquals(OwnershipStatus.OWNED, c.storage.cardRegistryRepository.resolve(CardReference(bank, "8219")))
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            assertTrue(runCatching { c.dashboard.settingsRegistryWorkflow.addAccount(bank.id, "3001", "Overwrite") }.isFailure)
            assertEquals("My account", c.storage.accountRegistryRepository.get(account)?.displayName)
        }
    }

    @Test fun newBankCardPatternCanBeApprovedWithoutInventingAFundingAccount() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val bankId = "CARD_BANK"
            w.addBank(w.catalog().generation, "Card bank", "CardBank", bankId)
            val ref = c.dashboard.settingsRegistryWorkflow.addCard(bankId, "8219", null,
                CardConfiguration(CardNetwork.VISA, CardType.CREDIT, CardRole.STANDALONE))
            val card = requireNotNull(c.storage.cardRegistryRepository.get(ref))
            val body = "Purchase\nCard:8219\nAmount:10SAR\nMerchant:SHOP\nAt:2026-10-15 12:30"
            val bindings = listOf("card" to "8219", "amount" to "10SAR", "merchant" to "SHOP", "occurred_at" to "2026-10-15 12:30")
                .map { (field, value) -> PatternBinding(field, body.indexOf(value), body.indexOf(value) + value.length) }
            w.saveTeaching(w.teach(w.catalog().generation, bankId, "CardBank", PatternOperation.PURCHASE, body, bindings))
            c.sms.processRawSmsUseCase.ingest(sms("card-pending", body, "CardBank"))
            val patternId = w.catalog().banks.single { it.bankId == bankId }.currentPatterns.single().id
            val preview = w.previewFinancial(w.catalog().generation, bankId, patternId, PatternOperation.PURCHASE,
                null, body, null, emptySet(), cardId = card.id)
            c.dashboard.settingsRegistryWorkflow.markCardExternal(ref)
            assertTrue(runCatching { w.saveFinancial(preview) }.isFailure)
            c.dashboard.settingsRegistryWorkflow.confirmCardOwned(ref)
            val approved = w.saveFinancial(preview)
            // A taught pattern starts off; financial approval switches it on.
            assertTrue(approved.banks.single { it.bankId == bankId }.currentPatterns.single().enabled)
            assertEquals(1, w.applyPending(bankId, approved.generation).posted)
            assertTrue(c.storage.accountRegistryRepository.listAll().none { it.bank.id == bankId })
            assertEquals(ref, c.storage.parsedEventRepository.findByRawSmsId("card-pending")?.event?.cardRef)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            assertEquals(com.baraa.masroof.domain.ids.FinancialContainerIdFactory.cardId(ref),
                c.storage.financialTransactionRepository.listAll().single().sourceContainerId)
        }
    }

    @Test fun approvalRejectsMissingOwnerWrongCardAndStaleConfigurationWithoutWriting() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val bankId = "GUARD_BANK"
            w.addBank(w.catalog().generation, "Guard bank", "GuardBank", bankId)
            val config = CardConfiguration(CardNetwork.VISA, CardType.CREDIT, CardRole.STANDALONE)
            val card = requireNotNull(c.storage.cardRegistryRepository.get(c.dashboard.settingsRegistryWorkflow.addCard(bankId, "8219", null, config)))
            val other = requireNotNull(c.storage.cardRegistryRepository.get(c.dashboard.settingsRegistryWorkflow.addCard(bankId, "1111", null, config)))
            val body = "Purchase\nCard:8219\nAmount:10SAR\nMerchant:SHOP\nAt:2026-10-15 12:30"
            val bindings = listOf("card" to "8219", "amount" to "10SAR", "occurred_at" to "2026-10-15 12:30")
                .map { (field, value) -> PatternBinding(field, body.indexOf(value), body.indexOf(value) + value.length) }
            w.saveTeaching(w.teach(w.catalog().generation, bankId, "GuardBank", PatternOperation.PURCHASE, body, bindings))
            val before = w.load()
            val id = before.banks.single { it.bankId == bankId }.currentPatterns.single().id
            suspend fun problem(generation: Long, cardId: String?) = (runCatching {
                w.previewFinancial(generation, bankId, id, PatternOperation.PURCHASE, null, body, null, emptySet(), cardId = cardId)
            }.exceptionOrNull() as? PatternApprovalException)?.problem
            assertEquals(PatternApprovalProblem.ACCOUNT_REQUIRED, problem(before.generation, null))
            assertEquals(PatternApprovalProblem.CARD_MISMATCH, problem(before.generation, other.id))
            assertEquals(PatternApprovalProblem.CONFIGURATION_CHANGED, problem(before.generation - 1, card.id))
            assertEquals(before, w.load())
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
        }
    }

    @Test fun manualStcCardLearnsAPurchasePatternWithAMaskedCardNumber() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val ref = c.dashboard.settingsRegistryWorkflow.addCard("BANK_STC", "8219", null,
                CardConfiguration(CardNetwork.VISA, CardType.DEBIT))
            val card = requireNotNull(c.storage.cardRegistryRepository.get(ref))
            val body = "Purchase\nCard: *8219\nAmount:10SAR\nMerchant:SHOP\nAt:2026-10-15 12:30"
            val bindings = listOf("card" to "*8219", "amount" to "10SAR", "merchant" to "SHOP", "occurred_at" to "2026-10-15 12:30")
                .map { (field, value) -> PatternBinding(field, body.indexOf(value), body.indexOf(value) + value.length) }
            w.saveTeaching(w.teach(w.catalog().generation, "BANK_STC", "STC Bank", PatternOperation.PURCHASE, body, bindings))
            c.sms.processRawSmsUseCase.ingest(sms("stc-card-pending", body))
            val bundled = setOf("stc-internal-in", "stc-internal-out", "stc-local-in", "stc-qattah", "stc-sarie-in", "stc-top-up",
                "stc-qattah-ar", "stc-internal-out-ar")
            val patternId = w.catalog().banks.single { it.bankId == "BANK_STC" }.currentPatterns.single { it.id !in bundled }.id
            val approved = w.saveFinancial(w.previewFinancial(w.catalog().generation, "BANK_STC", patternId, PatternOperation.PURCHASE,
                null, body, null, emptySet(), cardId = card.id))
            val active = w.setEnabled(approved.generation, "BANK_STC", patternId, true)
            assertEquals(1, w.applyPending("BANK_STC", active.generation).posted)
            assertEquals(ref, c.storage.parsedEventRepository.findByRawSmsId("stc-card-pending")?.event?.cardRef)
        }
    }

    @Test fun taughtPatternCompletingABundledRoleIsOneMatchNotAConflict() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val body = "حوالة صادرة داخلية\nالمبلغ: 250.00 SAR\nإلى : **0312\n2026-09-26 16:29"
            c.sms.processRawSmsUseCase.ingest(sms("barq-out", body, sender = "barq app"))
            val bindings = listOf("amount" to "250.00 SAR", "account_reference" to "**0312", "occurred_at" to "2026-09-26 16:29")
                .map { (field, value) -> PatternBinding(field, body.indexOf(value), body.indexOf(value) + value.length) }
            val taught = w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_OUT, body, bindings)
            w.saveTeaching(taught)
            val account = w.confirmPatternAccount("BANK_BARQ", "4321")
            val approved = w.saveFinancial(w.previewFinancial(w.catalog().generation, "BANK_BARQ", taught.pattern.id,
                PatternOperation.TRANSFER_OUT, account.id, body, null, setOf("account_reference")))
            val active = w.setEnabled(approved.generation, "BANK_BARQ", taught.pattern.id, true)
            val row = w.previewPending("BANK_BARQ", active.generation).rows.single()
            assertEquals(PendingPatternStatus.MATCHED, row.status)
            assertEquals(taught.pattern.id, row.matches.single().patternId)
            assertEquals(1, w.applyPending("BANK_BARQ", active.generation).posted)
        }
    }

    private val barqOut = "حوالة صادرة داخلية\nالمبلغ: 175.74 SAR\nإلى : **0312\n2026-10-16 13:02"
    private val barqOutFields = listOf("amount" to "175.74 SAR", "destination_account" to "**0312", "occurred_at" to "2026-10-16 13:02")
    private fun bindings(body: String, fields: List<Pair<String, String>>) =
        fields.map { (field, value) -> PatternBinding(field, body.indexOf(value), body.indexOf(value) + value.length) }

    /** Approves and switches the pattern on; taught patterns start off. */
    private suspend fun approveBarqOut(w: MessagePatternsWorkflow, patternId: String): MessagePatternConfiguration {
        val account = w.confirmPatternAccount("BANK_BARQ", "1482")
        w.saveFinancial(w.previewFinancial(w.catalog().generation, "BANK_BARQ", patternId,
            PatternOperation.TRANSFER_OUT, account.id, barqOut, null, setOf("destination_account")))
        val current = w.load().banks.single { it.bankId == "BANK_BARQ" }.currentPatterns.single { it.id == patternId }
        return if (current.enabled) w.load() else w.setEnabled(w.load().generation, "BANK_BARQ", patternId, true)
    }

    @Test fun unapprovedTaughtPatternBesideABundledRoleOffersExamplesAndApproves() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            c.sms.processRawSmsUseCase.ingest(sms("barq-out-1", barqOut, sender = "barq app"))
            val taught = w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_OUT, barqOut, bindings(barqOut, barqOutFields))
            w.saveTeaching(taught)
            assertEquals(1, w.approvalExamples("BANK_BARQ", w.catalog().generation, taught.pattern.id).size)
            assertEquals(1, w.applyPending("BANK_BARQ", approveBarqOut(w, taught.pattern.id).generation).posted)
        }
    }

    // Earlier versions let Edit replace a bundled role with a taught template; the other roles then read UNKNOWN.
    @Test fun bundledRoleReplacedByAnEarlierEditCanStillBeApproved() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            c.sms.processRawSmsUseCase.ingest(sms("barq-out-2", barqOut, sender = "barq app"))
            val bank = w.load().banks.single { it.bankId == "BANK_BARQ" }
            val choices = barqOutFields.map { (field, value) -> barqOut.indexOf(value).let { FieldChoice(field, it, it + value.length) } }
            w.saveBank(w.load().generation, bank.copy(patterns = listOf(SavedMessagePattern.fromChoices("barq.transfer-out", "BANK_BARQ",
                "barq app", "TRANSFER_OUT", barqOut, choices).copy(revision = 2))))
            assertEquals(1, w.approvalExamples("BANK_BARQ", w.catalog().generation, "barq.transfer-out").size)
            assertEquals(1, w.applyPending("BANK_BARQ", approveBarqOut(w, "barq.transfer-out").generation).posted)
        }
    }

    @Test fun bundledPatternsCannotBeEdited() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            assertFalse(w.catalog().banks.single { it.bankId == "BANK_BARQ" }.currentPatterns.single { it.id == "barq.transfer-out" }.canEdit)
            assertTrue(runCatching { w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_OUT, barqOut,
                bindings(barqOut, barqOutFields), "barq.transfer-out") }.isFailure)
        }
    }

    @Test fun savingAnUnapprovedOverlappingPatternKeepsTheApprovedOnePosting() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val taught = w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_OUT, barqOut, bindings(barqOut, barqOutFields))
            w.saveTeaching(taught)
            approveBarqOut(w, taught.pattern.id)
            val overlap = w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_OUT, barqOut,
                bindings(barqOut, barqOutFields.filter { it.first != "destination_account" }))
            w.saveTeaching(overlap)
            w.setEnabled(w.load().generation, "BANK_BARQ", overlap.pattern.id, true)
            c.sms.processRawSmsUseCase.ingest(sms("barq-out-3", barqOut.replace("175.74", "99.00"), sender = "barq app"))
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
        }
    }

    @Test fun restoringAnOlderApprovedRevisionAsksForApprovalAgain() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val taught = w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_OUT, barqOut, bindings(barqOut, barqOutFields))
            w.saveTeaching(taught)
            approveBarqOut(w, taught.pattern.id)
            // A different operation never keeps its approval, so the current revision is unapproved.
            w.saveTeaching(w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.BILL_PAYMENT, barqOut,
                bindings(barqOut, barqOutFields.filter { it.first != "destination_account" }), taught.pattern.id))
            val restored = w.restoreRevision(w.catalog().generation, "BANK_BARQ", taught.pattern.id, 2).toCatalog()
                .banks.single { it.bankId == "BANK_BARQ" }.currentPatterns.single { it.id == taught.pattern.id }
            assertEquals(PatternApprovalStatus.PREVIEW_ONLY, restored.status)
            // Toggling keeps the current approval.
            approveBarqOut(w, taught.pattern.id)
            w.setEnabled(w.catalog().generation, "BANK_BARQ", taught.pattern.id, false)
            w.setEnabled(w.catalog().generation, "BANK_BARQ", taught.pattern.id, true)
            assertEquals(PatternApprovalStatus.APPROVED, w.catalog().banks.single { it.bankId == "BANK_BARQ" }
                .currentPatterns.single { it.id == taught.pattern.id }.status)
        }
    }

    @Test fun approvalOffersOnlyMovementsThatFitTheTaughtFields() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val taught = w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_OUT, barqOut, bindings(barqOut, barqOutFields))
            w.saveTeaching(taught)
            val view = w.catalog().banks.single { it.bankId == "BANK_BARQ" }.currentPatterns.single { it.id == taught.pattern.id }
            assertEquals(listOf(PatternOperation.TRANSFER_IN, PatternOperation.TRANSFER_OUT), view.operations)
            val account = w.confirmPatternAccount("BANK_BARQ", "1482")
            assertTrue(runCatching { w.previewFinancial(w.catalog().generation, "BANK_BARQ", taught.pattern.id,
                PatternOperation.PURCHASE, account.id, barqOut, null, setOf("destination_account")) }.isFailure)
        }
    }

    @Test fun bundledRoleThatCannotCompleteAMessageAsksForTeachingInsteadOfOfferingProcessing() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            c.sms.processRawSmsUseCase.ingest(sms("barq-in", "حوالة واردة داخلية\n100.00 SAR\nحساب المرسل: **1482\n2026-10-02 22:01",
                sender = "barq app"))
            val report = w.previewPending("BANK_BARQ", w.catalog().generation)
            assertEquals(PendingPatternStatus.INCOMPLETE, report.rows.single().status)
            assertEquals(0, report.matchingCount("barq.transfer-in"))
        }
    }

    @Test fun pendingMessageTimedByItsStoredReceiptIsOneMatch() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            // Date and time on separate lines: the stored receipt time supplies the transaction time.
            val body = "خصم رسوم\n25 SAR : المبلغ، العملة\nLATEFEES : السبب\nXX1234 : رقم الحساب\n2026-09-15 : التاريخ\n12:30 : الوقت"
            w.setEnabled(w.catalog().generation, "BANK_EMIRATES_NBD", "emirates_nbd.fee", false)
            c.sms.processRawSmsUseCase.ingest(sms("enbd-fee", body, sender = "EmiratesNBD"))
            w.setEnabled(w.catalog().generation, "BANK_EMIRATES_NBD", "emirates_nbd.fee", true)
            val row = w.previewPending("BANK_EMIRATES_NBD", w.catalog().generation).rows.single()
            assertEquals(PendingPatternStatus.MATCHED, row.status)
            assertEquals("emirates_nbd.fee", row.matches.single().patternId)
        }
    }

    @Test fun overlappingActivePatternsAreBothMarkedConflicting() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val body = "حوالة صادرة داخلية\nالمبلغ: 250.00 SAR\nإلى : **0312\n2026-09-26 16:29"
            c.sms.processRawSmsUseCase.ingest(sms("barq-dup", body, sender = "barq app"))
            val bindings = listOf("amount" to "250.00 SAR", "account_reference" to "**0312", "occurred_at" to "2026-09-26 16:29")
                .map { (field, value) -> PatternBinding(field, body.indexOf(value), body.indexOf(value) + value.length) }
            val account = w.confirmPatternAccount("BANK_BARQ", "4321")
            val ids = List(2) {
                val taught = w.teach(w.catalog().generation, "BANK_BARQ", "barq app", PatternOperation.TRANSFER_OUT, body, bindings)
                w.saveTeaching(taught)
                w.saveFinancial(w.previewFinancial(w.catalog().generation, "BANK_BARQ", taught.pattern.id,
                    PatternOperation.TRANSFER_OUT, account.id, body, null, setOf("account_reference")))
                // Approval switches a pattern on and rejects an overlap with an active one; switch it off so the twin can be approved.
                w.setEnabled(w.catalog().generation, "BANK_BARQ", taught.pattern.id, false)
                taught.pattern.id
            }
            // Two approved while off can both be switched on; the overlap is then reported, never resolved by guessing.
            ids.forEach { w.setEnabled(w.catalog().generation, "BANK_BARQ", it, true) }
            val report = w.previewPending("BANK_BARQ", w.catalog().generation)
            assertEquals(PendingPatternStatus.PATTERN_CONFLICT, report.rows.single().status)
            assertTrue(report.conflictingPatternIds.containsAll(ids))
        }
    }

    @Test fun aPatternThatIsOffStillFindsApprovalExamples() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            w.addBank(w.catalog().generation, "Shop bank", "ShopBank", "SHOP_BANK")
            val body = "Purchase\nAmount:10SAR\nMerchant:SHOP\nAt:2026-10-15 12:30"
            val bindings = listOf("amount" to "10SAR", "merchant" to "SHOP", "occurred_at" to "2026-10-15 12:30")
                .map { (field, value) -> PatternBinding(field, body.indexOf(value), body.indexOf(value) + value.length) }
            val taught = w.teach(w.catalog().generation, "SHOP_BANK", "ShopBank", PatternOperation.PURCHASE, body, bindings)
            w.saveTeaching(taught)
            c.sms.processRawSmsUseCase.ingest(sms("shop-pending", body, "ShopBank"))
            assertEquals(0, w.previewPending("SHOP_BANK", w.catalog().generation).matchingCount(taught.pattern.id))
            assertEquals(listOf(body), w.approvalExamples("SHOP_BANK", w.catalog().generation, taught.pattern.id).map { it.body })
        }
    }

    private suspend fun fixture(source: com.baraa.masroof.sms.datasource.SmsDataSource? = null, block: suspend (AppContainer) -> Unit) =
        withAppContainer(uniqueStorageContext("pattern"), source, block)
    private fun sms(id: String, body: String, sender: String = "STC Bank") = RawSms(id, sender, body,
        Instant.parse("2026-10-15T10:00:00Z"), id, SmsBodyHasher.sha256Hex(body))
    private val incoming = "Internal incoming transfer\nAmount:10SAR\nFrom:TEST PERSON\nAcc:321\nAt:15/10/26 12:30"

    @Test fun applyToCurrentReviewLeavesOtherPendingMessagesAndManualDecisionsUntouched() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            c.sms.processRawSmsUseCase.ingest(sms("current-ui", incoming))
            c.sms.processRawSmsUseCase.ingest(sms("other-ui", incoming.replace("10SAR", "20SAR")))
            val current = c.storage.reviewRepository.findByRawSmsId("current-ui")!!
            val other = c.storage.reviewRepository.findByRawSmsId("other-ui")!!
            val untouched = c.storage.parsedEventRepository.findByRawSmsId("other-ui")
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            val approved = w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in",
                PatternOperation.TRANSFER_IN, account.id, incoming, "account_reference", emptySet()))
            val result = w.applyPending("BANK_STC", approved.generation, current.id)
            assertEquals(1, result.processed)
            assertEquals(1, result.posted)
            assertFalse(result.incomplete)
            assertEquals(untouched, c.storage.parsedEventRepository.findByRawSmsId("other-ui"))
            assertEquals(ReviewStatus.REQUIRED, c.storage.reviewRepository.getById(other.id)!!.status)
            assertEquals(0, w.applyPending("BANK_STC", approved.generation, current.id).processed)
            c.storage.reviewRepository.markResolved(other.id, ReviewResolutionKind.USER_NON_FINANCIAL, Instant.now(), null)
            assertEquals(0, w.applyPending("BANK_STC", approved.generation, other.id).processed)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            assertEquals(untouched, c.storage.parsedEventRepository.findByRawSmsId("other-ui"))
        }
    }

    @Test fun pendingBatchPersistsRetryIntentWithFactsBeforeItsDerivedPass() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val raw = sms("interrupted-batch", incoming)
            c.sms.processRawSmsUseCase.ingest(raw)
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "account_reference", emptySet()))
            val batch = c.sms.historicalSmsBatchProcessor.startBatch(Bank("BANK_STC"))
            assertTrue(batch.reprocess(raw) is SmsIngestionResult.Parsed)
            val saved = c.storage.parsedEventRepository.findByRawSmsId(raw.id)
            assertEquals(listOf(raw.id), c.storage.processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH))
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            // A new recovery pass can finish even if the original caller never reaches finish().
            c.sms.historicalDerivedRecovery.recoverPending()
            assertEquals(saved, c.storage.parsedEventRepository.findByRawSmsId(raw.id))
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            assertTrue(c.storage.processingRetryRepository.listRetryableRawSmsIds().isEmpty())
        }
    }

    @Test fun everyStcLayoutCanBeExplicitlyApprovedWithoutInventingFundingCardsOrIncome() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            val rows = Json.parseToJsonElement(java.io.File("src/test/resources/testdata/patterns/stc-seed-v1.json").readText())
                .jsonObject.getValue("rows").let { it as kotlinx.serialization.json.JsonArray }.map { it.jsonObject }
                .filter { it.getValue("teach").jsonPrimitive.content == "true" }
            assertEquals(8, rows.size)
            for (row in rows) {
                val id = row.getValue("id").jsonPrimitive.content
                val body = row.getValue("body").jsonPrimitive.content
                val fields = row.getValue("fields").jsonObject
                val reference = fields["account_reference"]?.jsonPrimitive?.content?.takeIf { it.matches(Regex("[0-9]{1,3}")) }?.let { "account_reference" }
                val context = fields.keys.filter { it in setOf("source_account", "destination_account", "account_reference", "funding_reference", "counterparty_bank") && it != reference }.toSet()
                val operation = PatternOperation.valueOf(row.getValue("operation").jsonPrimitive.content)
                w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", id, operation, account.id, body, reference, context))
                assertTrue(c.sms.processRawSmsUseCase.ingest(sms("approved-$id", body)) is SmsIngestionResult.Parsed)
                val event = c.storage.parsedEventRepository.findByRawSmsId("approved-$id")!!.event
                assertEquals(ParseStatus.SUCCESS, event.parseStatus)
                assertEquals(MessageFamily.valueOf(operation.name), event.messageFamily)
                assertNull(event.bankNetworkType)
            }
            assertTrue(c.storage.cardRegistryRepository.listAll().isEmpty())
            assertFalse(c.storage.financialTransactionRepository.listAll().any { it.type in setOf(FinancialTransactionType.INCOME, FinancialTransactionType.LOAN_REPAYMENT) })
        }
    }

    @Test fun realRedactedOtpWithTransferAndAmountIsNeverFinancial() = runBlocking {
        fixture { c ->
            val sample = Json.parseToJsonElement(java.io.File("src/test/resources/testdata/patterns/stc-otp-redacted.json").readText()).jsonObject
            val text = sample.getValue("body").jsonPrimitive.content
            val result = c.sms.processRawSmsUseCase.ingest(sms("real-otp", text))
            assertTrue(result is SmsIngestionResult.NonFinancial)
            val event = c.storage.parsedEventRepository.findByRawSmsId("real-otp")!!.event
            assertEquals(MessageFamily.OTP, event.messageFamily)
            assertEquals(ParseStatus.NON_FINANCIAL, event.parseStatus)
            assertNull(event.amount)
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
        }
    }

    @Test fun captureSnapshotsThatExpireBeforeProcessingAreRetriedBeforeSavingFacts() = runBlocking {
        fixture { c ->
            val store = c.storage.messagePatternStore
            val registry = com.baraa.masroof.bank.BankSmsRegistry(emptyList(), ConfiguredPatternAdapters(store)::snapshot)
            val raw = sms("stale-capture", incoming)
            val route = registry.route(raw.sender, raw.body)
            c.storage.rawSmsRepository.insertIfAbsent(raw)
            val w = c.patterns.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "account_reference", emptySet()))
            assertTrue(c.sms.processStoredSmsUseCase.process(raw, route) is SmsIngestionResult.Failed)
            assertNull(c.storage.parsedEventRepository.findByRawSmsId(raw.id))
            assertTrue(c.sms.processStoredSmsUseCase.process(raw.id) is SmsIngestionResult.Parsed)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
        }
    }

    @Test fun failedFinancialBatchKeepsReviewsAndAtomicRetrySetUntilRecoverySucceeds() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            c.sms.processRawSmsUseCase.ingest(sms("failed-stc", incoming))
            val approved = w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in",
                PatternOperation.TRANSFER_IN, account.id, incoming, "account_reference", emptySet()))
            c.storage.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_pattern_post BEFORE INSERT ON financial_transaction BEGIN SELECT RAISE(ABORT,'injected'); END")
            val applied = w.applyPending("BANK_STC", approved.generation)
            assertTrue(applied.incomplete)
            assertNull(applied.failed) // A thrown write has no returned reconciliation count.
            assertTrue(c.storage.appLogService.readAll().any { it.message.contains("retry_state=marked") })
            assertEquals(0, applied.posted)
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            assertEquals(ReviewStatus.REQUIRED, c.storage.reviewRepository.findByRawSmsId("failed-stc")!!.status)
            assertEquals(listOf("failed-stc"), c.storage.processingRetryRepository.listRetryableRawSmsIds(ProcessingRetryMode.HISTORICAL_BATCH))
            c.storage.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_pattern_post")
            c.sms.historicalDerivedRecovery.recoverPending()
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            assertTrue(c.storage.processingRetryRepository.listRetryableRawSmsIds().isEmpty())
            assertEquals(ReviewStatus.RESOLVED, c.storage.reviewRepository.findByRawSmsId("failed-stc")!!.status)
        }
    }

    @Test fun ownershipChangesPreventPostingAndLaterConfirmationResumesWithoutReparse() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val body = "Purchase\nAmount:10SAR\nAt:2026-10-15 12:30"
            val p = SavedMessagePattern.fromChoices("owned-purchase", "OWNERSHIP_BANK", "Ownership Bank", "PURCHASE", body,
                listOf("amount" to "10SAR", "occurred_at" to "2026-10-15 12:30").map { (field, value) ->
                    FieldChoice(field, body.indexOf(value), body.indexOf(value) + value.length)
                })
            w.saveBank(w.load().generation, BankMessagePatterns("OWNERSHIP_BANK", listOf("Ownership Bank"), patterns = listOf(p)))
            c.sms.processRawSmsUseCase.ingest(sms("ownership-race", body, "Ownership Bank"))
            val account = w.confirmPatternAccount("OWNERSHIP_BANK", "1234")
            val approved = w.saveFinancial(w.previewFinancial(w.load().generation, "OWNERSHIP_BANK", p.id, PatternOperation.PURCHASE,
                account.id, body, null, emptySet()))
            val sql = c.storage.database.openHelper.writableDatabase
            for (action in listOf("INSERT", "UPDATE")) sql.execSQL("CREATE TRIGGER revoke_on_$action AFTER $action ON parsed_event WHEN NEW.rawSmsId='ownership-race' BEGIN UPDATE account_registry SET ownershipStatus='EXTERNAL' WHERE bankId='OWNERSHIP_BANK'; END")
            val result = w.applyPending("OWNERSHIP_BANK", approved.generation)
            assertEquals(0, result.posted)
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            assertEquals(OwnershipStatus.EXTERNAL, c.storage.accountRegistryRepository.get(AccountReference(Bank("OWNERSHIP_BANK"), "1234"))!!.ownership)
            val saved = c.storage.parsedEventRepository.findByRawSmsId("ownership-race")
            for (action in listOf("INSERT", "UPDATE")) sql.execSQL("DROP TRIGGER revoke_on_$action")
            w.confirmPatternAccount("OWNERSHIP_BANK", "1234")
            c.sms.processStoredSmsUseCase.process("ownership-race")
            assertEquals(saved, c.storage.parsedEventRepository.findByRawSmsId("ownership-race"))
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            assertTrue(c.storage.processingRetryRepository.listRetryableRawSmsIds().isEmpty())
        }
    }

    @Test fun financialCorrectionRetryResumesSavedEvidenceWithoutReparsingOrLosingTheDecision() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            c.sms.processRawSmsUseCase.ingest(sms("manual-retry", incoming))
            val original = c.storage.parsedEventRepository.findByRawSmsId("manual-retry")
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            w.saveFinancial(w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "account_reference", emptySet()))
            c.storage.database.saveCorrection(correction("manual-retry", MessageFamily.PURCHASE,
                com.baraa.masroof.core.money.Money.of("15", com.baraa.masroof.core.money.Currency.SAR), "MANUAL", null, Instant.now()))
            val review = c.storage.reviewRepository.findByRawSmsId("manual-retry")!!
            c.storage.reviewRepository.markResolved(review.id, ReviewResolutionKind.USER_CORRECTION, Instant.now(), null)
            c.review.processingRecovery.markExhausted("manual-retry")
            c.sms.processStoredSmsUseCase.process("manual-retry")
            assertEquals(original, c.storage.parsedEventRepository.findByRawSmsId("manual-retry"))
            assertEquals("15.00", c.storage.financialTransactionRepository.listAll().single().amount.amount.toPlainString())
            assertEquals(ReviewResolutionKind.USER_CORRECTION, c.storage.reviewRepository.findByRawSmsId("manual-retry")!!.resolutionKind)
            assertTrue(c.storage.processingRetryRepository.listRetryableRawSmsIds().isEmpty())
        }
    }

    @Test fun activatingANewBankImportsOnlyItsMessages() = runBlocking {
        val source = object : com.baraa.masroof.sms.datasource.SmsDataSource {
            override suspend fun <T> withInbox(receivedAfter: Instant?, block: suspend (Sequence<com.baraa.masroof.sms.datasource.InboxRow>) -> T): T = block(queryInbox(receivedAfter))
            fun queryInbox(receivedAfter: Instant?) = listOf("AlRajhiBank", "STC Bank", "barq app").mapIndexed { index, sender ->
                com.baraa.masroof.sms.datasource.InboxRow.Valid(com.baraa.masroof.sms.model.ProviderSmsRecord(
                    "new-bank-$index", sender, incoming, Instant.parse("2026-10-15T10:00:00Z")))
            }.asSequence()
        }
        fixture(source) { c ->
            val w = c.patterns.messagePatternsWorkflow
            assertTrue("A sender owned by another bank is refused", runCatching { w.addBank(w.load().generation, "Copy", "STC Bank") }.isFailure)
            val bankId = w.addBank(w.load().generation, "Al Rajhi", "AlRajhiBank").banks.single { it.displayName == "Al Rajhi" }.bankId
            val result = c.sms.historicalSmsScanner.scan(bankScopes = setOf(Bank(bankId)))
            assertEquals(result.toString(), 1, result.inserted)
            assertEquals(result.toString(), 2, result.notRelevant)
            assertNotNull(c.storage.rawSmsRepository.findByDeviceMessageId("new-bank-0"))
        }
    }

    @Test fun aSenderAddedToAlJaziraIsRecognized() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val bank = w.load().banks.single { it.bankId == "BANK_ALJAZIRA" }
            w.updateBank(w.load().generation, "BANK_ALJAZIRA", bank.displayName ?: "AlJazira", bank.senders + "JZ-NEW")
            c.sms.processRawSmsUseCase.ingest(sms("jz-new-sender", incoming, sender = "JZ-NEW"))
            assertNotNull("Captured for review instead of dropped as not matched", c.storage.rawSmsRepository.getById("jz-new-sender"))
        }
    }

    @Test fun scopedImportUsesDynamicRoutingAndPreservesCollisionsWithoutClaimingOtherBanks() = runBlocking {
        var queries = 0
        val source = object : com.baraa.masroof.sms.datasource.SmsDataSource {
            override suspend fun <T> withInbox(receivedAfter: Instant?, block: suspend (Sequence<com.baraa.masroof.sms.datasource.InboxRow>) -> T): T = block(queryInbox(receivedAfter))
            fun queryInbox(receivedAfter: Instant?): Sequence<com.baraa.masroof.sms.datasource.InboxRow> {
                queries++
                return listOf("STC Bank", "AlJazira", "PERSON").mapIndexed { index, sender ->
                    com.baraa.masroof.sms.datasource.InboxRow.Valid(com.baraa.masroof.sms.model.ProviderSmsRecord(
                        "import-$index", sender, incoming, Instant.parse("2026-10-15T10:00:00Z")))
                }.asSequence()
            }
        }
        fixture(source) { c ->
            val w = c.patterns.messagePatternsWorkflow
            assertTrue(runCatching { c.sms.historicalSmsScanner.scan(bankScopes = setOf(Bank("NOT_REGISTERED"))) }.isFailure)
            assertEquals(0, queries)
            w.saveBank(w.load().generation, BankMessagePatterns("COLLISION", listOf("STC Bank")))
            val result = c.sms.historicalSmsScanner.scan(bankScopes = setOf(Bank("BANK_STC")))
            assertEquals(1, result.inserted)
            assertEquals(2, result.notRelevant)
            assertTrue(c.storage.parsedEventRepository.listAll().isEmpty())
            assertEquals(1, c.storage.reviewRepository.listRequired().size)
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
        }
    }

    @Test fun staleApprovalAndChangedOwnershipCannotPublishTheReviewedContract() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val account = w.confirmPatternAccount("BANK_STC", "4321")
            val preview = w.previewFinancial(w.load().generation, "BANK_STC", "stc-internal-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "account_reference", emptySet())
            c.storage.accountRegistryRepository.setOwnership(AccountReference(Bank("BANK_STC"), "4321"), OwnershipStatus.EXTERNAL)
            assertTrue(runCatching { w.saveFinancial(preview) }.isFailure)
            assertNull(w.load().banks.single { it.bankId == "BANK_STC" }.currentPatterns.single { it.id == "stc-internal-in" }.factContract)
            w.confirmPatternAccount("BANK_STC", "4321")
            w.setEnabled(w.load().generation, "BANK_STC", "stc-internal-in", false)
            assertTrue(runCatching { w.saveFinancial(preview) }.isFailure)
        }
    }

    @Test fun seededStcRoutesBeforeOpeningSettingsAndOnlyExplicitApprovalUnlocksMappedFacts() = runBlocking {
        fixture { c ->
            val workflow = c.patterns.messagePatternsWorkflow
            assertTrue(c.sms.processRawSmsUseCase.ingest(sms("pending", incoming)) is SmsIngestionResult.ReviewRequired)
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            val before = c.storage.parsedEventRepository.findByRawSmsId("pending")!!
            val account = workflow.confirmPatternAccount("BANK_STC", "4321")
            val snapshot = workflow.load()
            val preview = workflow.previewFinancial(snapshot.generation, "BANK_STC", "stc-internal-in", PatternOperation.TRANSFER_IN,
                account.id, incoming, "account_reference", emptySet())
            assertEquals(before, c.storage.parsedEventRepository.findByRawSmsId("pending"))
            assertTrue(c.storage.financialTransactionRepository.listAll().isEmpty())
            val saved = workflow.saveFinancial(preview)
            assertEquals(2, saved.banks.single { it.bankId == "BANK_STC" }.currentPatterns.single { it.id == "stc-internal-in" }.revision)
            val applied = workflow.applyPending("BANK_STC", saved.generation)
            assertEquals(1, applied.processed)
            assertEquals(ParseStatus.SUCCESS, c.storage.parsedEventRepository.findByRawSmsId("pending")!!.event.parseStatus)
            assertEquals(AccountReference(Bank("BANK_STC"), "4321"), c.storage.parsedEventRepository.findByRawSmsId("pending")!!.event.destinationAccountRef)
            // Existing core policy permits an external transfer with an explicitly confirmed owned leg.
            val transfer = c.storage.financialTransactionRepository.listAll().single()
            assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_IN, transfer.type)
            assertNull(transfer.sourceContainerId)
            assertEquals("account:BANK_STC:4321", transfer.destinationContainerId)
            assertEquals(1, applied.posted)
            assertEquals(ReviewStatus.RESOLVED, c.storage.reviewRepository.findByRawSmsId("pending")!!.status)
            assertTrue(c.sms.processRawSmsUseCase.ingest(sms("different", incoming.replace("321", "322"))) is SmsIngestionResult.ReviewRequired)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
        }
    }

    @Test fun editingAnApprovedPatternKeepsApprovalOnlyWhenTheSameCheckPassesAgain() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            w.addBank(w.catalog().generation, "Edit bank", "EditBank", "EDIT_BANK")
            val body = "Purchase\nAmount:10SAR\nMerchant:SHOP\nAt:2026-10-15 12:30"
            fun bind(vararg values: Pair<String, String>) = values.map { (field, value) ->
                PatternBinding(field, body.indexOf(value), body.indexOf(value) + value.length) }
            w.saveTeaching(w.teach(w.catalog().generation, "EDIT_BANK", "EditBank", PatternOperation.PURCHASE, body,
                bind("amount" to "10SAR", "occurred_at" to "2026-10-15 12:30")))
            val id = w.catalog().banks.single { it.bankId == "EDIT_BANK" }.currentPatterns.single().id
            val account = w.confirmPatternAccount("EDIT_BANK", "1234")
            w.saveFinancial(w.previewFinancial(w.catalog().generation, "EDIT_BANK", id, PatternOperation.PURCHASE,
                account.id, body, null, emptySet()))
            val kept = w.saveTeaching(w.teach(w.load().generation, "EDIT_BANK", "EditBank", PatternOperation.PURCHASE, body,
                bind("amount" to "10SAR", "merchant" to "SHOP", "occurred_at" to "2026-10-15 12:30"), patternId = id))
            val edited = kept.banks.single { it.bankId == "EDIT_BANK" }.currentPatterns.single()
            assertEquals("1234", edited.factContract?.accountBindings?.single()?.last4)
            assertTrue(edited.enabled)
            // A different operation is a different financial meaning, so it always needs a fresh approval.
            val changed = w.saveTeaching(w.teach(kept.generation, "EDIT_BANK", "EditBank", PatternOperation.REFUND, body,
                bind("amount" to "10SAR", "occurred_at" to "2026-10-15 12:30"), patternId = id))
            assertNull(changed.banks.single { it.bankId == "EDIT_BANK" }.currentPatterns.single().factContract)
        }
    }

    @Test fun editingFromAnotherAccountsMessageDropsApproval() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            w.addBank(w.catalog().generation, "Edit bank", "EditBank", "EDIT_BANK")
            val body = "Transfer in\nAmount:10SAR\nAcc:321\nAt:2026-10-15 12:30"
            fun bind(text: String, vararg values: Pair<String, String>) = values.map { (field, value) ->
                PatternBinding(field, text.indexOf(value), text.indexOf(value) + value.length) }
            w.saveTeaching(w.teach(w.catalog().generation, "EDIT_BANK", "EditBank", PatternOperation.TRANSFER_IN, body,
                bind(body, "amount" to "10SAR", "account_reference" to "321", "occurred_at" to "2026-10-15 12:30")))
            val id = w.catalog().banks.single { it.bankId == "EDIT_BANK" }.currentPatterns.single().id
            val account = w.confirmPatternAccount("EDIT_BANK", "4321")
            w.saveFinancial(w.previewFinancial(w.catalog().generation, "EDIT_BANK", id, PatternOperation.TRANSFER_IN,
                account.id, body, "account_reference", emptySet()))
            // The second account's message carries 765; keeping approval would credit it to ****4321.
            val other = body.replace("321", "765")
            val edited = w.saveTeaching(w.teach(w.load().generation, "EDIT_BANK", "EditBank", PatternOperation.TRANSFER_IN, other,
                bind(other, "amount" to "10SAR", "account_reference" to "765", "occurred_at" to "2026-10-15 12:30"), patternId = id))
            assertNull(edited.banks.single { it.bankId == "EDIT_BANK" }.currentPatterns.single().factContract)
        }
    }

    @Test fun aPatternChangeWhileATransferLegWaitsStillPostsThePair() = runBlocking {
        fixture { c ->
            listOf("3001", "3002").forEach { c.review.ownershipConfirmationService.confirmAccountOwned(
                com.baraa.masroof.domain.model.AccountReference(com.baraa.masroof.domain.model.Bank.BANK_ALJAZIRA, it)) }
            val out = "حوالة صادرة الى حسابك الجاري\nمن: 3001\nمبلغ: SAR 2,000.00\nإلى: 3002\nفي: 2026-10-15 13:00"
            val inn = "حوالة واردة داخلية\nمبلغ: SAR 2,000.00\nإلى: 3002\nاسم المرسل: TEST_PERSON\nرقم حساب المرسل: 3001\nالبنك المرسل: بنك الجزيرة\nفي: 2026-10-15 13:00"
            c.sms.processRawSmsUseCase.ingest(sms("leg-out", out, "AlJazira"))
            // Any configuration save moves the generation, even in an unrelated bank.
            c.patterns.messagePatternsWorkflow.addBank(c.patterns.messagePatternsWorkflow.catalog().generation, "Other bank", "OtherBank", "OTHER_BANK")
            c.sms.processRawSmsUseCase.ingest(RawSms("leg-in", "AlJazira", inn, Instant.parse("2026-10-15T10:00:05Z"), "leg-in",
                SmsBodyHasher.sha256Hex(inn)))
            val tx = c.storage.financialTransactionRepository.listAll().single()
            assertEquals(FinancialTransactionType.SELF_TRANSFER, tx.type)
            assertEquals(setOf("leg-in", "leg-out"), c.storage.financialTransactionRepository.listRawSmsIds(tx.id).toSet())
        }
    }

    @Test fun approvedGenericPatternPostsOnceAndNeverReparsesPostedOrManuallyDecidedEvidence() = runBlocking {
        fixture { c ->
            val w = c.patterns.messagePatternsWorkflow
            val body = "Purchase\nAmount:10SAR\nMerchant:SHOP\nAt:2026-10-15 12:30"
            val p = SavedMessagePattern.fromChoices("purchase", "NEW_BANK", "New Bank", "PURCHASE", body,
                listOf("amount" to "10SAR", "merchant" to "SHOP", "occurred_at" to "2026-10-15 12:30").map { (field,value) ->
                    FieldChoice(field, body.indexOf(value), body.indexOf(value) + value.length)
                })
            w.saveBank(w.load().generation, BankMessagePatterns("NEW_BANK", listOf("New Bank"), patterns = listOf(p)))
            c.sms.processRawSmsUseCase.ingest(sms("purchase-pending", body, "New Bank"))
            c.sms.processRawSmsUseCase.ingest(sms("manual-ignore", body, "New Bank"))
            val ignored = c.storage.reviewRepository.findByRawSmsId("manual-ignore")!!
            c.storage.reviewRepository.markResolved(ignored.id, ReviewResolutionKind.USER_NON_FINANCIAL, Instant.now(), null)
            val beforeIgnored = c.storage.parsedEventRepository.findByRawSmsId("manual-ignore")
            val account = w.confirmPatternAccount("NEW_BANK", "1234")
            val preview = w.previewFinancial(w.load().generation, "NEW_BANK", p.id, PatternOperation.PURCHASE,
                account.id, body, null, emptySet())
            val approved = w.saveFinancial(preview)
            assertEquals(1, w.applyPending("NEW_BANK", approved.generation).processed)
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            assertEquals(beforeIgnored, c.storage.parsedEventRepository.findByRawSmsId("manual-ignore"))
            val postedEvent = c.storage.parsedEventRepository.findByRawSmsId("purchase-pending")
            w.setEnabled(w.load().generation, "NEW_BANK", p.id, false)
            c.sms.processStoredSmsUseCase.process("purchase-pending")
            assertEquals(postedEvent, c.storage.parsedEventRepository.findByRawSmsId("purchase-pending"))
            assertEquals(1, c.storage.financialTransactionRepository.listAll().size)
            c.sms.processStoredSmsUseCase.process("manual-ignore")
            assertEquals(beforeIgnored, c.storage.parsedEventRepository.findByRawSmsId("manual-ignore"))
        }
    }
}
