package hivens.ui.i18n

import hivens.i18n.Message
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * No catalogue string is set entirely in capitals. A section header typed as
 * "О ПРОГРАММЕ" instead of "О программе" shouts with no design around it to
 * carry the weight, and the other locales of the same key are in sentence case.
 * Acronyms, units and brand spellings are written in capitals by convention, so
 * they are named in [allowedCapsTokens] and do not count.
 *
 * Every message of every shipped table is read in every form it can take, each
 * plural and select branch on its own, with its arguments left empty, so a
 * placeholder adds no letters and only the literal ones are judged.
 */
class NoAllCapsStringsTest {

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

    private fun shouts(value: String): Boolean {
        val letters = token.findAll(value)
            .map { it.value }
            .filterNot { it in allowedCapsTokens }
            .flatMap { it.asSequence() }
            .filter { it.isLetter() }
            .toList()
        return letters.size >= MIN_LETTERS && letters.none { it.isLowerCase() }
    }

    @Test
    fun `no catalogue string is set in capitals`() {
        val violations = buildList {
            for ((tag, table) in NexiraStrings.tables()) {
                for ((key, text) in table) {
                    val forms = runCatching { Message.parse(text).variants() }.getOrDefault(listOf(text))
                    forms.filter(::shouts).forEach { add("$tag.$key: \"$it\"") }
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
