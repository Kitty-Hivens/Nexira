package hivens.ui.skin3d

import hivens.ui.render3d.Texture
import hivens.ui.scene3d.Mesh
import hivens.ui.scene3d.Node
import hivens.ui.scene3d.Transform3
import kotlin.math.PI

// The cape as a scene node -- the first accessory the scene API carries: its
// own texture exercises multi-texture batching, its attachment to the Body
// node makes it follow torso posing for free.
//
// Unlike the figure's boxes (baked in model space, identity node transform),
// the cape is authored PIVOT-LOCAL: the box hangs down from a local origin at
// the attachment line, and the node transform places it at the shoulders.

// Resting tilt away from the body, radians. Keeps the hanging plane off the
// jacket's back plane (z = -2.5) everywhere below the attachment line, so
// the two never z-fight; only the attachment line itself grazes it.
private const val CAPE_TILT = 0.12f

/**
 * Builds the cape node for [texture] (standard 64x32 layout, HD multiples
 * supported; the legacy 22x17 format is not a scaled 64x32 -- it falls back
 * to uvScale 1 and edge-clamp, the same approximation the 2D thumbnail
 * accepts). Attach the result to the rig's Body node.
 *
 * Geometry: 10 wide x 16 tall x 1 thick, hanging down -y from the local
 * origin. The node transform seats it at the shoulder line (y = 8) just
 * behind the body overlay (z = -2.5), tilted [tiltRad] so the bottom swings
 * away from the legs, and turned 180 degrees so the cape texture's OUTSIDE
 * region (1,1 10x16) lands on the world-outward face -- vanilla renders the
 * cape flipped the same way.
 */
fun buildCapeNode(texture: Texture, tiltRad: Float = CAPE_TILT): Node {
    val box = Box(
        x0 = -5f, y0 = -16f, z0 = 0f,
        x1 = 5f, y1 = 0f, z1 = 1f,
        u = 0f, v = 0f, w = 10f, h = 16f, d = 1f,
    )
    val uvScale = if (texture.width >= 64) texture.width / 64f else 1f
    return Node(
        transform = Transform3.translate(0f, 8f, -2.5f) *
            Transform3.rotateX(tiltRad) *
            Transform3.rotateY(PI.toFloat()),
        meshes = listOf(Mesh(box.faces(), texture, uvScale)),
    )
}
