package hivens.look.surface.nx.controls

import hivens.look.surface.nx.SurfaceNxKeys
import hivens.widget.api.KeyContribution
import hivens.widget.model.Entrance
import hivens.widget.model.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The blur switch is the nx surface provider's control: a widget of this module, shown only while nx
 * draws the planes, already there when its page opens, and named in every language the module ships.
 */
class SurfaceControlsTest {

    @Test
    fun `the switch is this module's one widget, shown while nx draws the planes and already in place`() {
        val widgets = SurfaceNxControls.widgets.all()
        assertEquals(setOf(WidgetKind("surface.blur")), widgets.keys)
        val blur = assertNotNull(widgets[WidgetKind("surface.blur")])
        assertEquals(NX_SURFACE_DRAWING_ID, blur.shownWhen)
        assertEquals(Entrance.None, blur.defaultEntrance)
        assertEquals(emptyList(), blur.needs, "a need with no answer draws the misplaced placeholder, where the switch is to be absent")
    }

    @Test
    fun `it brings the source its widget is shown under and steers the provider through the provider's own keys`() {
        assertEquals(listOf<Any>(SurfaceControlsKeys), SurfaceNxControls.contributions)
        assertTrue(SurfaceNxControls.contributions.all { it is KeyContribution })
        assertEquals("surface.nx.drawing", NxSurfaceDrawing.id)
        assertEquals("surface.nx.blur", SurfaceNxKeys.Blur.id)
        assertEquals("surface.nx.blur.set", SurfaceNxKeys.SetBlur.id)
    }

    @Test
    fun `its words are there in every language it ships`() {
        val keys = setOf("controls.name", "settingsSurfaceBlur", "settingsSurfaceBlurDesc", "widget.surface.blur")
        val tables = SurfaceNxControls.strings.tables
        assertEquals(setOf("de", "en", "ja", "ru"), tables.keys)
        tables.forEach { (language, table) -> assertTrue(keys.all { it in table }, "$language misses ${keys - table.keys}") }
    }
}
