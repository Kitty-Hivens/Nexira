package hivens.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The host joins the themes module and the language module through their keys and their APIs, and
 * names nothing of how either is made: no class of theirs, no file in their packages, and no engine
 * of the launcher's own words of its own. The one place either module is named is where the build
 * lists the modules compiled into it, by its CompiledModule, the way every compiled module is listed
 * there, and the one engine of the launcher's own words the host makes is the recovery surface's
 * floor, which has to speak when every module failed.
 *
 * Read as text, since an import the compiler drops leaves no trace in what it builds. The text is read
 * the way the compiler would join it: names in backticks without them, dots spaced or not, and string
 * literals put together with a plus as one string, so a name spelled out for reflection is seen too.
 */
class HostBoundaryTest {

    private val roots = listOf("commonMain", "jvmSharedMain", "desktopMain", "desktopPuppetMain", "androidMain").map { File("src/$it/kotlin") }

    /** Where the build lists the modules compiled into it. */
    private val listing = "hivens/ui/widgets/modules/CompiledModules.kt"

    /** What the listing may name of the two modules: each one's CompiledModule, and nothing else of either. */
    private val registrations = setOf("import hivens.themes.ThemesModule", "import hivens.i18n.I18nModule")

    /** The recovery surface, and the one engine of the launcher's own words it may make. */
    private val floor = "hivens/ui/RecoverySurface.kt" to "NexiraStrings.translator(Locale.getDefault().language)"

    /** Every line of [text] that names what the host may not, with why, as `path:line: why: text`. */
    private fun findings(path: String, text: String): List<String> = text.lines().mapIndexedNotNull { index, raw ->
        val line = joined(raw)
        val why = when {
            PACKAGE.containsMatchIn(line) -> "a file in a module's own package"
            IMPLEMENTATION.containsMatchIn(line) && !(path == listing && line.trim() in registrations) -> "a name of a module that is not in its API"
            OWN_ENGINE.containsMatchIn(line) && !(path == floor.first && floor.second in line) -> "an engine of the launcher's own words"
            OWN_WORDS_SCOPE.containsMatchIn(line) -> "the launcher's own words as a scope, where an engine is made unseen"
            else -> return@mapIndexedNotNull null
        }
        "$path:${index + 1}: $why: ${raw.trim()}"
    }

    /** [raw] as the compiler joins it: no backticks, no space around a dot, adjacent string literals as one. */
    private fun joined(raw: String): String = raw
        .replace("`", "")
        .replace(Regex("""\s*\.\s*"""), ".")
        .replace(Regex(""""\s*\+\s*""""), "")

    private fun sources(): List<Pair<String, String>> = roots.filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }.toList()
    }

    @Test
    fun `the host's sources are where this test reads them`() {
        assertTrue(File("src/jvmSharedMain/kotlin", listing).isFile, "expected the shared sources under ${File("src").absolutePath}: this test reads them as text")
    }

    @Test
    fun `no host source names a class of the themes or the language module, or makes the launcher's words itself`() {
        val found = sources().flatMap { (path, text) -> findings(path, text) }
        assertEquals(emptyList(), found, "the host reaches these modules through their keys and APIs only")
    }

    @Test
    fun `the build lists both modules where it lists every compiled module, and recovery keeps its floor`() {
        val all = sources().toMap()
        val listed = all.getValue(listing).lines().map { it.trim() }.toSet()
        assertEquals(registrations, registrations.filter { it in listed }.toSet(), "the exception above names registrations that are gone")
        assertTrue(floor.second in all.getValue(floor.first), "the exception above names a floor that is gone")
    }

    @Test
    fun `every way of naming a module's insides is seen`() {
        val holes = mapOf(
            "a wildcard import" to "import hivens.themes.*",
            "an import of a class" to "import hivens.i18n.I18nEngine",
            "an import under another name" to "import hivens.themes.ThemeManager as Manager",
            "a name in backticks" to "val m = `hivens`.`themes`.`ThemeManager`::class",
            "a name spaced around its dots" to "val m = hivens . themes . ThemeManager::class",
            "a name put together for reflection" to """Class.forName("hivens." + "themes.ThemeManager")""",
            "a name put together on more pluses" to """Class.forName("hivens.i18n" + "." + "I18nEngine")""",
            "a file in the module's package" to "package hivens.themes",
            "a file in its API's package" to "package hivens.i18n.api",
            "an engine taken by reference" to "val make = NexiraStrings::engine",
            "an engine called" to "NexiraStrings.engine(dir, \"en\")",
            "an engine member imported" to "import hivens.ui.i18n.NexiraStrings.translator",
            "the words object as a scope" to "with(NexiraStrings) { engine(dir, \"en\") }",
            "the words object run as a receiver" to "NexiraStrings.run { shipped(\"en\") }",
            "the words object imported under another name" to "import hivens.ui.i18n.NexiraStrings as Words",
        )
        holes.forEach { (what, line) ->
            assertEquals(1, findings("hivens/ui/Somewhere.kt", line).size, "$what is not seen: $line")
        }
        val allowed = listOf(
            "import hivens.themes.api.ThemesKeys",
            "import hivens.i18n.api.I18nKeys",
            "val s = nexiraStrings()",
            "val n = NexiraStrings.NAMESPACE",
            "import hivens.ui.i18n.NexiraStrings",
        )
        allowed.forEach { line -> assertEquals(emptyList(), findings("hivens/ui/Somewhere.kt", line), "$line is the API, and not refused") }
        assertEquals(emptyList(), findings(listing, "import hivens.themes.ThemesModule"))
        assertEquals(1, findings(listing, "import hivens.themes.ThemeManager").size, "the listing names the CompiledModule only")
        assertEquals(emptyList(), findings(floor.first, "    boot[I18nKeys.Words] ?: ${floor.second}"))
        assertEquals(1, findings("hivens/ui/ShellHost.kt", floor.second).size, "the floor is the recovery surface's alone")
    }

    private companion object {
        /** A file whose package is one of the two modules', their API's among them. */
        val PACKAGE = Regex("""^\s*package\s+hivens\.(themes|i18n)\b""")

        /** A name in the themes or the language module that is not in its API, a wildcard among them. */
        val IMPLEMENTATION = Regex("""\bhivens\.(themes|i18n)\.(?!api\b)(\w|\*)""")

        /** An engine of the launcher's own words, called, taken by reference or imported. */
        val OWN_ENGINE = Regex("""\bNexiraStrings(\.|::)(engine|translator|shipped)\b""")

        /** The launcher's own words used as a scope or under another name, where an engine can be made without its name. */
        val OWN_WORDS_SCOPE = Regex("""\b(with|run|apply|also|let)\(\s*NexiraStrings\b|\bNexiraStrings\.(run|apply|also|let)\b|import hivens\.ui\.i18n\.NexiraStrings as\b""")
    }
}
