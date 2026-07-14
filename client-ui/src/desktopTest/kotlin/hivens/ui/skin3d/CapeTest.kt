package hivens.ui.skin3d

import hivens.ui.render3d.Texture
import hivens.ui.scene3d.Transform3
import hivens.ui.scene3d.UvRect
import hivens.ui.scene3d.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapeTest {

    private fun tex(w: Int = 64, h: Int = 32) = Texture(IntArray(w * h) { -0x1 }, width = w, height = h)

    private fun close(a: Float, b: Float, eps: Float = 1e-3f) = kotlin.math.abs(a - b) <= eps

    @Test fun `cape faces map the standard 64x32 regions`() {
        val faces = buildCapeNode(tex()).meshes.single().faces
        val expected = setOf(
            UvRect(1f, 1f, 10f, 16f),    // outside
            UvRect(12f, 1f, 10f, 16f),   // inside
            UvRect(0f, 1f, 1f, 16f),     // right edge
            UvRect(11f, 1f, 1f, 16f),    // left edge
            UvRect(1f, 0f, 10f, 1f),     // top
            // Bottom region (11,0)-(21,1), V-flipped per the Box rule: the
            // origin sits at the region's far edge with negative height.
            UvRect(11f, 1f, 10f, -1f),
        )
        assertEquals(expected, faces.map { it.uv }.toSet())
        assertTrue(faces.none { it.layer }, "the cape is base geometry, no overlay bias")
    }

    @Test fun `uvScale follows HD capes and falls back to 1 for legacy`() {
        assertEquals(1f, buildCapeNode(tex(64, 32)).meshes.single().uvScale)
        assertEquals(2f, buildCapeNode(tex(128, 64)).meshes.single().uvScale)
        assertEquals(1f, buildCapeNode(tex(22, 17)).meshes.single().uvScale)
    }

    @Test fun `the cape hangs behind the body from the shoulder line`() {
        val node = buildCapeNode(tex())
        val corners = node.meshes.single().faces.flatMap { f ->
            listOf(f.p0, f.pu, f.pv, Vec3(f.pu.x + f.pv.x - f.p0.x, f.pu.y + f.pv.y - f.p0.y, f.pu.z + f.pv.z - f.p0.z))
        }.map { node.transform.apply(it) }
        assertTrue(corners.all { it.z <= -2.5f + 1e-3f }, "everything stays behind the jacket plane")
        assertTrue(corners.any { close(it.y, 8f, eps = 0.02f) }, "top edge sits at the shoulder line")
        val bottom = corners.minBy { it.y }
        assertTrue(bottom.y < -7f, "hangs roughly a body's length down, got ${bottom.y}")
        assertTrue(bottom.z < -4f, "the tilt swings the bottom away from the legs, got ${bottom.z}")
    }

    @Test fun `attached to the body node the cape follows torso posing`() {
        val rig = buildRig(SkinModel.Classic, legacy = false, texture = tex(64, 64))
        val cape = buildCapeNode(tex())
        rig.node(BodyPart.Body).attach(cape)

        fun capeBottomWorld(): Vec3 {
            val body = rig.node(BodyPart.Body)
            val world = rig.root.transform * body.transform * cape.transform
            // Local bottom-center of the hanging box.
            return world.apply(Vec3(0f, -16f, 0.5f))
        }

        rig.apply(Pose.IDENTITY)
        val rest = capeBottomWorld()
        rig.apply(Pose(body = PartAngles(pitch = 0.6f)))
        val posed = capeBottomWorld()
        assertTrue(!close(rest.z, posed.z, eps = 0.5f), "body pitch must swing the cape (z $rest -> $posed)")

        // Rigid under the body transform: distance to the body pivot is preserved.
        val pivot = Vec3(0f, 8f, 0f)
        fun dist(a: Vec3, b: Vec3): Float {
            val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }
        assertTrue(close(dist(rest, pivot), dist(posed, pivot), eps = 1e-2f))
    }

    @Test fun `detaching the cape restores the bare rig`() {
        val rig = buildRig(SkinModel.Classic, legacy = false, texture = tex(64, 64))
        val body = rig.node(BodyPart.Body)
        val before = body.children.size
        val cape = buildCapeNode(tex())
        body.attach(cape)
        assertEquals(before + 1, body.children.size)
        body.detach(cape)
        assertEquals(before, body.children.size)
    }

    @Test fun `the outside region faces the world behind the model`() {
        // The Ry(pi) flip must land the outside UV region (1,1 10x16) on the
        // face whose outward normal points to world -z (visible from behind).
        val node = buildCapeNode(tex(), tiltRad = 0f)
        val outside = node.meshes.single().faces.single { it.uv == UvRect(1f, 1f, 10f, 16f) }
        val p0 = node.transform.apply(outside.p0)
        val pu = node.transform.apply(outside.pu)
        val pv = node.transform.apply(outside.pv)
        val ux = pu.x - p0.x; val uy = pu.y - p0.y; val uz = pu.z - p0.z
        val vx = pv.x - p0.x; val vy = pv.y - p0.y; val vz = pv.z - p0.z
        // Texture UxV points INWARD on an outward face (see the winding test);
        // inward for the world-back face is +z.
        val nz = ux * vy - uy * vx
        assertTrue(nz > 0f, "outside face normal flipped: inward z=$nz")
    }
}
