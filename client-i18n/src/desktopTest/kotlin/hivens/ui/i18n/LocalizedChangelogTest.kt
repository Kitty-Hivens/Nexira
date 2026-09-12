package hivens.ui.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every shipped locale has player notes, and every file names the same release.
 *
 * The update dialog builds the filename from the locale's own tag and then
 * takes the section headed by the release tag. Both misses are silent by
 * design: no file for the language, or no section for the version, falls back
 * to the English copy CI froze into the release manifest. That is the right
 * behaviour at runtime and a poor way to learn that a translation was never
 * written -- Japanese shipped as an interface language with no notes file at
 * all, and nothing anywhere said so.
 *
 * The engineering log is held to the same header because a section is matched
 * across the files by its header alone.
 */
class LocalizedChangelogTest {

    private val repoRoot: File =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "CHANGELOG_EN.md").isFile }
            ?: fail("no repository root above ${File(".").absolutePath}")

    private fun changelog(name: String) = File(repoRoot, name)

    /** The newest release in the English notes: the first header that names a version. */
    private val releaseHeader: String by lazy {
        changelog("CHANGELOG_EN.md").readLines()
            .firstOrNull { it.startsWith("## [") && !it.startsWith("## [Unreleased]") }
            ?: fail("CHANGELOG_EN.md names no released version")
    }

    private val version: String by lazy { releaseHeader.substringAfter('[').substringBefore(']') }

    /**
     * The body of one version's section, header excluded. Deliberately the same
     * rule the launcher applies: matched with the closing bracket so 2.4.0 does
     * not select 2.4.0-beta5, and ended at the next version header.
     */
    private fun sectionOf(markdown: String): String? {
        val header = "## [$version]"
        val start = markdown.indexOf(header)
        if (start == -1) return null
        return markdown.substring(start + header.length)
            .substringAfter('\n', "")
            .substringBefore("\n## [")
            .trim()
            .ifBlank { null }
    }

    @Test
    fun `every locale in the picker has a notes file`() {
        val missing = AppLocale.entries
            .map { "CHANGELOG_${it.tag.uppercase()}.md" }
            .filterNot { changelog(it).isFile }

        assertTrue(
            missing.isEmpty(),
            "a locale the picker offers has no player notes, so its readers silently get English:\n" +
                missing.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `every notes file carries the newest release, under the same header`() {
        val files = AppLocale.entries.map { "CHANGELOG_${it.tag.uppercase()}.md" } + "CHANGELOG.md"

        val wrong = files.mapNotNull { name ->
            val file = changelog(name)
            if (!file.isFile) return@mapNotNull null
            val text = file.readText()
            val header = text.lines().firstOrNull { it.startsWith("## [$version]") }
            when {
                header == null -> "$name: no section for $version"
                header != releaseHeader -> "$name: header is \"$header\", English has \"$releaseHeader\""
                sectionOf(text) == null -> "$name: the $version section is empty"
                else -> null
            }
        }

        assertTrue(
            wrong.isEmpty(),
            "the notes for $version disagree across languages, and the dialog matches them by header:\n" +
                wrong.joinToString("\n") { "  $it" },
        )
    }

    /**
     * A paragraph is one physical line. These notes are rendered verbatim, and
     * in the GitHub release body every newline becomes a break, so a
     * hand-wrapped paragraph arrives as a staircase.
     */
    @Test
    fun `the notes are not hand-wrapped`() {
        val wrapped = AppLocale.entries.mapNotNull { locale ->
            val name = "CHANGELOG_${locale.tag.uppercase()}.md"
            val file = changelog(name)
            if (!file.isFile) return@mapNotNull null
            val offenders = sectionOf(file.readText())
                ?.lines()
                ?.filter { it.startsWith(" ") || it.startsWith("\t") }
                ?: return@mapNotNull null
            if (offenders.isEmpty()) null else "$name: ${offenders.size} continuation lines"
        }

        assertTrue(wrapped.isEmpty(), "hand-wrapped paragraphs in:\n" + wrapped.joinToString("\n") { "  $it" })
    }
}
