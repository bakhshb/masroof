package com.baraa.masroof.application.notification

import com.baraa.masroof.testsupport.FakeNotificationPreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCenterServiceTest {
    @Test
    fun `build aggregates active notifications and counts unread`() {
        val prefs = FakeNotificationPreferencesRepository()
        val service = NotificationCenterService(prefs)
        val snapshot = NotificationCenterSnapshot(
            smsPermissionGranted = false,
            reviewRequiredCount = 2,
            unregisteredCardCount = 1,
            unregisteredAccountCount = 3,
            excludedForeignCurrencyCount = 4,
            periodLabel = "Aug 2026",
            updateAvailableVersion = "0.3.0",
        )

        val result = service.build(snapshot)

        assertEquals(6, result.items.size)
        assertEquals(6, result.unreadCount)
        assertTrue(result.items.any { it.type == NotificationType.SMS_PERMISSION })
        assertTrue(result.items.any { it.type == NotificationType.REVIEW_REQUIRED })
        assertTrue(result.items.any { it.type == NotificationType.APP_UPDATE_AVAILABLE })
    }

    @Test
    fun `markRead reduces unread count but keeps active notification`() {
        val prefs = FakeNotificationPreferencesRepository()
        val service = NotificationCenterService(prefs)
        val snapshot = NotificationCenterSnapshot(
            smsPermissionGranted = false,
            reviewRequiredCount = 1,
            unregisteredCardCount = 0,
            unregisteredAccountCount = 0,
            excludedForeignCurrencyCount = 0,
            periodLabel = "Aug 2026",
        )

        val initial = service.build(snapshot)
        service.markRead(NotificationCenterService.ID_REVIEW_REQUIRED)
        val afterRead = service.build(snapshot)

        assertEquals(2, initial.unreadCount)
        assertEquals(1, afterRead.unreadCount)
        assertTrue(afterRead.items.single { it.type == NotificationType.REVIEW_REQUIRED }.isRead)
        assertFalse(afterRead.items.single { it.type == NotificationType.SMS_PERMISSION }.isRead)
    }

    @Test
    fun `resolved conditions remove notifications from list`() {
        val prefs = FakeNotificationPreferencesRepository()
        val service = NotificationCenterService(prefs)
        prefs.markRead(NotificationCenterService.ID_SMS_PERMISSION)

        val result = service.build(
            NotificationCenterSnapshot(
                smsPermissionGranted = true,
                reviewRequiredCount = 0,
                unregisteredCardCount = 0,
                unregisteredAccountCount = 0,
                excludedForeignCurrencyCount = 0,
                periodLabel = "Aug 2026",
            ),
        )

        assertTrue(result.items.isEmpty())
        assertEquals(0, result.unreadCount)
        assertTrue(prefs.getReadIds().isEmpty())
    }

    @Test
    fun `foreign currency read state survives browsing another period and a language change`() {
        val prefs = FakeNotificationPreferencesRepository()
        val service = NotificationCenterService(prefs)
        fun snapshot(count: Int, label: String, key: String) = NotificationCenterSnapshot(
            smsPermissionGranted = true,
            reviewRequiredCount = 0,
            unregisteredCardCount = 0,
            unregisteredAccountCount = 0,
            excludedForeignCurrencyCount = count,
            periodLabel = label,
            periodKey = key,
        )

        val august = service.build(snapshot(2, "Aug 2026", "2026-08-01")).items.single()
        service.markRead(august.id)
        service.build(snapshot(0, "Jul 2026", "2026-07-01"))
        val back = service.build(snapshot(2, "أغسطس 2026", "2026-08-01"))

        assertEquals(0, back.unreadCount)
        assertTrue(back.items.single().isRead)
    }

    @Test
    fun `an old label-keyed foreign currency read id is pruned`() {
        val prefs = FakeNotificationPreferencesRepository()
        prefs.markRead("foreign_currency:Aug 2026")
        NotificationCenterService(prefs).build(
            NotificationCenterSnapshot(
                smsPermissionGranted = true,
                reviewRequiredCount = 0,
                unregisteredCardCount = 0,
                unregisteredAccountCount = 0,
                excludedForeignCurrencyCount = 0,
                periodLabel = "Aug 2026",
            ),
        )
        assertTrue(prefs.getReadIds().isEmpty())
    }

    @Test
    fun `only the newest foreign currency period read ids are kept`() {
        val prefs = FakeNotificationPreferencesRepository()
        val starts = (0 until NotificationCenterService.MAX_KEPT_FOREIGN_CURRENCY_PERIODS + 3)
            .map { java.time.LocalDate.parse("2025-01-01").plusMonths(it.toLong()) }
        starts.forEach { prefs.markRead(NotificationCenterService.foreignCurrencyId(it.toString())) }
        NotificationCenterService(prefs).build(
            NotificationCenterSnapshot(
                smsPermissionGranted = true,
                reviewRequiredCount = 0,
                unregisteredCardCount = 0,
                unregisteredAccountCount = 0,
                excludedForeignCurrencyCount = 0,
                periodLabel = "",
            ),
        )
        val expected = starts.takeLast(NotificationCenterService.MAX_KEPT_FOREIGN_CURRENCY_PERIODS)
            .map { NotificationCenterService.foreignCurrencyId(it.toString()) }.toSet()
        assertEquals(expected, prefs.getReadIds())
    }
}
