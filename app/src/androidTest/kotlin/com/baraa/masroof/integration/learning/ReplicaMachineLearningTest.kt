package com.baraa.masroof.integration.learning

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baraa.masroof.testsupport.*
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.application.review.ReviewWorkflowResult
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.sms.hash.SmsBodyHasher
import com.baraa.masroof.data.learning.LearningDatabase
import com.baraa.masroof.data.learning.LearningLabelRow
import com.baraa.masroof.ml.family.FamilyInputCleaner
import com.baraa.masroof.application.interpretation.InterpretationOrigin
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter

/** Actual capture → new engine/ML → production Room/review/reconciliation, without mock postings. */
@RunWith(AndroidJUnit4::class)
class ReplicaMachineLearningTest {
    private val host=InstrumentationRegistry.getInstrumentation().targetContext
    private val bank=Bank.BANK_ALJAZIRA
    private fun at()=LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    private fun evidence(id: String,body: String)=RawSms(id,"AlJazira",body,Instant.now(),id,SmsBodyHasher.sha256Hex(body))
    private suspend fun fresh(): AppContainer {
        TestRuntime.reset(host)
        val c=TestRuntime.container(host)
        c.awaitPostStartupBackgroundWorkForTesting()
        c.onboardingOwnershipWorkflow.confirmAccountOwned(AccountReference(bank,"3001"))
        return c
    }
    private suspend fun await(timeoutMillis: Long = 15_000, condition: suspend ()->Boolean) =
        withTimeout(timeoutMillis) {while(!condition()) delay(25)}
    private fun writeLegacyModelCache(text: String): File =
        File(TestStorageContext(host).filesDir,"learning/family-model.json").apply {
            parentFile!!.mkdirs()
            writeText(text)
        }

    @Test fun explicitExternalDirectionsTeachFromTheCommittedTypesAndSurviveColdReopen() = runBlocking {
        var c=fresh()
        val incoming="حوالة محلية واردة\nبنك المرسل: بنك الرياض\nاودعت الى حساب: 3001\nمن: TEST_PERSON\nمبلغ العملية: SAR 20.00\nفي: ${at()}"
        val outgoing="حوالة صادرة الى حسابك الجاري\nمن: 3001\nمبلغ: SAR 21.00\nإلى: 3999\nفي: ${at()}"
        try {
            val service=requireNotNull(c.interpretationService)
            service.synchronizeFeedback()
            // Seed durable pending evidence as retained from an earlier import,
            // without running automatic reconciliation before the explicit action.
            val engine=com.baraa.masroof.engine.bank.interpretation.RuleInterpreter(com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile.current())
            val adapter=com.baraa.masroof.testsupport.InterpretationTestAdapter {
                engine.interpretRouted(it,com.baraa.masroof.finance.value.BankId("BANK_ALJAZIRA"))
            }
            val parsed=adapter.parse(com.baraa.masroof.parsing.model.SmsParseInput("external-out","AlJazira",outgoing,Instant.now()))
                as com.baraa.masroof.parsing.model.ParseResult.Success
            c.database.withTransaction {
                c.rawSmsRepository.insertIfAbsent(evidence("external-out",outgoing))
                c.parsedEventRepository.save(parsed.event,parsed.details)
                c.rawSmsRepository.insertIfAbsent(evidence("external-in",incoming))
                c.parsedEventRepository.save(parsed.event.copy(id="legacy-external-in",rawSmsId="external-in",
                    messageFamily=MessageFamily.TRANSFER_IN,direction=MoneyDirection.INCOMING,
                    amount=com.baraa.masroof.core.money.Money.of(java.math.BigDecimal("20.00"),com.baraa.masroof.core.money.Currency.SAR),
                    sourceAccountRef=null,destinationAccountRef=AccountReference(bank,"3001"),bankNetworkType=BankNetworkType.INTER_BANK),parsed.details)
                for(id in listOf("external-in","external-out"))
                    c.reviewRepository.upsertRequired(id,ReviewKind.PENDING_MATCH,listOf("transfer_pending_match"),Instant.now())
            }
            for((id,direction) in listOf("external-in" to com.baraa.masroof.application.review.ExternalTransferDirection.INCOMING,
                "external-out" to com.baraa.masroof.application.review.ExternalTransferDirection.OUTGOING)) {
                val review=requireNotNull(c.reviewRepository.findByRawSmsId(id)) { "Missing pending review for $id" }
                val result=c.reviewWorkflowService.resolveTransferAsExternal(review.id,direction)
                assertTrue(result.toString(),result is ReviewWorkflowResult.Success)
                service.synchronizeFeedback()
                assertEquals(com.baraa.masroof.application.interpretation.LearningFeedbackState.LEARNED,service.feedbackStatus(id)!!.state)
                assertEquals(ReviewResolutionKind.USER_EXTERNAL_TRANSFER,c.reviewRepository.findByRawSmsId(id)!!.resolutionKind)
                assertEquals(InterpretationOrigin.MANUAL,c.interpretationProvenanceWorkflow.load(id).origin)
            }
            assertEquals(setOf("TRANSFER_IN","TRANSFER_OUT"),service.learningLabels().map { it.family }.toSet())
            val before=c.financialTransactionRepository.listAll()
            TestRuntime.close()
            writeLegacyModelCache("{corrupt")
            c=TestRuntime.container(host)
            c.awaitPostStartupBackgroundWorkForTesting()
            requireNotNull(c.interpretationService).synchronizeFeedback()
            assertEquals(before,c.financialTransactionRepository.listAll())
            c.processRawSmsUseCase.ingest(evidence("external-next",incoming.replace("20.00","22.00")))
            assertEquals(FinancialTransactionType.EXTERNAL_TRANSFER_IN,c.financialTransactionRepository.findByRawSmsId("external-next")!!.type)
            assertEquals(InterpretationOrigin.HUMAN_TEMPLATE,c.interpretationProvenanceWorkflow.load("external-next").origin)
            // A later contradictory financial edit supersedes the earlier external decision.
            val original=c.financialTransactionRepository.findByRawSmsId("external-in")!!
            assertTrue(c.transactionReclassificationService.reclassify(original.id,FinancialTransactionType.EXPENSE)
                is com.baraa.masroof.application.transaction.ReclassificationResult.Success)
            requireNotNull(c.interpretationService).synchronizeFeedback()
            assertEquals(com.baraa.masroof.application.interpretation.LearningFeedbackState.BLOCKED,
                requireNotNull(c.interpretationService).feedbackStatus("external-in")!!.state)
            c.processRawSmsUseCase.ingest(evidence("external-blocked",incoming.replace("20.00","23.00")))
            assertNull(c.financialTransactionRepository.findByRawSmsId("external-blocked"))
        } finally { TestRuntime.reset(host) }
    }
    @Test fun pendingCorrectionLearnsWhenEvidenceArrivesWithoutAnotherHumanEdit() = runBlocking {
        val c=fresh()
        try {
            val service=requireNotNull(c.interpretationService)
            service.synchronizeFeedback()
            val body="سداد تمويل خاص\nمن: 3001\nالقسط: SAR 20\nالمبلغ المتبقي: SAR 300\nلـ: تمويل شخصي\nفي: ${at()}"
            c.rawSmsRepository.insertIfAbsent(evidence("pending-evidence",body))
            c.userCorrectionRepository.save(UserCorrection("early-family","pending-evidence",MessageFamily.FINANCING_INSTALLMENT,
                null,null,null,Instant.now()))
            service.synchronizeFeedback()
            assertEquals(com.baraa.masroof.application.interpretation.LearningFeedbackState.PENDING,service.feedbackStatus("pending-evidence")!!.state)
            c.processStoredSmsUseCase.process("pending-evidence")
            await { service.feedbackStatus("pending-evidence")?.state == com.baraa.masroof.application.interpretation.LearningFeedbackState.LEARNED }
            assertNotNull(c.financialTransactionRepository.findByRawSmsId("pending-evidence"))
        } finally { TestRuntime.reset(host) }
    }
    @Test fun explicitFamilyFeedbackIsNotHiddenBySettlementMetadataAndMerchantEditsDoNotTeachFamilies() = runBlocking {
        val c=fresh()
        val body="سداد تمويل خاص\nمن: 3001\nالقسط: SAR 20\nالمبلغ المتبقي: SAR 300\nلـ: تمويل شخصي\nفي: ${at()}"
        try {
            val service=requireNotNull(c.interpretationService)
            service.synchronizeFeedback()
            c.processRawSmsUseCase.ingest(evidence("explicit-family",body))
            val review=requireNotNull(c.reviewRepository.findByRawSmsId("explicit-family"))
            val corrected=c.reviewWorkflowService.applyCorrection(review.id,correctedType=MessageFamily.FINANCING_INSTALLMENT)
            assertTrue(corrected.toString(),corrected is ReviewWorkflowResult.Success)
            service.synchronizeFeedback()
            await { service.feedbackStatus("explicit-family")?.state == com.baraa.masroof.application.interpretation.LearningFeedbackState.LEARNED }
            c.processRawSmsUseCase.ingest(evidence("merchant-only",body.replace("سداد تمويل خاص","سداد تمويل شخصي خاص")))
            val merchantReview=requireNotNull(c.reviewRepository.findByRawSmsId("merchant-only"))
            assertTrue(c.reviewWorkflowService.applyCorrection(merchantReview.id,correctedMerchant="TEST") is ReviewWorkflowResult.Success)
            service.synchronizeFeedback()
            assertNull(c.financialTransactionRepository.findByRawSmsId("merchant-only"))
            assertEquals("FIELDS_ONLY",service.feedbackStatus("merchant-only")!!.reason)
            assertEquals(ReviewStatus.REQUIRED,c.reviewRepository.findByRawSmsId("merchant-only")!!.status)
        } finally { TestRuntime.reset(host) }
    }
    @Test fun aPostedCorrectionBlocksTheSameTemplateAfterCorruptCacheAndColdReopen() = runBlocking {
        var c=fresh()
        val body="سداد تمويل خاص\nمن: 3001\nالقسط: SAR 20\nالمبلغ المتبقي: SAR 300\nلـ: تمويل شخصي\nفي: ${at()}"
        try {
            requireNotNull(c.interpretationService).synchronizeFeedback()
            c.processRawSmsUseCase.ingest(evidence("cycle-teach",body))
            val review=requireNotNull(c.reviewRepository.findByRawSmsId("cycle-teach"))
            val taught=c.reviewWorkflowService.resolveAsFinancialType(review.id,FinancialTransactionType.LOAN_REPAYMENT)
            assertTrue(taught.toString(),taught is ReviewWorkflowResult.Success)
            requireNotNull(c.interpretationService).synchronizeFeedback()
            c.processRawSmsUseCase.ingest(evidence("cycle-wrong",body.replace("SAR 20","SAR 21")))
            val wrong=requireNotNull(c.financialTransactionRepository.findByRawSmsId("cycle-wrong"))
            val correction=c.transactionReclassificationService.reclassify(wrong.id,FinancialTransactionType.EXPENSE)
            assertTrue(correction.toString(),correction is com.baraa.masroof.application.transaction.ReclassificationResult.Success)
            val before=c.financialTransactionRepository.listAll()
            TestRuntime.close()
            writeLegacyModelCache("{corrupt")
            c=TestRuntime.container(host)
            c.awaitPostStartupBackgroundWorkForTesting()
            requireNotNull(c.interpretationService).synchronizeFeedback()
            assertEquals(before,c.financialTransactionRepository.listAll())
            assertEquals(com.baraa.masroof.application.interpretation.LearningFeedbackState.BLOCKED,
                requireNotNull(c.interpretationService).feedbackStatus("cycle-wrong")!!.state)
            c.processRawSmsUseCase.ingest(evidence("cycle-next",body.replace("SAR 20","SAR 22")))
            assertNull(c.financialTransactionRepository.findByRawSmsId("cycle-next"))
            assertEquals(ReviewStatus.REQUIRED,c.reviewRepository.findByRawSmsId("cycle-next")!!.status)
            assertEquals(before,c.financialTransactionRepository.listAll())
        } finally { TestRuntime.reset(host) }
    }
    @Test fun anUnprovenStatisticalFamilyStaysReviewableAndDoesNotBypassMissingRoles() = runBlocking {
        val c=fresh()
        try {
            val service=requireNotNull(c.interpretationService)
            service.synchronizeFeedback()
            val body="حوالة محلية واردة\nبنك المرسل: بنك الرياض\nاودعت الى حساب: 3001\nمن: TEST_EMPLOYER\nمبلغ العملية: SAR 2000.00\nفي: ${at()}"
            c.processRawSmsUseCase.ingest(evidence("ml-income",body))
            assertTrue(c.financialTransactionRepository.listAll().isEmpty())
            val review=requireNotNull(c.reviewRepository.findByRawSmsId("ml-income"))
            assertEquals(ReviewStatus.REQUIRED,review.status)
            assertEquals(MessageFamily.UNKNOWN,c.parsedEventRepository.findByRawSmsId("ml-income")!!.event.messageFamily)
            await { service.auditHistory("ml-income").any { it.assistRequested && it.assistOutcome == "ABSTAINED" } }
            val insights=c.learningInsightsWorkflow.load()
            assertTrue(insights.attempted>=1)
            assertEquals(0,insights.posted)
        } finally {TestRuntime.reset(host)}
    }
    @Test fun aRealManualLoanDecisionTeachesPendingAndFutureMessagesAfterColdReopen() = runBlocking {
        var c=fresh()
        fun body(amount: Int,remaining: Int)="سداد تمويل خاص\nمن: 3001\nالقسط: SAR $amount\nالمبلغ المتبقي: SAR $remaining\nلـ: تمويل شخصي\nفي: ${at()}"
        try {
            c.processRawSmsUseCase.ingest(evidence("ml-loan-one",body(20,300)))
            c.processRawSmsUseCase.ingest(evidence("ml-loan-pending",body(21,299)))
            assertEquals(2,c.reviewRepository.listRequired().size)
            assertTrue(c.financialTransactionRepository.listAll().isEmpty())
            val review=requireNotNull(c.reviewRepository.findByRawSmsId("ml-loan-one"))
            val decision=c.reviewWorkflowService.resolveAsFinancialType(review.id,FinancialTransactionType.LOAN_REPAYMENT)
            assertTrue(decision.toString(),decision is ReviewWorkflowResult.Success)
            val version=requireNotNull(requireNotNull(c.interpretationService).synchronizeFeedback())
            await { c.financialTransactionRepository.listAll().size==2 && c.reviewRepository.listRequired().isEmpty() }
            assertTrue(requireNotNull(c.parsedEventRepository.findByRawSmsId("ml-loan-pending")).event.confidence.reasons.any {it=="family_origin:HUMAN_TEMPLATE"})
            TestRuntime.close()
            c=TestRuntime.container(host)
            c.awaitPostStartupBackgroundWorkForTesting()
            requireNotNull(c.interpretationService).synchronizeFeedback()
            assertEquals(version,requireNotNull(c.interpretationService).modelVersion)
            c.processRawSmsUseCase.ingest(evidence("ml-loan-next",body(22,298)))
            await {c.financialTransactionRepository.listAll().size==3}
            assertTrue(c.reviewRepository.listRequired().isEmpty())
            val parsed=requireNotNull(c.parsedEventRepository.findByRawSmsId("ml-loan-next"))
            assertEquals(ParseStatus.SUCCESS,parsed.event.parseStatus)
            assertEquals("22.00",parsed.event.amount!!.amount.toPlainString())
            assertTrue(parsed.event.confidence.reasons.toString(),parsed.event.confidence.reasons.any {it=="family_origin:HUMAN_TEMPLATE"})
            assertEquals(InterpretationOrigin.HUMAN_TEMPLATE,c.interpretationProvenanceWorkflow.load("ml-loan-next").origin)
            assertEquals(InterpretationOrigin.MANUAL,c.interpretationProvenanceWorkflow.load("ml-loan-one").origin)
            val dashboard=c.dashboardService.loadCurrentOverview()
            assertEquals("298.00",dashboard.loansOverview!!.loans.single().remainingBalance!!.amount.toPlainString())
            File(host.getExternalFilesDir(null),"ml-feedback.txt").writeText("First two messages: UNKNOWN and review required.\nOne real production manual loan decision.\nPending message automatically reparsed and posted.\nModel: $version\nCold reopen preserved learning.\nNext amount extracted fresh: ${parsed.event.amount}\nLatest remaining balance: ${dashboard.loansOverview.loans.single().remainingBalance}\nNo repeated review.\n")
        } finally {TestRuntime.reset(host)}
    }
    @Test fun corruptLearningCacheDoesNotBlockKnownRulesOrChangeFinancialHistory() = runBlocking {
        var c=fresh()
        try {
            c.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(bank,"7271"))
            fun body(amount: Int)="شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR $amount\nلدى: TEST_SHOP\nفي: ${at()}"
            c.processRawSmsUseCase.ingest(evidence("ml-known-one",body(12)))
            requireNotNull(c.interpretationService).synchronizeFeedback()
            val before=c.financialTransactionRepository.listAll()
            TestRuntime.close()
            writeLegacyModelCache("{corrupt")
            c=TestRuntime.container(host)
            c.awaitPostStartupBackgroundWorkForTesting()
            assertEquals(before,c.financialTransactionRepository.listAll())
            c.processRawSmsUseCase.ingest(evidence("ml-known-two",body(13)))
            assertEquals(2,c.financialTransactionRepository.listAll().size)
            val parsed=requireNotNull(c.parsedEventRepository.findByRawSmsId("ml-known-two"))
            assertEquals(ParseStatus.SUCCESS,parsed.event.parseStatus)
            assertFalse(parsed.event.confidence.reasons.any {it.startsWith("model:")})
        } finally {TestRuntime.reset(host)}
    }

    @Test fun oldFeatureLabelsAreRebuiltFromHumanDecisionsWithoutChangingPostedHistory() = runBlocking {
        var c=fresh()
        fun body(amount: Int)="سداد تمويل خاص\nمن: 3001\nالقسط: SAR $amount\nالمبلغ المتبقي: SAR 300\nلـ: تمويل شخصي\nفي: ${at()}"
        try {
            c.processRawSmsUseCase.ingest(evidence("old-model-human",body(20)))
            val review=requireNotNull(c.reviewRepository.findByRawSmsId("old-model-human"))
            assertTrue(c.reviewWorkflowService.resolveAsFinancialType(review.id,FinancialTransactionType.LOAN_REPAYMENT) is ReviewWorkflowResult.Success)
            requireNotNull(c.interpretationService).synchronizeFeedback()
            val before=c.financialTransactionRepository.listAll()
            TestRuntime.close()
            val scoped=TestStorageContext(host)
            val legacy=Room.databaseBuilder(scoped,LearningDatabase::class.java,"learning.db").build()
            try {
                legacy.learning().replaceLabels(listOf(LearningLabelRow("legacy","old-model-human","BANK_ALJAZIRA",
                    "FINANCING_INSTALLMENT","old-template","{\"w:private_person\":1}","bank-family-title-v1",1L,"USER_EVENT_KIND","hash")))
            } finally {legacy.close()}
            val obsoleteCache = writeLegacyModelCache("{\"schema\":1,\"featuresVersion\":\"bank-family-title-v1\"}")
            c=TestRuntime.container(host)
            c.awaitPostStartupBackgroundWorkForTesting()
            requireNotNull(c.interpretationService).synchronizeFeedback()
            assertEquals(before,c.financialTransactionRepository.listAll())
            val rebuilt=Room.databaseBuilder(scoped,LearningDatabase::class.java,"learning.db").build()
            try {
                val rows=rebuilt.learning().labels()
                assertEquals(1,rows.size)
                assertEquals(FamilyInputCleaner.FEATURES_VERSION,rows.single().featuresVersion)
                assertFalse(rows.single().featuresJson.contains("private_person"))
            } finally {rebuilt.close()}
            assertTrue(obsoleteCache.readText().contains("bank-family-title-v1"))
            assertEquals(ReviewStatus.RESOLVED,c.reviewRepository.findByRawSmsId("old-model-human")!!.status)
            c.processRawSmsUseCase.ingest(evidence("old-model-next",body(21)))
            assertEquals(2,c.financialTransactionRepository.listAll().size)
            assertTrue(c.reviewRepository.listRequired().isEmpty())
            File(host.getExternalFilesDir(null),"ml-cleaning-upgrade.txt").writeText(
                "Obsolete model cache ignored.\nHuman decision rebuilt using ${FamilyInputCleaner.VERSION}.\nPosted history unchanged across reopen.\nFuture amount 21 SAR extracted fresh; no repeated review.\n")
        } finally {TestRuntime.reset(host)}
    }

    @Test fun aRuleUpgradeRefreshesUnresolvedEvidenceWithoutRewritingPostedHistory() = runBlocking {
        var c=fresh()
        try {
            c.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(bank,"7271"))
            c.processRawSmsUseCase.ingest(evidence("rule-posted","شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR 12\nلدى: TEST\nفي: ${at()}"))
            c.processRawSmsUseCase.ingest(evidence("rule-old-loyalty","رصيد نقاطك في برنامج مكافآتي هو 99 نقطة."))
            val old=requireNotNull(c.parsedEventRepository.findByRawSmsId("rule-old-loyalty"))
            c.parsedEventRepository.save(old.event.copy(messageFamily=MessageFamily.UNKNOWN,parseStatus=ParseStatus.REVIEW_REQUIRED,
                confidence=Confidence(0.0,listOf("engine:previous","rules:previous"))),old.details)
            c.reviewRepository.upsertRequired("rule-old-loyalty",ReviewKind.NEEDS_REVIEW,listOf("unknown_or_unsupported_format"),Instant.now())
            val before=c.financialTransactionRepository.listAll()
            val posted=requireNotNull(c.parsedEventRepository.findByRawSmsId("rule-posted"))
            TestRuntime.close()
            c=TestRuntime.container(host)
            c.awaitPostStartupBackgroundWorkForTesting()
            requireNotNull(c.interpretationService).synchronizeFeedback()
            // A worker admitted by the previous container can retry during close/reopen.
            // Cover one default WorkManager backoff before requiring the real refresh to finish.
            await(timeoutMillis = androidx.work.WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS + 15_000) {
                c.reviewRepository.listRequired().isEmpty()
            }
            assertEquals(MessageFamily.NON_FINANCIAL,c.parsedEventRepository.findByRawSmsId("rule-old-loyalty")!!.event.messageFamily)
            assertEquals(before,c.financialTransactionRepository.listAll())
            assertEquals(posted,c.parsedEventRepository.findByRawSmsId("rule-posted"))
            assertEquals(InterpretationOrigin.RULES,c.interpretationProvenanceWorkflow.load("rule-old-loyalty").origin)
        } finally { TestRuntime.reset(host) }
    }
}
