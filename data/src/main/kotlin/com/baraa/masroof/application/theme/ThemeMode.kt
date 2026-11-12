package com.baraa.masroof.application.theme

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
    ;

    companion object {
        val DEFAULT: ThemeMode = SYSTEM

        fun fromStorage(value: String?): ThemeMode =
            when (value) {
                LIGHT.name -> LIGHT
                DARK.name -> DARK
                SYSTEM.name -> SYSTEM
                else -> DEFAULT
            }
    }
}
