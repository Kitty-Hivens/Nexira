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
}
