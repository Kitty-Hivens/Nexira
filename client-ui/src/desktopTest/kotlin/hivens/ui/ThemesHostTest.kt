package hivens.ui

import androidx.compose.ui.graphics.Color
import hivens.config.Storage
import hivens.core.data.NewerBuildData
import hivens.i18n.I18nModule
import hivens.i18n.api.FixedTranslations
import hivens.i18n.api.I18nKeys
import hivens.test.stopThenDelete
import hivens.themes.ThemeLibraryFile
import hivens.themes.ThemeManager
import hivens.themes.Themes
import hivens.themes.ThemesModule
import hivens.themes.ThemesSettingsDomain
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemeScheme
import hivens.themes.api.ThemeSettings
import hivens.themes.api.ThemesApi
import hivens.themes.api.ThemesKeys
import hivens.ui.bootstrap.bootValues
import hivens.ui.bootstrap.documentModules
import hivens.ui.host.launcherServices
import hivens.ui.i18n.NexiraStrings
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.theme.Theme
import hivens.ui.widgets.modules.CompiledModules
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.modules.WidgetModules.Contributed
import hivens.widget.api.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The themes module as the launcher brings it in: its settings read from the launcher's former
 * settings file until it has a file of its own and written back there with every change, a choice
 * that survives a build from before the move, and the boot window opening on what the module's keys
 * answer once they are up.
 */
class ThemesHostTest {

    private val dir: Path = Files.createTempDirectory("themes-host")
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val levels = ArrayList<ModuleAppLevel>()
    private val legacy: Path get() = dir.resolve(Storage.SETTINGS_FILE)
    private val domainFile: Path get() = dir.resolve(Storage.SETTINGS_DIR).resolve("${ThemesSettingsDomain.id}.json")
    private val themesJson: Path get() = dir.resolve(ThemeLibraryFile.FILE_NAME)

    @AfterTest
    fun tearDown() {
        levels.forEach { it.close() }
        stopThenDelete(scope, dir)
    }

    private fun domains() = SettingsDomainFiles(dir.resolve(Storage.SETTINGS_DIR), legacy)

    /** The app level with the themes module's keys and the language module's, as the launcher brings them in over the same files. */
    private fun shell(): ModuleAppLevel = ModuleAppLevel(dir, listOf(I18nModule, ThemesModule), scope).also { levels += it }

    /** The manager the themes module makes for such an app level. */
    private fun manager(): ThemeManager = shell().app.launcherServices.get<ThemeManager>()

    /** The theme the boot window is drawn in, as the modules answer it before the boot. */
    private fun bootTheme(): Theme? = bootValues(dir)[ThemesKeys.Active]

    /** Whether the boot window is drawn dark, as the modules answer it before the boot. */
    private fun bootDark(): Boolean? = bootValues(dir)[ThemesKeys.Dark]

    private fun legacyJson(): JsonObject = Json.parseToJsonElement(legacy.readText()).jsonObject

    @Test
    fun `the dark flag and the mode are taken from the old settings file until the domain has a file`() {
        legacy.writeText("""{"isDarkTheme":false,"themeMode":"Manual","locale":"de"}""")

        assertEquals(ThemeSettings(theme = null, mode = ThemeMode.Manual, dark = false), domains().peek(ThemesSettingsDomain))
        val themes = manager()
        assertEquals(false, themes.dark.value)
        assertEquals(ThemeMode.Manual, themes.mode.value)
        assertFalse(domainFile.exists(), "reading writes nothing")
    }

    @Test
    fun `the oldest wallpaper flag, from before the mode, opens in the wallpaper mode`() {
        legacy.writeText("""{"isDarkTheme":true,"themeFromWallpaper":true}""")

        assertEquals(ThemeMode.Wallpaper, manager().mode.value)
    }

    @Test
    fun `every choice is mirrored into the old file, its own fields only, for an older build to read`() {
        legacy.writeText("""{"isDarkTheme":true,"themeMode":"System","javaPath":"/opt/jdk","locale":"de"}""")
        val themes = manager()

        themes.setDark(false)
        var old = legacyJson()
        assertEquals(false, old.getValue("isDarkTheme").jsonPrimitive.boolean)
        assertEquals("Manual", old.getValue("themeMode").jsonPrimitive.content)
        assertEquals(false, old.getValue("themeFromWallpaper").jsonPrimitive.boolean)

        themes.setMode(ThemeMode.Wallpaper)
        old = legacyJson()
        assertEquals("Wallpaper", old.getValue("themeMode").jsonPrimitive.content)
        assertEquals(true, old.getValue("themeFromWallpaper").jsonPrimitive.boolean, "the oldest builds read only this")
        assertEquals("/opt/jdk", old.getValue("javaPath").jsonPrimitive.content, "every other field is left as it was")
        assertEquals("de", old.getValue("locale").jsonPrimitive.content)
    }

    @Test
    fun `a downgrade, a change made there, and the upgrade after keep the last choice`() {
        legacy.writeText("""{"isDarkTheme":true,"themeMode":"System"}""")
        // The new build: the person picks Signal in light, by hand.
        manager().apply {
            choose(Themes.Signal.id)
            setDark(false)
        }

        // A build from before the move reads its two files and opens on the same choice.
        assertEquals(false, legacyJson().getValue("isDarkTheme").jsonPrimitive.boolean)
        assertEquals("Manual", legacyJson().getValue("themeMode").jsonPrimitive.content)
        assertEquals(Themes.Signal.id, Json.parseToJsonElement(themesJson.readText()).jsonObject.getValue("selected").jsonPrimitive.content)

        // There the person goes back to following the system, in the dark, on Lotus. That build
        // writes the fields it knows and nothing else.
        legacy.writeText("""{"isDarkTheme":true,"themeMode":"System","themeFromWallpaper":false}""")
        themesJson.writeText("""{"selected":"lotus","themes":[],"wallpaper":[],"schema_version":2}""")

        // Up again: the boot window and the shell open on that.
        val boot = bootTheme()
        val themes = manager()
        assertEquals(Themes.Lotus, boot)
        assertEquals(Themes.Lotus, themes.active.value)
        assertEquals(true, bootDark())
        assertEquals(true, themes.dark.value)
        assertEquals(ThemeMode.System, themes.mode.value)
        assertEquals(Themes.Lotus, bootTheme(), "and keep it at the next start")
        assertEquals(Themes.Lotus, manager().active.value)

        // A choice made here again is the choice in every file.
        themes.choose(Themes.Abyssal.id)
        assertEquals(Themes.Abyssal, bootTheme())
        assertEquals(Themes.Abyssal.id, ThemeLibraryFile(dir) { _, _ -> }.load().library.selected)
    }

    @Test
    fun `a theme an older build chose, with nothing else changed there, is taken back at the upgrade`() {
        manager().choose(Themes.Signal.id)
        // The older build changes only the theme, so the old settings file holds what this one wrote.
        themesJson.writeText("""{"selected":"lotus","themes":[],"wallpaper":[],"schema_version":2}""")

        assertEquals(Themes.Lotus, bootTheme())
        assertEquals(Themes.Lotus, manager().active.value)
        assertEquals(Themes.Lotus.id, domains().peek(ThemesSettingsDomain).theme, "taken into the settings, so it stays at the next start")
    }

    @Test
    fun `the boot window and the shell open on the same theme and scheme, whatever the files hold`() {
        val setups: List<Pair<String, () -> Unit>> = listOf(
            "a fresh install" to {},
            "only the old settings file" to { legacy.writeText("""{"isDarkTheme":false,"themeMode":"Manual"}""") },
            "only an older build's theme file" to { themesJson.writeText("""{"selected":"blood-rain","schema_version":2}""") },
            "the first version's theme file" to { themesJson.writeText("""{"name":"Lotus Dark"}""") },
            "the domain's own file and the theme file holding what it was given" to {
                writeDomain("""{"theme":"signal","mode":"Manual","dark":false,"copied":"signal"}""")
                themesJson.writeText("""{"selected":"signal","schema_version":2}""")
            },
            "a choice an older build changed since" to {
                writeDomain("""{"theme":"signal","mode":"Manual","dark":false,"copied":"signal"}""")
                themesJson.writeText("""{"selected":"abyssal","schema_version":2}""")
            },
            "a choice whose theme file was never written" to {
                writeDomain("""{"theme":"signal","mode":"Manual","dark":false}""")
                themesJson.writeText("""{"selected":"abyssal","schema_version":2}""")
            },
            "the wallpaper theme with its colours kept" to {
                writeDomain("""{"theme":"wallpaper","mode":"Manual","dark":true,"copied":"wallpaper"}""")
                themesJson.writeText("""{"selected":"wallpaper","wallpaper":["#E0457B","#2E86C1"],"schema_version":2}""")
            },
            "a module's theme whose module has not loaded yet" to {
                writeDomain("""{"theme":"looks/outrun","mode":"Manual","dark":true}""")
            },
            "a newer build's theme file" to {
                writeDomain("""{"theme":"lotus","mode":"System","dark":false}""")
                themesJson.writeText("""{"selected":"abyssal","schema_version":99}""")
            },
            "a newer build's settings of the themes module" to {
                domainFile.parent.createDirectories()
                domainFile.writeText("""{"schema":7,"value":{"theme":"lotus","mode":"Manual","dark":false,"later":true}}""")
                themesJson.writeText("""{"selected":"abyssal","schema_version":2}""")
            },
        )
        setups.forEach { (what, setup) ->
            clear()
            setup()
            val boot = bootValues(dir)
            val shell = shell()
            assertEquals(shell.value(ThemesKeys.Active), boot[ThemesKeys.Active], "$what: the boot window draws another theme than the shell")
            assertEquals(shell.value(ThemesKeys.Dark), boot[ThemesKeys.Dark], "$what: the boot window draws another scheme than the shell")
        }
    }

    @Test
    fun `a damaged theme file and a scheme an older build changed keep the chosen theme`() {
        legacy.writeText("""{"isDarkTheme":true,"themeMode":"Manual"}""")
        manager().choose(Themes.Signal.id)
        assertEquals(Themes.Signal.id, domains().peek(ThemesSettingsDomain).theme)
        themesJson.writeText("{ damaged")
        legacy.writeText("""{"isDarkTheme":false,"themeMode":"Manual","themeFromWallpaper":false}""")

        val themes = manager()
        assertEquals(false, themes.dark.value, "the older build's scheme is taken")
        assertEquals(Themes.Signal.id, domains().peek(ThemesSettingsDomain).theme, "the choice is still stored")
        assertEquals(Themes.Signal, themes.active.value)
        assertEquals(Themes.Signal, bootTheme())
    }

    @Test
    fun `a choice whose theme file could not be written survives the next start`() {
        // The theme file as a build from before the record left it.
        themesJson.writeText("""{"selected":"lotus","themes":[],"wallpaper":[],"schema_version":2}""")
        val failing = ThemeManager(
            store        = domains().of(ThemesSettingsDomain),
            file         = ThemeLibraryFile(dir) { _, _ -> throw IOException("disk full") },
            translations = FixedTranslations(NexiraStrings.translator("en")),
            scope        = scope,
        )
        failing.choose(Themes.Signal.id)

        assertEquals(Themes.Signal, bootTheme())
        assertEquals(Themes.Signal, manager().active.value)
    }

    @Test
    fun `a theme file a newer build wrote is put on record for the notice as the look starts, under the name the module gives it`() {
        themesJson.writeText("""{"schema_version":99,"selected":"abyssal"}""")
        NewerBuildData.reset()
        try {
            // The standard document, and nothing read of the themes module's keys: the look manager's
            // first pass, which the host runs before the shell first draws, opens the colour provider,
            // which makes the module's manager.
            val shell = ModuleAppLevel(dir, CompiledModules, scope, named = documentModules(dir)).also { levels += it }
            shell.app.prepareLook()
            val recorded = NewerBuildData.modulesWithReason().keys.single()
            assertEquals(ThemesApi.ID, recorded.module)
            assertEquals("the theme", shell.value(I18nKeys.Words).strings(recorded.module).text(recorded.name), "named in the module's own words")
            assertTrue(NewerBuildData.affected().isEmpty(), "and as none of the launcher's own stores")
        } finally {
            NewerBuildData.reset()
        }
    }

    @Test
    fun `a module's theme chosen and gone draws the default and stays chosen, in the boot window as in the shell`() {
        val shell = shell()
        shell.jars.value = withLooks()
        shell.fire(ThemesKeys.Choose, "looks/outrun")
        assertEquals("looks/outrun", shell.value(ThemesKeys.Active).id)

        // The next start, with the module away.
        val boot = bootTheme()
        val next = shell()
        assertEquals(Themes.default, boot)
        assertEquals(Themes.default, next.value(ThemesKeys.Active))
        assertEquals("looks/outrun", domains().peek(ThemesSettingsDomain).theme, "the choice is still there")
        assertNull(next.value(ThemesKeys.Offered).firstOrNull { it.id == "looks/outrun" })

        next.jars.value = withLooks()
        assertEquals("looks/outrun", next.value(ThemesKeys.Active).id, "and comes back with the module")
    }

    /** The module list with a jar that brings Outrun loaded. */
    private fun withLooks(): WidgetModules.Snapshot =
        ModuleAppLevel.noJars().copy(contributions = listOf(Contributed("looks", "Looks", Outrun())))

    private fun writeDomain(value: String) {
        domainFile.parent.createDirectories()
        domainFile.writeText("""{"schema":1,"value":$value}""")
    }

    private fun clear() {
        listOf(legacy, domainFile, themesJson).forEach { Files.deleteIfExists(it) }
    }

    private class Outrun : ThemeContribution {
        override val id = "outrun"
        override val label = "Outrun"
        override val dark = ThemeScheme(listOf(Color(0xFF140A28), Color(0xFF1F1038)), listOf(Color(0xFFFF4DA6)))
    }
}
