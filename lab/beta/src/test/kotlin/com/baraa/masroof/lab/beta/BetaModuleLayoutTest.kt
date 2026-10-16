package com.baraa.masroof.lab.beta

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaModuleLayoutTest {
    @Test
    fun betaActivity_isExplicitAndNotADeepLink() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        val nameAt = xml.indexOf("android:name=\".presentation.BetaActivity\"")
        assertTrue(nameAt > 0)
        val start = xml.lastIndexOf("<activity", nameAt)
        val end = xml.indexOf(">", nameAt)
        val tag = xml.substring(start, end + 1)
        assertTrue(tag.contains("android:exported=\"false\""))
        assertFalse(tag.contains("intent-filter"))
        assertFalse(xml.contains("BROWSABLE"))
    }

    @Test
    fun betaSources_doNotDependOnAppOrProductionPackages() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val banned = listOf(
            "import com.baraa.masroof.application.",
            "import com.baraa.masroof.data.",
            "import com.baraa.masroof.domain.",
            "import com.baraa.masroof.presentation.",
            "import com.baraa.masroof.sms.",
            "import com.baraa.masroof.MasroofApplication",
            "import com.baraa.masroof.MainActivity",
            "MasroofDatabase",
        )
        sources.forEach { file ->
            val text = file.readText()
            banned.forEach { phrase ->
                assertFalse("${file.name} contains $phrase", phrase in text)
            }
        }
        assertFalse(File("build.gradle.kts").readText().contains("project(\":app\")"))
    }

    @Test
    fun databaseIsIndependentBetaSchema() {
        val database = File("src/main/kotlin/com/baraa/masroof/lab/beta/data/BetaDatabase.kt").readText()
        assertTrue(database.contains("version = BetaDatabase.VERSION"))
        assertTrue(database.contains("const val VERSION: Int = 5"))
        assertTrue(database.contains("BetaEnvironment.DATABASE_NAME"))
        assertFalse(database.contains("MasroofDatabase"))
        assertEquals("masroof_beta.db", BetaEnvironment.DATABASE_NAME)
    }
}
