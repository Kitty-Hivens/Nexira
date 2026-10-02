package hivens.widget.api

import androidx.compose.runtime.Composable
import hivens.widget.model.Entrance
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.WidgetMotion
import kotlin.test.Test
import kotlin.test.assertEquals

/** The same order the plane takes: the instance, then the declaration, then the default. */
class WidgetEntranceResolutionTest {

    private fun descriptor(entrance: Entrance?) = object : WidgetDescriptor {
        override val kind = WidgetKind("test")
        override val displayName = "test"
        override val removable = true
        override val defaultEntrance = entrance
        @Composable override fun Render(instance: WidgetInstance) = Unit
    }

    private fun instance(enter: String?) =
        WidgetInstance(kind = WidgetKind("test"), instanceId = "i1", motion = enter?.let { WidgetMotion(enter = it) })

    @Test
    fun `an instance that says nothing gets its widget's declaration`() {
        assertEquals(Entrance.Rise, descriptor(Entrance.Rise).resolveEntrance(instance(null)))
    }

    @Test
    fun `an instance's own character wins, including none`() {
        assertEquals(Entrance.Settle, descriptor(Entrance.Rise).resolveEntrance(instance("settle")))
        assertEquals(Entrance.None, descriptor(Entrance.Rise).resolveEntrance(instance("none")))
    }

    @Test
    fun `a character this build does not know falls through to the declaration`() {
        assertEquals(Entrance.Rise, descriptor(Entrance.Rise).resolveEntrance(instance("spiral")))
    }

    @Test
    fun `nothing declared and nothing said is the default`() {
        assertEquals(Entrance.DEFAULT, descriptor(null).resolveEntrance(instance(null)))
    }
}
