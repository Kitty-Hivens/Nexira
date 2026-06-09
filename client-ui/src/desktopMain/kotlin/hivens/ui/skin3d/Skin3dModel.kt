package hivens.ui.skin3d

import kotlin.math.abs

// Pure-Kotlin (Compose-free) geometry for the 3D skin renderer. Builds the
// player model as a set of textured boxes and exposes their faces; the UV
// rectangles index the standard Minecraft skin layout in 1x texel units, so
// an HD skin (64*k texels) multiplies them by k = textureWidth / 64 at draw
// time. Compose-free on purpose: everything here is unit-tested without a
// renderer (see Skin3dModelTest), matching SkinManager's split.
//
// Model space: Y up, +Z toward the viewer, X to the right. The figure is
// centred on the origin -- feet at y = -16, head top at y = +16 -- so yaw
// rotation spins around the body's middle, not its feet.

/** Player arm width. Classic (Steve) = 4 texels, Slim (Alex) = 3. */
enum class SkinModel(val armWidth: Float) { Classic(4f), Slim(3f) }

data class Vec3(val x: Float, val y: Float, val z: Float)

/** A 2D point in screen pixels (own type so the core carries no Compose dep). */
data class Pt2(val x: Float, val y: Float)

/** Texture sub-rectangle in 1x texels, top-left origin. */
data class UvRect(val u: Float, val v: Float, val w: Float, val h: Float)

/**
 * One quad of a box. [p0] is the model-space corner that maps to the texture
 * rect's top-left (u, v); [pu] maps to the top-right (u+w, v); [pv] maps to the
 * bottom-left (u, v+h). The fourth corner is implied (p0 + (pu-p0) + (pv-p0)),
 * so the quad is always a parallelogram -- exact under orthographic projection.
 * [layer] is the paint group: false = base box, true = the inflated overlay
 * (hat / jacket / sleeves / pants), which carries transparency.
 */
data class Face(val p0: Vec3, val pu: Vec3, val pv: Vec3, val uv: UvRect, val layer: Boolean)

/**
 * A box in model space ([x0..x1] x [y0..y1] x [z0..z1]) textured from the
 * standard MC unwrap anchored at texture offset ([u], [v]) with part texel
 * dimensions [w] (width, +x span), [h] (height, +y span), [d] (depth, +z span).
 * [layer] tags base vs overlay. The unwrap places the six faces as:
 *   right (u, v+d), front (u+d, v+d), left (u+d+w, v+d), back (u+2d+w, v+d),
 *   top (u+d, v), bottom (u+d+w, v).
 */
data class Box(
    val x0: Float, val y0: Float, val z0: Float,
    val x1: Float, val y1: Float, val z1: Float,
    val u: Float, val v: Float,
    val w: Float, val h: Float, val d: Float,
    val layer: Boolean = false,
)

/**
 * The six faces of a box, with the model corners ordered so each face is wound
 * the same way when seen from outside (used by the back-face cull) and its
 * (p0, pu, pv) line up with the texture rect's (TL, TR, BL).
 */
fun Box.faces(): List<Face> = listOf(
    // front (+z)
    Face(Vec3(x0, y1, z1), Vec3(x1, y1, z1), Vec3(x0, y0, z1), UvRect(u + d, v + d, w, h), layer),
    // back (-z): seen from behind, world +x runs right-to-left, so u reverses
    Face(Vec3(x1, y1, z0), Vec3(x0, y1, z0), Vec3(x1, y0, z0), UvRect(u + d + w + d, v + d, w, h), layer),
    // right (-x): texture u runs back -> front (matching the unwrap net), v top -> bottom
    Face(Vec3(x0, y1, z0), Vec3(x0, y1, z1), Vec3(x0, y0, z0), UvRect(u, v + d, d, h), layer),
    // left (+x): texture u runs front -> back
    Face(Vec3(x1, y1, z1), Vec3(x1, y1, z0), Vec3(x1, y0, z1), UvRect(u + d + w, v + d, d, h), layer),
    // top (+y): u left-to-right = +x, v back-to-front = +z
    Face(Vec3(x0, y1, z0), Vec3(x1, y1, z0), Vec3(x0, y1, z1), UvRect(u + d, v, w, d), layer),
    // bottom (-y): v front-to-back
    Face(Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x0, y0, z0), UvRect(u + d + w, v, w, d), layer),
)

// ── Figure assembly ──────────────────────────────────────────────────────────

// Part sizes are fixed; only the arm width changes between Classic and Slim.
// Overlay boxes inflate the base box by OVERLAY_INFLATE on every side so the
// hat/jacket/sleeves/pants sit just outside the skin.
private const val OVERLAY_INFLATE = 0.5f

private fun Box.inflated(by: Float, u: Float, v: Float, layer: Boolean): Box = Box(
    x0 - by, y0 - by, z0 - by, x1 + by, y1 + by, z1 + by,
    u = u, v = v, w = w, h = h, d = d, layer = layer,
)

/**
 * Builds the figure's faces for the given model + skin format. [legacy] is the
 * old 64x32 layout: no second layer except the hat, and the left arm/leg reuse
 * the right limb's texture (a mirror in vanilla; here a direct reuse, acceptable
 * for the rare legacy skins SmartyCraft still serves).
 */
fun buildFigure(model: SkinModel = SkinModel.Classic, legacy: Boolean = false): List<Face> {
    val aw = model.armWidth
    val boxes = mutableListOf<Box>()

    // Base parts.
    val head     = Box(-4f,  8f, -4f, 4f, 16f, 4f, u = 0f,  v = 0f,  w = 8f,  h = 8f,  d = 8f)
    val body     = Box(-4f, -4f, -2f, 4f,  8f, 2f, u = 16f, v = 16f, w = 8f,  h = 12f, d = 4f)
    val rightArm = Box(-4f - aw, -4f, -2f, -4f, 8f, 2f, u = 40f, v = 16f, w = aw, h = 12f, d = 4f)
    val leftArm  = Box(4f, -4f, -2f, 4f + aw, 8f, 2f, u = 32f, v = 48f, w = aw, h = 12f, d = 4f)
    val rightLeg = Box(-4f, -16f, -2f, 0f, -4f, 2f, u = 0f,  v = 16f, w = 4f, h = 12f, d = 4f)
    val leftLeg  = Box(0f, -16f, -2f, 4f, -4f, 2f, u = 16f, v = 48f, w = 4f, h = 12f, d = 4f)

    boxes += head
    boxes += body
    boxes += rightArm
    boxes += rightLeg
    if (legacy) {
        // Legacy has no dedicated left-limb texture; reuse the right limb's.
        boxes += leftArm.copy(u = 40f, v = 16f)
        boxes += leftLeg.copy(u = 0f, v = 16f)
    } else {
        boxes += leftArm
        boxes += leftLeg
    }

    // Overlay (second layer). Hat exists on every skin; the rest are 64x64 only.
    boxes += head.inflated(OVERLAY_INFLATE, u = 32f, v = 0f, layer = true)
    if (!legacy) {
        boxes += body.inflated(OVERLAY_INFLATE,     u = 16f, v = 32f, layer = true)
        boxes += rightArm.inflated(OVERLAY_INFLATE, u = 40f, v = 32f, layer = true)
        boxes += leftArm.inflated(OVERLAY_INFLATE,  u = 48f, v = 48f, layer = true)
        boxes += rightLeg.inflated(OVERLAY_INFLATE, u = 0f,  v = 32f, layer = true)
        boxes += leftLeg.inflated(OVERLAY_INFLATE,  u = 0f,  v = 48f, layer = true)
    }

    return boxes.flatMap { it.faces() }
}

/**
 * Best-effort Classic/Slim guess from the raw skin's alpha. A Slim (3-wide)
 * skin leaves the 4th arm column transparent; if those texels are opaque the
 * skin is Classic. [alphaAt] returns 0..255 for a 1x texel coordinate (the
 * caller scales by the HD factor). Defaults to Classic when ambiguous -- the
 * same default the old 2D renderer used (armW = 4).
 */
fun guessModel(alphaAt: (x: Int, y: Int) -> Int): SkinModel {
    // The right arm's 4th column on a 64x64 skin spans texels x=54..55 across
    // the front/side faces at y=20..31. If any are opaque, it's Classic.
    var opaque = 0
    for (y in 20..31) {
        if (alphaAt(54, y) > 16) opaque++
        if (alphaAt(55, y) > 16) opaque++
    }
    return if (opaque > 0) SkinModel.Classic else SkinModel.Slim
}

// Small vector helper kept here so the projection file and tests share it.
internal fun nearlyEqual(a: Float, b: Float, eps: Float = 1e-3f): Boolean = abs(a - b) <= eps
