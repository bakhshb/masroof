package com.baraa.masroof.application.locale

/** Display policy only: stored financial facts and original SMS remain unchanged. */
object AppDigits {
    const val numberingSystem = "latn"

    fun display(text: String): String {
        if (text.none { it in '\u0660'..'\u0669' || it in '\u06F0'..'\u06F9' }) return text
        return buildString(text.length) {
            text.forEach { character ->
                append(when (character) {
                    in '\u0660'..'\u0669' -> '0' + (character - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (character - '\u06F0')
                    else -> character
                })
            }
        }
    }
}
