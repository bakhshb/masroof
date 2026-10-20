package com.baraa.masroof.lab.beta.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.lab.bank.*
import com.baraa.masroof.lab.beta.BetaContainer
import com.baraa.masroof.lab.beta.application.evidence.*
import com.baraa.masroof.lab.beta.verification.FinancialArchiveFixture
import com.baraa.masroof.lab.finance.asset.*
import com.baraa.masroof.lab.finance.classification.*
import com.baraa.masroof.lab.finance.corrections.*
import com.baraa.masroof.lab.finance.fx.*
import com.baraa.masroof.lab.finance.posting.*
import com.baraa.masroof.lab.finance.value.*
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [28])
class RoomBetaFinancialAdoptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: BetaDatabase
    private val bank = BankId("BANK_ALJAZIRA")
    private val account = OwnedAsset(AssetId(Environment.BETA, bank, "owned"), AssetKind.ACCOUNT, "1234")
    private val instant = Instant.parse("2026-10-17T10:30:00Z")
    private val route = EvidenceRoute(RouteKind.MATCHED, setOf(bank), "test-router")
    @Before fun open() = runBlocking {
        BetaContainer.resetOffline(context)
        db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build()
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value, "ENABLED"))
        BetaAssetRegistry(db).register(account)
    }
    @After fun close() { db.close(); BetaContainer.resetOffline(context) }
    private fun reopen() { db.close(); db = Room.databaseBuilder(context, BetaDatabase::class.java, BetaDatabase.NAME).build() }
    private fun archive(id: String, amount: String = "23", currency: Currency = Currency.USD,
        rate: BigDecimal? = BigDecimal("3.756957"), family: MessageFamily = MessageFamily.PURCHASE) = FinancialArchiveFixture.encode(
        Interpretation(id, FinancialArchiveFixture.VERSION, bank,
            Facts(family, Money.of(amount, currency), MoneyDirection.OUTGOING,
                occurredAtLocal = LocalDateTime.parse("2026-10-17T13:30:00"), exchangeRate = rate),
            ParseStatus.SUCCESS, null, emptyList(), emptySet()))
    private suspend fun capture(id: String = "one", archive: String = archive(id)) {
        val store = RoomBetaEvidenceStore(db)
        store.capture(CapturedEvidence(id, EvidenceRequest("fixture", EvidenceOrigin.EXPLICIT_SAMPLE),
            EvidenceMessage(id, "fixture", "synthetic only", instant, ReceiptTimeSource.USER_SUPPLIED), instant, route, emptyList()))
        store.begin(id, instant)
        store.complete(id, route, EvidenceComparison(EvidenceDisposition.COMPARISON_ONLY, emptySet(), "{}", archive), instant)
    }
    private suspend fun request(id: String = "one", expected: PostingRevision? = null): BetaFinancialApproval {
        val item = RoomBetaFinancialAdoption(db).reviewPage().single { it.evidenceId == id }
        return BetaFinancialApproval(id, item.archiveRevision, expected, FinancialType.EXPENSE, account.id, null)
    }
    private suspend fun posting(id: String = "one") = RoomBetaFinancialLedger(db).read(RoomBetaFinancialAdoption.key(id))

    @Test fun explicitApprovalWritesArchivedNativeFactsAndWholeSmsFxAndSurvivesReopenRetry() = runBlocking {
        capture()
        assertNull(posting()) // Comparison and acknowledgement never grant financial authority.
        val request = request()
        assertTrue(RoomBetaFinancialAdoption(db).approve(request) is BetaFinancialAdoptionResult.Posted)
        val posted = requireNotNull(posting())
        assertEquals(Money.of("23", Currency.USD), posted.command.amount)
        assertEquals(instant, posted.command.time.instant)
        assertEquals(AppliedFx(BigDecimal("3.756957"), "SMS"), posted.command.appliedFx)
        assertEquals(CorrectionAuthority.USER_FINANCIAL_TYPE, posted.classification.input.authority)
        assertNull(db.financialAdoptionDao().retry("one"))
        reopen()
        assertTrue((RoomBetaFinancialAdoption(db).approve(request) as BetaFinancialAdoptionResult.Posted).result is FinancialPostingResult.AlreadyApplied)
        assertEquals(1, RoomBetaFinancialLedger(db).history(posted.command.key).size)
    }

    @Test fun firstApprovalPreviewNeverPostsAndStaleRegistryRequiresExactPendingCancellation() = runBlocking {
        capture()
        val service = RoomBetaFinancialAdoption(db)
        val preview = requireNotNull(service.preview("one"))
        assertEquals(Money.of("23", Currency.USD), preview.amount)
        assertEquals(instant, preview.time?.instant)
        assertNull(preview.blockedReason)
        assertNull(posting())
        assertNull(betaApprovalDraft(preview, "EXPENSE", "external", "external", null))
        val approval = requireNotNull(betaApprovalDraft(preview, "EXPENSE", "asset:${account.id.localId}", "external", null))
        BetaAssetRegistry(db).save(account.copy(last4 = "5678"), true, account)
        val result = service.approve(approval) as BetaFinancialAdoptionResult.Review
        assertEquals("STALE_REGISTRY", result.reason)
        assertNull(posting())
        reopen()
        val reopened = RoomBetaFinancialAdoption(db)
        val pending = requireNotNull(reopened.preview("one")?.pending)
        assertEquals(approval, pending)
        assertFalse(reopened.cancelApproval(pending.copy(type = FinancialType.FEE)))
        assertEquals("STALE_REGISTRY", (reopened.retryApproval("one") as BetaFinancialAdoptionResult.Review).reason)
        assertTrue(reopened.cancelApproval(pending))
        val fresh = requireNotNull(reopened.preview("one"))
        assertNull(fresh.pending)
        val replacement = requireNotNull(betaApprovalDraft(fresh, "EXPENSE", "asset:${account.id.localId}", "external", null))
        assertTrue(reopened.approve(replacement) is BetaFinancialAdoptionResult.Posted)
        assertEquals(1, RoomBetaFinancialLedger(db).history(RoomBetaFinancialAdoption.key("one")).size)
    }

    @Test fun previewRejectsUnsafeBankDisabledAndStaleArchiveWithoutPromotingCandidate() = runBlocking {
        capture()
        val service = RoomBetaFinancialAdoption(db)
        val approvedDraft = requireNotNull(betaApprovalDraft(requireNotNull(service.preview("one")), "EXPENSE", "asset:${account.id.localId}", "external", null))
        val store = RoomBetaEvidenceStore(db)
        store.begin("one", instant)
        assertEquals("INTERPRETATION_PENDING", service.preview("one")?.blockedReason)
        store.complete("one", route, EvidenceComparison(EvidenceDisposition.COMPARISON_ONLY, emptySet(), "{}", archive("one", amount = "29")), instant)
        assertEquals("STALE_ARCHIVE", (service.approve(approvedDraft) as BetaFinancialAdoptionResult.Review).reason)
        assertNull(posting())
        service.cancelApproval(approvedDraft)
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value, "DISABLED"))
        assertEquals("BANK_DISABLED", service.preview("one")?.blockedReason)
        assertNull(betaApprovalDraft(requireNotNull(service.preview("one")), "EXPENSE", "asset:${account.id.localId}", "external", null))
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value, "ENABLED"))
        capture("unsafe", archive("unsafe", family = MessageFamily.OTP))
        assertEquals("UNSAFE_INTERPRETATION", service.preview("unsafe")?.blockedReason)
        assertNull(service.preview("unsafe")?.amount)
    }

    @Test fun explicitInboundAttributionAndStableAssetIdentityRequired() = runBlocking {
        capture()
        val preview = requireNotNull(RoomBetaFinancialAdoption(db).preview("one"))
        assertNull(betaApprovalDraft(preview, "EXTERNAL_TRANSFER_IN", "external", "asset:${account.id.localId}", null))
        assertNull(betaApprovalDraft(preview, "EXPENSE", "1234", "external", null))
        val draft = requireNotNull(betaApprovalDraft(preview, "EXTERNAL_TRANSFER_IN", "external", "asset:${account.id.localId}", "OTHER"))
        assertEquals(IncomeAttribution.OTHER, draft.incomeAttribution)
        assertEquals(account.id, draft.destination)
    }

    @Test fun externalNamedAssetIsDistinctAndReviewedRetryCannotReplaceSupersedingIntent() = runBlocking {
        val named = account.copy(id = account.id.copy(localId = "external"))
        BetaAssetRegistry(db).register(named)
        capture()
        val service = RoomBetaFinancialAdoption(db)
        val preview = requireNotNull(service.preview("one"))
        val original = requireNotNull(betaApprovalDraft(preview, "EXPENSE", "asset:external", "external", null))
        assertEquals(named.id, original.source)
        assertNull(original.destination)
        // A stale archive leaves the original exact durable intent, then a newer user choice supersedes it.
        RoomBetaEvidenceStore(db).begin("one", instant)
        assertTrue(service.approve(original) is BetaFinancialAdoptionResult.Review)
        val replacement = original.copy(type = FinancialType.FEE)
        assertTrue(service.approve(replacement) is BetaFinancialAdoptionResult.Review)
        assertEquals("SUPERSEDED_APPROVAL", (service.retryApproval("one", original) as BetaFinancialAdoptionResult.Review).reason)
        assertEquals(replacement, service.preview("one")?.pending)
        assertFalse(service.cancelApproval(original))
        assertNull(posting())
    }

    @Test fun reviewedLegacyRetryStillChecksEnabledBankAtomically() = runBlocking {
        capture()
        val original = request() // Saved requests predating the UI have no registry fingerprint.
        val service = RoomBetaFinancialAdoption(db)
        RoomBetaEvidenceStore(db).begin("one", instant)
        assertTrue(service.approve(original) is BetaFinancialAdoptionResult.Review)
        // Keep the completed original archive and durable financial intent, simulating a
        // restored pending approval without a successful financial commit.
        db.evidenceDao().clearRetry("one")
        db.bankSelectionDao().upsert(BetaBankSelectionEntity(bank.value, "DISABLED"))
        val preview = requireNotNull(service.preview("one"))
        val pending = requireNotNull(preview.pending)
        assertNull(pending.expectedRegistryRevision)
        assertEquals("BANK_DISABLED", preview.blockedReason)
        assertEquals("BANK_DISABLED", (service.retryApproval("one", pending) as BetaFinancialAdoptionResult.Review).reason)
        assertNull(posting())
        assertEquals(pending, service.preview("one")?.pending)
    }

    @Test fun durableIntentRecoversCrashAfterCommitBeforeAcknowledgementWithoutDuplicate() = runBlocking {
        capture(); val request = request()
        RoomBetaFinancialAdoption(db).approve(request)
        // Restore the exact saved request as if the process died before the separate marker clear.
        val adopted = requireNotNull(db.financialAdoptionDao().evidence("one"))
        db.financialAdoptionDao().retry(BetaFinancialApprovalRetryEntity("one", adopted.approvalRequest))
        reopen()
        assertTrue((RoomBetaFinancialAdoption(db).retryApproval("one") as BetaFinancialAdoptionResult.Posted).result is FinancialPostingResult.AlreadyApplied)
        assertNull(db.financialAdoptionDao().retry("one"))
        assertEquals(1, RoomBetaFinancialLedger(db).history(RoomBetaFinancialAdoption.key("one")).size)
    }

    @Test fun failedInitialSqlWriteRollsBackAllFinancialStateButRetainsExactExplicitRetry() = runBlocking {
        capture(); val request = request()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_initial_post BEFORE INSERT ON beta_audit_log WHEN NEW.eventKind='LEDGER_REVISION' BEGIN SELECT RAISE(ABORT, 'synthetic-fault'); END")
        try { RoomBetaFinancialAdoption(db).approve(request); fail("Expected the injected write failure") }
        catch (_: android.database.sqlite.SQLiteException) { /* The explicit request was committed first. */ }
        assertNotNull(db.financialAdoptionDao().retry("one"))
        assertNull(db.financialAdoptionDao().evidence("one")); assertNull(posting())
        assertTrue(RoomBetaFinancialLedger(db).history(RoomBetaFinancialAdoption.key("one")).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_initial_post")
        reopen()
        assertTrue((RoomBetaFinancialAdoption(db).retryApproval("one") as BetaFinancialAdoptionResult.Posted).result is FinancialPostingResult.Applied)
        assertNotNull(posting()); assertNull(db.financialAdoptionDao().retry("one"))
    }

    @Test fun reparseHoldsFinancialResultAndPreservesAuditAndRejectsStaleApproval() = runBlocking {
        capture(); val initial = request(); RoomBetaFinancialAdoption(db).approve(initial)
        val saved = requireNotNull(posting())
        RoomBetaEvidenceStore(db).begin("one", instant)
        assertNull(posting()); assertEquals(saved, RoomBetaFinancialLedger(db).readAudit(saved.command.key))
        RoomBetaEvidenceStore(db).complete("one", route, EvidenceComparison(EvidenceDisposition.COMPARISON_ONLY,
            emptySet(), "{}", archive("one", "777", Currency.EUR)), instant)
        assertTrue(RoomBetaFinancialAdoption(db).approve(initial) is BetaFinancialAdoptionResult.Review)
        assertNull(posting()); assertEquals(saved, RoomBetaFinancialLedger(db).readAudit(saved.command.key))
        val fresh = request(expected = saved.command.revision)
        assertEquals(BetaFinancialAdoptionResult.Review("FX_CURRENCY_CHANGE_REQUIRES_CORRECTION"), RoomBetaFinancialAdoption(db).approve(fresh))
    }

    @Test fun securityUnknownAndUnsupportedBaselineNeverAuthorizeEvenExplicitFinancialChoice() = runBlocking {
        for ((index, family) in listOf(MessageFamily.OTP, MessageFamily.NON_FINANCIAL, MessageFamily.UNKNOWN, MessageFamily.BALANCE_NOTICE).withIndex()) {
            // UNKNOWN's well-formed archive requires a blocker, constructed directly for this corrupt archive boundary.
            val json = JSONObject(archive("unsafe$index")).apply { getJSONObject("facts").put("family", family.name) }.toString()
            capture("unsafe$index", json)
            assertEquals(BetaFinancialAdoptionResult.Review("UNSAFE_INTERPRETATION"), RoomBetaFinancialAdoption(db).approve(request("unsafe$index")))
            assertNull(posting("unsafe$index"))
        }
    }

    @Test fun routeEngineDiagnosticsAndMissingBankTimeFailClosed() = runBlocking {
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("engineVersion", "unaccepted-candidate") },
            { it.getJSONObject("facts").put("occurredAtLocal", JSONObject.NULL) },
            { it.getJSONArray("diagnostics").put(JSONObject().put("code", "OTHER").put("message", "fixture").put("severity", "ERROR")) },
            { it.put("contractVersion", 999) })
        variants.forEachIndexed { i, mutate ->
            capture("bad$i", JSONObject(archive("bad$i")).apply(mutate).toString())
            assertEquals(BetaFinancialAdoptionResult.Review("UNSAFE_INTERPRETATION"), RoomBetaFinancialAdoption(db).approve(request("bad$i")))
        }
        capture("route")
        val old = requireNotNull(db.evidenceDao().outcome("route"))
        db.evidenceDao().outcome(old.copy(route = "{\"kind\":\"SUSPECTED\",\"candidates\":[]}"))
        assertEquals(BetaFinancialAdoptionResult.Review("UNSAFE_INTERPRETATION"), RoomBetaFinancialAdoption(db).approve(request("route")))
    }

    @Test fun manualRegistryChangeHoldsAdoptionAndDirectLegacyPostingWithoutDeletingFacts() = runBlocking {
        capture(); RoomBetaFinancialAdoption(db).approve(request())
        val saved = requireNotNull(posting())
        val direct = saved.classification.input.copy(key = PostingKey(Environment.BETA, "direct"), evidenceIds = setOf("direct-evidence"))
        assertTrue(RoomBetaFinancialLedger(db).post(direct) is FinancialPostingResult.Applied)
        val changed = account.copy(last4 = "5678")
        BetaAssetRegistry(db).save(changed, true, account)
        assertEquals("REGISTRY_CHANGED", db.financialAdoptionDao().evidence("one")?.reason)
        assertNull(posting()); assertNull(RoomBetaFinancialLedger(db).read(direct.key))
        assertEquals(saved, RoomBetaFinancialLedger(db).readAudit(saved.command.key))
        assertNotNull(RoomBetaFinancialLedger(db).readAudit(direct.key))
    }

    @Test fun unrelatedRegistrySnapshotDoesNotHoldAnOtherwiseUnchangedPosting() = runBlocking {
        capture(); RoomBetaFinancialAdoption(db).approve(request())
        val saved = requireNotNull(posting())
        val unrelated = account.copy(id = account.id.copy(localId = "unrelated"), last4 = "9999")
        BetaAssetRegistry(db).register(unrelated)
        val direct = saved.classification.input.copy(key = PostingKey(Environment.BETA, "wide-snapshot"),
            evidenceIds = setOf("wide-evidence"), assets = saved.classification.input.assets + (unrelated.id to unrelated))
        RoomBetaFinancialLedger(db).post(direct)
        val directBefore = requireNotNull(RoomBetaFinancialLedger(db).read(direct.key))
        BetaAssetRegistry(db).save(unrelated.copy(last4 = "8888"), true, unrelated)
        assertEquals(saved, posting())
        assertEquals("ACTIVE", db.financialAdoptionDao().evidence("one")?.status)
        assertEquals(directBefore, RoomBetaFinancialLedger(db).read(direct.key))
    }

    @Test fun marketFxPersistenceRechecksRevisionAndPreservesCompletePair() = runBlocking {
        capture(archive = archive("one", rate = null)); RoomBetaFinancialAdoption(db).approve(request())
        val first = requireNotNull(posting()); assertNull(first.command.appliedFx)
        val provider = FxMarketRateProvider { currency, date ->
            assertEquals(Currency.USD, currency); assertEquals(LocalDate.parse("2026-10-17"), date); BigDecimal("3.77") }
        assertTrue(RoomBetaFinancialAdoption(db).enrich("one", first.command.revision, provider) is BetaFinancialAdoptionResult.Posted)
        val enriched = requireNotNull(posting()); assertEquals(AppliedFx(BigDecimal("3.77"), "MARKET"), enriched.command.appliedFx)
        assertTrue(RoomBetaFinancialAdoption(db).enrich("one", first.command.revision, provider) is BetaFinancialAdoptionResult.Review)
        RoomBetaFinancialAdoption(db).enrich("one", enriched.command.revision, FxMarketRateProvider { _, _ -> error("Must not replace complete pair") })
        assertEquals(enriched, posting())
    }

    @Test fun marketRaceWithReparseCannotCommitStaleRate() = runBlocking {
        capture(archive = archive("one", rate = null)); RoomBetaFinancialAdoption(db).approve(request())
        val first = requireNotNull(posting())
        val result = RoomBetaFinancialAdoption(db).enrich("one", first.command.revision, FxMarketRateProvider { _, _ ->
            RoomBetaEvidenceStore(db).begin("one", instant); BigDecimal("3.77") })
        assertEquals(BetaFinancialAdoptionResult.Review("STALE_OR_HELD"), result)
        assertNull(posting()); assertNull(RoomBetaFinancialLedger(db).readAudit(first.command.key)!!.command.appliedFx)
    }

    @Test fun correctionOwnedSuppressionCannotBeResurrectedByApprovalOrEnrichment() = runBlocking {
        capture(); RoomBetaFinancialAdoption(db).approve(request())
        val saved = requireNotNull(posting())
        val observation = requireNotNull(RoomBetaFinancialAdoption(db).observation(saved.command.key))
        RoomBetaCorrectionLifecycle(db).submit(observation, CorrectionRequest("hide", saved.command.key,
            saved.command.revision.correction + 1, observation.input.revision.interpretation, setOf("one"), FinancialDecision.SuppressAsNonFinancial))
        assertNull(posting())
        assertEquals(BetaFinancialAdoptionResult.Review("CORRECTION_OWNS_DECISION"), RoomBetaFinancialAdoption(db).approve(request(expected = saved.command.revision)))
        assertTrue(RoomBetaFinancialAdoption(db).enrich("one", saved.command.revision, FxMarketRateProvider { _, _ -> BigDecimal.TEN }) is BetaFinancialAdoptionResult.Review)
        assertNull(posting())
    }

    @Test fun trustedRebasePreservesManualFactsAcrossOrdinaryReparseAndKeepsSecuritySticky() = runBlocking {
        capture(); RoomBetaFinancialAdoption(db).approve(request())
        val first = requireNotNull(posting())
        val observation = requireNotNull(RoomBetaFinancialAdoption(db).observation(first.command.key))
        RoomBetaCorrectionLifecycle(db).submit(observation, CorrectionRequest("manual-amount", first.command.key,
            first.command.revision.correction + 1, observation.input.revision.interpretation, setOf("one"),
            FinancialDecision.ConfirmFamilyOrAmount(amount = Money.of("30", Currency.USD))))
        assertEquals(Money.of("30", Currency.USD), posting()!!.command.amount)
        capture(archive = archive("one", "777"))
        assertNull(posting())
        assertTrue(RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one") is BetaFinancialAdoptionResult.Posted)
        assertEquals(Money.of("30", Currency.USD), posting()!!.command.amount)
        assertEquals(first.command.appliedFx, posting()!!.command.appliedFx)
        val size = RoomBetaFinancialLedger(db).history(first.command.key).size
        RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one")
        assertEquals(size, RoomBetaFinancialLedger(db).history(first.command.key).size)
        capture(archive = archive("one", family = MessageFamily.OTP))
        assertNull(RoomBetaFinancialAdoption(db).observation(first.command.key))
        assertEquals(BetaFinancialAdoptionResult.Review("CORRECTION_REVIEW"), RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one"))
        assertNull(posting())
        capture() // A later ordinary interpretation cannot silently remove a security hold.
        assertEquals(BetaFinancialAdoptionResult.Review("CORRECTION_REVIEW"), RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one"))
        assertNull(posting()); assertNotNull(RoomBetaFinancialLedger(db).readAudit(first.command.key))
    }

    @Test fun exactPendingApprovalCancellationCannotClearArchiveHoldOrAnotherRequest() = runBlocking {
        capture(); val first = request(); RoomBetaFinancialAdoption(db).approve(first)
        val old = requireNotNull(posting())
        val stale = first.copy(archiveRevision = "stale")
        assertEquals(BetaFinancialAdoptionResult.Review("STALE_ARCHIVE"), RoomBetaFinancialAdoption(db).approve(stale))
        assertNull(posting())
        assertFalse(RoomBetaFinancialAdoption(db).cancelApproval(first))
        assertTrue(RoomBetaFinancialAdoption(db).cancelApproval(stale))
        assertEquals(old, posting())
        RoomBetaEvidenceStore(db).begin("one", instant)
        assertFalse(RoomBetaFinancialAdoption(db).cancelApproval(stale)); assertNull(posting())
    }

    @Test fun immediateReconcileAfterManualTypeChangeIsIdempotentAndRealReparsePreservesSelection() = runBlocking {
        capture(); RoomBetaFinancialAdoption(db).approve(request())
        val first = requireNotNull(posting())
        val observation = requireNotNull(RoomBetaFinancialAdoption(db).observation(first.command.key))
        RoomBetaCorrectionLifecycle(db).submit(observation, CorrectionRequest("manual-type", first.command.key,
            first.command.revision.correction + 1, observation.input.revision.interpretation, setOf("one"),
            FinancialDecision.ResolveFinancialType(FinancialType.FEE)))
        val manuallySelected = requireNotNull(posting())
        val size = RoomBetaFinancialLedger(db).history(first.command.key).size
        assertEquals(FinancialType.FEE, manuallySelected.command.type)
        assertTrue((RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one") as BetaFinancialAdoptionResult.Posted).result is FinancialPostingResult.AlreadyApplied)
        assertEquals(size, RoomBetaFinancialLedger(db).history(first.command.key).size)
        capture(archive = archive("one", "777"))
        assertTrue(RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one") is BetaFinancialAdoptionResult.Posted)
        assertEquals(FinancialType.FEE, posting()!!.command.type)
        assertEquals(manuallySelected.command.amount, posting()!!.command.amount)
    }
    @Test fun existingApprovalRebaseKeepsItsSelectionAndMakesSecuritySticky() = runBlocking {
        capture();RoomBetaFinancialAdoption(db).approve(request())
        val first=requireNotNull(posting())
        capture(archive=archive("one","777"))
        assertTrue(RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one") is BetaFinancialAdoptionResult.Posted)
        assertEquals(first.command.amount,posting()!!.command.amount)
        assertEquals(first.command.time,posting()!!.command.time)
        assertEquals(first.command.appliedFx,posting()!!.command.appliedFx)
        capture(archive=archive("one",family=MessageFamily.OTP))
        assertEquals(BetaFinancialAdoptionResult.Review("CORRECTION_REVIEW"),RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one"))
        assertNull(posting())
        capture()
        assertEquals(BetaFinancialAdoptionResult.Review("CORRECTION_REVIEW"),RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one"))
        assertNull(posting())
    }

    @Test fun rebaseDoesNotReleaseAChangedOwnedAccount() = runBlocking {
        capture();RoomBetaFinancialAdoption(db).approve(request())
        BetaAssetRegistry(db).save(account.copy(last4="9999"),true,account)
        capture(archive=archive("one","777"))
        assertEquals(BetaFinancialAdoptionResult.Review("REGISTRY_CHANGED"),RoomBetaFinancialAdoption(db).reconcileApprovedEvidence("one"))
        assertNull(posting())
        assertNotNull(RoomBetaFinancialLedger(db).readAudit(RoomBetaFinancialAdoption.key("one")))
    }

}
