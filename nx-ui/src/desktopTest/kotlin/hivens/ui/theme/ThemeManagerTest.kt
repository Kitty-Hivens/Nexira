package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The manager hands every write to the publisher it was given rather than putting
 * bytes on disk itself, and it reads every file an older build wrote without losing
 * what was in it.
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
    fun `saving goes through the publisher and never writes directly`() {
        val dir = tempDir()
        val published = mutableListOf<Pair<Path, String>>()
        val manager = ThemeManager(dir) { file, content -> published += file to content }

        manager.save(ThemeLibrary(selected = Themes.NeonDreams.id))

        assertEquals(1, published.size, "the write must be delegated, not performed here")
        assertEquals(dir.resolve("themes.json"), published.single().first)
        assertTrue(published.single().second.contains(Themes.NeonDreams.id))
        assertFalse(Files.exists(dir.resolve("themes.json")), "nothing may reach disk except through the publisher")
    }

    @Test
    fun `a publisher that throws does not take the caller down`() {
        ThemeManager(tempDir()) { _, _ -> throw java.io.IOException("disk full") }.save(ThemeLibrary())
    }

    @Test
    fun `an absent file selects the default theme`() {
        assertEquals(Themes.default, ThemeManager(tempDir()) { _, _ -> }.load().active)
    }

    @Test
    fun `a first-version file naming a theme that ships selects it`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("themes.json"), """{"name":"Blood Rain","primary":"#A01818"}""")
        assertEquals(Themes.BloodRain, ThemeManager(dir) { _, _ -> }.load().active)
    }

    @Test
    fun `the first version's default name is Celestia`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("themes.json"), """{"name":"Celestia Dark"}""")
        assertEquals(Themes.Celestia, ThemeManager(dir) { _, _ -> }.load().active)
    }

    @Test
    fun `a first-version theme of somebody's own becomes their own theme with nothing dropped`() {
        val dir = tempDir()
        Files.writeString(
            dir.resolve("themes.json"),
            """{"name":"Mine","primary":"#112233","secondary":"#445566","background":"#010203","surface":"#0A0B0C","error":"#FF0000"}""",
        )

        val library = ThemeManager(dir) { _, _ -> }.load()
        val mine = library.active

        assertEquals("Mine", mine.name)
        assertEquals(listOf(Color(0xFF010203), Color(0xFF0A0B0C)), mine.dark.steps, "its ground and surface open the ladder")
        assertEquals(listOf(Color(0xFF112233), Color(0xFF445566), Color(0xFFFF0000)), mine.dark.colors)
        assertEquals(listOf(mine), library.own)
    }

    @Test
    fun `an own theme survives a save and a load`() {
        val dir = tempDir()
        var written = ""
        val own = Theme(
            id = "own-test",
            name = "Test",
            dark = Scheme(listOf(Color(0xFF101010), Color(0xFF202020)), listOf(Color.White, Color(0xFFBBBBBB)), listOf(Color(0xFF3366FF))),
        )
        ThemeManager(dir) { _, c -> written = c }.save(ThemeLibrary(selected = own.id, own = listOf(own)))
        Files.writeString(dir.resolve("themes.json"), written)

        val back = ThemeManager(dir) { _, _ -> }.load()
        assertEquals(own, back.active)
    }

    @Test
    fun `a selection naming a theme that is gone lands on the default`() {
        assertEquals(Themes.default, ThemeLibrary(selected = "no-such-theme").active)
    }

    @Test
    fun `a file from a newer build is read, and not written back over`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("themes.json"), """{"schema_version":99,"selected":"matrix"}""")
        val published = mutableListOf<String>()
        val manager = ThemeManager(dir) { _, c -> published += c }

        assertEquals(Themes.Matrix, manager.load().active, "read it as far as this build can")
        assertTrue(manager.readOnly)

        manager.save(ThemeLibrary(selected = Themes.Celestia.id))
        assertTrue(published.isEmpty(), "writing back would drop whatever the newer build understood")
    }

    @Test
    fun `a saved file carries the stamp that makes the guard possible`() {
        val published = mutableListOf<String>()
        ThemeManager(tempDir()) { _, c -> published += c }.save(ThemeLibrary())
        assertTrue(published.single().contains("\"${ThemeManager.SCHEMA_KEY}\""))
    }
}
