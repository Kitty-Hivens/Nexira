package hivens.ui.i18n

import java.lang.reflect.Method
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No catalogue string is set entirely in capitals. A section header typed as
 * "О ПРОГРАММЕ" instead of "О программе" shouts with no design around it to
 * carry the weight, and the other locales of the same key are in sentence case.
 * Acronyms, units and brand spellings are written in capitals by convention, so
 * they are named in [allowedCapsTokens] and do not count.
 *
 * Every string getter of [AppStrings] is read, and every function is called with
 * neutral arguments: an empty string for text, so a placeholder adds no letters
 * and only the literal ones are judged, and a spread of counts for numbers, so
 * each plural form and count branch is reached.
 */
class NoAllCapsStringsTest {

    private val locales = AppLocale.entries.associate { it.name to stringsFor(it) }

    private val allowedCapsTokens = setOf(
        // Hardware, runtime and platform acronyms.
        "RAM", "CPU", "GPU", "JVM", "JDK", "JIT", "JFR", "GC", "G1GC", "ZGC", "CDS", "OOM", "NUMA", "OS",
        // Game and server shorthand.
        "MC", "SC", "MOD", "PBR", "GUI", "UI",
        // Identifiers, protocols and file formats.
        "ID", "UUID", "URL", "HTTP", "SSL", "RSS", "ZIP", "PNG", "GIF", "ASCII",
        "MP3", "FLAC", "OGG", "WAV", "AI",
        // Byte units.
        "KB", "MB", "GB", "TB",
        // Licences and brand spellings.
        "LGPL", "GPL", "LWJGL", "GTNH",
        // Log levels, printed the way the log itself prints them.
        "WARN", "ERROR",
    )

    // A run of cased letters and digits, so "G1GC" and "MP3" stay one token.
    // Kana and kanji have no case and split tokens without being counted.
    private val token = Regex("""[\p{Lu}\p{Ll}\p{Lt}\p{Nd}]+""")

    private val intSamples = listOf(0, 1, 2, 3, 5, 6, 8, 11, 21)

    private fun shouts(value: String): Boolean {
        val letters = token.findAll(value)
            .map { it.value }
            .filterNot { it in allowedCapsTokens }
            .flatMap { it.asSequence() }
            .filter { it.isLetter() }
            .toList()
        return letters.size >= MIN_LETTERS && letters.none { it.isLowerCase() }
    }

    private fun argumentSets(method: Method): List<Array<Any>> {
        val types = method.parameterTypes
        if (types.isEmpty()) return listOf(emptyArray())
        return intSamples.map { n ->
            Array<Any>(types.size) { i ->
                when (types[i]) {
                    String::class.java -> ""
                    Int::class.javaPrimitiveType -> n
                    Long::class.javaPrimitiveType -> n.toLong()
                    Instant::class.java -> Instant.EPOCH
                    else -> fail("${method.name}: no sample argument for ${types[i].name}, add one to argumentSets")
                }
            }
        }.distinctBy { it.toList() }
    }

    private fun valuesOf(strings: AppStrings): List<Pair<String, String>> = buildList {
        for (method in AppStrings::class.java.declaredMethods.filterNot { it.isSynthetic }) {
            when (method.returnType) {
                String::class.java -> for (args in argumentSets(method)) {
                    add(method.name to method.invoke(strings, *args) as String)
                }
                else -> when (val result = if (method.parameterCount == 0) method.invoke(strings) else null) {
                    is List<*> -> result.forEach { add(method.name to it.toString()) }
                    is Map<*, *> -> result.forEach { (key, value) -> add("${method.name}[$key]" to value.toString()) }
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun `no catalogue string is set in capitals`() {
        val violations = buildList {
            for ((name, strings) in locales) {
                for ((key, value) in valuesOf(strings).distinct()) {
                    if (shouts(value)) add("$name.$key: \"$value\"")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Write these in sentence case, or add a real acronym or brand to allowedCapsTokens:\n" +
                violations.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `detector catches shouting and spares acronyms`() {
        for (s in listOf("О ПРОГРАММЕ", "ÜBER", "DESIGN WÄHLEN", "PFLICHT-UPDATE", "SETTINGS: 3", "JVM FLAGS")) {
            assertTrue(shouts(s), "should flag: $s")
        }
        for (s in listOf(
            "О программе", "Über", "RAM", "JVM", "WARN 0  ERROR 0", "UUID: ", "MP3, FLAC, OGG, WAV",
            "HTTP クライアント", "パックの MOD", "G1GC", "0 KB",
        )) {
            assertTrue(!shouts(s), "should not flag: $s")
        }
    }

    private companion object {
        // Three capitals is an acronym more often than a word, so the line sits at four.
        const val MIN_LETTERS = 4
    }
}
