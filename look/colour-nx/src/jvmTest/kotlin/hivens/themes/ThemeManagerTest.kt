package hivens.themes

import androidx.compose.ui.graphics.Color
import hivens.i18n.api.LanguageInfo
import hivens.i18n.api.Strings
import hivens.i18n.api.Translations
import hivens.i18n.api.Translator
import hivens.themes.api.SystemScheme
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemeScheme
import hivens.themes.api.ThemeSettings
import hivens.ui.theme.WallpaperTone
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The manager holds the choice and the library together: what modules bring comes and goes with
 * them, a choice survives its theme being away, a choice an older build made is taken back, and
 * what decides dark and light listens only to the source the mode names.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemeManagerTest {

    private val dir: Path = Files.createTempDirectory("theme-manager")
    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private val words = Words("en")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun file() = ThemeLibraryFile(dir) { path, content -> Files.writeString(path, content) }

    private fun manager(store: MemoryStore = MemoryStore(ThemeSettings())) = ThemeManager(store, file(), words, scope)

    private val themesJson: Path get() = dir.resolve(ThemeLibraryFile.FILE_NAME)

    @Test
    fun `a module's theme is offered under the module's id in its own words, and leaves with it`() {
        val themes = manager()

        themes.contribute("looks", listOf(Outrun()))
        val offered = themes.offered.value.single { it.id == "looks/outrun" }
        assertEquals("Outrun", offered.name)

        words.speak("ru")
        assertEquals("Аутран", themes.offered.value.single { it.id == "looks/outrun" }.name, "named anew in the new language")

        themes.withdraw("looks")
        assertTrue(themes.offered.value.none { it.id == "looks/outrun" })
    }

    @Test
    fun `a chosen theme whose module is gone draws the default and stays chosen, and comes back with the module`() {
        val store = MemoryStore(ThemeSettings())
        val themes = manager(store)
        themes.contribute("looks", listOf(Outrun()))

        themes.choose("looks/outrun")
        assertEquals("looks/outrun", themes.active.value.id)

        themes.withdraw("looks")
        assertEquals(Themes.default, themes.active.value)
        assertEquals("looks/outrun", store.value.value.theme, "the choice is kept while the module is away")
        assertEquals("looks/outrun", themes.library.value.selected)

        themes.contribute("looks", listOf(Outrun()))
        assertEquals("looks/outrun", themes.active.value.id)
    }

    @Test
    fun `a module's theme reads in the launcher's inks where it gives none, and stays dark without a light scheme`() {
        val themes = manager()
        themes.contribute("looks", listOf(Outrun()))
        val outrun = themes.offered.value.single { it.id == "looks/outrun" }

        assertEquals(Themes.Celestia.dark.inks, outrun.dark.inks)
        assertFalse(outrun.hasLight)
    }

    @Test
    fun `a theme that cannot make a scheme costs only itself`() {
        val themes = manager()
        themes.contribute("looks", listOf(Broken(), Outrun()))

        assertEquals(listOf("looks/outrun"), themes.offered.value.map { it.id }.filter { it.startsWith("looks/") })
    }

    @Test
    fun `choosing a theme keeps it in the settings and copies it to the theme file for an older build`() {
        val store = MemoryStore(ThemeSettings())
        val themes = manager(store)

        themes.choose(Themes.Signal.id)

        assertEquals(Themes.Signal.id, store.value.value.theme)
        assertEquals(Themes.Signal, themes.active.value)
        val back = ThemeLibraryFile(dir) { _, _ -> }.load()
        assertEquals(Themes.Signal.id, back.library.selected)
        assertEquals(Themes.Signal.id, store.value.value.copied, "the settings record what the file now holds")
    }

    @Test
    fun `a choice whose theme file could not be written survives the next start`() {
        // The theme file as a build from before the record left it.
        themesJson.writeText("""{"selected":"lotus","themes":[],"wallpaper":[],"schema_version":2}""")
        val store = MemoryStore(ThemeSettings())
        ThemeManager(store, ThemeLibraryFile(dir) { _, _ -> throw IOException("disk full") }, words, scope).choose(Themes.Signal.id)
        assertEquals(Themes.Signal.id, store.value.value.theme)
        assertEquals(null, store.value.value.copied, "nothing landed, so nothing is recorded")

        assertEquals(Themes.Signal, manager(store).active.value)
        assertEquals(Themes.Signal.id, store.value.value.theme)
    }

    @Test
    fun `a theme chosen through the settings is drawn and copied like one chosen through the command`() {
        val store = MemoryStore(ThemeSettings())
        val themes = manager(store)

        store.update { it.copy(theme = Themes.Lotus.id) }

        assertEquals(Themes.Lotus, themes.active.value)
        assertEquals(Themes.Lotus.id, ThemeLibraryFile(dir) { _, _ -> }.load().library.selected)
    }

    @Test
    fun `with nothing chosen in the settings, the theme the file was left on is in use`() {
        themesJson.writeText("""{"selected":"abyssal","themes":[],"wallpaper":[],"schema_version":2}""")

        val themes = manager(MemoryStore(ThemeSettings(theme = null)))

        assertEquals(Themes.Abyssal, themes.active.value)
    }

    @Test
    fun `with nothing chosen and the file holding the theme it was last given, that theme opens`() {
        themesJson.writeText("""{"selected":"abyssal","themes":[],"wallpaper":[],"schema_version":2}""")
        val settings = ThemeSettings(theme = null, copied = Themes.Abyssal.id)

        assertEquals(Themes.Abyssal, ThemeManager.opening(settings, ThemeLibraryFile(dir) { _, _ -> }.load()).theme)
        assertEquals(Themes.Abyssal, manager(MemoryStore(settings)).active.value)
    }

    @Test
    fun `a theme an older build chose since this one last wrote the file is taken back`() {
        val chosen = ThemeSettings(theme = Themes.Abyssal.id, copied = Themes.Abyssal.id)
        val store = MemoryStore(chosen)
        // Written by an older build since: not the theme the settings record it was given.
        themesJson.writeText("""{"selected":"lotus","themes":[],"wallpaper":[],"schema_version":2}""")

        val themes = manager(store)

        assertEquals(Themes.Lotus, themes.active.value)
        assertEquals(Themes.Lotus.id, store.value.value.theme)
        assertEquals(Themes.Lotus.id, store.value.value.copied)
        assertEquals(Themes.Lotus, ThemeManager.opening(chosen, ThemeLibraryFile(dir) { _, _ -> }.load()).theme)
    }

    @Test
    fun `a file of the first version found after this build wrote one is an older build's choice, taken back`() {
        val store = MemoryStore(ThemeSettings(theme = Themes.Signal.id, copied = Themes.Signal.id))
        themesJson.writeText("""{"name":"Blood Rain","primary":"#A01818"}""")

        assertEquals(Themes.BloodRain, manager(store).active.value)
        assertEquals(Themes.BloodRain.id, store.value.value.theme)
    }

    @Test
    fun `a file holding the theme it was last given does not override the settings`() {
        val store = MemoryStore(ThemeSettings(theme = Themes.Signal.id, copied = Themes.Lotus.id))
        themesJson.writeText("""{"selected":"lotus","themes":[],"wallpaper":[],"schema_version":2}""")

        val themes = manager(store)

        assertEquals(Themes.Signal, themes.active.value)
        assertEquals(Themes.Signal.id, store.value.value.theme)
    }

    @Test
    fun `a file that cannot be read, or a newer build's, takes nothing back`() {
        val store = MemoryStore(ThemeSettings(theme = Themes.Signal.id, copied = Themes.Signal.id))
        themesJson.writeText("{ damaged")
        assertEquals(Themes.Signal, manager(store).active.value)

        themesJson.writeText("""{"schema_version":99,"selected":"lotus"}""")
        assertEquals(Themes.Signal, manager(store).active.value)
        assertEquals(Themes.Signal.id, store.value.value.theme)
    }

    @Test
    fun `day or night chosen by hand takes the scheme back from an automatic mode`() {
        val store = MemoryStore(ThemeSettings(mode = ThemeMode.System, dark = true))
        val themes = manager(store)

        themes.setDark(false)

        assertEquals(ThemeSettings(mode = ThemeMode.Manual, dark = false), store.value.value)
        assertEquals(false, themes.dark.value)
        assertEquals(ThemeMode.Manual, themes.mode.value)
    }

    @Test
    fun `the wallpaper decides only in the wallpaper mode, and the mode applies what it last said`() {
        val store = MemoryStore(ThemeSettings(mode = ThemeMode.Manual, dark = false))
        val themes = manager(store)

        themes.wallpaper(WallpaperTone(listOf(0xFF102030.toInt()), avgLuminance = 0.2f))
        assertEquals(false, themes.dark.value, "a manual choice is not overridden")

        themes.setMode(ThemeMode.Wallpaper)
        assertEquals(true, themes.dark.value, "the dark wallpaper seen before applies at once")

        themes.wallpaper(WallpaperTone(listOf(0xFFF0F0E0.toInt()), avgLuminance = 0.9f))
        assertEquals(false, themes.dark.value)
        assertEquals(false, store.value.value.dark, "kept, so the next start opens on it")
    }

    @Test
    fun `a mode changed through the settings applies its source the same way`() {
        val store = MemoryStore(ThemeSettings(mode = ThemeMode.Manual, dark = false))
        val themes = manager(store)
        themes.wallpaper(WallpaperTone(listOf(0xFF102030.toInt()), avgLuminance = 0.2f))

        store.update { it.copy(mode = ThemeMode.Wallpaper) }

        assertEquals(true, themes.dark.value)
    }

    @Test
    fun `the system decides only in the system mode, and says whether it can be followed at all`() {
        val store = MemoryStore(ThemeSettings(mode = ThemeMode.Manual, dark = true))
        val themes = manager(store)
        assertFalse(themes.systemSchemeAvailable.value)

        themes.systemScheme(SystemScheme(readable = true, dark = false))
        assertTrue(themes.systemSchemeAvailable.value)
        assertEquals(true, themes.dark.value)

        themes.setMode(ThemeMode.System)
        themes.systemScheme(SystemScheme(readable = true, dark = false))
        assertEquals(false, themes.dark.value)

        themes.systemScheme(SystemScheme(readable = true, dark = null))
        assertEquals(false, themes.dark.value, "a scheme that cannot be read at the moment says nothing")
    }

    @Test
    fun `the wallpaper's colours are kept for the next start, and the theme made from them is offered`() {
        val themes = manager()
        val colours = listOf(0xFFE0457B.toInt(), 0xFF2E86C1.toInt())

        themes.wallpaper(WallpaperTone(colours, avgLuminance = 0.4f))

        assertTrue(themes.offered.value.any { it.id == "wallpaper" })
        assertEquals(colours, ThemeLibraryFile(dir) { _, _ -> }.load().library.wallpaper)

        themes.wallpaper(WallpaperTone.NONE)
        assertTrue(themes.offered.value.none { it.id == "wallpaper" }, "no wallpaper, no theme made from it")
    }

    private class Outrun : ThemeContribution {
        override val id = "outrun"
        override val label = "outrun"
        override val dark = ThemeScheme(listOf(Color(0xFF140A28), Color(0xFF1F1038)), listOf(Color(0xFFFF4DA6)))
    }

    private class Broken : ThemeContribution {
        override val id = "broken"
        override val label = "broken"
        override val dark = ThemeScheme(listOf(Color.Black), listOf(Color.Red))
    }
}

/** A settings store held in memory, applying every change at once, as the host's does. */
internal class MemoryStore(initial: ThemeSettings) : SettingsStore<ThemeSettings> {
    private val flow = MutableStateFlow(initial)
    override val value: StateFlow<ThemeSettings> = flow.asStateFlow()
    override val readOnly: Boolean = false
    override fun update(change: SettingsChange<ThemeSettings>) {
        flow.value = change.apply(flow.value)
    }
}

/** The looks module's words in two languages, switched by [speak]. */
private class Words(initial: String) : Translations {
    private val tables = mapOf(
        "en" to mapOf("outrun" to "Outrun"),
        "ru" to mapOf("outrun" to "Аутран"),
    )
    private val flow = MutableStateFlow(translator(initial))
    override val current: StateFlow<Translator> = flow.asStateFlow()
    override val languages: StateFlow<List<LanguageInfo>> = MutableStateFlow(emptyList<LanguageInfo>()).asStateFlow()

    fun speak(tag: String) {
        flow.value = translator(tag)
    }

    private fun translator(tag: String): Translator = object : Translator {
        override val language = LanguageInfo(tag, tag, "one-other")
        override fun strings(namespace: String): Strings = object : Strings {
            override val namespace = namespace
            override val language = tag
            override val locale: Locale = Locale.forLanguageTag(tag)
            override fun text(key: String, vararg args: Any?): String = tables[tag].orEmpty()[key] ?: key
            override fun has(key: String): Boolean = key in tables[tag].orEmpty()
        }
        override fun inLanguage(tag: String): Translator = translator(tag)
    }
}
