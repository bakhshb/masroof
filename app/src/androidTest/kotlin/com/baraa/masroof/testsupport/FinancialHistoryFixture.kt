package com.baraa.masroof.testsupport

import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.domain.model.*
import com.baraa.masroof.sms.hash.SmsBodyHasher
import java.time.*
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import org.junit.Assert.*

/** Synthetic history through real capture/engine/production workflows. No direct ledger inserts. */
object FinancialHistoryFixture {
    private val bank=Bank.BANK_ALJAZIRA
    suspend fun own(c: AppContainer) {
        c.maintenance.awaitPostStartupBackgroundWorkForTesting()
        c.review.onboardingOwnershipWorkflow.confirmAccountOwned(AccountReference(bank,"3001"))
        c.review.onboardingOwnershipWorkflow.confirmCardOwned(CardReference(bank,"7271"))
    }
    fun message(id: String,amount: Int): RawSms {
        val at=LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val body="شراء عبر الانترنت\nبطاقة ائتمانية: 7271\nمبلغ: SAR $amount\nلدى: SYNTHETIC_SHOP\nفي: $at"
        return RawSms(id,"AlJazira",body,Instant.now(),id,SmsBodyHasher.sha256Hex(body))
    }
    suspend fun seed(c: AppContainer,count: Int) {
        own(c)
        repeat(count) {index->c.sms.processRawSmsUseCase.ingest(message("replica-synthetic-$index",10+index))}
        assertEquals(count,c.storage.financialTransactionRepository.listAll().size)
        assertTrue(c.storage.reviewRepository.listRequired().isEmpty())
    }
    suspend fun fingerprint(c: AppContainer): String {
        val rows=c.storage.financialTransactionRepository.listAll().sortedBy {it.id}
        val accounts=c.storage.accountRegistryRepository.listAll().sortedBy {it.id}
        val cards=c.storage.cardRegistryRepository.listAll().sortedBy {it.id}
        val rawIds=c.storage.rawSmsRepository.listIdsByReceivedAt().toSet()
        (accounts.flatMap {listOfNotNull(it.firstSeenRawSmsId,it.lastSeenRawSmsId)}+
            cards.flatMap {listOfNotNull(it.firstSeenRawSmsId,it.lastSeenRawSmsId)}).forEach {assertTrue("Observation must reference retained evidence",it in rawIds)}
        // Original restore re-discovers in persisted id order. Observation pointers may move;
        // compare every financial/ownership/configuration field, and validate those links above.
        return sha((rows.toString()+accounts.map {it.copy(firstSeenRawSmsId=null,lastSeenRawSmsId=null)}.toString()+
            cards.map {it.copy(firstSeenRawSmsId=null,lastSeenRawSmsId=null)}.toString()).toByteArray())
    }
    fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
}
