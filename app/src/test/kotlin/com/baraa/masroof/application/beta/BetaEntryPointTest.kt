package com.baraa.masroof.application.beta

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaEntryPointTest {
    @Test
    fun betaActivity_isExplicitAndNotADeepLink() {
        val xml = File("src/main/AndroidManifest.xml").readText()
        val nameAt = xml.indexOf("android:name=\".presentation.beta.BetaActivity\"")
        assertTrue(nameAt > 0)
        val start = xml.lastIndexOf("<activity", nameAt)
        val end = xml.indexOf(">", nameAt)
        val tag = xml.substring(start, end + 1)
        assertTrue(tag.endsWith("/>"))
        assertTrue(tag.contains("android:exported=\"false\""))
        assertFalse(tag.contains("intent-filter"))
        assertEquals(1, Regex("BetaActivity").findAll(xml).count())
        assertFalse(xml.contains("BROWSABLE"))
    }

    @Test
    fun onlyDeveloperSettings_canConstructTheBetaIntent() {
        val root = File("src/main")
        val hits = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { file ->
                val text = file.readText()
                text.contains("BetaActivity") || text.contains("BetaEntry")
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        assertEquals(
            setOf(
                "AndroidManifest.xml",
                "kotlin/com/baraa/masroof/presentation/beta/BetaActivity.kt",
                "kotlin/com/baraa/masroof/presentation/beta/BetaEntry.kt",
                "kotlin/com/baraa/masroof/presentation/settings/SettingsScreen.kt",
            ),
            hits,
        )
        val settings = File("src/main/kotlin/com/baraa/masroof/presentation/settings/SettingsScreen.kt").readText()
        assertEquals(1, settings.split("BetaEntry.open").size - 1)
        val developer = File(
            "src/main/kotlin/com/baraa/masroof/presentation/settings/SettingsDeveloperScreen.kt",
        ).readText()
        assertTrue(developer.contains("R.string.beta_entry_title"))
        assertFalse(developer.contains("BetaEntry"))
        assertFalse(developer.contains("BetaActivity"))
        assertFalse(
            File("src/main/kotlin/com/baraa/masroof/presentation/navigation/AppNavigation.kt")
                .readText()
                .contains("Beta"),
        )
    }

    @Test
    fun betaContainer_doesNotUseProductionRepositories() {
        val sources = File("src/main/kotlin/com/baraa/masroof/application/beta")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .toList()
        assertTrue(sources.isNotEmpty())
        val forbidden = listOf(
            "import com.baraa.masroof.application.AppContainer",
            "import com.baraa.masroof.domain.repository",
            "import com.baraa.masroof.presentation.",
            "import com.baraa.masroof.MasroofApplication",
        )
        sources.forEach { file ->
            val imports = file.readLines().filter { it.trimStart().startsWith("import ") }
            forbidden.forEach { banned ->
                assertFalse("${file.name} imports $banned", imports.any { it.contains(banned) })
            }
        }
        val presentation = File("src/main/kotlin/com/baraa/masroof/presentation/beta")
            .walkTopDown()
            .filter { it.extension == "kt" }
        presentation.forEach { file ->
            val imports = file.readLines().filter { it.trimStart().startsWith("import ") }
            listOf(
                "import com.baraa.masroof.application.AppContainer",
                "import com.baraa.masroof.domain.repository",
                "import com.baraa.masroof.data.",
                "import com.baraa.masroof.MasroofApplication",
            ).forEach { banned ->
                assertFalse("${file.name} imports $banned", imports.any { it.contains(banned) })
            }
        }
    }

    @Test
    fun productionPreferenceLiterals_areAllGuarded() {
        val pattern = Regex("""const val PREFS_NAME(?:\s*:\s*String)?\s*=\s*"([^"]+)"""")
        val literals = File("src/main/kotlin/com/baraa/masroof").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/application/beta/") }
            .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
        assertEquals(BetaEnvironment.productionPreferenceNames, literals)
        assertFalse(BetaEnvironment.PREFS_NAME in literals)
    }
}
