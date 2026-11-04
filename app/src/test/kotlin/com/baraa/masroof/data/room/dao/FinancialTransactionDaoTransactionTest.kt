package com.baraa.masroof.data.room.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.FinancialTransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinancialTransactionDaoTransactionTest {
    private lateinit var db: MasroofDatabase
    private val statements = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> statements += sql.trim() }, { it.run() })
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun updatePreservingExchangeRate_readAndWriteShareOneTransaction() = runBlocking {
        val dao = db.financialTransactionDao()
        val row = FinancialTransactionEntity(
            id = "tx-1",
            type = "PURCHASE",
            amountDecimal = "10.00",
            amountCurrency = "USD",
            occurredAtEpochMillis = 1L,
            sourceContainerId = null,
            destinationContainerId = null,
            merchant = "Shop",
            counterparty = null,
            categoryId = null,
        )
        dao.insertTransactionIfAbsent(row)
        statements.clear()

        dao.updatePreservingExchangeRate(row.copy(merchant = "Shop 2"))

        val snapshot = statements.toList()
        val select = snapshot.indexOfFirst { it.startsWith("SELECT") && "financial_transaction" in it }
        val update = snapshot.indexOfFirst { it.startsWith("UPDATE") && "financial_transaction" in it }
        assertTrue("statements: $snapshot", select >= 0 && update > select)
        // The read itself must already run inside the transaction that later writes.
        val begin = snapshot.indexOfFirst { it.startsWith("BEGIN") }
        assertTrue("statements: $snapshot", begin in 0 until select)
        // A concurrent FX write between the read and the write must not be lost:
        // no transaction may end between them.
        val between = snapshot.subList(select + 1, update)
        assertTrue("statements: $snapshot", between.none { it.startsWith("END") || it.startsWith("COMMIT") })
    }
}
