package hivens.ui.widgets.modules

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.config.Storage
import hivens.i18n.I18nEngine
import hivens.themes.ThemeManager
import hivens.themes.Themes
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemeScheme
import hivens.ui.hostThemeManager
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.modules.WidgetModules.Contributed
import hivens.widget.api.ModuleStrings
import hivens.widget.api.ParticleContribution
import hivens.widget.api.ParticleMote
import hivens.widget.api.ParticlePalette
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a module's themes and particle fields become inside the launcher. The themes go to the
 * themes module's manager the way the host hands them over, as the module list changes.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ModuleContributionsTest {

    private val dataDir: Path = Files.createTempDirectory("module-contributions")
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dataDir.deleteRecursively()
    }

    private val strings = ModuleStrings(mapOf("en" to mapOf("night" to "Night"), "ru" to mapOf("night" to "Ночь")))

    /** The themes module's manager as the host makes it, naming in the language [tag] with the module's words in. */
    private fun manager(tag: String): ThemeManager = hostThemeManager(
        dataDir      = dataDir,
        settings     = SettingsDomainFiles(dataDir.resolve(Storage.SETTINGS_DIR), dataDir.resolve(Storage.SETTINGS_FILE)),
        translations = I18nEngine(null, tag).apply { take("looks", strings) },
        scope        = scope,
    )

    private class Theme1(override val dark: ThemeScheme, override val light: ThemeScheme? = null) : ThemeContribution {
        override val id = "night"
        override val label = "night"
    }

    private val good = ThemeScheme(listOf(Color(0xFF101010), Color(0xFF202020)), listOf(Color.Red))

    private object NoWidgets : WidgetRegistry {
        override fun all(): Map<WidgetKind, WidgetDescriptor> = emptyMap()
        override fun get(kind: WidgetKind): WidgetDescriptor? = null
    }

    /** The module list with the looks module loaded and carrying [themes], or with it gone when there are none. */
    private fun loaded(vararg themes: ThemeContribution) =
        WidgetModules.Snapshot(NoWidgets, emptyList(), themes.map { Contributed("looks", "Looks", it) })

    /** [manager] following a module list that starts as [first], and the list, to change. */
    private fun following(manager: ThemeManager, first: WidgetModules.Snapshot): MutableStateFlow<WidgetModules.Snapshot> =
        MutableStateFlow(first).also { followModuleThemes(it, manager, scope) }

    @Test
    fun `a module's theme takes its module's id and is named in the person's language`() {
        val ru = manager("ru")
        following(ru, loaded(Theme1(good)))
        assertEquals("Ночь", ru.offered.value.single { it.id == "looks/night" }.name)

        val ja = manager("ja")
        following(ja, loaded(Theme1(good)))
        assertEquals("Night", ja.offered.value.single { it.id == "looks/night" }.name)
    }

    @Test
    fun `a module's theme leaves with the module, and a choice of it draws the default until it is back`() {
        val themes = manager("en")
        val modules = following(themes, loaded(Theme1(good)))
        themes.choose("looks/night")
        assertEquals("looks/night", themes.active.value.id)

        modules.value = loaded()
        assertTrue(themes.offered.value.none { it.id == "looks/night" })
        assertEquals(Themes.default, themes.active.value)
        assertEquals("looks/night", themes.settings.value.theme, "the choice is kept while the module is away")

        modules.value = loaded(Theme1(good))
        assertEquals("looks/night", themes.active.value.id)
    }

    @Test
    fun `a theme with no inks of its own reads in the launcher's`() {
        val themes = manager("en")
        following(themes, loaded(Theme1(good)))

        assertEquals(Themes.Celestia.dark.inks, themes.offered.value.single { it.id == "looks/night" }.dark.inks)
    }

    @Test
    fun `a theme without a light scheme stays dark`() {
        val themes = manager("en")
        val modules = following(themes, loaded(Theme1(good)))
        assertEquals(false, themes.offered.value.single { it.id == "looks/night" }.hasLight)

        modules.value = loaded(Theme1(good, light = good))
        assertTrue(themes.offered.value.single { it.id == "looks/night" }.hasLight)
    }

    @Test
    fun `a theme that cannot make a scheme costs only itself`() {
        val themes = manager("en")
        val broken = object : ThemeContribution {
            override val id = "broken"
            override val label = "broken"
            override val dark = ThemeScheme(listOf(Color.Black), listOf(Color.Red))
        }
        following(themes, loaded(broken, Theme1(good)))

        assertEquals(listOf("looks/night"), themes.offered.value.map { it.id }.filter { it.startsWith("looks/") })
    }

    private class Field : ParticleContribution {
        override val id = "sparks"
        override val label = "sparks"
        override fun palette(of: ParticlePalette) = listOf(Color.Yellow)
        override fun moteAt(i: Int, t: Float, w: Float, h: Float) = ParticleMote(i * 3f % w, t % h, 2f, 0.5f, i)
    }

    private fun fields() = ModuleParticleWidgets(listOf(Contributed("looks", "Looks", Field() as ParticleContribution)))

    @Test
    fun `a particle field is a widget of its module with a density prop`() {
        val descriptor = fields().all().values.single()

        assertEquals("looks.particles.sparks", descriptor.kind.value)
        assertEquals("sparks", descriptor.displayName)
        assertTrue(descriptor.removable)
        assertNotNull(descriptor.propsSerializer)
        assertTrue("density" in descriptor.defaultPropsJson)
        assertNull(fields()[WidgetKind("looks.particles.nope")])
    }

    @Test
    fun `a particle field widget draws`() {
        val descriptor = fields().all().values.single()
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(120, 120, density = Density(1f)) {
                NxTheme(Themes.default, dark = true) {
                    descriptor.Render(WidgetInstance(instanceId = "i", kind = descriptor.kind))
                }
            }
            try {
                scene.render(0L)
                scene.render(500_000_000L)
            } finally {
                scene.close()
            }
        }
    }
}
