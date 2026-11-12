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
import org.junit.Assert.assertTrue

/**
 * Plan 2.2, run on the JVM and on a device: a real v19 file (the committed v18 fixture upgraded by `MIGRATION_18_19`)
 * holding every legacy decision trace plus rows the v19 dual write already appended, upgraded to v20 by the
 * production builder.
 */
object UserDecisionBackfillContract {
    /** 2026-09-16T09:00:00Z, when the corpus resolved the fixture's two reviews. */
    private const val FIXTURE_RESOLVED_AT = 1_789_549_200_000L

    /** What `MIGRATION_19_20` backfills from the v18 fixture's two resolved reviews. */
    val V18_FIXTURE_DECISIONS: List<UserDecisionEntity> = listOf(
        // Its review names the transaction of another SMS; this SMS is linked to none, so no posting columns.
        UserDecisionEntity(1, "android-sms:all-banks-bank_sab-unknown", null, "FINANCIAL_TYPE", FIXTURE_RESOLVED_AT),
        UserDecisionEntity(2, "android-sms:all-banks-bank_snb-unknown", null, "NON_FINANCIAL", FIXTURE_RESOLVED_AT),
    )

    /** One row per subject the v19 dual write appended before the upgrade, in that order. */
    private val V19_ROWS = listOf(
        UserDecisionEntity(1, "bf-reclass", null, "RECLASSIFY", t(21), "INCOME", null, "account:BANK_SNB:1111", t(-60), "Asia/Riyadh"),
        UserDecisionEntity(2, "bf-corr", null, "CORRECTION", t(22), counterparty = "PERSON"),
        UserDecisionEntity(3, "bf-skew", null, "CORRECTION", t(23), merchant = "NOW"),
        UserDecisionEntity(4, "bf-bank2", null, "BANK_SELECTION", t(24), bankId = "BANK_ALINMA"),
    )

    /** Every row after the upgrade: the backfill in legacy time order, then the v19 rows re-appended after it. */
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
            // Stamped later than the v19 row of the same subject by a clock that ran ahead; still ordered before it.
            UserDecisionEntity(0, "bf-skew", null, "CORRECTION", t(99), merchant = "AHEAD"),
        ) + V19_ROWS
        ).mapIndexed { index, row -> row.copy(sequence = V19_ROWS.size + 1L + index) }

    /** A v19 file: the v18 fixture through the real `MIGRATION_18_19`, then every legacy trace and the v19 rows. */
    fun v19File(context: Context, name: String, fixture: () -> InputStream): File {
        val file = MigrationOpenPathContract.copyFixture(context, name, fixture)
        val callback = object : SupportSQLiteOpenHelper.Callback(19) {
            override fun onCreate(db: SupportSQLiteDatabase) = error("the fixture exists")

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                check(oldVersion == 18)
                // The step stamps the v19 hash, as Room would after it.
                MasroofDatabase.MIGRATION_18_19.migrate(db)
            }
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
        assertEquals(before - "user_decision", after - "user_decision")
    }

    /** No trace the v19 dual write recorded is backfilled again, and every v19 row outranks every backfilled one. */
    fun dualWriteRowsStayLatestAndAreNotDuplicated(context: Context, fixture: () -> InputStream) {
        val decisions = upgrade(context, fixture).second.getValue("user_decision").map { it.split("|") }
        val v19 = decisions.takeLast(V19_ROWS.size)
        assertEquals(V19_ROWS.map { dump(it).split("|").drop(1) }, v19.map { it.drop(1) })
        assertTrue(decisions.dropLast(V19_ROWS.size).all { it[0].toLong() < v19.minOf { row -> row[0].toLong() } })
        // The latest row of each v19 subject is its v19 row.
        for (row in v19) assertEquals(row, decisions.last { it[1] == row[1] })
        // A reclassification's review (stamped 1 ms after its row) and a repeated bank are not backfilled again.
        assertEquals(1, decisions.count { it[1] == "bf-reclass" })
        assertEquals(1, decisions.count { it[1] == "bf-bank2" })
        assertEquals(listOf("SHOP", "SHOP 2", "null"), decisions.filter { it[1] == "bf-corr" }.map { it[13] })
    }

    /** A v19 file with an unknown identity hash fails the check in `MIGRATION_19_20` and stays untouched. */
    fun unknownV19IdentityHashIsRejectedUntouched(context: Context, fixture: () -> InputStream) =
        MigrationOpenPathContract.editedFixtureIsRejectedUntouched(context, fixture, MigrationOpenPathContract.UNKNOWN_HASH,
            copy = { v19File(context, it, fixture) })

    private fun upgrade(context: Context, fixture: () -> InputStream): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val name = "backfill-upgrade.db"
        val file = v19File(context, name, fixture)
        val before = MigrationOpenPathContract.readOnly(file) { assertEquals(19, it.version); MigrationOpenPathContract.rows(it) }
        MigrationOpenPathContract.openOnce(context, MasroofDatabase::class.java, name)
        val after = MigrationOpenPathContract.readOnly(file) { assertEquals(MasroofDatabase.VERSION, it.version); MigrationOpenPathContract.rows(it) }
        context.deleteDatabase(name)
        return before to after
    }

    /** [row] as [MigrationOpenPathContract.rows] prints it. */
    fun dump(row: UserDecisionEntity): String = with(row) {
        listOf(sequence, rawSmsId, pairedRawSmsId, kind, decidedAtEpochMillis, transactionType, sourceContainerId,
            destinationContainerId, occurredAtEpochMillis, occurredAtZone, messageFamily, amountDecimal, amountCurrency,
            merchant, counterparty, bankId).joinToString("|") { it?.toString() ?: "null" }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        val ids = listOf("bf-type", "bf-moved", "bf-gone", "bf-restore", "bf-ext", "bf-pair-a-in", "bf-pair-b-out", "bf-nonfin",
            "bf-corr", "bf-bank", "bf-bank-auto", "bf-auto", "bf-plain", "bf-required", "bf-reclass", "bf-skew", "bf-bank2")
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
        db.posted("transaction:bf-reclass", "INCOME", t(-60), null, "account:BANK_SNB:1111", "Asia/Riyadh", "bf-reclass")

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
        // v19 decisions: each wrote its legacy trace and its user_decision row together.
        db.review("bf-reclass", "USER_FINANCIAL_TYPE", t(21) + 1, "transaction:bf-reclass")
        db.review("bf-bank2", null, t(24), null, "ambiguous_bank_route${SEP}user_selected_bank:BANK_ALINMA")

        listOf(
            arrayOf<Any?>("c1", "bf-corr", "PURCHASE", "12.50", "SAR", "SHOP", null, t(8)),
            arrayOf<Any?>("c2", "bf-corr", null, null, null, "SHOP 2", null, t(9)),
            arrayOf<Any?>("c3", "bf-corr", null, null, null, null, "PERSON", t(22)),
            arrayOf<Any?>("c4", "bf-skew", null, null, null, "AHEAD", null, t(99)),
            arrayOf<Any?>("c5", "bf-skew", null, null, null, "NOW", null, t(23)),
        ).forEach { db.execSQL("INSERT INTO user_correction VALUES (?, ?, ?, ?, ?, ?, ?, ?)", it) }

        for (row in V19_ROWS) {
            db.execSQL(
                "INSERT INTO user_decision VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                dump(row).split("|").drop(1).map { if (it == "null") null else it }.toTypedArray(),
            )
        }
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
