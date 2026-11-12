package com.baraa.masroof.data.room

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The v19→v20 backfill of `user_decision` from the legacy stores (plan 2.2; rule, mapping and order in
 * docs/ARCHITECTURE.md). Plain SQL, frozen with the migration: it only inserts into `user_decision`. Backfilled rows
 * come first, then the rows already in the table are re-appended in their own order, so a backfilled (older)
 * decision never outranks one the dual write recorded. A trace an existing row already records is skipped.
 */
object UserDecisionBackfill {
    fun run(db: SupportSQLiteDatabase) {
        val existing = db.query("SELECT COALESCE(MAX(sequence), 0) FROM user_decision").use { it.moveToFirst(); it.getLong(0) }
        db.execSQL(INSERT_BACKFILL, arrayOf(existing))
        if (existing == 0L) return
        db.execSQL("INSERT INTO user_decision ($COLUMNS) SELECT $COLUMNS FROM user_decision WHERE sequence <= ? ORDER BY sequence", arrayOf(existing))
        db.execSQL("DELETE FROM user_decision WHERE sequence <= ?", arrayOf(existing))
    }

    private const val COLUMNS = "rawSmsId, pairedRawSmsId, kind, decidedAtEpochMillis, transactionType, sourceContainerId, " +
        "destinationContainerId, occurredAtEpochMillis, occurredAtZone, messageFamily, amountDecimal, amountCurrency, " +
        "merchant, counterparty, bankId"

    private const val OUTCOME_KINDS = "'FINANCIAL_TYPE', 'EXTERNAL_TRANSFER', 'SELF_TRANSFER_PAIR', 'NON_FINANCIAL', " +
        "'RECLASSIFY', 'IGNORE', 'RESTORE', 'RESTORE_REVERTED'"

    /** `?1` is the highest sequence before the backfill: only those rows count as already recorded. */
    private val INSERT_BACKFILL = """
        INSERT INTO user_decision ($COLUMNS)
        WITH
        single AS (
            SELECT rawSmsId, NULL AS pairedRawSmsId,
                CASE
                    WHEN resolutionKind = 'USER_FINANCIAL_TYPE' AND resolvedTransactionId IS NULL THEN 'RESTORE'
                    WHEN resolutionKind = 'USER_FINANCIAL_TYPE' THEN 'FINANCIAL_TYPE'
                    WHEN resolutionKind = 'USER_EXTERNAL_TRANSFER' THEN 'EXTERNAL_TRANSFER'
                    ELSE 'NON_FINANCIAL'
                END AS kind,
                COALESCE(resolvedAtEpochMillis, updatedAtEpochMillis) AS decidedAt, id AS legacyId
            FROM review_item
            WHERE status = 'RESOLVED' AND resolutionKind IN ('USER_FINANCIAL_TYPE', 'USER_EXTERNAL_TRANSFER', 'USER_NON_FINANCIAL')
        ),
        pair_leg AS (
            SELECT r.id, r.rawSmsId, COALESCE(r.resolvedAtEpochMillis, r.updatedAtEpochMillis) AS decidedAt,
                COALESCE(r.resolvedTransactionId,
                    (SELECT l.transactionId FROM financial_transaction_raw_sms_link l WHERE l.rawSmsId = r.rawSmsId)) AS txId,
                COALESCE(
                    (SELECT c.correctedMessageFamily FROM user_correction c
                        WHERE c.targetRawSmsId = r.rawSmsId AND c.correctedMessageFamily IS NOT NULL
                        ORDER BY c.createdAtEpochMillis DESC, c.id DESC LIMIT 1),
                    (SELECT p.messageFamily FROM parsed_event p WHERE p.rawSmsId = r.rawSmsId),
                    '') = 'TRANSFER_OUT' AS outgoing
            FROM review_item r
            WHERE r.status = 'RESOLVED' AND r.resolutionKind = 'USER_SELF_TRANSFER_PAIR'
        ),
        pair AS (
            SELECT o.rawSmsId,
                (SELECT i.rawSmsId FROM pair_leg i WHERE i.txId = o.txId AND i.rawSmsId <> o.rawSmsId
                    ORDER BY i.rawSmsId LIMIT 1) AS pairedRawSmsId,
                'SELF_TRANSFER_PAIR' AS kind, o.decidedAt, o.id AS legacyId
            FROM pair_leg o
            -- One row per pair: the outgoing leg, else the lower raw SMS id.
            WHERE NOT EXISTS (
                SELECT 1 FROM pair_leg i WHERE i.txId = o.txId AND i.rawSmsId <> o.rawSmsId
                    AND (i.outgoing > o.outgoing OR (i.outgoing = o.outgoing AND i.rawSmsId < o.rawSmsId))
            )
        ),
        outcome AS (
            SELECT d.*, 2 AS rank, NULL AS messageFamily, NULL AS amountDecimal, NULL AS amountCurrency,
                NULL AS merchant, NULL AS counterparty, NULL AS bankId
            FROM (SELECT * FROM single UNION ALL SELECT * FROM pair) d
            WHERE NOT EXISTS (
                SELECT 1 FROM user_decision s WHERE s.sequence <= ?1 AND s.kind IN ($OUTCOME_KINDS)
                    AND (s.rawSmsId IN (d.rawSmsId, d.pairedRawSmsId) OR s.pairedRawSmsId IN (d.rawSmsId, d.pairedRawSmsId))
            )
        ),
        correction AS (
            SELECT c.targetRawSmsId AS rawSmsId, NULL AS pairedRawSmsId, 'CORRECTION' AS kind,
                c.createdAtEpochMillis AS decidedAt, c.id AS legacyId, 1 AS rank,
                c.correctedMessageFamily AS messageFamily, c.correctedAmountDecimal AS amountDecimal,
                c.correctedAmountCurrency AS amountCurrency, c.correctedMerchant AS merchant,
                c.correctedCounterparty AS counterparty, NULL AS bankId
            FROM user_correction c
            WHERE NOT EXISTS (
                SELECT 1 FROM user_decision s WHERE s.sequence <= ?1 AND s.kind = 'CORRECTION'
                    AND s.rawSmsId = c.targetRawSmsId AND s.decidedAtEpochMillis = c.createdAtEpochMillis
                    AND s.messageFamily IS c.correctedMessageFamily AND s.amountDecimal IS c.correctedAmountDecimal
                    AND s.amountCurrency IS c.correctedAmountCurrency AND s.merchant IS c.correctedMerchant
                    AND s.counterparty IS c.correctedCounterparty
            )
        ),
        selected AS (
            SELECT id, rawSmsId, updatedAtEpochMillis, rest,
                CASE WHEN instr(rest, char(30)) > 0 THEN substr(rest, 1, instr(rest, char(30)) - 1) ELSE rest END AS bankId
            FROM (
                SELECT id, rawSmsId, updatedAtEpochMillis,
                    substr(reasons, instr(char(30) || reasons, char(30) || 'user_selected_bank:') + 19) AS rest
                FROM review_item
                WHERE instr(char(30) || reasons, char(30) || 'user_selected_bank:') > 0
            )
        ),
        selection AS (
            SELECT rawSmsId, NULL AS pairedRawSmsId, 'BANK_SELECTION' AS kind, updatedAtEpochMillis AS decidedAt,
                id AS legacyId, 0 AS rank, NULL AS messageFamily, NULL AS amountDecimal, NULL AS amountCurrency,
                NULL AS merchant, NULL AS counterparty, bankId
            FROM selected
            -- Exactly one non-blank selection, as ExplicitBankSelection.selectedBankId reads it.
            WHERE instr(rest, char(30) || 'user_selected_bank:') = 0 AND trim(bankId) <> ''
                AND NOT EXISTS (SELECT 1 FROM user_decision s WHERE s.sequence <= ?1 AND s.kind = 'BANK_SELECTION' AND s.rawSmsId = selected.rawSmsId)
        )
        SELECT d.rawSmsId, d.pairedRawSmsId, d.kind, d.decidedAt, f.type, f.sourceContainerId, f.destinationContainerId,
            f.occurredAtEpochMillis, f.occurredAtZone, d.messageFamily, d.amountDecimal, d.amountCurrency,
            d.merchant, d.counterparty, d.bankId
        FROM (
            SELECT * FROM outcome
            UNION ALL SELECT * FROM correction
            UNION ALL SELECT * FROM selection
        ) d
        -- Posting kinds carry the row the SMS is linked to now; with none, the columns stay null.
        LEFT JOIN financial_transaction_raw_sms_link l
            ON l.rawSmsId = d.rawSmsId AND d.kind IN ('FINANCIAL_TYPE', 'EXTERNAL_TRANSFER', 'SELF_TRANSFER_PAIR')
        LEFT JOIN financial_transaction f ON f.id = l.transactionId
        ORDER BY d.decidedAt, d.rank, d.rawSmsId, d.legacyId
    """.trimIndent()
}
