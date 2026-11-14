package com.baraa.masroof.application.patterns

import com.baraa.masroof.testsupport.withPatternDatabase
import com.baraa.masroof.data.patterns.MessagePatternStore
import com.baraa.masroof.data.room.DatabaseAccessGate
import com.baraa.masroof.data.room.DatabaseRestartRequiredException
import com.baraa.masroof.engine.bank.patterns.BankMessagePatterns
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.baraa.masroof.testsupport.NoOpAppLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessagePatternsWorkflowTest {
    @Test fun everyBundledBankKeepsTheUsersSendersAndYieldsAnOwnedSender() {
        for (file in listOf("BANK_ALJAZIRA", "BANK_SNB")) {
            val seed = com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec
                .decode(java.io.File("src/main/assets/bank-patterns/$file.patterns.json").readText()).banks.single()
            val edited = seed.copy(senders = listOf(seed.senders.first()))
            val installed = com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration(banks = listOf(edited))
            assertNull("$file: a removed bundled sender is not re-added", MessagePatternStore.installation(installed, seed))
            val other = com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration(
                banks = listOf(BankMessagePatterns("BANK_USER", listOf(seed.senders.first()))))
            assertNull("$file: a sender the user's bank owns blocks the bundled bank", MessagePatternStore.installation(other, seed))
        }
    }

    @Test fun approvedLinksSurviveRestartsAndTravelWithNewerBundledRules() {
        val text = java.io.File("src/main/assets/bank-patterns/BANK_BARQ.patterns.json").readText()
        fun bank(json: String) = com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec.decode(json).banks.single()
        val seed = bank(text)
        val links = listOf(com.baraa.masroof.engine.bank.patterns.PatternAccountBinding(
            com.baraa.masroof.engine.bank.patterns.PatternAccountRole.DESTINATION, com.baraa.masroof.finance.value.BankId("BANK_BARQ"), "1234"))
        val approved = seed.copy(patterns = seed.patterns.map {
            if (it.id != "barq.transfer-in") it else it.copy(revision = 2, factContract = it.factContract!!.copy(version = 2, accountBindings = links))
        })
        val configuration = com.baraa.masroof.engine.bank.patterns.MessagePatternConfiguration(banks = listOf(approved))
        assertNull("Links alone are not a newer bundled rule", MessagePatternStore.installation(configuration, seed))
        val newer = bank(text.replace("\"incoming transfer\"", "\"incoming transfer v2\""))
        val upgraded = MessagePatternStore.installation(configuration, newer)!!.currentPatterns.single { it.id == "barq.transfer-in" }
        assertEquals(3, upgraded.revision)
        assertEquals(newer.patterns.single { it.id == "barq.transfer-in" }.roleProfile, upgraded.roleProfile)
        assertEquals(links, upgraded.factContract!!.accountBindings)
    }

    @Test fun aSenderThatDetectsAsAnotherBanksSenderCannotBeAdded() = runBlocking {
        withPatternDatabase { db ->
            val store = MessagePatternStore(db)
            store.saveBank(0, BankMessagePatterns("BANK_ALINMA", listOf("alinma"), "Alinma"))
            val workflow = MessagePatternsWorkflow(store, DatabaseAccessGate(), appLogService = NoOpAppLog)
            val generation = store.load().generation
            for (sender in listOf("ALINMA", "Al-inma", "Alinma-AD", "---")) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { workflow.addBank(generation, "Mine", sender) } }
            }
            workflow.addBank(generation, "Mine", "Alinma Pay")
            Unit
        }
    }

    @Test fun newerBundledRolesReachInstalledBanksUnlessTheUserDisabledThem() = runBlocking {
        withPatternDatabase { db ->
            val store = MessagePatternStore(db)
            val text = java.io.File("src/main/assets/bank-patterns/BANK_BARQ.patterns.json").readText()
            fun bank(json: String) = com.baraa.masroof.data.patterns.MessagePatternConfigurationCodec.decode(json).banks.single()
            val updated = bank(text)
            val old = bank(text.replace("\"incoming transfer\"", "\"incoming transfer v0\"").replace("\"outgoing transfer\"", "\"outgoing transfer v0\""))
            store.installMissingBanks(listOf(old))
            fun workflow() = MessagePatternsWorkflow(store, DatabaseAccessGate(), bundledBanks = listOf(updated), appLogService = NoOpAppLog)
            workflow().setEnabled(store.load().generation, "BANK_BARQ", "barq.transfer-out", false)
            val installed = workflow().load().banks.single().currentPatterns.associateBy { it.id }
            val seeds = updated.patterns.associateBy { it.id }
            assertEquals(seeds.getValue("barq.transfer-in"), installed.getValue("barq.transfer-in").copy(revision = 1))
            assertEquals(2, installed.getValue("barq.transfer-in").revision)
            assertNotEquals(seeds.getValue("barq.transfer-out").roleProfile, installed.getValue("barq.transfer-out").roleProfile)
            val generation = store.load().generation
            workflow().load()
            assertEquals("A current bank is not rewritten again", generation, store.load().generation)
            // Re-enabling hands the rule back to bundled updates.
            workflow().setEnabled(store.load().generation, "BANK_BARQ", "barq.transfer-out", true)
            assertEquals(seeds.getValue("barq.transfer-out").roleProfile,
                workflow().load().banks.single().currentPatterns.single { it.id == "barq.transfer-out" }.roleProfile)
            // Many releases keep one stored revision per role, far below the 128-revision bank limit.
            repeat(200) { release ->
                MessagePatternsWorkflow(store, DatabaseAccessGate(), bundledBanks = listOf(if (release % 2 == 0) old else updated), appLogService = NoOpAppLog).load()
            }
            assertEquals(1, store.load().banks.single().patterns.count { it.id == "barq.transfer-in" })
        }
    }

    @Test fun deletingABundledPatternDoesNotReinstallItOnCatalogLoadOrRestart() = runBlocking {
        withPatternDatabase { db ->
            val store = MessagePatternStore(db)
            val body = "Payment\nAmount:10SAR"
            val seed = com.baraa.masroof.engine.bank.patterns.SavedMessagePattern.fromChoices("bundled", "BANK_TEST", "Sender",
                "PURCHASE", body, listOf(com.baraa.masroof.engine.bank.patterns.FieldChoice("amount", 15, body.length)))
            val bank = BankMessagePatterns("BANK_TEST", listOf("Sender"), patterns = listOf(seed))
            fun workflow() = MessagePatternsWorkflow(store, DatabaseAccessGate(), bundledBanks = listOf(bank), appLogService = NoOpAppLog)
            val w = workflow()
            val installed = w.load()
            assertTrue(runCatching { w.deletePattern(installed.generation, bank.bankId, seed.id) }.isFailure)
            w.setEnabled(installed.generation, bank.bankId, seed.id, false)
            w.deletePattern(installed.generation + 1, bank.bankId, seed.id)
            val deleted = w.load()
            assertTrue(deleted.banks.single().patterns.isEmpty())
            assertEquals(setOf(seed.id), deleted.banks.single().deletedPatternIds)
            assertEquals(deleted, workflow().load())
            assertTrue(runCatching { w.restoreRevision(deleted.generation, bank.bankId, seed.id, 1) }.isFailure)
            assertEquals(deleted, w.load())
        }
    }

    @Test fun aMessageFromAFoldEqualSenderIsTaughtUnderTheConfiguredSender() = runBlocking {
        withPatternDatabase { db ->
            val w = MessagePatternsWorkflow(MessagePatternStore(db), DatabaseAccessGate(), appLogService = NoOpAppLog)
            w.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank")))
            val body = "Payment\nAmount:10SAR\nTo:SHOP"
            val preview = w.teach(1, "STC", "STC BANK-AD", PatternOperation.PURCHASE, body, listOf(PatternBinding("amount", 15, 20)))
            assertEquals("STC Bank", preview.pattern.sender)
            assertNotNull(w.tryPreview(preview, "stc bank", body))
        }
    }

    @Test fun editingKeepsPatternIdentityAndHistoryButRequiresFreshFinancialApproval() = runBlocking {
        withPatternDatabase { db ->
            val store = MessagePatternStore(db)
            val w = MessagePatternsWorkflow(store, DatabaseAccessGate(), appLogService = NoOpAppLog)
            w.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank")))
            val body = "Payment\nAmount:10SAR\nTo:SHOP"
            val amount = PatternBinding("amount", 15, 20)
            w.saveTeaching(w.teach(1, "STC", "STC Bank", PatternOperation.PURCHASE, body, listOf(amount)))
            val original = w.load().banks.single().patterns.single()
            val approved = original.copy(revision = 2, factContract = com.baraa.masroof.engine.bank.patterns.PatternFactContract(
                com.baraa.masroof.engine.bank.MessageFamily.PURCHASE, com.baraa.masroof.engine.bank.MoneyDirection.OUTGOING))
            store.saveBank(2, BankMessagePatterns("STC", listOf("STC Bank"), patterns = listOf(approved)))
            val before = store.load()
            assertEquals(listOf(amount), w.editBindings(before.generation, "STC", original.id, body))
            val edit = w.teach(before.generation, "STC", "STC Bank", PatternOperation.TRANSFER_OUT, body,
                listOf(amount, PatternBinding("counterparty", body.indexOf("SHOP"), body.length)), patternId = original.id)
            assertEquals(before, store.load())
            assertEquals("OTHER", w.tryPreview(edit, "STC Bank", body.replace("SHOP", "OTHER"))!!.getValue("counterparty").text)
            val saved = w.saveTeaching(edit)
            val bank = saved.banks.single()
            assertEquals(listOf(1, 2, 3), bank.patterns.map { it.revision })
            val current = bank.currentPatterns.single()
            assertEquals(original.id, current.id)
            assertEquals("TRANSFER_OUT", current.label)
            assertNull(current.factContract)
            assertEquals(approved, bank.patterns.single { it.revision == 2 })
            assertTrue(runCatching { w.saveTeaching(edit) }.isFailure)
            val stale = w.teach(saved.generation, "STC", "STC Bank", PatternOperation.PURCHASE, body, listOf(amount), patternId = original.id)
            w.setEnabled(saved.generation, "STC", original.id, false)
            w.deletePattern(saved.generation + 1, "STC", original.id)
            assertTrue(runCatching { w.saveTeaching(stale) }.isFailure)
            assertTrue(w.load().banks.single().patterns.isEmpty())
        }
    }

    @Test fun bankEditsKeepIdentityAndRejectCrossBankSendersWithoutChangingTheCatalog() = runBlocking {
        withPatternDatabase { db ->
            val workflow = MessagePatternsWorkflow(MessagePatternStore(db), DatabaseAccessGate(), appLogService = NoOpAppLog)
            workflow.addBank(0, "First bank", "FIRST", "FIRST_BANK")
            workflow.addBank(1, "Second bank", "SECOND", "SECOND_BANK")
            workflow.updateBank(2, "FIRST_BANK", "Renamed bank", listOf("FIRST", "FIRST_ALIAS"))
            val saved = workflow.catalog()
            assertEquals("Renamed bank", saved.banks.single { it.bankId == "FIRST_BANK" }.displayName)
            assertEquals(listOf("FIRST", "FIRST_ALIAS"), saved.banks.single { it.bankId == "FIRST_BANK" }.senders)
            assertTrue(runCatching { workflow.updateBank(3, "FIRST_BANK", "Conflict", listOf("SECOND")) }.isFailure)
            assertTrue(runCatching { workflow.addBank(3, "Duplicate", "FIRST_ALIAS") }.isFailure)
            assertEquals(saved, workflow.catalog())
        }
    }

    @Test fun teachingRequiresPreviewAndPreservesRevisionsWithoutFinancialWrites() = runBlocking {
        withPatternDatabase { db ->
            val workflow = MessagePatternsWorkflow(MessagePatternStore(db), DatabaseAccessGate(), appLogService = NoOpAppLog)
            workflow.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank")))
            val body = "Payment\nAmount:10SAR\nTo:SHOP"
            val bindings = listOf(PatternBinding("amount", body.indexOf("10SAR"), body.indexOf("10SAR") + 5),
                PatternBinding("merchant", body.indexOf("SHOP"), body.length))
            val preview = workflow.teach(1, "STC", "STC Bank", PatternOperation.PURCHASE, body, bindings)
            assertEquals("10SAR", preview.fields.getValue("amount").text)
            assertTrue(workflow.load().banks.single().patterns.isEmpty())
            assertEquals("250SAR", workflow.tryPreview(preview, "STC Bank", body.replace("10SAR", "250SAR"))!!.getValue("amount").text)
            assertNull(workflow.tryPreview(preview, "OTHER", body))
            val saved = workflow.saveTeaching(preview)
            val id = saved.banks.single().currentPatterns.single().id
            // A taught pattern starts off: it matches nothing until the user switches it on.
            val stored = saved.banks.single().currentPatterns.single()
            assertFalse(stored.enabled)
            assertNull(stored.preview("STC", "STC Bank", body))
            workflow.setEnabled(2, "STC", id, true)
            val active = workflow.load().banks.single().currentPatterns.single()
            assertNotNull(active.preview("STC", "STC Bank", body))
            assertNull(active.preview("OTHER", "STC Bank", body))
            workflow.setEnabled(3, "STC", id, false)
            assertNull(workflow.load().banks.single().currentPatterns.single().preview("STC", "STC Bank", body))
            assertTrue(runCatching { workflow.saveTeaching(preview) }.isFailure)
            workflow.restoreRevision(4, "STC", id, 2)
            val restored = workflow.load().banks.single()
            val versions = restored.patterns
            assertEquals(listOf(1, 2, 3, 4), versions.map { it.revision })
            assertNotNull(restored.currentPatterns.single().preview("STC", "STC Bank", body))
            // Editing keeps the pattern's current on/off state.
            assertTrue(workflow.teach(5, "STC", "STC Bank", PatternOperation.PURCHASE, body, bindings, patternId = id).pattern.enabled)
            assertTrue(runCatching { workflow.teach(5, "STC", "STC Bank", PatternOperation.PURCHASE, body,
                listOf(PatternBinding("merchant", body.indexOf("SHOP"), body.length))) }.isFailure)
            assertTrue(runCatching { workflow.teach(5, "STC", "STC Bank", PatternOperation.PURCHASE, body,
                bindings + PatternBinding("source_account", 0, 7)) }.isFailure)
        }
    }

    @Test fun aValueAtTheStartOfTheMessageIsRefusedAndTheScreenRuleSaysSoFirst() = runBlocking {
        withPatternDatabase { db ->
            val w = MessagePatternsWorkflow(MessagePatternStore(db), DatabaseAccessGate(), appLogService = NoOpAppLog)
            w.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank")))
            val body = "stc prepaid services payment\nAmount: 23.00 SAR\nAt: 10/11/26 22:43"
            fun bind(field: String, text: String) = PatternBinding(field, body.indexOf(text), body.indexOf(text) + text.length)
            val rest = listOf(bind("amount", "23.00 SAR"), bind("occurred_at", "10/11/26 22:43"))
            assertTrue(opensMessage(body, 0))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { w.teach(1, "STC", "STC Bank", PatternOperation.BILL_PAYMENT, body, rest + bind("biller", "stc prepaid services")) }
            }
            assertTrue(rest.none { opensMessage(body, it.start) })
            assertEquals(setOf("amount", "occurred_at"), w.teach(1, "STC", "STC Bank", PatternOperation.BILL_PAYMENT, body, rest).fields.keys)
        }
    }

    @Test fun unselectedNumbersAndDatesAreIgnoredAutomaticallySoOtherMessagesStillMatch() = runBlocking {
        withPatternDatabase { db ->
            val workflow = MessagePatternsWorkflow(MessagePatternStore(db), DatabaseAccessGate(), appLogService = NoOpAppLog)
            workflow.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank")))
            val body = "Payment\nAmount:10SAR\nTo:SHOP\nRef:123456\nBalance:900.25SAR\nOn:15/10/26 12:30"
            assertEquals("MONEY", workflow.candidates(body).single { it.text == "10SAR" }.kind)
            assertEquals("DATE_TIME", workflow.candidates(body).single { it.text == "15/10/26 12:30" }.kind)
            val preview = workflow.teach(1, "STC", "STC Bank", PatternOperation.PURCHASE, body, listOf(
                PatternBinding("amount", body.indexOf("10SAR"), body.indexOf("10SAR") + 5),
                PatternBinding("merchant", body.indexOf("SHOP"), body.indexOf("SHOP") + 4)))
            assertEquals(setOf("amount", "merchant"), preview.fields.keys)
            assertTrue(preview.fixedValues.isEmpty())
            val other = body.replace("123456", "654321").replace("900.25", "12.00").replace("15/10/26 12:30", "16/10/26 09:05")
            assertEquals("10SAR", workflow.tryPreview(preview, "STC Bank", other)!!.getValue("amount").text)
            // Only an explicit selection may land before the first literal anchor; a leading number stays fixed text.
            val leading = "123 Payment\nAmount:10SAR"
            val fixed = workflow.teach(1, "STC", "STC Bank", PatternOperation.PURCHASE, leading,
                listOf(PatternBinding("amount", leading.indexOf("10SAR"), leading.length)))
            assertNull(workflow.tryPreview(fixed, "STC Bank", leading.replace("123", "456")))
        }
    }

    @Test fun editsWaitForAdmittedFinancialWorkAndRetiredInstancesCannotEdit() = runBlocking {
        withPatternDatabase { db ->
            val store = MessagePatternStore(db)
            val gate = DatabaseAccessGate()
            val workflow = MessagePatternsWorkflow(store, gate, Dispatchers.Unconfined, appLogService = NoOpAppLog)
            val release = CompletableDeferred<Unit>()
            val reader = async(start = CoroutineStart.UNDISPATCHED) { gate.withAccess { release.await() } }
            val saving = async(start = CoroutineStart.UNDISPATCHED) {
                workflow.saveBank(0, BankMessagePatterns("STC", listOf("STC Bank")))
            }
            assertFalse(saving.isCompleted)
            assertEquals(0L, store.load().generation)
            release.complete(Unit)
            reader.await()
            assertEquals(1L, saving.await().generation)
            gate.retire()
            assertTrue(runCatching { workflow.saveBank(1, BankMessagePatterns("OTHER", listOf("Other"))) }
                .exceptionOrNull() is DatabaseRestartRequiredException)
            assertEquals(listOf("STC"), store.load().banks.map { it.bankId })
        }
    }
}
