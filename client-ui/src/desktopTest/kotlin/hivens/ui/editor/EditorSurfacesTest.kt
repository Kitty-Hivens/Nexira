package hivens.ui.editor

import hivens.ui.Screen
import hivens.widget.model.DefaultLayout
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.updateWidgetProps
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        val home = EditorSurfaces.availableFor(Screen.Home, bundled)
        assertEquals(SurfaceId("home.new"), home.first(), "the centre surface is the default selection")
        assertTrue(SurfaceId("appshell.root") in home, "the shell is editable from every screen")

        val library = EditorSurfaces.availableFor(Screen.Library, bundled)
        assertEquals(SurfaceId("library"), library.first())
        assertTrue(SurfaceId("home.new") !in library, "another screen's centre surface is not mounted here")
    }

    @Test
    fun `a screen with no widget surface still offers the shell`() {
        val settings = EditorSurfaces.availableFor(Screen.Settings, bundled)
        assertTrue(settings.isNotEmpty(), "the shell frames every screen")
        assertTrue(settings.none { it.value.startsWith("home.") }, "no centre surface is mounted on Settings")
    }

    @Test
    fun `a surface the graph does not carry is not offered`() {
        assertEquals(
            emptyList(),
            EditorSurfaces.availableFor(Screen.Home, LayoutGraph.EMPTY),
            "selecting a surface with no slots would open an editor over nothing",
        )
    }

    @Test
    fun `every stub belongs to a surface that declares one`() {
        val declared = EditorSurfaces.all.count { it.stub != null }
        assertEquals(declared, EditorSurfaces.stubs.size, "the spread must carry exactly the declared stubs")
    }

    // ── What is on screen right now ──────────────────────────────────

    private fun collapsed(kind: String): LayoutGraph = bundled.updateWidgetProps(
        SlotPath(SurfaceId("appshell.body"), SlotId("content")),
        instanceId = "appshell-region-$kind-default",
        props = JsonObject(mapOf("collapsed" to JsonPrimitive(true))),
    )

    @Test
    fun `a collapsed rail is reported folded, and unfolded when it comes back`() {
        assertTrue(
            EditorSurfaces.foldedAway(SurfaceId("appshell.rightrail"), collapsed("right")),
            "a rail with no width is not a place to arrange anything: the drop targets are a hairline",
        )
        assertFalse(
            EditorSurfaces.foldedAway(SurfaceId("appshell.rightrail"), bundled),
            "and it is unfolded the moment the rail is",
        )
    }

    @Test
    fun `the left rail answers the same question`() {
        assertTrue(EditorSurfaces.foldedAway(SurfaceId("appshell.leftrail"), collapsed("left")))
        assertFalse(EditorSurfaces.foldedAway(SurfaceId("appshell.leftrail"), bundled))
    }

    @Test
    fun `one rail folding says nothing about the other`() {
        assertFalse(EditorSurfaces.foldedAway(SurfaceId("appshell.rightrail"), collapsed("left")))
        assertFalse(EditorSurfaces.foldedAway(SurfaceId("appshell.leftrail"), collapsed("right")))
    }

    @Test
    fun `nothing but a rail folds`() {
        listOf("home.new", "appshell.topbar", "appshell.root", "appshell.body", "appshell.overlay").forEach {
            assertFalse(EditorSurfaces.foldedAway(SurfaceId(it), collapsed("left")), it)
        }
    }

    @Test
    fun `folding a rail leaves the tab set alone`() {
        // The tabs are what the screen mounts, and folding a rail is not leaving
        // the screen. The editor keys its whole state on this list, so a fold that
        // changed it dropped the reader out of edit mode mid-edit.
        assertEquals(
            EditorSurfaces.availableFor(Screen.Home, bundled),
            EditorSurfaces.availableFor(Screen.Home, collapsed("right")),
        )
        assertTrue(SurfaceId("appshell.leftrail") in EditorSurfaces.availableFor(Screen.Home, collapsed("left")))
    }

    // ── A region's own settings, from the region's own tab ───────────

    @Test
    fun `each rail and the top bar resolve to the region that holds them`() {
        val cases = mapOf(
            "appshell.leftrail" to "appshell-region-left-default",
            "appshell.rightrail" to "appshell-region-right-default",
            "appshell.topbar" to "appshell-region-top-default",
            "home.new" to "appshell-region-center-default",
        )
        cases.forEach { (surface, expected) ->
            val owner = EditorSurfaces.ownerRegionOf(SurfaceId(surface), bundled)
            assertEquals(expected, owner?.second, "$surface should reach its own region's settings")
        }
    }

    @Test
    fun `the two frames are searched, not assumed`() {
        // The top bar sits in the window's column and the rails in its row. Both
        // resolve, which is the whole point of looking in both rather than
        // writing the frame's shape down a fourth time.
        val top = EditorSurfaces.ownerRegionOf(SurfaceId("appshell.topbar"), bundled)
        val rail = EditorSurfaces.ownerRegionOf(SurfaceId("appshell.rightrail"), bundled)
        assertEquals(SurfaceId("appshell.root"), top?.first?.surface)
        assertEquals(SurfaceId("appshell.body"), rail?.first?.surface)
    }

    @Test
    fun `a surface that is nobody's inside resolves to nothing`() {
        assertNull(
            EditorSurfaces.ownerRegionOf(SurfaceId("appshell.overlay"), bundled),
            "the overlay lane is a lane inside the centre, not a region with settings of its own",
        )
        assertNull(EditorSurfaces.ownerRegionOf(SurfaceId("appshell.body"), bundled))
    }
}
