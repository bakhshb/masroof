package com.baraa.masroof.application.dashboard

import com.baraa.masroof.domain.model.FinancialTransaction
import com.baraa.masroof.parsing.repository.ParsedEventRecord

/** Parsed records a transaction is linked to, in link order; missing ids are skipped. */
internal fun linkedRecords(tx: FinancialTransaction, parsedRecordsById: Map<String, ParsedEventRecord>): List<ParsedEventRecord> =
    tx.linkedParsedEventIds.mapNotNull { parsedRecordsById[it] }

internal fun linkedRecords(tx: FinancialTransaction, context: AccountFlowClassificationContext): List<ParsedEventRecord> =
    linkedRecords(tx, context.parsedRecordsById)
