package hivens.widget.api

import androidx.compose.runtime.Composable
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which plane a widget draws, and who gets the last word about it.
 *
 * It used to depend on how the widget reached the layout: dropped from the palette
 * it carried its declaration, shipped in the bundled layout it carried nothing, so
 * the bundled file repeated JSON the widget already had. The two are one order now,
 * the same one the props take.
 */
class WidgetSurfaceResolutionTest {

    private val declared = SurfaceSpec(fill = "raised", opacity = 0.4f)

    private fun descriptor(surface: SurfaceSpec?) = object : WidgetDescriptor {
        override val kind = WidgetKind("test")
        override val displayName = "test"
        override val removable = true
        override val defaultSurface = surface
        @Composable override fun Render(instance: WidgetInstance) = Unit
    }

    private fun instance(surface: SurfaceSpec?) =
        WidgetInstance(kind = WidgetKind("test"), instanceId = "i1", surface = surface)

    @Test
    fun `an instance that says nothing gets its widget's declaration`() {
        assertEquals(declared, descriptor(declared).resolveSurface(instance(null)))
    }

    @Test
    fun `an instance's own plane wins over the declaration`() {
        val own = SurfaceSpec(fill = "#FF102030", opacity = 1f)
        assertEquals(own, descriptor(declared).resolveSurface(instance(own)))
    }

    /**
     * The case that keeps the rule usable: a plane can still be turned off. It is a
     * named zero rather than a removed record, because absence has to keep meaning
     * "nothing said" for the declaration to be reachable through it.
     */
    @Test
    fun `a named zero opacity is an instance's plane, not an absence`() {
        val off = SurfaceSpec(opacity = 0f)
        assertEquals(off, descriptor(declared).resolveSurface(instance(off)))
    }

    @Test
    fun `a widget that declares nothing and an instance that says nothing draw no plane`() {
        assertNull(descriptor(null).resolveSurface(instance(null)))
    }

    @Test
    fun `an instance can give a plane to a widget that declares none`() {
        val own = SurfaceSpec(fill = "base")
        assertEquals(own, descriptor(null).resolveSurface(instance(own)))
    }
}
