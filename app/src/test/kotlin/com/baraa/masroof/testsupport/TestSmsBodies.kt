package com.baraa.masroof.testsupport

/** AlJazira SMS bodies shared by capture, processing and scan tests. */
internal object TestSmsBodies {
    const val OTP_CODE = "482913"

    val PURCHASE_BODY = """
        شراء عبر الانترنت
        بطاقة: 7271
        لدى: Keeta
        بمبلغ: 51.99 SAR
        في: 14:32 03-08-2026
    """.trimIndent()

    const val OTP_BODY =
        "رمز التحقق لعملية شراء عبر الانترنت: $OTP_CODE\nبمبلغ: 250.00 SAR\nلدى: TEST_STORE\nلا تشارك الرمز مع أحد"

    /** Incoming and outgoing transfer wording in one SMS: competing families stay UNKNOWN. */
    val COLLIDING_TRANSFER_BODY = """
        حوالة واردة
        حوالة صادرة
        مبلغ: SAR 50.00
        في: 2026-08-05 11:00
    """.trimIndent()
}
