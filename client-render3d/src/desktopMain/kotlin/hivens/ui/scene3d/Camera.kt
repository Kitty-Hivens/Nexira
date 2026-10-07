package hivens.ui.scene3d

// Orthographic camera over the scene graph. Deliberately the orbit form
// (yaw/pitch angles, not a free Transform3): the view step must stay the
// sequential rotate() math so a rig at rest keeps its bit-parity with the
// pre-rig renderer (see Projection.kt); a general matrix camera would round
// differently and needs a parity re-baseline -- future work alongside
// perspective-correct UV interpolation.

/**
 * [yaw]/[pitch] orbit the scene, [scale] is pixels per world unit, and
 * (centerX, centerY) is where the world origin lands on screen.
 */
data class OrthoCamera(
    val yaw: Float,
    val pitch: Float,
    val scale: Float,
    val centerX: Float,
    val centerY: Float,
)
