package com.baraa.masroof.integration.replica

import androidx.test.platform.app.InstrumentationRegistry

internal fun referenceFixtureText(name: String): String {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    fun locate(path: String): List<String> = assets.list(path).orEmpty().flatMap { child ->
        val full = "$path/$child"
        if (child == "$name.json") listOf(full) else if (!child.endsWith(".json")) locate(full) else emptyList()
    }
    val path = (locate("bank_aljazira") + locate("engine")).single()
    return assets.open(path).bufferedReader().use { it.readText() }
}
