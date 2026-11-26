package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SlotPathTest {

    private val surface = SurfaceId("home.new")
    private val body = SlotId("body")
    private val main = SlotId("main")

    @Test
    fun `root path has no nested segments`() {
        val path = SlotPath(surface, main)
        assertEquals(emptyList(), path.nested)
        assertEquals(main, path.leafSlot)
        assertEquals(SlotAddress(surface, main), path.leafAddress)
        assertNull(path.parentPath, "root path has no parent")
    }

    @Test
    fun `child appends a segment and bumps leafSlot`() {
        val root = SlotPath(surface, main)
        val nested = root.child("container1", body)
        assertEquals(1, nested.nested.size)
        assertEquals("container1", nested.nested[0].parentInstanceId)
        assertEquals(body, nested.leafSlot)
        assertEquals(SlotAddress(surface, body), nested.leafAddress)
    }

    @Test
    fun `parentPath of a one-level-deep path is the root path`() {
        val root = SlotPath(surface, main)
        val nested = root.child("container1", body)
        assertEquals(root, nested.parentPath)
    }

    @Test
    fun `parentPath of a two-level-deep path drops the deepest hop`() {
        val deep = SlotPath(surface, main)
            .child("container1", body)
            .child("container2", SlotId("nested"))
        val expectedParent = SlotPath(surface, main).child("container1", body)
        assertEquals(expectedParent, deep.parentPath)
    }

    @Test
    fun `equality holds across structurally identical paths`() {
        val a = SlotPath(surface, main).child("c1", body)
        val b = SlotPath(surface, main).child("c1", body)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equality fails when nested chains differ`() {
        val a = SlotPath(surface, main).child("c1", body)
        val b = SlotPath(surface, main).child("c2", body)
        kotlin.test.assertNotEquals(a, b)
    }

    @Test
    fun `SlotAddress toPath bridges to a root SlotPath`() {
        val addr = SlotAddress(surface, main)
        val path = addr.toPath()
        assertEquals(surface, path.surface)
        assertEquals(main, path.rootSlot)
        assertEquals(emptyList(), path.nested)
    }

    @Test
    fun `toString format is path-readable for diagnostics`() {
        val deep = SlotPath(surface, main).child("c1", body)
        assertEquals("home.new/general:main > c1:body", deep.toString())
    }

    @Test
    fun `the family is part of the address and survives a descent`() {
        val project = FamilyId("projectView")
        val deep = SlotPath(surface, main, family = project).child("c1", body)
        assertEquals(project, deep.family)
        assertEquals("home.new/projectView:main > c1:body", deep.toString())
    }

    @Test
    fun `two paths that differ only in family are different paths`() {
        val general = SlotPath(surface, main)
        val project = SlotPath(surface, main, family = FamilyId("projectView"))
        kotlin.test.assertNotEquals(general, project)
    }

    @Test
    fun `a slot path and its node path name the same slot`() {
        val deep = SlotPath(surface, main, family = FamilyId("projectView")).child("c1", body)
        assertEquals(deep, deep.toNodePath().slotPath)
        assertEquals(deep.child("c2", main).toNodePath(), deep.toNodePath().child("c2", main))
    }

    @Test
    fun `two node paths are equal exactly where their slot paths are`() {
        val a = SlotPath(surface, main).child("c1", body)
        assertEquals(a.toNodePath(), SlotPath(surface, main).child("c1", body).toNodePath())
        assertNotEquals(a.toNodePath(), SlotPath(surface, main).child("c2", body).toNodePath())
        assertNotEquals(a.toNodePath(), SlotPath(surface, main, family = FamilyId("projectView")).child("c1", body).toNodePath())
    }

    @Test
    fun `a path into a document has no slot path`() {
        assertNull(NodePath(CompositionName("launcher"), NodeRoot.Residents).slotPath)
        assertNull(NodePath(CompositionName("launcher"), NodeRoot.Layout(ConfigId("home"), LayoutId("main"))).child("c1", body).slotPath)
    }

    @Test
    fun `a document holds nothing under a surface and is not edited there`() {
        val launcher = CompositionName("")
        val document = Document(
            start = launcher,
            compositions = mapOf(launcher to Composition(start = ConfigId("home"), residents = SlotContent(widgets = listOf(WidgetInstance(WidgetKind("a"), "a"))))),
        )
        val path = SlotPath(surface, main).toNodePath()
        assertNull(document.contentAt(path))
        assertSame(document, document.edit(path) { SlotContent() })
    }
}
