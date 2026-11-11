package com.baraa.masroof.application.logging

/** The write side of the app log. Application services take this, so a test passes a no-op instead of null. */
interface AppLog {
    fun log(level: AppLogLevel, category: String, message: String)

    fun info(category: String, message: String) = log(AppLogLevel.INFO, category, message)

    fun warn(category: String, message: String) = log(AppLogLevel.WARN, category, message)

    fun error(category: String, message: String) = log(AppLogLevel.ERROR, category, message)

    /** A caught failure: the step, exception type and a masked message, so a report can be traced without SMS data. */
    fun failure(category: String, action: String, error: Throwable) =
        error(category, "$action failed (${AppLogService.describe(error)})")
}
