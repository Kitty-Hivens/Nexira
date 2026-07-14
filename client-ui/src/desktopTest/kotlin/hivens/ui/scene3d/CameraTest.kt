package hivens.ui.scene3d

import hivens.ui.render3d.Texture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraTest {

    private fun tex() = Texture(intArrayOf(-0x1), width = 1, height = 1)

    private fun unitFace() = Face(
        p0 = Vec3(0f, 1f, 0f), pu = Vec3(1f, 1f, 0f), pv = Vec3(0f, 0f, 0f),
        uv = UvRect(0f, 0f, 1f, 1f), layer = false,
    )

    private fun close(a: Float, b: Float, eps: Float = 1e-3f) = kotlin.math.abs(a - b) <= eps

    @Test fun `worldBounds accumulates node transforms`() {
        val root = Node(transform = Transform3.translate(10f, -2f, 3f))
        root.attach(Node(meshes = listOf(Mesh(listOf(unitFace()), tex()))))
        val b = worldBounds(root)
        assertTrue(close(b.min.x, 10f) && close(b.max.x, 11f), "x ${b.min.x}..${b.max.x}")
        assertTrue(close(b.min.y, -2f) && close(b.max.y, -1f), "y ${b.min.y}..${b.max.y}")
        assertTrue(close(b.min.z, 3f) && close(b.max.z, 3f), "z ${b.min.z}..${b.max.z}")
    }

    @Test fun `worldBounds of an empty scene collapses to the origin`() {
        assertEquals(Bounds3(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f)), worldBounds(Node()))
    }

    @Test fun `fitOrtho keeps every bounds corner inside the viewport margin`() {
        val bounds = Bounds3(Vec3(-11f, -16f, -4.5f), Vec3(11f, 18f, 4.5f))
        val w = 200f; val h = 300f; val margin = 0.1f
        for (yaw in listOf(0f, 0.7f, 2.4f)) {
            for (pitch in listOf(-0.4f, 0f, 0.9f)) {
                val cam = fitOrtho(bounds, yaw, pitch, w, h, margin)
                for (x in listOf(bounds.min.x, bounds.max.x)) {
                    for (y in listOf(bounds.min.y, bounds.max.y)) {
                        for (z in listOf(bounds.min.z, bounds.max.z)) {
                            val s = project(rotate(Vec3(x, y, z), yaw, pitch), cam.scale, cam.centerX, cam.centerY)
                            assertTrue(
                                s.x >= w * margin - 0.5f && s.x <= w * (1f - margin) + 0.5f &&
                                    s.y >= h * margin - 0.5f && s.y <= h * (1f - margin) + 0.5f,
                                "corner ($x,$y,$z) lands at (${s.x},${s.y}) yaw=$yaw pitch=$pitch",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test fun `renderScene equals a manual flatten-and-rasterize`() {
        val root = Node(meshes = listOf(Mesh(listOf(unitFace()), tex())))
        val cam = OrthoCamera(yaw = 0.5f, pitch = -0.2f, scale = 8f, centerX = 16f, centerY = 16f)
        val manual = hivens.ui.render3d.rasterize(
            collectTriBatches(root, cam.yaw, cam.pitch, cam.scale, cam.centerX, cam.centerY),
            32, 32,
        )
        assertTrue(renderScene(root, cam, 32, 32).contentEquals(manual))
    }
}
