package hivens.ui.skin3d

import hivens.ui.scene3d.Pt2
import hivens.ui.scene3d.Vec3
import kotlin.math.cos
import kotlin.math.sin

// The view step of the skin pipeline: rotate the model by yaw/pitch and
// orthographic-project to screen pixels. These two functions pin the sign
// conventions (yaw around Y, then pitch around X, +Z toward the viewer,
// screen Y down) -- the Transform3-based scene path is tested against them
// as the oracle (see Transform3Test), so they stay even though the scene
// graph could express the same math.

/**
 * Rotates a model-space point by [yaw] (around Y) then [pitch] (around X).
 * Angles in radians. Keeps +Z toward the viewer.
 */
fun rotate(p: Vec3, yaw: Float, pitch: Float): Vec3 {
    val cy = cos(yaw); val sy = sin(yaw)
    val x1 = p.x * cy + p.z * sy
    val z1 = -p.x * sy + p.z * cy
    val cp = cos(pitch); val sp = sin(pitch)
    val y2 = p.y * cp - z1 * sp
    val z2 = p.y * sp + z1 * cp
    return Vec3(x1, y2, z2)
}

/**
 * Orthographic projection to screen pixels. [scale] is pixels per model unit;
 * (centerX, centerY) is where the model origin lands. Screen Y grows downward,
 * so model +Y maps to -screen-Y.
 */
fun project(p: Vec3, scale: Float, centerX: Float, centerY: Float): Pt2 =
    Pt2(centerX + p.x * scale, centerY - p.y * scale)
