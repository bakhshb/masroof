package com.baraa.masroof.testsupport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baraa.masroof.application.AppContainer
import com.baraa.masroof.sms.datasource.SmsDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** A storage root no other test (or fork) shares. */
internal fun uniqueStorageContext(prefix: String): TestStorageContext =
    TestStorageContext(ApplicationProvider.getApplicationContext<Context>(), "$prefix-${UUID.randomUUID()}")

/**
 * Runs [block] on a real AppContainer, then closes it and deletes its database folder.
 * Takes a [TestStorageContext] so the folder it deletes is never the shared app database folder.
 */
internal suspend fun withAppContainer(
    context: TestStorageContext,
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

/**
 * Runs [block] off the Robolectric main thread while this thread keeps the main looper running.
 * For journeys that wait on work posted to the main thread, as a device test does from its own thread.
 */
internal fun <T> runOffMainThread(block: suspend () -> T): T {
    val result = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { block() }
    while (!result.isCompleted) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
    return kotlinx.coroutines.runBlocking { result.await() }
}
