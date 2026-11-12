package com.baraa.masroof.data.room

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.baraa.masroof.data.room.entity.UserDecisionEntity
import java.io.File
import java.io.InputStream
import java.time.Instant
import org.junit.Assert.assertEquals

/**
 * Plan 2.2, run on the JVM and on a device: the committed v18 fixture with every legacy decision trace added, upgraded
 * by the production builder (`MIGRATION_18_22` creates `user_decision` and backfills it).
 */
object UserDecisionBackfillContract {
    /** 2026-09-16T09:00:00Z, when the corpus resolved the fixture's two reviews. */
    private const val FIXTURE_RESOLVED_AT = 1_789_549_200_000L

    /** What `MIGRATION_18_22` backfills from the v18 fixture's two resolved reviews. */
    val V18_FIXTURE_DECISIONS: List<UserDecisionEntity> = listOf(
        // Its review names the transaction of another SMS; this SMS is linked to none, so no posting columns.
        UserDecisionEntity(1, "android-sms:all-banks-bank_sab-unknown", null, "FINANCIAL_TYPE", FIXTURE_RESOLVED_AT),
        UserDecisionEntity(2, "android-sms:all-banks-bank_snb-unknown", null, "NON_FINANCIAL", FIXTURE_RESOLVED_AT),
    )

    /** Every row after the upgrade: the fixture's decisions, then the added traces in legacy time order. */
    val EXPECTED: List<UserDecisionEntity> = (
        V18_FIXTURE_DECISIONS + listOf(
            UserDecisionEntity(0, "bf-type", null, "FINANCIAL_TYPE", t(1), "EXPENSE", "account:BANK_SNB:1111", null, t(-60), "Asia/Riyadh"),
            // The resolved transaction was recreated: the row the SMS is linked to now.
            UserDecisionEntity(0, "bf-moved", null, "FINANCIAL_TYPE", t(2), "INCOME", null, "account:BANK_SNB:1111", t(-50), "Asia/Riyadh"),
            // The resolved transaction is gone and the SMS is linked to nothing.
            UserDecisionEntity(0, "bf-gone", null, "FINANCIAL_TYPE", t(3)),
            // A restore that stayed financial and posted nothing.
            UserDecisionEntity(0, "bf-restore", null, "RESTORE", t(4)),
            UserDecisionEntity(0, "bf-ext", null, "EXTERNAL_TRANSFER", t(5), "EXTERNAL_TRANSFER_OUT", "account:BANK_SNB:1111", null, t(-40), null),
            // The outgoing leg is the subject although its id sorts after the incoming one.
            UserDecisionEntity(0, "bf-pair-b-out", "bf-pair-a-in", "SELF_TRANSFER_PAIR", t(6), "SELF_TRANSFER",
                "account:BANK_SNB:1111", "account:BANK_SNB:2222", t(-30), "Asia/Riyadh"),
            UserDecisionEntity(0, "bf-nonfin", null, "NON_FINANCIAL", t(7)),
            UserDecisionEntity(0, "bf-corr", null, "CORRECTION", t(8), messageFamily = "PURCHASE", amountDecimal = "12.50",
                amountCurrency = "SAR", merchant = "SHOP"),
            UserDecisionEntity(0, "bf-corr", null, "CORRECTION", t(9), merchant = "SHOP 2"),
            UserDecisionEntity(0, "bf-bank", null, "BANK_SELECTION", t(10), bankId = "BANK_SNB"),
            UserDecisionEntity(0, "bf-bank-auto", null, "BANK_SELECTION", t(11), bankId = "BANK_SAB"),
            UserDecisionEntity(0, "bf-skew", null, "CORRECTION", t(99), merchant = "AHEAD"),
        )
        ).mapIndexed { index, row -> row.copy(sequence = 1L + index) }

    /** A v18 file: the committed fixture plus every legacy decision trace, written at v18 (no migration runs). */
    fun legacyTracesFile(context: Context, name: String, fixture: () -> InputStream): File {
        val file = MigrationOpenPathContract.copyFixture(context, name, fixture)
        val callback = object : SupportSQLiteOpenHelper.Callback(MasroofDatabase.BASELINE_VERSION) {
            override fun onCreate(db: SupportSQLiteDatabase) = error("the fixture exists")

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("the fixture is v18")
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build(),
        )
        try {
            helper.writableDatabase.apply {
                beginTransaction()
                try {
                    seed(this)
                    setTransactionSuccessful()
                } finally {
                    endTransaction()
                }
            }
        } finally {
            helper.close()
        }
        return file
    }

    /** The upgrade backfills exactly [EXPECTED] and leaves every other table unchanged. */
    fun everyKindIsBackfilled(context: Context, fixture: () -> InputStream) {
        val (before, after) = upgrade(context, fixture)
        assertEquals(EXPECTED.map(::dump), after.getValue("user_decision"))
        // The same step adds the empty pattern table.
        assertEquals(emptyList<String>(), after.getValue("pattern_configuration"))
        assertEquals(before - "user_decision", after - "user_decision" - "pattern_configuration")
    }

    private fun upgrade(context: Context, fixture: () -> InputStream): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val name = "backfill-upgrade.db"
        val file = legacyTracesFile(context, name, fixture)
        val before = MigrationOpenPathContract.readOnly(file) { assertEquals(18, it.version); MigrationOpenPathContract.rows(it) }
        MigrationOpenPathContract.openOnce(context, MasroofDatabase::class.java, name)
        val after = MigrationOpenPathContract.readOnly(file) { assertEquals(MasroofDatabase.VERSION, it.version); MigrationOpenPathContract.rows(it) }
        context.deleteDatabase(name)
        return before to after
    }

    /** [row] as [MigrationOpenPathContract.rows] prints it. */
    fun dump(row: UserDecisionEntity): String = with(row) {
        listOf(sequence, rawSmsId, pairedRawSmsId, kind, decidedAtEpochMillis, transactionType, sourceContainerId,
            destinationContainerId, occurredAtEpochMillis, occurredAtZone, messageFamily, amountDecimal, amountCurrency,
            merchant, counterparty, bankId, subjectKey, answer, payload).joinToString("|") { it?.toString() ?: "null" }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        val ids = listOf("bf-type", "bf-moved", "bf-gone", "bf-restore", "bf-ext", "bf-pair-a-in", "bf-pair-b-out", "bf-nonfin",
            "bf-corr", "bf-bank", "bf-bank-auto", "bf-auto", "bf-plain", "bf-required", "bf-skew")
        // Synthetic evidence only: no SMS body.
        ids.forEachIndexed { index, id ->
            db.execSQL("INSERT INTO raw_sms VALUES (?, 'TEST', 'synthetic', ?, NULL, ?, ?)", arrayOf<Any?>(id, t(-100 + index), "hash-$id", id))
        }
        db.execSQL(PARSED, arrayOf<Any?>("parsed:bf-pair-a-in", "bf-pair-a-in", "TRANSFER_IN"))
        db.execSQL(PARSED, arrayOf<Any?>("parsed:bf-pair-b-out", "bf-pair-b-out", "TRANSFER_OUT"))

        db.posted("transaction:bf-type", "EXPENSE", t(-60), "account:BANK_SNB:1111", null, "Asia/Riyadh", "bf-type")
        db.posted("transaction:bf-moved-new", "INCOME", t(-50), null, "account:BANK_SNB:1111", "Asia/Riyadh", "bf-moved")
        db.posted("transaction:bf-ext", "EXTERNAL_TRANSFER_OUT", t(-40), "account:BANK_SNB:1111", null, null, "bf-ext")
        db.posted("transaction:bf-pair", "SELF_TRANSFER", t(-30), "account:BANK_SNB:1111", "account:BANK_SNB:2222", "Asia/Riyadh",
            "bf-pair-a-in", "bf-pair-b-out")
        db.posted("transaction:bf-auto", "EXPENSE", t(-20), "account:BANK_SNB:1111", null, "Asia/Riyadh", "bf-auto")
        db.posted("transaction:bf-plain", "EXPENSE", t(-10), "account:BANK_SNB:1111", null, "Asia/Riyadh", "bf-plain")

        db.review("bf-type", "USER_FINANCIAL_TYPE", t(1), "transaction:bf-type")
        db.review("bf-moved", "USER_FINANCIAL_TYPE", t(2), "transaction:bf-moved-old")
        db.review("bf-gone", "USER_FINANCIAL_TYPE", t(3), "transaction:bf-gone")
        db.review("bf-restore", "USER_FINANCIAL_TYPE", t(4), null)
        db.review("bf-ext", "USER_EXTERNAL_TRANSFER", t(5), "transaction:bf-ext")
        db.review("bf-pair-a-in", "USER_SELF_TRANSFER_PAIR", t(6), "transaction:bf-pair")
        db.review("bf-pair-b-out", "USER_SELF_TRANSFER_PAIR", t(6), "transaction:bf-pair")
        db.review("bf-nonfin", "USER_NON_FINANCIAL", t(7), null)
        db.review("bf-corr", "USER_CORRECTION", t(9), null)
        db.review("bf-bank", null, t(10), null, "ambiguous_bank_route${SEP}user_selected_bank:BANK_SNB")
        db.review("bf-bank-auto", "AUTO_NO_LONGER_REQUIRED", t(11), null, "suspected_bank_sender${SEP}user_selected_bank:BANK_SAB")
        db.review("bf-auto", "AUTO_NO_LONGER_REQUIRED", t(12), "transaction:bf-auto")
        db.review("bf-required", null, t(13), null)

        listOf(
            arrayOf<Any?>("c1", "bf-corr", "PURCHASE", "12.50", "SAR", "SHOP", null, t(8)),
            arrayOf<Any?>("c2", "bf-corr", null, null, null, "SHOP 2", null, t(9)),
            arrayOf<Any?>("c4", "bf-skew", null, null, null, "AHEAD", null, t(99)),
        ).forEach { db.execSQL("INSERT INTO user_correction VALUES (?, ?, ?, ?, ?, ?, ?, ?)", it) }
    }

    private fun SupportSQLiteDatabase.posted(
        id: String, type: String, at: Long, source: String?, destination: String?, zone: String?, vararg rawSmsIds: String,
    ) {
        execSQL("INSERT INTO financial_transaction VALUES (?, ?, '10.00', 'SAR', ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?)",
            arrayOf<Any?>(id, type, at, source, destination, zone))
        rawSmsIds.forEach { execSQL("INSERT INTO financial_transaction_raw_sms_link VALUES (?, ?)", arrayOf<Any?>(it, id)) }
    }

    private fun SupportSQLiteDatabase.review(
        rawSmsId: String, resolution: String?, at: Long, transactionId: String?, reasons: String = "unknown_message_family",
    ) = execSQL("INSERT INTO review_item VALUES (?, ?, 'NEEDS_REVIEW', ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(
        "review:$rawSmsId", rawSmsId, if (resolution == null) "REQUIRED" else "RESOLVED", reasons, t(0), at,
        if (resolution == null) null else at, resolution, transactionId,
    ))

    private const val PARSED = "INSERT INTO parsed_event (id, rawSmsId, bankId, messageFamily, confidenceScore, confidenceReasons, " +
        "parseStatus) VALUES (?, ?, 'BANK_SNB', ?, 1.0, '', 'SUCCESS')"

    private const val SEP = '\u001e'


    /** Minutes after a fixed instant later than every fixture row. */
    private fun t(minutes: Int): Long = Instant.parse("2026-10-01T09:00:00Z").plusSeconds(minutes * 60L).toEpochMilli()
}
