package hivens.ui.scene3d

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue

// Pins the view-step sign conventions rotate/project carry as the pipeline's
// oracle: yaw around Y, then pitch around X, +Z toward the viewer, screen Y
// growing downward.
class ProjectionTest {

    private val halfPi = (PI / 2).toFloat()

    private fun close(a: Float, b: Float, eps: Float = 1e-3f) = kotlin.math.abs(a - b) <= eps

    @Test fun `identity rotation leaves a point unchanged`() {
        val p = rotate(Vec3(1f, 2f, 3f), 0f, 0f)
        assertTrue(close(p.x, 1f) && close(p.y, 2f) && close(p.z, 3f))
    }

    @Test fun `yaw of 90 degrees turns the front (+z) toward +x`() {
        val p = rotate(Vec3(0f, 0f, 1f), halfPi, 0f)
        assertTrue(close(p.x, 1f), "x=${p.x}")
        assertTrue(close(p.y, 0f) && close(p.z, 0f), "p=$p")
    }

    @Test fun `orthographic project flips model +Y to screen up`() {
        val s = project(Vec3(0f, 1f, 0f), scale = 10f, centerX = 100f, centerY = 100f)
        assertTrue(close(s.x, 100f) && close(s.y, 90f), "s=$s")
    }
}
