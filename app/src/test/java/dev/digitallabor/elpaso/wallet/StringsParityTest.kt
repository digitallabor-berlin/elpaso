package dev.digitallabor.elpaso.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Fails the build if `values/`, `values-de/` and `values-fr/` disagree on which
 * `<string name="…">` keys exist. Catches stale translations and missing keys before they
 * ship as blank labels. English is canonical; every other locale must match it exactly.
 */
class StringsParityTest {
    private val keyRegex = Regex("""<string\s+name="([^"]+)"""")

    // From `app/src/test/...` the Gradle working directory is the module root.
    private val resDir = File("src/main/res")

    private fun keysOf(dir: String): Set<String> {
        val file = File(resDir, "$dir/strings.xml")
        check(file.exists()) { "$dir/strings.xml not found at ${file.absolutePath}" }
        return keyRegex.findAll(file.readText()).map { it.groupValues[1] }.toSortedSet()
    }

    private fun assertParity(
        canonical: String,
        other: String,
    ) {
        val a = keysOf(canonical)
        val b = keysOf(other)
        assertEquals(
            "Translation drift between $canonical/ and $other/.\n" +
                "Missing in $other/: ${(a - b).toList()}\n" +
                "Extra in $other/:   ${(b - a).toList()}",
            emptyList<String>() to emptyList<String>(),
            (a - b).toList() to (b - a).toList(),
        )
    }

    @Test
    fun germanMatchesEnglish() = assertParity("values", "values-de")

    @Test
    fun frenchMatchesEnglish() = assertParity("values", "values-fr")
}
