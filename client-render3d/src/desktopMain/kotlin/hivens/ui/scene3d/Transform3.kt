package hivens.ui.scene3d

import kotlin.math.cos
import kotlin.math.sin

// 3x4 affine transform: a 3x3 rotation/scale block plus a translation column,
// row-major. Deliberately not a full Mat4: the pipeline is orthographic only
// (the rasterizer interpolates UVs linearly in screen space, which is exact
// under ortho and wrong under perspective -- a perspective camera would need
// 1/w-correct interpolation in Raster.kt first), so a projective fourth row
// would be dead weight. Model space matches the skin renderer: Y up, +Z toward
// the viewer, right-handed rotations.
data class Transform3(
    val m00: Float, val m01: Float, val m02: Float, val m03: Float,
    val m10: Float, val m11: Float, val m12: Float, val m13: Float,
    val m20: Float, val m21: Float, val m22: Float, val m23: Float,
) {
    fun apply(p: Vec3): Vec3 = Vec3(
        m00 * p.x + m01 * p.y + m02 * p.z + m03,
        m10 * p.x + m11 * p.y + m12 * p.z + m13,
        m20 * p.x + m21 * p.y + m22 * p.z + m23,
    )

    /** Composition: (a * b).apply(p) == a.apply(b.apply(p)). */
    operator fun times(o: Transform3): Transform3 = Transform3(
        m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
        m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
        m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
        m00 * o.m03 + m01 * o.m13 + m02 * o.m23 + m03,
        m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
        m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
        m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
        m10 * o.m03 + m11 * o.m13 + m12 * o.m23 + m13,
        m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
        m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
        m20 * o.m02 + m21 * o.m12 + m22 * o.m22,
        m20 * o.m03 + m21 * o.m13 + m22 * o.m23 + m23,
    )

    companion object {
        val IDENTITY: Transform3 = Transform3(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
        )

        fun translate(x: Float, y: Float, z: Float): Transform3 = Transform3(
            1f, 0f, 0f, x,
            0f, 1f, 0f, y,
            0f, 0f, 1f, z,
        )

        /** Rotation around +X; matches the skin projection's pitch convention. */
        fun rotateX(rad: Float): Transform3 {
            val c = cos(rad); val s = sin(rad)
            return Transform3(
                1f, 0f, 0f, 0f,
                0f, c, -s, 0f,
                0f, s, c, 0f,
            )
        }

        /** Rotation around +Y; matches the skin projection's yaw convention. */
        fun rotateY(rad: Float): Transform3 {
            val c = cos(rad); val s = sin(rad)
            return Transform3(
                c, 0f, s, 0f,
                0f, 1f, 0f, 0f,
                -s, 0f, c, 0f,
            )
        }

        fun rotateZ(rad: Float): Transform3 {
            val c = cos(rad); val s = sin(rad)
            return Transform3(
                c, -s, 0f, 0f,
                s, c, 0f, 0f,
                0f, 0f, 1f, 0f,
            )
        }

        fun scale(s: Float): Transform3 = Transform3(
            s, 0f, 0f, 0f,
            0f, s, 0f, 0f,
            0f, 0f, s, 0f,
        )

        /** [r] applied around [pivot] instead of the origin: T(p) * r * T(-p). */
        fun aboutPivot(pivot: Vec3, r: Transform3): Transform3 =
            translate(pivot.x, pivot.y, pivot.z) * r * translate(-pivot.x, -pivot.y, -pivot.z)
    }
}
