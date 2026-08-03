package hivens.ui.scene3d

import hivens.ui.render3d.downsample
import hivens.ui.render3d.rasterize
import kotlin.math.max
import kotlin.math.min

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

data class Bounds3(val min: Vec3, val max: Vec3)

/**
 * Axis-aligned world-space bounds over every mesh vertex under the
 * accumulated node transforms, implied fourth corners included. An empty
 * scene collapses to a point at the origin.
 */
fun worldBounds(root: Node): Bounds3 {
    var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
    var any = false

    fun take(p: Vec3) {
        any = true
        minX = min(minX, p.x); minY = min(minY, p.y); minZ = min(minZ, p.z)
        maxX = max(maxX, p.x); maxY = max(maxY, p.y); maxZ = max(maxZ, p.z)
    }

    fun visit(node: Node, parentWorld: Transform3) {
        val world = parentWorld * node.transform
        for (mesh in node.meshes) {
            for (f in mesh.faces) {
                val w0 = world.apply(f.p0)
                val wu = world.apply(f.pu)
                val wv = world.apply(f.pv)
                take(w0); take(wu); take(wv)
                take(Vec3(wu.x + wv.x - w0.x, wu.y + wv.y - w0.y, wu.z + wv.z - w0.z))
            }
        }
        for (child in node.children) visit(child, world)
    }

    visit(root, Transform3.IDENTITY)
    return if (any) Bounds3(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ)) else {
        Bounds3(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f))
    }
}

/**
 * Camera that shows all of [bounds] at the given view angles inside a
 * [viewportW] x [viewportH] viewport: the eight bounds corners go through the
 * view rotation, and scale + center are picked so the rotated extent sits
 * centred with a relative [margin] on each side. Fixes the raised-arm case a
 * fixed framing clips (a Wave hand rises past the standing figure's budget).
 */
fun fitOrtho(
    bounds: Bounds3,
    yaw: Float,
    pitch: Float,
    viewportW: Float,
    viewportH: Float,
    margin: Float = 0.1f,
): OrthoCamera {
    var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
    for (x in listOf(bounds.min.x, bounds.max.x)) {
        for (y in listOf(bounds.min.y, bounds.max.y)) {
            for (z in listOf(bounds.min.z, bounds.max.z)) {
                val r = rotate(Vec3(x, y, z), yaw, pitch)
                minX = min(minX, r.x); maxX = max(maxX, r.x)
                minY = min(minY, r.y); maxY = max(maxY, r.y)
            }
        }
    }
    val spanX = max(maxX - minX, 1e-3f)
    val spanY = max(maxY - minY, 1e-3f)
    val usable = 1f - 2f * margin
    val scale = min(viewportW * usable / spanX, viewportH * usable / spanY)
    // Screen x = cx + rx * scale, screen y = cy - ry * scale: put the rotated
    // midpoint at the viewport centre.
    val midX = (minX + maxX) * 0.5f
    val midY = (minY + maxY) * 0.5f
    return OrthoCamera(
        yaw = yaw,
        pitch = pitch,
        scale = scale,
        centerX = viewportW / 2f - midX * scale,
        centerY = viewportH / 2f + midY * scale,
    )
}

/**
 * One frame: flatten [root] under [camera] and rasterize. [supersample] > 1
 * renders at that multiple and box-resolves back down (SSAA): silhouette
 * edges and rotating texel boundaries stop stair-stepping, while the texture
 * sampling stays NEAREST -- the pixel-art look inside faces is kept, only
 * geometry and texel EDGES gain coverage-accurate blending.
 */
fun renderScene(root: Node, camera: OrthoCamera, outW: Int, outH: Int, supersample: Int = 1): IntArray {
    val ss = supersample.coerceAtLeast(1)
    val batches = collectTriBatches(
        root, camera.yaw, camera.pitch,
        camera.scale * ss, camera.centerX * ss, camera.centerY * ss,
    )
    return downsample(rasterize(batches, outW * ss, outH * ss), outW * ss, outH * ss, ss)
}
