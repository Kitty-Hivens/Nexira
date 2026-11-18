package hivens.look.motion.nx.controls

import hivens.look.motion.nx.MotionNxKeys
import hivens.widget.api.KeyContribution
import hivens.widget.model.Entrance
import hivens.widget.model.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reduce motion switch is the nx motion provider's control: a widget of this module, shown only
 * while nx draws the motion, already there when its page opens, and named in every language the module
 * ships.
 */
class MotionControlsTest {

    @Test
    fun `the switch is this module's one widget, shown while nx draws the motion and already in place`() {
        val widgets = MotionNxControls.widgets.all()
        assertEquals(setOf(WidgetKind("motion.reduce")), widgets.keys)
        val reduce = assertNotNull(widgets[WidgetKind("motion.reduce")])
        assertEquals(NX_MOTION_DRAWING_ID, reduce.shownWhen)
        assertEquals(Entrance.None, reduce.defaultEntrance)
        assertEquals(emptyList(), reduce.needs, "a need with no answer draws the misplaced placeholder, where the switch is to be absent")
    }

    @Test
    fun `it brings the source its widget is shown under and steers the provider through the provider's own keys`() {
        assertEquals(listOf<Any>(MotionControlsKeys), MotionNxControls.contributions)
        assertTrue(MotionNxControls.contributions.all { it is KeyContribution })
        assertEquals("motion.nx.drawing", NxMotionDrawing.id)
        assertEquals("motion.nx.reduce", MotionNxKeys.ReduceMotion.id)
        assertEquals("motion.nx.reduce.set", MotionNxKeys.SetReduceMotion.id)
    }

    @Test
    fun `its words are there in every language it ships`() {
        val keys = setOf("controls.name", "settingsReduceMotion", "settingsReduceMotionDesc", "widget.motion.reduce")
        val tables = MotionNxControls.strings.tables
        assertEquals(setOf("de", "en", "ja", "ru"), tables.keys)
        tables.forEach { (language, table) -> assertTrue(keys.all { it in table }, "$language misses ${keys - table.keys}") }
    }
}
