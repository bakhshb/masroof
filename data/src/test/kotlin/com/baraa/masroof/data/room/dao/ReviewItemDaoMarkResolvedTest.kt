package com.baraa.masroof.data.room.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.data.room.MasroofDatabase
import com.baraa.masroof.data.room.entity.RawSmsEntity
import com.baraa.masroof.data.room.entity.ReviewItemEntity
import com.baraa.masroof.domain.model.ReviewKind
import com.baraa.masroof.domain.model.ReviewResolutionKind
import com.baraa.masroof.domain.model.ReviewStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewItemDaoMarkResolvedTest {
    private lateinit var db: MasroofDatabase
    private lateinit var dao: ReviewItemDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MasroofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.reviewItemDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun markResolvedAtomic_userNonFinancialToUserFinancialType_updatesResolution() = runBlocking {
        val now = Instant.parse("2026-08-01T12:00:00Z")
        db.rawSmsDao().insertIfAbsent(
            RawSmsEntity(
                id = "sms-1",
                sender = "AlJazira",
                body = "purchase 10 SAR",
                receivedAtEpochMillis = now.toEpochMilli(),
                deviceMessageId = "sms-1",
                bodyHash = "hash",
                dedupeKey = "AlJazira:${now.toEpochMilli()}:hash",
            ),
        )
        val entity = ReviewItemEntity(
            id = "review-1",
            rawSmsId = "sms-1",
            kind = ReviewKind.NEEDS_REVIEW.name,
            status = ReviewStatus.RESOLVED.name,
            reasons = "user_ignored_transaction",
            createdAtEpochMillis = now.toEpochMilli(),
            updatedAtEpochMillis = now.toEpochMilli(),
            resolvedAtEpochMillis = now.toEpochMilli(),
            resolutionKind = ReviewResolutionKind.USER_NON_FINANCIAL.name,
            resolvedTransactionId = null,
        )
        dao.insertIfAbsent(entity)

        val updated = dao.markResolvedAtomic(
            id = entity.id,
            status = ReviewStatus.RESOLVED.name,
            resolutionKind = ReviewResolutionKind.USER_FINANCIAL_TYPE.name,
            resolvedAtEpochMillis = now.toEpochMilli(),
            resolvedTransactionId = null,
            updatedAtEpochMillis = now.toEpochMilli(),
        )

        assertEquals(ReviewResolutionKind.USER_FINANCIAL_TYPE.name, updated?.resolutionKind)
        assertEquals(
            ReviewResolutionKind.USER_FINANCIAL_TYPE.name,
            dao.getById(entity.id)?.resolutionKind,
        )
    }
}
