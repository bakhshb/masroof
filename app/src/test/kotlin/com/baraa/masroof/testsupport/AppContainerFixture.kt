package com.baraa.masroof.testsupport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.sms.datasource.SmsDataSource
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** A storage root no other test (or fork) shares. */
internal fun uniqueStorageContext(prefix: String): TestStorageContext =
    TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "$prefix-${UUID.randomUUID()}")

/** Runs [block] on a real AppContainer, then closes it and deletes its database folder. */
internal suspend fun withAppContainer(
    context: Context,
    source: SmsDataSource? = null,
    block: suspend (AppContainer) -> Unit,
) {
    val container = AppContainer(context, smsDataSourceOverride = source)
    try {
        withTimeout(20_000) { block(container) }
    } finally {
        container.close()
        context.getDatabasePath("masroof.db").parentFile?.deleteRecursively()
    }
}
