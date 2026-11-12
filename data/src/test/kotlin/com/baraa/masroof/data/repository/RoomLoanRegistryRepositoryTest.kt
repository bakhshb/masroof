package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.ids.RegistryEntityIdFactory
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.LoanReference
import com.baraa.masroof.domain.model.LoanType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomLoanRegistryRepositoryTest {
    private lateinit var db: MasroofDatabase
    private lateinit var repo: RoomLoanRegistryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RoomLoanRegistryRepository.from(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observe_sameLoanTwice_keepsSingleRegistryRow() = runBlocking {
        val reference = LoanReference(Bank.BANK_ALJAZIRA, LoanType.PERSONAL)

        repo.observe(reference, rawSmsId = "sms-1")
        repo.observe(reference, rawSmsId = "sms-2")

        val loans = repo.listAll()
        assertEquals(1, loans.size)
        assertEquals(
            RegistryEntityIdFactory.stableLoanId(Bank.BANK_ALJAZIRA.id, LoanType.PERSONAL.name),
            loans.single().id,
        )
        assertEquals("sms-1", loans.single().firstSeenRawSmsId)
        assertEquals("sms-2", loans.single().lastSeenRawSmsId)
    }
}
