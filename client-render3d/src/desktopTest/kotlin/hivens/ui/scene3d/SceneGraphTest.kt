package hivens.ui.scene3d

import hivens.ui.render3d.Texture
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SceneGraphTest {

    private fun tex() = Texture(intArrayOf(-0x1), width = 1, height = 1)

    // A unit quad in the z=0 plane: p0 top-left at (0,1,0), pu (1,1,0), pv (0,0,0).
    private fun unitFace(layer: Boolean = false) = Face(
        p0 = Vec3(0f, 1f, 0f), pu = Vec3(1f, 1f, 0f), pv = Vec3(0f, 0f, 0f),
        uv = UvRect(2f, 3f, 4f, 5f), layer = layer,
    )

    private fun close(a: Float, b: Float, eps: Float = 1e-4f) = kotlin.math.abs(a - b) <= eps

    // Head-on identity view (yaw=0, pitch=0, scale=1, center 0,0): screen x =
    // world x, screen y = -world y, depth = world z. Lets expectations read off
    // the world coordinates directly.
    private fun flatten(root: Node) = collectTriBatches(root, 0f, 0f, 1f, 0f, 0f)

    // ── tree mechanics ───────────────────────────────────────────────────────

    @Test fun `attach reparents from the previous parent`() {
        val a = Node(); val b = Node(); val child = Node()
        a.attach(child)
        b.attach(child)
        assertTrue(a.children.isEmpty(), "old parent keeps no stale child")
        assertEquals(listOf(child), b.children)
        assertSame(b, child.parent)
    }

    @Test fun `detach clears the parent`() {
        val a = Node(); val child = Node()
        a.attach(child)
        a.detach(child)
        assertTrue(a.children.isEmpty())
        assertNull(child.parent)
    }

    @Test fun `attaching an ancestor throws`() {
        val root = Node(); val mid = Node(); val leaf = Node()
        root.attach(mid)
        mid.attach(leaf)
        assertFailsWith<IllegalArgumentException> { leaf.attach(root) }
        assertFailsWith<IllegalArgumentException> { root.attach(root) }
    }

    // ── traversal + transforms ───────────────────────────────────────────────

    @Test fun `world transform accumulates parent to child`() {
        val root = Node(transform = Transform3.translate(10f, 0f, 0f))
        val child = Node(
            transform = Transform3.translate(0f, 5f, 0f),
            meshes = listOf(Mesh(listOf(unitFace()), tex())),
        )
        root.attach(child)
        val tris = flatten(root).single().tris
        // p0 (0,1,0) -> world (10,6,0) -> screen (10,-6).
        val v0 = tris.first().a
        assertTrue(close(v0.x, 10f) && close(v0.y, -6f), "v0=(${v0.x}, ${v0.y})")
    }

    @Test fun `each face emits two opaque double-sided triangles regardless of orientation`() {
        val node = Node(meshes = listOf(Mesh(listOf(unitFace()), tex())))
        // Seen from behind (yaw = pi) the face must still be emitted -- the
        // rasterizer normalizes winding; culling is deliberately absent.
        val fromBehind = collectTriBatches(node, PI.toFloat(), 0f, 1f, 0f, 0f).single().tris
        assertEquals(2, fromBehind.size)
        assertTrue(fromBehind.all { it.opaque }, "cutout layers draw through the opaque pass")
    }

    @Test fun `batches group by texture identity in first-seen order`() {
        val skin = tex(); val cape = tex()
        val root = Node(
            meshes = listOf(
                Mesh(listOf(unitFace()), skin),
                Mesh(listOf(unitFace()), cape),
                Mesh(listOf(unitFace()), skin),   // same texture instance -> first batch
            ),
        )
        val batches = flatten(root)
        assertEquals(2, batches.size)
        assertSame(skin, batches[0].texture)
        assertSame(cape, batches[1].texture)
        assertEquals(4, batches[0].tris.size, "both skin meshes land in one batch, traversal order")
        assertEquals(2, batches[1].tris.size)
    }

    @Test fun `uvScale converts 1x UV rects to native texels`() {
        val node = Node(meshes = listOf(Mesh(listOf(unitFace()), tex(), uvScale = 2f)))
        val v = flatten(node).single().tris.first().a
        // Texture top-left of UvRect(2,3,4,5) at k=2 -> (4,6).
        assertTrue(close(v.tu, 4f) && close(v.tv, 6f), "uv=(${v.tu}, ${v.tv})")
    }

    @Test fun `overlay faces get the depth bias, base faces do not`() {
        val node = Node(
            meshes = listOf(Mesh(listOf(unitFace(layer = false), unitFace(layer = true)), tex())),
        )
        val tris = flatten(node).single().tris
        val base = tris[0].a.z
        val overlay = tris[2].a.z
        assertTrue(close(overlay - base, 0.02f), "bias=${overlay - base}")
    }

    @Test fun `corner positions and UVs match the rotate-project oracle`() {
        val yaw = 0.6f; val pitch = -0.35f; val scale = 7f; val cx = 50f; val cy = 80f
        val f = unitFace()
        val node = Node(meshes = listOf(Mesh(listOf(f), tex())))
        val tris = collectTriBatches(node, yaw, pitch, scale, cx, cy).single().tris

        val s0 = project(rotate(f.p0, yaw, pitch), scale, cx, cy)
        val su = project(rotate(f.pu, yaw, pitch), scale, cx, cy)
        val p3 = Vec3(f.pu.x + f.pv.x - f.p0.x, f.pu.y + f.pv.y - f.p0.y, f.pu.z + f.pv.z - f.p0.z)
        val s3 = project(rotate(p3, yaw, pitch), scale, cx, cy)

        val (a, b, c) = tris.first()
        assertTrue(close(a.x, s0.x) && close(a.y, s0.y), "a=(${a.x}, ${a.y}) vs $s0")
        assertTrue(close(b.x, su.x) && close(b.y, su.y), "b=(${b.x}, ${b.y}) vs $su")
        assertTrue(close(c.x, s3.x) && close(c.y, s3.y), "c=(${c.x}, ${c.y}) vs $s3")
        // Corner UVs: TL, TR, BR of UvRect(2,3,4,5) at uvScale 1.
        assertTrue(close(a.tu, 2f) && close(a.tv, 3f))
        assertTrue(close(b.tu, 6f) && close(b.tv, 3f))
        assertTrue(close(c.tu, 6f) && close(c.tv, 8f))
    }
}
