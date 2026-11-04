package com.baraa.masroof.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.domain.model.AccountReference
import com.baraa.masroof.domain.model.Bank
import com.baraa.masroof.domain.model.OwnershipStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Covers every branch of the exact-then-last-4 registry lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomAccountRegistryRepositoryTest {
    private val bank = Bank.BANK_ALJAZIRA
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        MasroofDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val repository = RoomAccountRegistryRepository.from(database)

    @After fun tearDown() = database.close()

    private fun ref(masked: String) = AccountReference(bank, masked)

    private suspend fun seed(masked: String, status: OwnershipStatus) {
        repository.observe(ref(masked), "sms-$masked")
        if (status != OwnershipStatus.UNKNOWN) repository.setOwnership(ref(masked), status)
    }

    @Test fun knownExactRow_winsOverOwnedSuffixMatch() = runBlocking {
        seed("3001", OwnershipStatus.EXTERNAL)
        seed("SA001234563001", OwnershipStatus.OWNED)
        assertEquals("3001", repository.get(ref("3001"))?.maskedNumber)
        assertEquals(OwnershipStatus.EXTERNAL, repository.resolve(ref("3001")))
    }

    @Test fun unknownExactRow_yieldsToSingleOwnedSuffixMatch() = runBlocking {
        seed("3001", OwnershipStatus.UNKNOWN)
        seed("SA001234563001", OwnershipStatus.OWNED)
        seed("SA009999993001", OwnershipStatus.EXTERNAL)
        assertEquals(OwnershipStatus.OWNED, repository.resolve(ref("3001")))
    }

    @Test fun unknownExactRow_staysWhenOwnedSuffixMatchIsMissingOrAmbiguous() = runBlocking {
        seed("3001", OwnershipStatus.UNKNOWN)
        seed("SA009999993001", OwnershipStatus.EXTERNAL)
        assertEquals("3001", repository.get(ref("3001"))?.maskedNumber)
        seed("SA001234563001", OwnershipStatus.OWNED)
        seed("SA005555553001", OwnershipStatus.OWNED)
        assertEquals(OwnershipStatus.UNKNOWN, repository.resolve(ref("3001")))
    }

    @Test fun missingExactRow_usesOnlyAUniqueSuffixMatch() = runBlocking {
        seed("SA009999993001", OwnershipStatus.EXTERNAL)
        repository.observe(AccountReference(Bank("D360"), "3001"), "sms-other-bank")
        assertEquals(OwnershipStatus.EXTERNAL, repository.resolve(ref("3001")))
        seed("SA001234563001", OwnershipStatus.UNKNOWN)
        assertNull(repository.get(ref("3001")))
    }

    @Test fun shortReference_usesExactRowOnly() = runBlocking {
        seed("SA0012300001", OwnershipStatus.OWNED)
        assertNull(repository.get(ref("001")))
    }
}
