package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals("home.new:main > c1:body", deep.toString())
    }
}
