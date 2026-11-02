package hivens.ui.widgets.modules

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.modules.WidgetModules.Contributed
import hivens.widget.api.ModuleStrings
import hivens.widget.api.ParticleContribution
import hivens.widget.api.ParticleMote
import hivens.widget.api.ParticlePalette
import hivens.widget.api.ThemeContribution
import hivens.widget.api.ThemeScheme
import hivens.widget.model.WidgetInstance
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a module's themes and particle fields become inside the launcher. */
@OptIn(ExperimentalComposeUiApi::class)
class ModuleContributionsTest {

    private val strings = ModuleStrings(mapOf("en" to mapOf("night" to "Night"), "ru" to mapOf("night" to "Ночь")))

    private class Theme1(override val dark: ThemeScheme, override val light: ThemeScheme? = null) : ThemeContribution {
        override val id = "night"
        override val label = "night"
    }

    private val good = ThemeScheme(listOf(Color(0xFF101010), Color(0xFF202020)), listOf(Color.Red))

    private fun theme(dark: ThemeScheme = good, light: ThemeScheme? = null) =
        Contributed("looks", "Looks", Theme1(dark, light) as ThemeContribution, strings)

    @Test
    fun `a module's theme takes its module's id and is named in the person's language`() {
        val themes = listOf(theme()).toThemes("ru")

        assertEquals("looks/night", themes.single().id)
        assertEquals("Ночь", themes.single().name)
        assertEquals("Night", listOf(theme()).toThemes("ja").single().name)
    }

    @Test
    fun `a theme with no inks of its own reads in the launcher's`() {
        val scheme = listOf(theme()).toThemes("en").single().dark

        assertEquals(hivens.ui.theme.Themes.Celestia.dark.inks, scheme.inks)
    }

    @Test
    fun `a theme without a light scheme stays dark`() {
        assertEquals(false, listOf(theme()).toThemes("en").single().hasLight)
        val withLight = listOf(theme(light = good)).toThemes("en").single()

        assertTrue(withLight.hasLight)
    }

    @Test
    fun `a theme that cannot make a scheme costs only itself`() {
        val broken = theme(dark = ThemeScheme(listOf(Color.Black), listOf(Color.Red)))

        val themes = listOf(broken, theme()).toThemes("en")

        assertEquals(1, themes.size)
    }

    private class Field : ParticleContribution {
        override val id = "sparks"
        override val label = "sparks"
        override fun palette(of: ParticlePalette) = listOf(Color.Yellow)
        override fun moteAt(i: Int, t: Float, w: Float, h: Float) = ParticleMote(i * 3f % w, t % h, 2f, 0.5f, i)
    }

    private fun fields() = ModuleParticleWidgets(listOf(Contributed("looks", "Looks", Field() as ParticleContribution, strings)))

    @Test
    fun `a particle field is a widget of its module with a density prop`() {
        val descriptor = fields().all().values.single()

        assertEquals("looks.particles.sparks", descriptor.kind.value)
        assertEquals("sparks", descriptor.displayName)
        assertTrue(descriptor.removable)
        assertNotNull(descriptor.propsSerializer)
        assertTrue("density" in descriptor.defaultPropsJson)
        assertNull(fields()[hivens.widget.model.WidgetKind("looks.particles.nope")])
    }

    @Test
    fun `a particle field widget draws`() {
        val descriptor = fields().all().values.single()
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(120, 120, density = Density(1f)) {
                NxTheme(dark = true) {
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
