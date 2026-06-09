package hivens.ui.skin3d

import kotlin.math.cos
import kotlin.math.sin

// Pure-Kotlin (Compose-free) projection pipeline: rotate the figure by
// yaw/pitch, orthographic-project to screen pixels, drop back faces, depth-sort
// for the painter's algorithm, and turn each surviving face into the affine
// that maps its texture rect onto the projected parallelogram. Orthographic +
// planar-quad means that affine is exact (no perspective skew), so the renderer
// can draw each face with a single Skia drawImageRect under a Matrix33.

/**
 * A face projected to screen space: [o] is the texture top-left corner, [u] the
 * screen vector along the texture's +u (to top-right), [v] along +v (to
 * bottom-left). [depth] is the rotated z of the face centroid -- larger = nearer
 * the viewer, so painter's order draws ascending depth (far first).
 */
data class ProjectedFace(
    val o: Pt2,
    val u: Pt2,
    val v: Pt2,
    val uv: UvRect,
    val layer: Boolean,
    val depth: Float,
)

/** Texture-space -> screen affine, row-major [a b c; d e f; 0 0 1]. */
data class Affine(
    val scaleX: Float, val skewX: Float, val transX: Float,
    val skewY: Float, val scaleY: Float, val transY: Float,
)

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

/**
 * Projects, back-face culls, and depth-sorts the figure's faces for one frame.
 * A face is visible when its projected (u x v) cross product is positive -- the
 * winding that [Box.faces] assigns to outward-facing quads. The result is ready
 * to draw front-to-back-correct (painter's: iterate in order).
 */
fun projectFaces(
    faces: List<Face>,
    yaw: Float,
    pitch: Float,
    scale: Float,
    centerX: Float,
    centerY: Float,
): List<ProjectedFace> = faces.mapNotNull { f ->
    val o = project(rotate(f.p0, yaw, pitch), scale, centerX, centerY)
    val pu = project(rotate(f.pu, yaw, pitch), scale, centerX, centerY)
    val pv = project(rotate(f.pv, yaw, pitch), scale, centerX, centerY)
    val u = Pt2(pu.x - o.x, pu.y - o.y)
    val v = Pt2(pv.x - o.x, pv.y - o.y)
    if (!frontFacing(u, v)) return@mapNotNull null

    // Centroid depth = rotated z of (p0 + (U + V) / 2), U/V in model space.
    val cx = f.p0.x + (f.pu.x - f.p0.x) * 0.5f + (f.pv.x - f.p0.x) * 0.5f
    val cy = f.p0.y + (f.pu.y - f.p0.y) * 0.5f + (f.pv.y - f.p0.y) * 0.5f
    val cz = f.p0.z + (f.pu.z - f.p0.z) * 0.5f + (f.pv.z - f.p0.z) * 0.5f
    val depth = rotate(Vec3(cx, cy, cz), yaw, pitch).z

    ProjectedFace(o, u, v, f.uv, f.layer, depth)
}.sortedBy { it.depth }

/** Outward faces wind so screen-space (u x v) is positive; backs are negative. */
fun frontFacing(u: Pt2, v: Pt2): Boolean = (u.x * v.y - u.y * v.x) > 0f

/**
 * Builds the texture-space -> screen affine for a projected face. Maps texture
 * pixel (x, y) within the face's UV rect to o + ((x-u)/w)*U + ((y-v)/h)*V.
 */
fun faceAffine(o: Pt2, u: Pt2, v: Pt2, uv: UvRect): Affine {
    val ax = u.x / uv.w; val bx = v.x / uv.h
    val ay = u.y / uv.w; val by = v.y / uv.h
    return Affine(
        scaleX = ax, skewX = bx, transX = o.x - (uv.u / uv.w) * u.x - (uv.v / uv.h) * v.x,
        skewY = ay, scaleY = by, transY = o.y - (uv.u / uv.w) * u.y - (uv.v / uv.h) * v.y,
    )
}

fun ProjectedFace.affine(): Affine = faceAffine(o, u, v, uv)

/** Applies an affine to a texture-space point -- used by tests and sanity checks. */
fun Affine.map(x: Float, y: Float): Pt2 =
    Pt2(scaleX * x + skewX * y + transX, skewY * x + scaleY * y + transY)
