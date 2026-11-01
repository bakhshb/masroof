package com.baraa.masroof.application.interpretation

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.review.EffectiveParsedEventProvider
import com.baraa.masroof.bank.InterpretationDraftMapper
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.repository.*
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.RawSms
import com.baraa.masroof.domain.repository.FinancialTransactionSaveResult
import com.baraa.masroof.engine.bank.*
import com.baraa.masroof.engine.bank.patterns.*
import com.baraa.masroof.finance.value.BankId
import com.baraa.masroof.finance.value.Currency
import com.baraa.masroof.parsing.model.ParseResult
import com.baraa.masroof.parsing.repository.ParsedEventRecord
import com.baraa.masroof.sms.hash.SmsBodyHasher
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomaticPatternWriteGuardTest {
    @Test fun rolePatternStampIsCheckedAndInvalidatedByDisableOrSameGenerationRestore() = runBlocking {
        val directory = Files.createTempDirectory("role-pattern-guard-").toFile()
        try {
            val store = MessagePatternStore(directory.resolve("patterns.json"))
            val definition = com.baraa.masroof.engine.bank.rules.AlJaziraRuleProfile.current()
                .asMessagePatterns("BANK_ALJAZIRA", listOf("AlJazira"))
            val snapshot = store.saveBank(0, definition)
            val input = MessageInput("role-guard", "AlJazira", "Purchase\nAmount:10 SAR\nCard:1234", Instant.parse("2026-10-15T10:00:00Z"), true)
            val result = InterpretationDraftMapper().map(input,
                PatternFactInterpreter(snapshot).interpretRouted(input, BankId(definition.bankId)), Bank.BANK_ALJAZIRA) as ParseResult.Success
            val record = ParsedEventRecord(result.event, result.details)
            val policy = PatternInterpretationWritePolicy(store)
            assertTrue(PatternInterpretationWritePolicy.isPatternDerived(record))
            assertTrue(policy.isCurrent(record))
            val purchase = definition.patterns.single { it.id == "bank.purchase" }
            val changed = snapshot.copy(banks = listOf(definition.copy(patterns = definition.patterns.map {
                if (it.id == purchase.id) it.copy(revision = 2, enabled = false) else it
            })))
            store.restoreRaw(com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec.encode(changed))
            assertFalse(policy.isCurrent(record))
            store.restoreRaw(com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec.encode(snapshot))
            assertTrue(policy.isCurrent(record))
            // A posted leg survives an unrelated save or a new enabled revision, but not a disable.
            val next = store.saveBank(snapshot.generation, definition.copy(patterns = listOf(purchase.copy(revision = 2))))
            assertFalse(policy.isCurrent(record))
            assertTrue(policy.isCurrent(record, posted = true))
            store.saveBank(next.generation, definition.copy(patterns = listOf(purchase.copy(revision = 3, enabled = false))))
            assertFalse(policy.isCurrent(record))
            assertFalse(policy.isCurrent(record, posted = true))
        } finally { directory.deleteRecursively() }
    }

    private val bank = BankId("BANK_STC")
    private val body = "Purchase\nAmount:10SAR"
    private val input = MessageInput("pattern-sms", "BANK", body, Instant.parse("2026-10-15T10:00:00Z"), true)
    private val pattern = SavedMessagePattern.fromChoices("purchase", bank.value, input.sender, "PURCHASE", body,
        listOf(FieldChoice("amount", 16, 21))).copy(factContract = PatternFactContract(MessageFamily.PURCHASE, MoneyDirection.OUTGOING))

    private suspend fun withFixture(block: suspend (MessagePatternStore, DatabaseAccessGate, ParsedEventRecord,
        AutomaticInterpretationWriteGuard, EffectiveParsedEventProvider, RoomUserCorrectionRepository) -> Unit) {
        val directory = Files.createTempDirectory("pattern-guard-").toFile()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = MessagePatternStore(directory.resolve("patterns.json"))
            val gate = DatabaseAccessGate()
            store.saveBank(0, BankMessagePatterns(bank.value, listOf(input.sender), patterns = listOf(pattern)))
            val raws = RoomRawSmsRepository(db.rawSmsDao())
            val parsed = RoomParsedEventRepository(db.parsedEventDao())
            raws.insertIfAbsent(RawSms(id = input.evidenceId, sender = input.sender, body = body,
                bodyHash = SmsBodyHasher.sha256Hex(body), receivedAt = input.receivedAt, deviceMessageId = null))
            val result = InterpretationDraftMapper().map(input, PatternFactInterpreter(store.load()).interpretRouted(input, bank), Bank(bank.value)) as ParseResult.Success
            parsed.save(result.event, result.details)
            val corrections = RoomUserCorrectionRepository(db.userCorrectionDao())
            val effective = EffectiveParsedEventProvider(parsed, corrections)
            val policy = PatternInterpretationWritePolicy(store)
            val guard = AutomaticInterpretationWriteGuard(effective, RoomReviewRepository(db.reviewItemDao()),
                RoomFinancialTransactionRepository(db.financialTransactionDao(), db.parsedEventDao()), { true },
                { work -> gate.withAccess { db.withTransaction { work() } } },
                patternsCurrent = { record, posted -> check(db.inTransaction()); policy.isCurrent(record, posted) })
            block(store, gate, requireNotNull(effective.findEffectiveByRawSmsId(input.evidenceId)), guard, effective, corrections)
        } finally { db.close(); directory.deleteRecursively() }
    }

    @Test fun currentProvenanceSurvivesRoomAndAllowsTheGuardedWrite() = runBlocking {
        withFixture { _, _, record, guard, _, _ ->
            var wrote = false
            assertEquals(FinancialTransactionSaveResult.Saved, guard.write(listOf(record)) { wrote = true; FinancialTransactionSaveResult.Saved })
            assertTrue(wrote)
        }
    }

    @Test fun disablingAndRestoringPatternsInvalidatesOldEvidenceWithoutChangingTheStoredEvent() = runBlocking {
        withFixture { store, gate, record, guard, effective, _ ->
            val bank = store.load().banks.single()
            for (revision in 2..3) {
                gate.withRestore { store.saveBank((revision - 1).toLong(), bank.copy(patterns = listOf(pattern.copy(revision = revision, enabled = revision == 3)))) }
                var wrote = false
                assertTrue(guard.write(listOf(record)) { wrote = true; FinancialTransactionSaveResult.Saved } is FinancialTransactionSaveResult.Conflict)
                assertFalse(wrote)
                assertEquals(record, effective.findEffectiveByRawSmsId(input.evidenceId))
            }
        }
    }

    @Test fun restoreWithTheSameGenerationAndCorruptConfigurationBothFailClosed() = runBlocking {
        withFixture { store, gate, record, guard, _, _ ->
            val saved = store.load()
            val changed = saved.copy(banks = saved.banks.map { it.copy(patterns = listOf(pattern.copy(
                factContract = pattern.factContract!!.copy(currency = Currency.USD)))) })
            gate.withRestore { store.restoreRaw(com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec.encode(changed)) }
            var wrote = false
            assertTrue(guard.write(listOf(record)) { wrote = true; FinancialTransactionSaveResult.Saved } is FinancialTransactionSaveResult.Conflict)
            gate.withRestore { store.restoreRaw("{broken", validate = false) }
            assertTrue(guard.write(listOf(record)) { wrote = true; FinancialTransactionSaveResult.Saved } is FinancialTransactionSaveResult.Conflict)
            assertFalse(wrote)
        }
    }

    @Test fun configurationEditsWaitUntilTheFinancialTransactionHasFinished() = runBlocking {
        withFixture { store, gate, record, guard, _, _ ->
            val attempted = CompletableDeferred<Unit>()
            var edit: Deferred<Unit>? = null
            assertEquals(FinancialTransactionSaveResult.Saved, guard.write(listOf(record)) {
                edit = async(Dispatchers.Default) {
                    attempted.complete(Unit)
                    gate.withRestore { store.saveBank(1, store.load().banks.single().copy(patterns = listOf(pattern.copy(revision = 2, enabled = false)))) }
                }
                attempted.await()
                assertFalse(edit.isCompleted)
                assertTrue(store.load().banks.single().currentPatterns.single().enabled)
                FinancialTransactionSaveResult.Saved
            })
            withTimeout(5_000) { edit!!.await() }
            assertFalse(store.load().banks.single().currentPatterns.single().enabled)
        }
    }

    @Test fun explicitFinancialCorrectionsRetainAuthorityWhileMerchantEditsDoNot() = runBlocking {
        withFixture { store, gate, _, guard, effective, corrections ->
            gate.withRestore { store.saveBank(1, store.load().banks.single().copy(patterns = listOf(pattern.copy(revision = 2, enabled = false)))) }
            corrections.save(com.baraa.masroof.domain.model.UserCorrection("merchant-edit", input.evidenceId,
                null, null, "Edited merchant", null, Instant.parse("2026-10-16T00:00:00Z")))
            val merchantOnly = requireNotNull(effective.findEffectiveByRawSmsId(input.evidenceId))
            var wrote = false
            assertTrue(guard.write(listOf(merchantOnly)) { wrote = true; FinancialTransactionSaveResult.Saved } is FinancialTransactionSaveResult.Conflict)
            assertFalse(wrote)
            corrections.save(com.baraa.masroof.domain.model.UserCorrection("amount-edit", input.evidenceId,
                null, com.baraa.masroof.core.money.Money.of("15", com.baraa.masroof.core.money.Currency.SAR),
                null, null, Instant.parse("2026-10-16T00:01:00Z")))
            val confirmed = requireNotNull(effective.findEffectiveByRawSmsId(input.evidenceId))
            assertEquals(FinancialTransactionSaveResult.Saved, guard.write(listOf(confirmed)) { wrote = true; FinancialTransactionSaveResult.Saved })
            assertTrue(wrote)
            // An older assembly still fails after the manual correction.
            assertTrue(guard.write(listOf(merchantOnly)) { fail("Stale evidence wrote"); FinancialTransactionSaveResult.Saved } is FinancialTransactionSaveResult.Conflict)
        }
    }
}
