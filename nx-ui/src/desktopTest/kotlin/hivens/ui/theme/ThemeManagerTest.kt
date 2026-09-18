package hivens.ui.theme

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A saved theme is authored -- nothing regenerates it -- so the manager must hand
 * every write to the publisher it was given rather than putting bytes on disk
 * itself. This module cannot see the launcher's atomic-write helper, so the only
 * thing holding that arrangement together is that nothing here opens a file.
 */
class ThemeManagerTest {

    private val temps = mutableListOf<Path>()

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        temps.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun tempDir(): Path = Files.createTempDirectory("theme-manager").also { temps.add(it) }

    @Test
    fun `saving a theme goes through the publisher and never writes directly`() {
        val dir = tempDir()
        val published = mutableListOf<Pair<Path, String>>()
        val manager = ThemeManager(dir) { file, content -> published += file to content }

        manager.saveTheme(ThemePresets.NEON_PINK)

        assertEquals(1, published.size, "the write must be delegated, not performed here")
        assertEquals(dir.resolve("themes.json"), published.single().first)
        assertTrue(published.single().second.contains(ThemePresets.NEON_PINK.name))
        assertFalse(
            Files.exists(dir.resolve("themes.json")),
            "nothing may reach disk except through the publisher -- that is what makes the write atomic",
        )
    }

    @Test
    fun `a publisher that throws does not take the caller down`() {
        // Saving a theme is a side effect of a colour picker; a failed write is
        // worth a log line, not an exception into the composition.
        val manager = ThemeManager(tempDir()) { _, _ -> throw java.io.IOException("disk full") }
        manager.saveTheme(ThemePresets.ABYSSAL)
    }

    @Test
    fun `an absent file loads the default preset`() {
        val manager = ThemeManager(tempDir()) { _, _ -> }
        assertEquals(ThemePresets.CELESTIA_DARK, manager.loadTheme())
    }

    @Test
    fun `a theme written before a role existed keeps everything it did say`() {
        // The regression this guards: the record had no field defaults, so the
        // release that added a role made every file on disk undecodable. The
        // loader fell back to a preset and the next save wrote that preset over
        // the user's colours, with one swallowed exception as the only trace.
        val dir = tempDir()
        Files.writeString(
            dir.resolve("themes.json"),
            """{"name":"Mine","primary":"#112233","secondary":"#445566"}""",
        )

        val loaded = ThemeManager(dir) { _, _ -> }.loadTheme()

        assertEquals("Mine", loaded.name, "a file that predates a role is still that user's theme")
        assertEquals("#112233", loaded.primary)
        assertEquals("#445566", loaded.secondary)
        assertEquals(ThemePresets.CELESTIA_DARK.error, loaded.error, "a role it never mentioned takes the default")
    }

    @Test
    fun `a theme from a newer build is read, and not written back over`() {
        val dir = tempDir()
        val file = dir.resolve("themes.json")
        Files.writeString(
            file,
            """{"schema_version":99,"name":"From the future","primary":"#ABCDEF"}""",
        )
        val published = mutableListOf<Pair<Path, String>>()
        val manager = ThemeManager(dir) { f, c -> published += f to c }

        val loaded = manager.loadTheme()
        assertEquals("From the future", loaded.name, "read it as far as this build can")
        assertTrue(manager.readOnly, "a newer stamp opens the file read-only")

        manager.saveTheme(ThemePresets.MATRIX)
        assertTrue(published.isEmpty(), "writing back would drop whatever the newer build understood and this does not")
    }

    @Test
    fun `a saved theme carries the stamp that makes the guard possible`() {
        val published = mutableListOf<String>()
        ThemeManager(tempDir()) { _, c -> published += c }.saveTheme(ThemePresets.VAPORWAVE)

        assertTrue(
            published.single().contains("\"${ThemeManager.SCHEMA_KEY}\""),
            "an unstamped file is indistinguishable from one written before stamping began",
        )
    }
}
