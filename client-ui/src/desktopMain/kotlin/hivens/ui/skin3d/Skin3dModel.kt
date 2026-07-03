package hivens.ui.skin3d

import hivens.ui.scene3d.Face
import hivens.ui.scene3d.UvRect
import hivens.ui.scene3d.Vec3

// Pure-Kotlin (Compose-free) geometry for the 3D skin renderer. Builds the
// player model as a set of textured boxes and exposes their faces (the shared
// scene3d types); the UV rectangles index the standard Minecraft skin layout
// in 1x texel units, so an HD skin (64*k texels) multiplies them by
// k = textureWidth / 64 at draw time. Compose-free on purpose: everything here
// is unit-tested without a renderer, matching SkinManager's split.
//
// Model space: Y up, +Z toward the viewer, X to the right. The figure is
// centred on the origin -- feet at y = -16, head top at y = +16 -- so yaw
// rotation spins around the body's middle, not its feet.

/** Player arm width. Classic (Steve) = 4 texels, Slim (Alex) = 3. */
enum class SkinModel(val armWidth: Float) { Classic(4f), Slim(3f) }

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
    // bottom (-y): v BACK-to-front. The MC down face is flipped vs the up face; mapping
    // it front-to-back renders the underside reversed (front texels at the back). The
    // winding must stay (else the cull drops the face), so V is flipped via the UV rect
    // (origin at the region's far edge, negative height) rather than by swapping corners.
    Face(Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x0, y0, z0), UvRect(u + d + w, v + d, w, -d), layer),
)

// ── Figure assembly ──────────────────────────────────────────────────────────

// Part sizes are fixed; only the arm width changes between Classic and Slim.
// Overlay boxes inflate the base box by OVERLAY_INFLATE on every side so the
// hat/jacket/sleeves/pants sit just outside the skin.
private const val OVERLAY_INFLATE = 0.5f

// How far a limb reaches past its neighbour's plane (see partBoxes).
private const val SEAM_OVERLAP = 0.03f

/**
 * Inflated copy of the box for the overlay (second) layer. [top]/[bottom] gate
 * the +y / -y growth: a seam face that abuts the adjacent part (head bottom over
 * the body top, arm/leg tops at the shoulders/hips) is kept FLUSH rather than
 * inflated. Otherwise two parts' overlays would overlap in a thin band at the
 * seam, and the painter's renderer (no depth buffer, centroid-depth sort) would
 * z-fight those coplanar quads -- visible as flickering garbage at e.g. the neck,
 * worst on skins that fully use the second layer (the vanilla defaults).
 */
private fun Box.inflated(
    by: Float, u: Float, v: Float, layer: Boolean,
    top: Boolean = true, bottom: Boolean = true,
): Box = Box(
    x0 - by, y0 - (if (bottom) by else 0f), z0 - by,
    x1 + by, y1 + (if (top) by else 0f), z1 + by,
    u = u, v = v, w = w, h = h, d = d, layer = layer,
)

/**
 * The figure's boxes grouped by body part -- base box first, then that part's
 * overlay box (when the format carries one). The rig turns each entry into one
 * node, so an overlay follows its base part structurally. [legacy] is the old
 * 64x32 layout: no second layer except the hat, and the left arm/leg reuse the
 * right limb's texture (a mirror in vanilla; here a direct reuse, acceptable
 * for the rare legacy skins SmartyCraft still serves).
 *
 * Overlay boxes: hat exists on every skin; the rest are 64x64 only. Seam faces
 * (where a part abuts another) are kept flush so the overlays do not overlap
 * and z-fight: head over body (head no bottom), torso between head and legs
 * (body no top/bottom), arms/legs at the shoulders/hips (no top).
 */
internal fun partBoxes(model: SkinModel, legacy: Boolean): Map<BodyPart, List<Box>> {
    val aw = model.armWidth

    // Limb inner planes historically COINCIDED with their neighbour's plane
    // (arm inner and body/head side both on x = +-4, the two legs sharing
    // x = 0). Coplanar faces of different parts tie in the depth buffer and
    // the winner is per-pixel rounding noise -- invisible while a limb hangs
    // at rest (the tied faces look at each other), but a raised arm or a
    // walk swing turns both faces toward the viewer and the tie flickers as
    // texture speckle. Each limb now reaches PAST the shared plane by a hair
    // (overlap, not a gap, so no background bleeds through between the
    // legs); the interpenetration is far below a pixel at any view scale.
    val head     = Box(-4f,  8f, -4f, 4f, 16f, 4f, u = 0f,  v = 0f,  w = 8f,  h = 8f,  d = 8f)
    val body     = Box(-4f, -4f, -2f, 4f,  8f, 2f, u = 16f, v = 16f, w = 8f,  h = 12f, d = 4f)
    val rightArm = Box(-4f - aw, -4f, -2f, -4f + SEAM_OVERLAP, 8f, 2f, u = 40f, v = 16f, w = aw, h = 12f, d = 4f)
    val leftArm  = Box(4f - SEAM_OVERLAP, -4f, -2f, 4f + aw, 8f, 2f, u = 32f, v = 48f, w = aw, h = 12f, d = 4f)
    val rightLeg = Box(-4f, -16f, -2f, SEAM_OVERLAP / 2f, -4f, 2f, u = 0f,  v = 16f, w = 4f, h = 12f, d = 4f)
    val leftLeg  = Box(-SEAM_OVERLAP / 2f, -16f, -2f, 4f, -4f, 2f, u = 16f, v = 48f, w = 4f, h = 12f, d = 4f)

    val parts = LinkedHashMap<BodyPart, MutableList<Box>>()
    parts[BodyPart.Head]     = mutableListOf(head)
    parts[BodyPart.Body]     = mutableListOf(body)
    parts[BodyPart.RightArm] = mutableListOf(rightArm)
    parts[BodyPart.LeftArm]  = mutableListOf(if (legacy) leftArm.copy(u = 40f, v = 16f) else leftArm)
    parts[BodyPart.RightLeg] = mutableListOf(rightLeg)
    parts[BodyPart.LeftLeg]  = mutableListOf(if (legacy) leftLeg.copy(u = 0f, v = 16f) else leftLeg)

    parts.getValue(BodyPart.Head) += head.inflated(OVERLAY_INFLATE, u = 32f, v = 0f, layer = true, bottom = false)
    if (!legacy) {
        parts.getValue(BodyPart.Body)     += body.inflated(OVERLAY_INFLATE,     u = 16f, v = 32f, layer = true, top = false, bottom = false)
        parts.getValue(BodyPart.RightArm) += rightArm.inflated(OVERLAY_INFLATE, u = 40f, v = 32f, layer = true, top = false)
        parts.getValue(BodyPart.LeftArm)  += leftArm.inflated(OVERLAY_INFLATE,  u = 48f, v = 48f, layer = true, top = false)
        parts.getValue(BodyPart.RightLeg) += rightLeg.inflated(OVERLAY_INFLATE, u = 0f,  v = 32f, layer = true, top = false)
        parts.getValue(BodyPart.LeftLeg)  += leftLeg.inflated(OVERLAY_INFLATE,  u = 0f,  v = 48f, layer = true, top = false)
    }
    return parts
}

/**
 * The figure as a flat face list in the historical emission order (all bases,
 * then all overlays) -- the pre-rig renderer's order, kept as the reference
 * the geometry tests and the render-parity pin are written against. Note the
 * two historical quirks: bases run right-limb-before-left-limb interleaved
 * (arm, leg, arm, leg) while overlays run arms-then-legs.
 */
fun buildFigure(model: SkinModel = SkinModel.Classic, legacy: Boolean = false): List<Face> {
    val parts = partBoxes(model, legacy)
    fun base(p: BodyPart) = parts.getValue(p).first()
    fun overlays(p: BodyPart) = parts.getValue(p).drop(1)

    val boxes = mutableListOf<Box>()
    boxes += base(BodyPart.Head)
    boxes += base(BodyPart.Body)
    boxes += base(BodyPart.RightArm)
    boxes += base(BodyPart.RightLeg)
    boxes += base(BodyPart.LeftArm)
    boxes += base(BodyPart.LeftLeg)
    for (p in listOf(
        BodyPart.Head, BodyPart.Body,
        BodyPart.RightArm, BodyPart.LeftArm, BodyPart.RightLeg, BodyPart.LeftLeg,
    )) {
        boxes += overlays(p)
    }
    return boxes.flatMap { it.faces() }
}

/**
 * Best-effort Classic/Slim guess from the raw skin's alpha. A Slim (3-wide)
 * skin leaves the 4th arm column transparent; if those texels are opaque the
 * skin is Classic. [alphaAt] returns 0..255 for a raw texel coordinate and is
 * never called outside [width] x [height]. Defaults to Classic when the sniff
 * region does not exist: legacy 64x32 predates the Slim model, and a valid
 * but undersized texture carries no 4th arm column to read -- the same
 * default the old 2D renderer used (armW = 4).
 */
fun guessModel(width: Int, height: Int, alphaAt: (x: Int, y: Int) -> Int): SkinModel {
    if (height <= width / 2) return SkinModel.Classic
    val k = (width / 64).coerceAtLeast(1)
    if (55 * k >= width || 31 * k >= height) return SkinModel.Classic
    // The right arm's 4th column on a 64x64 skin spans texels x=54..55 across
    // the front/side faces at y=20..31. If any are opaque, it's Classic.
    var opaque = 0
    for (y in 20..31) {
        if (alphaAt(54 * k, y * k) > 16) opaque++
        if (alphaAt(55 * k, y * k) > 16) opaque++
    }
    return if (opaque > 0) SkinModel.Classic else SkinModel.Slim
}
