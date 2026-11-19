package hivens.themes

import androidx.compose.ui.graphics.Color
import hivens.themes.api.ThemesApi.WALLPAPER_THEME_ID
import hivens.ui.theme.Scheme
import hivens.ui.theme.Theme
import java.io.IOException
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
 * The theme file hands every write to the publisher it was given rather than putting bytes
 * on disk itself, and it reads every file an older build wrote without losing what was in it.
 */
class ThemeLibraryFileTest {

    private val temps = mutableListOf<Path>()

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        temps.forEach { val _ = runCatching { it.deleteRecursively() } }
    }

    private fun tempDir(): Path = Files.createTempDirectory("theme-library-file").also { temps.add(it) }

    private fun load(dir: Path): ThemeLibrary = ThemeLibraryFile(dir) { _, _ -> }.load().library

    @Test
    fun `saving goes through the publisher and never writes directly`() {
        val dir = tempDir()
        val published = mutableListOf<Pair<Path, String>>()
        val file = ThemeLibraryFile(dir) { path, content -> published += path to content }

        file.save(ThemeLibrary(selected = Themes.Abyssal.id))

        assertEquals(1, published.size, "the write must be delegated, not performed here")
        assertEquals(dir.resolve("themes.json"), published.single().first)
        assertTrue(published.single().second.contains(Themes.Abyssal.id))
        assertFalse(Files.exists(dir.resolve("themes.json")), "nothing may reach disk except through the publisher")
    }

    @Test
    fun `a publisher that throws does not take the caller down`() {
        ThemeLibraryFile(tempDir()) { _, _ -> throw IOException("disk full") }.save(ThemeLibrary())
    }

    @Test
    fun `an absent file selects the default theme`() {
        assertEquals(Themes.default, load(tempDir()).active)
    }

    @Test
    fun `a first-version file naming a theme that ships selects it`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("themes.json"), """{"name":"Blood Rain","primary":"#A01818"}""")
        assertEquals(Themes.BloodRain, load(dir).active)
    }

    @Test
    fun `a first-version file naming a theme that no longer ships lands on the default, not on a theme of its own`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("themes.json"), """{"name":"Cyberpunk","primary":"#FF006E","background":"#0A0E27"}""")
        val library = load(dir)
        assertEquals(Themes.default, library.active)
        assertTrue(library.own.isEmpty(), "a retired theme came back as somebody's own")
    }

    @Test
    fun `Lotus Dark, by its first-version name or by its id, opens as Lotus`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("themes.json"), """{"name":"Lotus Dark"}""")
        assertEquals(Themes.Lotus, load(dir).active)
        assertEquals(Themes.Lotus, ThemeLibrary(selected = "lotus-dark").active)
    }

    @Test
    fun `the first version's default name is Celestia`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("themes.json"), """{"name":"Celestia Dark"}""")
        assertEquals(Themes.Celestia, load(dir).active)
    }

    @Test
    fun `a first-version theme of somebody's own becomes their own theme with nothing dropped`() {
        val dir = tempDir()
        Files.writeString(
            dir.resolve("themes.json"),
            """{"name":"Mine","primary":"#112233","secondary":"#445566","background":"#010203","surface":"#0A0B0C","error":"#FF0000"}""",
        )

        val library = load(dir)
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
        ThemeLibraryFile(dir) { _, c -> written = c }.save(ThemeLibrary(selected = own.id, own = listOf(own)))
        Files.writeString(dir.resolve("themes.json"), written)

        assertEquals(own, load(dir).active)
    }

    @Test
    fun `a selection naming a theme that is gone lands on the default`() {
        assertEquals(Themes.default, ThemeLibrary(selected = "no-such-theme").active)
    }

    @Test
    fun `the wallpaper theme opens from the stored colours, before any picture has decoded`() {
        val dir = tempDir()
        var written = ""
        val colours = listOf(0xFFE0457B.toInt(), 0xFF2E86C1.toInt())
        ThemeLibraryFile(dir) { _, c -> written = c }.save(ThemeLibrary(selected = WALLPAPER_THEME_ID, wallpaper = colours))
        Files.writeString(dir.resolve("themes.json"), written)

        val back = load(dir)
        assertEquals(colours, back.wallpaper)
        assertEquals(themeFromWallpaper(colours, "Wallpaper"), back.active)
    }

    @Test
    fun `the wallpaper theme without a wallpaper lands on the default and is not offered`() {
        val library = ThemeLibrary(selected = WALLPAPER_THEME_ID)
        assertEquals(Themes.default, library.active)
        assertTrue(library.all.none { it.id == WALLPAPER_THEME_ID })
    }

    @Test
    fun `a file from a newer build is read, and not written back over`() {
        val dir = tempDir()
        Files.writeString(dir.resolve("themes.json"), """{"schema_version":99,"selected":"abyssal"}""")
        val published = mutableListOf<String>()
        val file = ThemeLibraryFile(dir) { _, c -> published += c }

        val stored = file.load()
        assertEquals(Themes.Abyssal, stored.library.active, "read it as far as this build can")
        assertTrue(file.readOnly)
        assertFalse(stored.selectionRead, "a newer build's selection says nothing of what this one was given")

        file.save(ThemeLibrary(selected = Themes.Celestia.id))
        assertTrue(published.isEmpty(), "writing back would drop whatever the newer build understood")
    }

    @Test
    fun `a save says whether the file now holds the library`() {
        assertTrue(ThemeLibraryFile(tempDir()) { _, _ -> }.save(ThemeLibrary()))
        assertFalse(ThemeLibraryFile(tempDir()) { _, _ -> throw IOException("disk full") }.save(ThemeLibrary()))
    }

    @Test
    fun `a saved file carries the stamp that makes the guard possible`() {
        val published = mutableListOf<String>()
        ThemeLibraryFile(tempDir()) { _, c -> published += c }.save(ThemeLibrary())
        assertTrue(published.single().contains("\"${ThemeLibraryFile.SCHEMA_KEY}\""))
    }

    @Test
    fun `a selection is read only from a file that holds one`() {
        val dir = tempDir()
        assertFalse(ThemeLibraryFile(dir) { _, _ -> }.load().selectionRead, "no file")
        Files.writeString(dir.resolve("themes.json"), "{ damaged")
        assertFalse(ThemeLibraryFile(dir) { _, _ -> }.load().selectionRead, "a file that cannot be read")
        Files.writeString(dir.resolve("themes.json"), """{"selected":"lotus","themes":[],"wallpaper":[],"schema_version":2}""")
        assertTrue(ThemeLibraryFile(dir) { _, _ -> }.load().selectionRead)
        Files.writeString(dir.resolve("themes.json"), """{"name":"Lotus Dark"}""")
        assertTrue(ThemeLibraryFile(dir) { _, _ -> }.load().selectionRead, "a file of the first version")
    }

    private val moduleTheme = Theme(
        id = "looks/outrun",
        name = "Outrun",
        dark = Scheme(
            steps = listOf(Color(0xFF140A28), Color(0xFF1F1038)),
            inks = Themes.Celestia.dark.inks,
            colors = listOf(Color(0xFFFF4DA6)),
        ),
    )

    @Test
    fun `a theme a module brought is listed and can be the active one`() {
        val library = ThemeLibrary(selected = moduleTheme.id, contributed = listOf(moduleTheme))

        assertTrue(moduleTheme in library.all)
        assertEquals(moduleTheme, library.active)
    }

    @Test
    fun `a choice naming a theme whose module is gone lands on the default and keeps the choice`() {
        val library = ThemeLibrary(selected = moduleTheme.id)

        assertEquals(Themes.default, library.active)
        assertEquals(moduleTheme.id, library.selected)
    }

    @Test
    fun `themes a module brought are never written`() {
        var written = ""
        ThemeLibraryFile(tempDir()) { _, c -> written = c }
            .save(ThemeLibrary(selected = moduleTheme.id, contributed = listOf(moduleTheme)))

        assertTrue("looks/outrun" in written, "the choice is stored")
        assertTrue("Outrun" !in written && "FF4DA6" !in written, "the theme itself was stored")
    }
}
