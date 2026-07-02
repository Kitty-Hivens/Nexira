package hivens.ui.scene3d

import hivens.ui.skin3d.rotate
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue

class Transform3Test {

    private val halfPi = (PI / 2).toFloat()

    private fun close(a: Float, b: Float, eps: Float = 1e-4f) = kotlin.math.abs(a - b) <= eps
    private fun assertVec(expected: Vec3, actual: Vec3, eps: Float = 1e-4f) {
        assertTrue(
            close(expected.x, actual.x, eps) && close(expected.y, actual.y, eps) && close(expected.z, actual.z, eps),
            "expected $expected, got $actual",
        )
    }

    private val samplePoints = listOf(
        Vec3(1f, 2f, 3f), Vec3(-4f, 8f, -2f), Vec3(0f, -16f, 4f), Vec3(5.5f, 0f, -0.5f),
    )

    @Test fun `identity leaves points unchanged`() {
        for (p in samplePoints) assertVec(p, Transform3.IDENTITY.apply(p))
    }

    @Test fun `translate offsets points`() {
        assertVec(Vec3(3f, 1f, -2f), Transform3.translate(2f, -1f, -5f).apply(Vec3(1f, 2f, 3f)))
    }

    @Test fun `scale multiplies points`() {
        assertVec(Vec3(2f, 4f, 6f), Transform3.scale(2f).apply(Vec3(1f, 2f, 3f)))
    }

    @Test fun `rotateY of 90 degrees turns +z toward +x`() {
        assertVec(Vec3(1f, 0f, 0f), Transform3.rotateY(halfPi).apply(Vec3(0f, 0f, 1f)))
    }

    @Test fun `rotateX of 90 degrees turns +y toward +z`() {
        assertVec(Vec3(0f, 0f, 1f), Transform3.rotateX(halfPi).apply(Vec3(0f, 1f, 0f)))
    }

    @Test fun `rotateZ of 90 degrees turns +x toward +y`() {
        assertVec(Vec3(0f, 1f, 0f), Transform3.rotateZ(halfPi).apply(Vec3(1f, 0f, 0f)))
    }

    @Test fun `composition equals sequential application`() {
        val a = Transform3.translate(3f, -1f, 2f) * Transform3.rotateZ(0.7f)
        val b = Transform3.rotateY(1.3f) * Transform3.scale(1.5f) * Transform3.translate(0f, 4f, -2f)
        for (p in samplePoints) assertVec(a.apply(b.apply(p)), (a * b).apply(p))
    }

    @Test fun `aboutPivot keeps the pivot fixed`() {
        val pivot = Vec3(-5f, 6f, 0f)
        val t = Transform3.aboutPivot(pivot, Transform3.rotateX(0.9f) * Transform3.rotateZ(-0.4f))
        assertVec(pivot, t.apply(pivot))
    }

    @Test fun `affine transforms preserve the implied fourth corner`() {
        // Face carries only three corners; the fourth is p0 + (pu-p0) + (pv-p0).
        // Any affine must keep that identity or posed quads stop being
        // parallelograms and the two-triangle emission tears.
        val m = Transform3.translate(1f, 2f, 3f) *
            Transform3.aboutPivot(Vec3(0f, 8f, 0f), Transform3.rotateX(0.6f) * Transform3.rotateY(-1.1f)) *
            Transform3.scale(0.8f)
        val p0 = Vec3(-4f, 8f, 4f); val pu = Vec3(4f, 8f, 4f); val pv = Vec3(-4f, 0f, 4f)
        val implied = Vec3(pu.x + pv.x - p0.x, pu.y + pv.y - p0.y, pu.z + pv.z - p0.z)
        val mapped = m.apply(implied)
        val recomposed = Vec3(
            m.apply(pu).x + m.apply(pv).x - m.apply(p0).x,
            m.apply(pu).y + m.apply(pv).y - m.apply(p0).y,
            m.apply(pu).z + m.apply(pv).z - m.apply(p0).z,
        )
        assertVec(mapped, recomposed)
    }

    @Test fun `rotateX times rotateY matches the skin projection rotate oracle`() {
        // rotate(p, yaw, pitch) is the shipped convention pin (yaw around Y,
        // then pitch around X, +Z toward the viewer). The matrix path must
        // agree with it at every angle or the two pipelines drift apart.
        val angles = listOf(0f, 0.5f, -0.7f, 1.2f, 2.9f, -2.2f)
        for (yaw in angles) for (pitch in angles) {
            val m = Transform3.rotateX(pitch) * Transform3.rotateY(yaw)
            for (p in samplePoints) {
                assertVec(rotate(p, yaw, pitch), m.apply(p), eps = 1e-3f)
            }
        }
    }
}
