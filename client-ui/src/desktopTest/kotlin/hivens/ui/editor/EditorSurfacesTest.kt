package hivens.ui.editor

import hivens.core.data.HomeView
import hivens.ui.Screen
import hivens.widget.model.DefaultLayout
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SurfaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registry replaced six hardcoded spots that had to move in lockstep, so
 * what is worth pinning is that it stays in step with the layout it describes.
 * A surface present in one and absent from the other is exactly the drift the
 * registry exists to prevent -- and it is invisible at runtime, since a missing
 * spec degrades to a raw id in the picker rather than to an error.
 */
class EditorSurfacesTest {

    private val bundled = DefaultLayout.load()

    @Test
    fun `every bundled surface has a spec`() {
        val missing = bundled.surfaces.keys.filter { EditorSurfaces.spec(it) == null }
        assertEquals(
            emptyList(),
            missing,
            "these surfaces ship in the default layout but the editor has no name or icon for them",
        )
    }

    @Test
    fun `no spec describes a surface that does not ship`() {
        val orphans = EditorSurfaces.all.map { it.id }.filterNot { it in bundled.surfaces.keys }
        assertEquals(
            emptyList(),
            orphans,
            "these specs describe surfaces absent from the default layout, so nothing can select them",
        )
    }

    @Test
    fun `the screen's own surface leads, the shell follows`() {
        val classic = EditorSurfaces.availableFor(Screen.Home, HomeView.Classic, bundled)
        assertEquals(SurfaceId("home.classic"), classic.first(), "the centre surface is the default selection")
        assertTrue(SurfaceId("appshell.root") in classic, "the shell is editable from every screen")
        assertTrue(SurfaceId("home.new") !in classic, "the other home view is not mounted here")

        val new = EditorSurfaces.availableFor(Screen.Home, HomeView.New, bundled)
        assertEquals(SurfaceId("home.new"), new.first())
    }

    @Test
    fun `a screen with no widget surface still offers the shell`() {
        val settings = EditorSurfaces.availableFor(Screen.Settings, HomeView.New, bundled)
        assertTrue(settings.isNotEmpty(), "the shell frames every screen")
        assertTrue(settings.none { it.value.startsWith("home.") }, "no centre surface is mounted on Settings")
    }

    @Test
    fun `a surface the graph does not carry is not offered`() {
        assertEquals(
            emptyList(),
            EditorSurfaces.availableFor(Screen.Home, HomeView.New, LayoutGraph.EMPTY),
            "selecting a surface with no slots would open an editor over nothing",
        )
    }

    @Test
    fun `every stub belongs to a surface that declares one`() {
        val declared = EditorSurfaces.all.count { it.stub != null }
        assertEquals(declared, EditorSurfaces.stubs.size, "the spread must carry exactly the declared stubs")
    }
}
