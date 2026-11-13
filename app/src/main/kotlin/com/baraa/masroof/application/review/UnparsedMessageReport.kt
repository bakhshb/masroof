package com.baraa.masroof.application.review

/** The text a user shares to report a message the app could not read (plan 7.4). Nothing is sent by the app itself. */
object UnparsedMessageReport {
    /**
     * Every digit of [text] (Western, Arabic-Indic and any other script) becomes `#`: no amount, date, account,
     * card or phone number leaves the device, while the wording that a new pattern needs stays readable.
     */
    fun mask(text: String): String = buildString(text.length) { text.forEach { append(if (it.isDigit()) '#' else it) } }
}
